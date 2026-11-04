cmake_minimum_required(VERSION 3.22)
project(mobilestudio_native C CXX)

set(MOBILESTUDIO_16KB_PAGE_LINK_OPTIONS
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
string(JOIN " " MOBILESTUDIO_16KB_PAGE_LINK_FLAGS ${MOBILESTUDIO_16KB_PAGE_LINK_OPTIONS})
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${MOBILESTUDIO_16KB_PAGE_LINK_FLAGS}")

function(mobilestudio_enable_16kb_pages target_name)
    if(TARGET ${target_name})
        target_link_options(${target_name} PRIVATE ${MOBILESTUDIO_16KB_PAGE_LINK_OPTIONS})
    endif()
endfunction()

# --- llama.cpp configuration ---
# MOBILESTUDIO_BUILD_LLAMA=ON builds the bundled llama.cpp copy (CPU-only).
# Default OFF: the app uses the downloaded llama-vulkan-android runtime
# (arm64-v8a, CPU + Vulkan) via the dlopen-based llama_runtime_jni bridge,
# so llama.cpp is never recompiled when the app is built.
option(MOBILESTUDIO_BUILD_LLAMA "Build bundled llama.cpp (CPU-only). OFF = use the downloaded llama-vulkan runtime" OFF)
set(GGML_NNAPI OFF CACHE BOOL "Enable NNAPI support" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan support" FORCE)
set(GGML_VULKAN_INIT OFF CACHE BOOL "Enable Vulkan init" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Enable OpenMP for faster CPU inference" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable llama.cpp examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable llama.cpp tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable llama.cpp server" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Disable llama.cpp tools" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Disable llama.cpp common" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared libraries" FORCE)

# --- llama.cpp (bundled, CPU-only; only when MOBILESTUDIO_BUILD_LLAMA=ON) ---
if(MOBILESTUDIO_BUILD_LLAMA AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)
endif()

# --- llama_android (JNI Bridge) ---
if(TARGET llama)
    add_library(llama_android SHARED llama_jni.cpp)
    target_include_directories(llama_android PRIVATE llama.cpp/include llama.cpp/ggml/include)
    target_compile_options(llama_android PRIVATE -std=c++17 -O3)
    target_link_libraries(llama_android llama log c++_shared)
endif()

# --- llama_runtime_jni (dlopen-based bridge to the downloaded llama-vulkan
#     runtime: libllama.so / libggml*.so resolved via dlsym at runtime) ---
add_library(llama_runtime_jni SHARED llama_runtime_jni.cpp)
target_include_directories(llama_runtime_jni PRIVATE llama.cpp/include llama.cpp/ggml/include)
target_compile_options(llama_runtime_jni PRIVATE -std=c++17 -O2 -Wall)
target_link_libraries(llama_runtime_jni log dl)

# --- Shared libraries ---
add_library(mobilestudio SHARED mobilestudio_native.c)
target_compile_options(mobilestudio PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall)
target_link_libraries(mobilestudio log dl)

add_library(gradle_exec SHARED gradle_exec.c)
target_compile_options(gradle_exec PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall)
target_link_libraries(gradle_exec log c++_shared)

add_library(mobilestudio_jli SHARED mobilestudio_jli.c)
target_compile_options(mobilestudio_jli PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall)
target_link_libraries(mobilestudio_jli log dl)

# java_launcher — rebuilt as a PIE EXECUTABLE (was add_library ... SHARED).
# As a shared library e_entry was 0: invoking it via /system/bin/linker64
# jumped to 0x0 -> SIGILL (tombstone: "libjava_launcher.so + 0x0"). The
# executable links crt startup so e_entry -> _start -> main() for real.
#
# OUTPUT_NAME/SUFFIX keep the packaged file named libjava_launcher.so on
# purpose: the Android installer only extracts APK entries lib/<abi>/lib*.so
# (AOSP NativeLibraryHelper: endsWith(".so") && startsWith("lib")) and AGP
# only packages *.so (verified: non-.so files never reach merged_native_libs)
# — same constraint documented for luac below. The ELF itself is a normal
# PIE executable (PT_INTERP /system/bin/linker64) so it runs BOTH by direct
# exec and via "/system/bin/linker64 <path>".
add_executable(java_launcher java_launcher.c)
target_compile_options(java_launcher PRIVATE -std=c11 -O2 -Wall)
target_link_libraries(java_launcher log dl)
set_target_properties(java_launcher PROPERTIES
    OUTPUT_NAME "libjava_launcher"
    SUFFIX ".so"
    LINK_FLAGS "${MOBILESTUDIO_16KB_PAGE_LINK_FLAGS} -Wl,--disable-new-dtags")

add_library(exec_redirect SHARED exec_redirect.c)
target_compile_options(exec_redirect PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall -Wextra)
target_link_libraries(exec_redirect log dl)

add_library(tool_launcher_common STATIC
    elf_parse.c
    lib_resolver.c
)
target_compile_options(tool_launcher_common PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall -Wextra)
target_link_libraries(tool_launcher_common log dl)

# --- Virtual Runtime Engine (Universal ELF executor) ---
add_library(virtual_runtime_common STATIC
    elf_analysis.c
    vlib_resolver.c
    linker_resolver.c
    virtual_filesystem.c
    process_manager.c
)
target_compile_options(virtual_runtime_common PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall -Wextra)
target_link_libraries(virtual_runtime_common log dl)

add_library(virtual_runtime SHARED virtual_runtime.c)
target_compile_options(virtual_runtime PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall -Wextra)
target_link_libraries(virtual_runtime virtual_runtime_common log dl)
set_target_properties(virtual_runtime PROPERTIES LINK_FLAGS "${MOBILESTUDIO_16KB_PAGE_LINK_FLAGS} -Wl,--disable-new-dtags")

add_executable(tool_clang_launcher tool_launcher.c)
target_link_libraries(tool_clang_launcher tool_launcher_common log dl)
set_target_properties(tool_clang_launcher PROPERTIES OUTPUT_NAME "clang_launcher")
mobilestudio_enable_16kb_pages(tool_clang_launcher)

add_executable(tool_java_launcher tool_launcher.c)
target_link_libraries(tool_java_launcher tool_launcher_common log dl)
set_target_properties(tool_java_launcher PROPERTIES OUTPUT_NAME "java_launcher")
mobilestudio_enable_16kb_pages(tool_java_launcher)

add_executable(tool_ninja_launcher tool_launcher.c)
target_link_libraries(tool_ninja_launcher tool_launcher_common log dl)
set_target_properties(tool_ninja_launcher PROPERTIES OUTPUT_NAME "ninja_launcher")
mobilestudio_enable_16kb_pages(tool_ninja_launcher)

add_custom_command(TARGET tool_clang_launcher POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/tools/bin"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/clang"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/clang++"
    COMMENT "Creating symlinks for clang/clang++"
)

add_custom_command(TARGET tool_java_launcher POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/tools/bin"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_java_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/java"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_java_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/javac"
    COMMENT "Creating symlinks for java/javac"
)

add_custom_command(TARGET tool_ninja_launcher POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/tools/bin"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_ninja_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/ninja"
    COMMENT "Creating symlink for ninja"
)

add_custom_command(TARGET tool_clang_launcher POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/tools/bin"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/clang"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/clang++"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/clang-cpp"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/clang-cl"
    COMMENT "Creating symlinks for clang family"
)

add_custom_command(TARGET tool_java_launcher POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/tools/bin"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_java_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/java"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_java_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/javac"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_java_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/jar"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_java_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/jlink"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_java_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/jmod"
    COMMENT "Creating symlinks for Java tools"
)

add_custom_command(TARGET tool_ninja_launcher POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/tools/bin"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_ninja_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/ninja"
    COMMENT "Creating symlink for ninja"
)

add_custom_command(TARGET tool_clang_launcher POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/git"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/cmake"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/make"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/python3"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/pip3"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/node"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/npm"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/adb"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/logcat"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/strace"
    COMMAND ${CMAKE_COMMAND} -E create_symlink "$<TARGET_FILE:tool_clang_launcher>" "${CMAKE_CURRENT_BINARY_DIR}/tools/bin/ltrace"
    COMMENT "Creating symlinks for all common tools"
)

add_library(aapt2_launcher SHARED aapt2_launcher.c)
target_compile_options(aapt2_launcher PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall)
target_link_libraries(aapt2_launcher log)
set_target_properties(aapt2_launcher PROPERTIES LINK_FLAGS "${MOBILESTUDIO_16KB_PAGE_LINK_FLAGS} -Wl,-e,aapt2_launcher_start -Wl,--disable-new-dtags")

add_library(aapt2_daemon_proxy SHARED aapt2_daemon_proxy.c)
target_compile_options(aapt2_daemon_proxy PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall)
target_link_libraries(aapt2_daemon_proxy log)
set_target_properties(aapt2_daemon_proxy PROPERTIES LINK_FLAGS "${MOBILESTUDIO_16KB_PAGE_LINK_FLAGS} -Wl,-e,aapt2_daemon_proxy_start -Wl,--disable-new-dtags")

add_library(jdk_tool_proxy SHARED jdk_tool_proxy.c)
target_compile_options(jdk_tool_proxy PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall)
target_link_libraries(jdk_tool_proxy log dl)
set_target_properties(jdk_tool_proxy PROPERTIES LINK_FLAGS "${MOBILESTUDIO_16KB_PAGE_LINK_FLAGS} -Wl,-e,jdk_tool_proxy_start -Wl,--disable-new-dtags")

add_library(ai_inference SHARED ai_inference.c)
target_compile_options(ai_inference PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall -Wextra)
target_link_libraries(ai_inference log dl android atomic m)

add_library(cwd_fix SHARED cwd_fix.c)
target_compile_options(cwd_fix PRIVATE -O2 -ffreestanding -fno-stack-protector -fvisibility=hidden)
target_link_options(cwd_fix PRIVATE -nostdlib -Wl,-soname,libcwd_fix.so -Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/cwd_fix.map)

add_library(fork_loader SHARED fork_loader.c)
target_compile_options(fork_loader PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall)
target_link_libraries(fork_loader log dl)
set_target_properties(fork_loader PROPERTIES LINK_FLAGS "${MOBILESTUDIO_16KB_PAGE_LINK_FLAGS} -Wl,--disable-new-dtags")

add_library(thread_stub SHARED thread_stub.c)
target_compile_options(thread_stub PRIVATE -std=c11 -O2 -Wall -fPIC)
target_link_libraries(thread_stub log)
set_target_properties(thread_stub PROPERTIES LINK_FLAGS "${MOBILESTUDIO_16KB_PAGE_LINK_FLAGS} -Wl,--disable-new-dtags")

add_library(terminal_pty SHARED terminal_pty.c)
target_compile_options(terminal_pty PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall)
target_link_libraries(terminal_pty log)
mobilestudio_enable_16kb_pages(terminal_pty)

add_custom_command(TARGET fork_loader POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:fork_loader>" "$<TARGET_FILE_DIR:fork_loader>/libfork_loader.so"
    COMMENT "Copying fork_loader as libfork_loader.so for native lib packaging")

# --- llama_android (JNI Bridge) - DISABLED (requires llama.cpp which is disabled) ---
# add_library(llama_android SHARED llama_jni.cpp)
# target_include_directories(llama_android PRIVATE llama.cpp/include llama.cpp/ggml/include)
# target_compile_options(llama_android PRIVATE -std=c++17 -O3)
# target_link_libraries(llama_android llama log c++_shared)

# --- Lua 5.4.9 (LuaGame runtime: native Lua VM + luac bytecode compiler) ---
set(LUA54_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lua54)
set(LUA54_CORE
    ${LUA54_DIR}/lapi.c
    ${LUA54_DIR}/lauxlib.c
    ${LUA54_DIR}/lbaselib.c
    ${LUA54_DIR}/lcode.c
    ${LUA54_DIR}/lcorolib.c
    ${LUA54_DIR}/lctype.c
    ${LUA54_DIR}/ldblib.c
    ${LUA54_DIR}/ldebug.c
    ${LUA54_DIR}/ldo.c
    ${LUA54_DIR}/ldump.c
    ${LUA54_DIR}/lfunc.c
    ${LUA54_DIR}/lgc.c
    ${LUA54_DIR}/linit.c
    ${LUA54_DIR}/liolib.c
    ${LUA54_DIR}/llex.c
    ${LUA54_DIR}/lmathlib.c
    ${LUA54_DIR}/lmem.c
    ${LUA54_DIR}/loadlib.c
    ${LUA54_DIR}/lobject.c
    ${LUA54_DIR}/lopcodes.c
    ${LUA54_DIR}/loslib.c
    ${LUA54_DIR}/lparser.c
    ${LUA54_DIR}/lstate.c
    ${LUA54_DIR}/lstring.c
    ${LUA54_DIR}/lstrlib.c
    ${LUA54_DIR}/ltable.c
    ${LUA54_DIR}/ltablib.c
    ${LUA54_DIR}/ltm.c
    ${LUA54_DIR}/lundump.c
    ${LUA54_DIR}/lutf8lib.c
    ${LUA54_DIR}/lvm.c
    ${LUA54_DIR}/lzio.c
)

add_library(lua54_core STATIC ${LUA54_CORE})
target_compile_options(lua54_core PRIVATE -O2 -Wall)

add_library(lua_game SHARED lua_game_bridge.c)
target_include_directories(lua_game PRIVATE ${LUA54_DIR})
target_compile_options(lua_game PRIVATE -std=c11 -D_GNU_SOURCE -O2 -Wall)
target_link_libraries(lua_game lua54_core log)

# luac — Lua 5.4.9 bytecode compiler (.lua → .luac), arm64-v8a
add_executable(luac ${LUA54_DIR}/luac.c)
target_include_directories(luac PRIVATE ${LUA54_DIR})
target_compile_options(luac PRIVATE -O2 -Wall)
target_link_libraries(luac lua54_core)
mobilestudio_enable_16kb_pages(luac)

# Package luac into the APK (jniLibs pattern: executables must be named lib*.so
# to be extracted by the Android installer; direct exec works regardless of name)
add_custom_command(TARGET luac POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:luac>" "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libluac.so"
    COMMENT "Packaging luac as libluac.so (arm64-v8a)")

# lua — Lua 5.4.9 interpreter CLI (script/-e/REPL), arm64-v8a.
# The upstream `lua.c` is not vendored; lua_runner.c is the equivalent CLI.
add_executable(lua ${LUA54_DIR}/lua_runner.c)
target_include_directories(lua PRIVATE ${LUA54_DIR})
target_compile_options(lua PRIVATE -O2 -Wall)
target_link_libraries(lua lua54_core)
mobilestudio_enable_16kb_pages(lua)

# Package lua into the APK the same way as luac (see above).
add_custom_command(TARGET lua POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:lua>" "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/liblua.so"
    COMMENT "Packaging lua as liblua.so (arm64-v8a)")

# Enable 16KB pages for all targets
foreach(target mobilestudio gradle_exec java_launcher exec_redirect aapt2_launcher aapt2_daemon_proxy jdk_tool_proxy cwd_fix fork_loader thread_stub ai_inference terminal_pty tool_clang_launcher tool_java_launcher tool_ninja_launcher llama_android virtual_runtime mobilestudio_jli lua_game llama_runtime_jni)
    mobilestudio_enable_16kb_pages(${target})
endforeach()
