# Vendored SPIRV-Headers CMake package config (header-only, Khronos
# vulkan-sdk-1.4.363.0). ggml-vulkan calls
#   find_package(SPIRV-Headers CONFIG REQUIRED)
# but never links SPIRV-Headers::Headers itself (the include directory is
# added by the app CMakeLists), so this config only has to make the package
# findable and define the conventional imported interface target.
# Mirrors what `cmake --install` of the upstream project exports.

get_filename_component(_SPIRV_HEADERS_PREFIX "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)

if(NOT TARGET SPIRV-Headers::Headers)
    add_library(SPIRV-Headers::Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${_SPIRV_HEADERS_PREFIX}/include")
endif()

set(SPIRV-Headers_INCLUDE_DIRS "${_SPIRV_HEADERS_PREFIX}/include")
set(SPIRV-Headers_FOUND TRUE)

unset(_SPIRV_HEADERS_PREFIX)
