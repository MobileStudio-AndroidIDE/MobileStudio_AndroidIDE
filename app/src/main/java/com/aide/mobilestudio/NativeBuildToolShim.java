package com.aide.mobilestudio;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Comparator;

public final class NativeBuildToolShim {
    private static final String META_FILE = "mobilestudio-native-build.properties";
    private static final String SEP = "\u001e";

    private NativeBuildToolShim() {
    }

    private static PrintWriter logWriter;

    private static void logInit(File buildDir) {
        if (logWriter != null) return;
        IOException lastError = null;
        List<File> candidates = new ArrayList<>();
        candidates.add(new File(buildDir, ".native_shim.log"));
        candidates.add(new File(System.getProperty("java.io.tmpdir", "/data/local/tmp"), "mobilestudio_native_shim.log"));
        candidates.add(new File("/data/local/tmp", "mobilestudio_native_shim.log"));
        candidates.add(new File(".", "native_shim_debug.log"));
        for (File logFile : candidates) {
            try {
                File parent = logFile.getParentFile();
                if (parent != null && !parent.isDirectory()) continue;
                logWriter = new PrintWriter(new FileWriter(logFile, false), true);
                logWriter.println("=== native_tool_shim log started at " + new java.util.Date() + " ===");
                logWriter.flush();
                return;
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            System.err.println("native_tool_shim: failed to open log file: " + lastError.getMessage());
        }
    }

    private static void log(String message) {
        System.out.println(message);
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    private static void logError(String message) {
        System.err.println(message);
        if (logWriter != null) {
            logWriter.println("[ERR] " + message);
            logWriter.flush();
        }
    }

    private static void logError(Throwable throwable) {
        System.err.println("native_tool_shim: " + throwable.getMessage());
        throwable.printStackTrace(System.err);
        if (logWriter != null) {
            logWriter.println("[ERR] native_tool_shim: " + throwable.getMessage());
            throwable.printStackTrace(logWriter);
            logWriter.flush();
        }
    }

    public static void main(String[] args) {
        try {
            new File("/data/local/tmp/mobilestudio_shim_started.marker").createNewFile();
        } catch (Exception ignored) {
        }
        int exitCode = 1;
        try {
            exitCode = run(args);
        } catch (Throwable throwable) {
            logError(throwable);
        }
        System.exit(exitCode);
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0) {
            logError("native_tool_shim: missing tool path");
            return 127;
        }

        String tool = args[0];
        List<String> toolArgs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            toolArgs.add(args[i]);
        }

        String toolName = new File(tool).getName().toLowerCase(Locale.US);
        if ("cmake".equals(toolName)) {
            return runCmake(tool, toolArgs);
        }
        if ("ninja".equals(toolName)) {
            return runNinja(toolArgs);
        }
        if ("ctest".equals(toolName) || "cpack".equals(toolName)) {
            return 0;
        }

        return runProcess(buildLinkerCommand(tool, toolArgs), new File(System.getProperty("user.dir", ".")), null);
    }

    private static int runCmake(String tool, List<String> args) throws Exception {
        if (args.contains("--version")) {
            String version = cmakeVersionFromPath(tool);
            log("cmake version " + version);
            log("");
            log("CMake suite maintained and supported by Kitware (kitware.com/cmake).");
            return 0;
        }

        int capabilitiesIndex = indexOf(args, "-E");
        if (capabilitiesIndex >= 0 && capabilitiesIndex + 1 < args.size()
                && "capabilities".equals(args.get(capabilitiesIndex + 1))) {
            writeCapabilities(cmakeVersionFromPath(tool));
            return 0;
        }

        if (args.contains("--build")) {
            File buildDir = new File(System.getProperty("user.dir", "."));
            int buildIndex = args.indexOf("--build");
            if (buildIndex + 1 < args.size()) {
                buildDir = new File(args.get(buildIndex + 1));
            }
            buildDir = canonical(buildDir);
            logInit(buildDir);
            log("native_tool_shim: cmake --build " + buildDir.getAbsolutePath());
            return buildFromMetadata(buildDir, new LinkedHashSet<String>(), false);
        }

        ConfigureRequest request = ConfigureRequest.from(args);
        if (request.sourceDir == null) {
            request.sourceDir = new File(System.getProperty("user.dir", "."));
        }
        if (request.buildDir == null) {
            request.buildDir = request.sourceDir;
        }
        request.sourceDir = canonical(request.sourceDir);
        request.buildDir = canonical(request.buildDir);
        request.buildDir.mkdirs();

        logInit(request.buildDir);
        log("native_tool_shim: cmake configure " + request.buildDir.getAbsolutePath());

        List<NativeTarget> targets = discoverTargets(request.sourceDir);
        if (targets.isEmpty()) {
            targets.add(NativeTarget.fallback(request.sourceDir));
        }

        Toolchain toolchain = Toolchain.from(request);
        writeCmakeCache(request, toolchain);
        writeBuildMetadata(request, toolchain, targets);
        writeBuildNinja(request, toolchain, targets);
        writeCompileCommands(request, toolchain, targets);
        writeFileApiReply(request, toolchain, targets);

        return 0;
    }

    private static int runNinja(List<String> args) throws Exception {
        if (args.contains("--version")) {
            log("1.10.2");
            return 0;
        }

        File buildDir = new File(System.getProperty("user.dir", "."));
        boolean clean = false;
        Set<String> requestedTargets = new LinkedHashSet<>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-C".equals(arg) && i + 1 < args.size()) {
                buildDir = new File(args.get(++i));
            } else if ("-t".equals(arg) && i + 1 < args.size()) {
                clean = clean || "clean".equals(args.get(++i));
            } else if ("-j".equals(arg) || "-l".equals(arg) || "-f".equals(arg)) {
                if (i + 1 < args.size()) {
                    i++;
                }
            } else if (arg.startsWith("-j") || arg.startsWith("-l") || arg.startsWith("-")) {
                continue;
            } else if ("clean".equals(arg)) {
                clean = true;
            } else {
                requestedTargets.add(arg);
            }
        }

        buildDir = canonical(buildDir);
        logInit(buildDir);
        log("native_tool_shim: ninja -C " + buildDir.getAbsolutePath() + " targets=" + requestedTargets);
        return buildFromMetadata(buildDir, requestedTargets, clean);
    }

    private static int buildFromMetadata(File buildDir, Set<String> requestedTargets, boolean clean) throws Exception {
        Properties props = new Properties();
        File meta = new File(buildDir, META_FILE);
        if (!meta.isFile()) {
            logError("native_tool_shim: missing " + meta.getAbsolutePath());
            return 1;
        }
        try (InputStream in = new FileInputStream(meta)) {
            props.load(in);
        }

        int targetCount = intProp(props, "target.count", 0);
        if (clean) {
            for (int i = 0; i < targetCount; i++) {
                deletePath(new File(props.getProperty("target." + i + ".output", "")));
                for (String sourcePath : splitList(props.getProperty("target." + i + ".sources", ""))) {
                    deletePath(objectFile(buildDir, props.getProperty("target." + i + ".name", "native-lib"), new File(sourcePath)));
                }
            }
            return 0;
        }

        Toolchain toolchain = Toolchain.fromProperties(props);
        File sourceDir = new File(props.getProperty("sourceDir", "."));
        int exitCode = 0;
        for (int i = 0; i < targetCount; i++) {
            String targetName = props.getProperty("target." + i + ".name", "native-lib");
            if (!requestedTargets.isEmpty() && !requestedTargets.contains(targetName)
                    && !requestedTargets.contains("lib" + targetName + ".so")) {
                continue;
            }
            List<File> sources = new ArrayList<>();
            for (String sourcePath : splitList(props.getProperty("target." + i + ".sources", ""))) {
                if (!sourcePath.isEmpty()) {
                    sources.add(new File(sourcePath));
                }
            }
            File output = new File(props.getProperty("target." + i + ".output", ""));
            int targetExit = buildTarget(buildDir, sourceDir, toolchain, targetName, sources, output);
            if (targetExit != 0) {
                logError("native_tool_shim: target " + targetName + " failed with exit=" + targetExit);
                exitCode = targetExit;
                break;
            }
        }
        return exitCode;
    }

    private static int buildTarget(
            File buildDir,
            File sourceDir,
            Toolchain toolchain,
            String targetName,
            List<File> sources,
            File output
    ) throws Exception {
        if (sources.isEmpty()) {
            logError("native_tool_shim: no source files for " + targetName);
            return 1;
        }
        if (toolchain.synthetic) {
            return buildSyntheticTarget(output, targetName);
        }

        List<File> objects = new ArrayList<>();
        List<File> includeDirs = includeDirs(sourceDir, sources);
        boolean hasCpp = false;
        for (File source : sources) {
            hasCpp = hasCpp || isCpp(source);
            File object = objectFile(buildDir, targetName, source);
            objects.add(object);
            if (!object.isFile() || object.lastModified() < source.lastModified()) {
                object.getParentFile().mkdirs();
                List<String> command = new ArrayList<>();
                command.add("/system/bin/linker64");
                command.add(isCpp(source) ? toolchain.clangxx.getAbsolutePath() : toolchain.clang.getAbsolutePath());
                addCommonCompilerFlags(command, toolchain);
                command.add("-DANDROID");
                command.add("-D__ANDROID_API__=" + toolchain.api);
                command.add("-fPIC");
                command.add("-fdata-sections");
                command.add("-ffunction-sections");
                command.add("-funwind-tables");
                command.add("-fstack-protector-strong");
                command.add("-D_FORTIFY_SOURCE=2");
                command.add("-Wall");
                command.add("-Werror=format-security");
                command.add(isCpp(source) ? "-std=c++17" : "-std=c17");
                for (File includeDir : includeDirs) {
                    command.add("-I" + includeDir.getAbsolutePath());
                }
                command.add("-DX_NATIVE_SHIM_MARKER=1");
                command.add("-c");
                command.add(source.getAbsolutePath());
                command.add("-o");
                command.add(object.getAbsolutePath());
                log("native_tool_shim: compile-cmd-size=" + command.size());
                for (int dbg_i = 0; dbg_i < command.size(); dbg_i++) {
                    log("native_tool_shim:   cmd[" + dbg_i + "]=" + command.get(dbg_i));
                }
                int code = runProcess(command, sourceDir, toolchain.environment());
                if (code != 0) {
                    logError("native_tool_shim: compile failed for " + source.getAbsolutePath());
                    return code;
                }
            }
        }

        if (output.isFile()) {
            long outputTime = output.lastModified();
            boolean relink = false;
            for (File object : objects) {
                relink = relink || object.lastModified() > outputTime;
            }
            if (!relink) {
                return 0;
            }
        }

        output.getParentFile().mkdirs();
        // Debug: check output dir and libunwind
        if (!output.getParentFile().canWrite()) {
            logError("native_tool_shim: output dir not writable: " + output.getParentFile().getAbsolutePath());
        }
        File unwindCheck = new File(toolchain.clang.getParentFile().getParentFile(),
            "lib/clang/24/lib/aarch64-unknown-linux-android24/libunwind.a");
        if (!unwindCheck.isFile()) {
            logError("native_tool_shim: libunwind.a not found at " + unwindCheck.getAbsolutePath());
        } else {
            log("native_tool_shim: libunwind.a OK (" + unwindCheck.length() + " bytes)");
        }
        List<String> command = new ArrayList<>();
        command.add("/system/bin/linker64");
        command.add((hasCpp ? toolchain.clangxx : toolchain.clang).getAbsolutePath());
        addCommonCompilerFlags(command, toolchain);
        // Explicitly specify linker to handle broken ld.lld symlink
        File linker = findLinker(toolchain);
        if (linker != null) {
            command.add("--ld-path=" + linker.getAbsolutePath());
        }
        command.add("-shared");
        command.add("-v");
        // Ensure compiler-rt builtins exist for clang's resource dir
        ensureCompilerRt(toolchain);
        command.add("-Wl,--build-id=sha1");
        command.add("-Wl,--no-undefined");
        command.add("-Wl,--gc-sections");
        command.add("-Wl,-soname,lib" + targetName + ".so");
        command.add("-Wl,--verbose");
        for (File object : objects) {
            command.add(object.getAbsolutePath());
        }
        if (hasCpp) {
            addCppRuntimeLibraries(command, toolchain);
        }
        command.add("-llog");
        command.add("-landroid");
        command.add("-lm");
        command.add("-ldl");
        command.add("-o");
        command.add(output.getAbsolutePath());
        int linkCode = runProcess(command, sourceDir, toolchain.environment());
        if (linkCode != 0) {
            logError("native_tool_shim: link failed for " + output.getAbsolutePath());
        }
        return linkCode;
    }

    private static int buildSyntheticTarget(File output, String targetName) throws IOException {
        File parent = output.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File placeholder = findPlaceholderSharedLibrary();
        if (placeholder == null) {
            logError("native_tool_shim: no Android-hosted NDK compiler and no bundled placeholder .so found");
            return 1;
        }
        copyFile(placeholder, output);
        log("native_tool_shim: generated placeholder lib" + targetName
                + ".so because the installed NDK only contains desktop host tools. "
                + "Import an Android arm64/aarch64 hosted NDK for real native compilation.");
        return 0;
    }

    private static File findPlaceholderSharedLibrary() {
        String nativeLibDir = System.getenv("MOBILESTUDIO_NATIVE_LIB_DIR");
        if (nativeLibDir == null || nativeLibDir.trim().isEmpty()) {
            return null;
        }
        String[] names = {
                "libexec_redirect.so",
                "libjdk_tool_proxy.so",
                "libaapt2_proxy.so",
                "libgradle_exec.so"
        };
        for (String name : names) {
            File candidate = new File(nativeLibDir, name);
            if (candidate.isFile() && candidate.length() > 0) {
                return candidate;
            }
        }
        File[] libs = new File(nativeLibDir).listFiles();
        if (libs != null) {
            for (File lib : libs) {
                if (lib.isFile() && lib.getName().endsWith(".so") && lib.length() > 0) {
                    return lib;
                }
            }
        }
        return null;
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void addCommonCompilerFlags(List<String> command, Toolchain toolchain) {
        command.add("--target=" + toolchain.targetTriple + toolchain.api);
        command.add("--sysroot=" + toolchain.sysroot.getAbsolutePath());

        File clangRoot = new File(toolchain.prebuiltDir, "lib/clang");
        File[] versions = clangRoot.listFiles(File::isDirectory);
        String resourceDir = null;

        if (versions != null && versions.length > 0) {
            Arrays.sort(versions, Comparator.comparing(File::getName));
            resourceDir = versions[versions.length - 1].getAbsolutePath();
        } else {
            // Fallback: ask clang itself
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add("/system/bin/linker64");
                cmd.add(toolchain.clang.getAbsolutePath());
                cmd.add("--print-resource-dir");
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = br.readLine();
                int rc = p.waitFor();
                if (rc == 0 && line != null && !line.isEmpty()) {
                    resourceDir = line.trim();
                }
            } catch (Exception ignored) {
            }
        }

        if (resourceDir != null) {
            command.add("-resource-dir=" + resourceDir);
        } else {
            throw new RuntimeException("No Clang resource directory found: " + clangRoot);
        }

        command.add("-g");

        if ("Release".equalsIgnoreCase(toolchain.buildType)) {
            command.add("-O2");
            command.add("-DNDEBUG");
        } else {
            command.add("-O0");
        }
    }

    private static void addCppRuntimeLibraries(List<String> command, Toolchain toolchain) {
        File libcxx = findFirstExisting(
                new File(toolchain.prebuiltDir, "sysroot/usr/lib/" + toolchain.targetTriple + "/" + toolchain.api + "/libc++_static.a"),
                new File(toolchain.sysroot, "usr/lib/" + toolchain.targetTriple + "/" + toolchain.api + "/libc++_static.a"),
                new File(toolchain.sysroot, "usr/lib/" + toolchain.targetTriple + "/libc++_static.a")
        );
        File libcxxAbi = findFirstExisting(
                new File(toolchain.prebuiltDir, "sysroot/usr/lib/" + toolchain.targetTriple + "/" + toolchain.api + "/libc++abi.a"),
                new File(toolchain.sysroot, "usr/lib/" + toolchain.targetTriple + "/" + toolchain.api + "/libc++abi.a"),
                new File(toolchain.sysroot, "usr/lib/" + toolchain.targetTriple + "/libc++abi.a")
        );
        if (libcxx != null) {
            command.add(libcxx.getAbsolutePath());
            if (libcxxAbi != null) {
                command.add(libcxxAbi.getAbsolutePath());
            }
        } else {
            command.add("-lc++_shared");
        }
    }

    private static File findFirstExisting(File... files) {
        for (File file : files) {
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private static int runProcess(List<String> command, File cwd, Map<String, String> env) throws Exception {
        log("native_tool_shim: exec " + join(command, " "));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd);
        builder.redirectErrorStream(true);
        if (env != null) {
            builder.environment().putAll(env);
        }
        Process process = builder.start();
        StreamPump pump = new StreamPump(process.getInputStream(), System.out);
        pump.start();
        int exit = process.waitFor();
        pump.join();
        return exit;
    }

    private static List<String> buildLinkerCommand(String tool, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("/system/bin/linker64");
        command.add(tool);
        command.addAll(args);
        return command;
    }

    private static void writeCapabilities(String version) {
        Version parsed = Version.parse(version);
        log("{\"version\":{\"string\":\"" + json(version) + "\",\"major\":" + parsed.major
                + ",\"minor\":" + parsed.minor + ",\"patch\":" + parsed.patch
                + ",\"suffix\":\"\",\"isDirty\":false},\"generators\":[{\"name\":\"Ninja\","
                + "\"toolsetSupport\":false,\"platformSupport\":false,\"extraGenerators\":[]}]}");
    }

    private static void writeCmakeCache(ConfigureRequest request, Toolchain toolchain) throws IOException {
        File cache = new File(request.buildDir, "CMakeCache.txt");
        StringBuilder text = new StringBuilder();
        text.append("CMAKE_HOME_DIRECTORY:INTERNAL=").append(request.sourceDir.getAbsolutePath()).append('\n');
        text.append("CMAKE_GENERATOR:INTERNAL=Ninja\n");
        text.append("CMAKE_BUILD_TYPE:STRING=").append(request.buildType).append('\n');
        text.append("CMAKE_ANDROID_ARCH_ABI:STRING=").append(request.abi).append('\n');
        text.append("ANDROID_ABI:STRING=").append(request.abi).append('\n');
        text.append("ANDROID_PLATFORM:STRING=android-").append(request.api).append('\n');
        text.append("ANDROID_NDK:PATH=").append(request.ndkDir == null ? "" : request.ndkDir.getAbsolutePath()).append('\n');
        text.append("CMAKE_LINKER:FILEPATH=").append(linkerPath(toolchain).getAbsolutePath()).append('\n');
        writeText(cache, text.toString());
    }

    private static void writeBuildMetadata(ConfigureRequest request, Toolchain toolchain, List<NativeTarget> targets)
            throws IOException {
        Properties props = new Properties();
        props.setProperty("sourceDir", request.sourceDir.getAbsolutePath());
        props.setProperty("buildDir", request.buildDir.getAbsolutePath());
        props.setProperty("ndkDir", request.ndkDir == null ? "" : request.ndkDir.getAbsolutePath());
        props.setProperty("prebuiltDir", toolchain.prebuiltDir.getAbsolutePath());
        props.setProperty("sysrootDir", toolchain.sysroot.getAbsolutePath());
        props.setProperty("abi", request.abi);
        props.setProperty("api", Integer.toString(request.api));
        props.setProperty("buildType", request.buildType);
        props.setProperty("synthetic", Boolean.toString(toolchain.synthetic));
        props.setProperty("target.count", Integer.toString(targets.size()));
        for (int i = 0; i < targets.size(); i++) {
            NativeTarget target = targets.get(i);
            props.setProperty("target." + i + ".name", target.name);
            props.setProperty("target." + i + ".sources", joinFiles(target.sources));
            props.setProperty("target." + i + ".output", outputFile(request, target).getAbsolutePath());
        }
        try (OutputStream out = new FileOutputStream(new File(request.buildDir, META_FILE))) {
            props.store(out, "MobileStudio native build metadata");
        }
    }

    private static void writeBuildNinja(ConfigureRequest request, Toolchain toolchain, List<NativeTarget> targets)
            throws IOException {
        StringBuilder ninja = new StringBuilder();
        ninja.append("ninja_required_version = 1.3\n\n");
        ninja.append("rule cc\n  command = ").append(escapeNinja(toolchain.clang.getAbsolutePath()))
                .append(" --target=").append(toolchain.targetTriple).append(request.api)
                .append(" --sysroot=").append(escapeNinja(toolchain.sysroot.getAbsolutePath()))
                .append(" -DANDROID -fPIC -c $in -o $out\n\n");
        ninja.append("rule cxx\n  command = ").append(escapeNinja(toolchain.clangxx.getAbsolutePath()))
                .append(" --target=").append(toolchain.targetTriple).append(request.api)
                .append(" --sysroot=").append(escapeNinja(toolchain.sysroot.getAbsolutePath()))
                .append(" -DANDROID -fPIC -c $in -o $out\n\n");
        ninja.append("rule link\n  command = ").append(escapeNinja(toolchain.clang.getAbsolutePath()))
                .append(" --target=").append(toolchain.targetTriple).append(request.api)
                .append(" --sysroot=").append(escapeNinja(toolchain.sysroot.getAbsolutePath()))
                .append(" -shared $in -llog -landroid -o $out\n\n");
        for (NativeTarget target : targets) {
            List<String> objectPaths = new ArrayList<>();
            for (File source : target.sources) {
                File object = objectFile(request.buildDir, target.name, source);
                objectPaths.add(escapeNinja(object.getAbsolutePath()));
                ninja.append("build ").append(escapeNinja(object.getAbsolutePath()))
                        .append(": ").append(isCpp(source) ? "cxx" : "cc").append(' ')
                        .append(escapeNinja(source.getAbsolutePath())).append('\n');
            }
            File output = outputFile(request, target);
            ninja.append("build ").append(escapeNinja(output.getAbsolutePath()))
                    .append(": link ").append(join(objectPaths, " ")).append('\n');
            ninja.append("build ").append(target.name).append(": phony ")
                    .append(escapeNinja(output.getAbsolutePath())).append("\n\n");
        }
        if (!targets.isEmpty()) {
            ninja.append("default ").append(targets.get(0).name).append('\n');
        }
        writeText(new File(request.buildDir, "build.ninja"), ninja.toString());
    }

    private static void writeCompileCommands(ConfigureRequest request, Toolchain toolchain, List<NativeTarget> targets)
            throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("[\n");
        boolean first = true;
        for (NativeTarget target : targets) {
            for (File source : target.sources) {
                if (!first) {
                    json.append(",\n");
                }
                first = false;
                File object = objectFile(request.buildDir, target.name, source);
                String compiler = isCpp(source) ? toolchain.clangxx.getAbsolutePath() : toolchain.clang.getAbsolutePath();
                String command = compiler + " --target=" + toolchain.targetTriple + request.api
                        + " --sysroot=" + toolchain.sysroot.getAbsolutePath()
                        + " -DANDROID -fPIC -c " + source.getAbsolutePath()
                        + " -o " + object.getAbsolutePath();
                json.append("  {\"directory\":\"").append(json(request.sourceDir.getAbsolutePath()))
                        .append("\",\"command\":\"").append(json(command))
                        .append("\",\"file\":\"").append(json(source.getAbsolutePath())).append("\"}");
            }
        }
        json.append("\n]\n");
        writeText(new File(request.buildDir, "compile_commands.json"), json.toString());
    }

    private static void writeFileApiReply(ConfigureRequest request, Toolchain toolchain, List<NativeTarget> targets)
            throws IOException {
        File replyDir = new File(request.buildDir, ".cmake/api/v1/reply");
        replyDir.mkdirs();

        String indexFile = "index-mobilestudio.json";
        String cacheFile = "cache-v2-mobilestudio.json";
        String cmakeFilesFile = "cmakeFiles-v1-mobilestudio.json";
        String codeModelFile = "codemodel-v2-mobilestudio.json";

        writeText(new File(replyDir, cacheFile), cacheJson(request, toolchain));
        writeText(new File(replyDir, cmakeFilesFile), cmakeFilesJson(request));
        writeText(new File(replyDir, codeModelFile), codeModelJson(request, targets));
        for (NativeTarget target : targets) {
            writeText(new File(replyDir, targetJsonFile(target)), targetJson(request, toolchain, target));
        }
        writeText(new File(replyDir, indexFile), indexJson(request, cacheFile, cmakeFilesFile, codeModelFile));
    }

    private static String cacheJson(ConfigureRequest request, Toolchain toolchain) {
        return "{\n"
                + "  \"version\":{\"major\":2,\"minor\":0},\n"
                + "  \"entries\":[\n"
                + "    {\"name\":\"CMAKE_LINKER\",\"type\":\"FILEPATH\",\"value\":\""
                + json(linkerPath(toolchain).getAbsolutePath()) + "\"},\n"
                + "    {\"name\":\"CMAKE_ANDROID_ARCH_ABI\",\"type\":\"STRING\",\"value\":\"" + json(request.abi) + "\"},\n"
                + "    {\"name\":\"ANDROID_ABI\",\"type\":\"STRING\",\"value\":\"" + json(request.abi) + "\"}\n"
                + "  ]\n"
                + "}\n";
    }

    private static String cmakeFilesJson(ConfigureRequest request) {
        return "{\n"
                + "  \"version\":{\"major\":1,\"minor\":0},\n"
                + "  \"paths\":{\"source\":\"" + json(request.sourceDir.getAbsolutePath())
                + "\",\"build\":\"" + json(request.buildDir.getAbsolutePath()) + "\"},\n"
                + "  \"inputs\":[{\"path\":\"CMakeLists.txt\"}]\n"
                + "}\n";
    }

    private static String codeModelJson(ConfigureRequest request, List<NativeTarget> targets) {
        StringBuilder text = new StringBuilder();
        text.append("{\n");
        text.append("  \"version\":{\"major\":2,\"minor\":4},\n");
        text.append("  \"paths\":{\"source\":\"").append(json(request.sourceDir.getAbsolutePath()))
                .append("\",\"build\":\"").append(json(request.buildDir.getAbsolutePath())).append("\"},\n");
        text.append("  \"configurations\":[{\"name\":\"").append(json(request.buildType)).append("\",\"targets\":[");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            NativeTarget target = targets.get(i);
            text.append("{\"id\":\"").append(json(target.name)).append("::@mobilestudio\",\"name\":\"")
                    .append(json(target.name)).append("\",\"directoryIndex\":0,\"projectIndex\":0,\"jsonFile\":\"")
                    .append(json(targetJsonFile(target))).append("\"}");
        }
        text.append("]}],\n");
        text.append("  \"directories\":[{\"source\":\"").append(json(request.sourceDir.getAbsolutePath()))
                .append("\",\"build\":\"").append(json(request.buildDir.getAbsolutePath()))
                .append("\",\"projectIndex\":0}],\n");
        text.append("  \"projects\":[{\"name\":\"MobileStudioNative\",\"directoryIndexes\":[0],\"targetIndexes\":[");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(i);
        }
        text.append("]}]\n");
        text.append("}\n");
        return text.toString();
    }

    private static String targetJson(ConfigureRequest request, Toolchain toolchain, NativeTarget target) {
        StringBuilder text = new StringBuilder();
        String sourceIndexes = indexes(target.sources.size());
        String language = target.hasCppSources() ? "CXX" : "C";
        text.append("{\n");
        text.append("  \"name\":\"").append(json(target.name)).append("\",\n");
        text.append("  \"type\":\"SHARED_LIBRARY\",\n");
        text.append("  \"paths\":{\"source\":\"").append(json(request.sourceDir.getAbsolutePath()))
                .append("\",\"build\":\"").append(json(request.buildDir.getAbsolutePath())).append("\"},\n");
        text.append("  \"artifacts\":[{\"path\":\"").append(json(outputFile(request, target).getAbsolutePath())).append("\"}],\n");
        text.append("  \"sourceGroups\":[{\"name\":\"Source Files\",\"sourceIndexes\":[")
                .append(sourceIndexes).append("]}],\n");
        text.append("  \"compileGroups\":[{\"language\":\"").append(language)
                .append("\",\"sourceIndexes\":[").append(sourceIndexes).append("],\"sysroot\":{\"path\":\"")
                .append(json(toolchain.sysroot.getAbsolutePath()))
                .append("\"},\"compileCommandFragments\":[{\"fragment\":\"--target=")
                .append(json(toolchain.targetTriple + request.api))
                .append(" --sysroot=").append(json(toolchain.sysroot.getAbsolutePath()))
                .append(" -DANDROID -fPIC\"}],\"defines\":[{\"define\":\"ANDROID\"},{\"define\":\"__ANDROID_API__=")
                .append(request.api).append("\"}],\"includes\":[{\"path\":\"")
                .append(json(request.sourceDir.getAbsolutePath())).append("\"}]}],\n");
        text.append("  \"sources\":[");
        for (int i = 0; i < target.sources.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append("{\"path\":\"").append(json(target.sources.get(i).getAbsolutePath()))
                    .append("\",\"sourceGroupIndex\":0,\"compileGroupIndex\":0}");
        }
        text.append("],\n");
        text.append("  \"link\":{\"language\":\"C\",\"sysroot\":{\"path\":\"")
                .append(json(toolchain.sysroot.getAbsolutePath()))
                .append("\"},\"commandFragments\":[{\"role\":\"libraries\",\"fragment\":\"-llog -landroid\"}]}\n");
        text.append("}\n");
        return text.toString();
    }

    private static String indexes(int size) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(i);
        }
        return text.toString();
    }

    private static String indexJson(
            ConfigureRequest request,
            String cacheFile,
            String cmakeFilesFile,
            String codeModelFile
    ) {
        String version = "3.22.1";
        return "{\n"
                + "  \"cmake\":{\"generator\":{\"multiConfig\":false,\"name\":\"Ninja\"},"
                + "\"paths\":{\"cmake\":\"cmake\",\"cpack\":\"cpack\",\"ctest\":\"ctest\",\"root\":\""
                + json(request.sourceDir.getAbsolutePath()) + "\"},\"version\":{\"string\":\"" + version
                + "\",\"major\":3,\"minor\":22,\"patch\":1,\"suffix\":\"\",\"isDirty\":false}},\n"
                + "  \"objects\":[\n"
                + "    {\"kind\":\"cache\",\"version\":{\"major\":2,\"minor\":0},\"jsonFile\":\"" + cacheFile + "\"},\n"
                + "    {\"kind\":\"cmakeFiles\",\"version\":{\"major\":1,\"minor\":0},\"jsonFile\":\"" + cmakeFilesFile + "\"},\n"
                + "    {\"kind\":\"codemodel\",\"version\":{\"major\":2,\"minor\":4},\"jsonFile\":\"" + codeModelFile + "\"}\n"
                + "  ],\n"
                + "  \"reply\":{\n"
                + "    \"cache-v2\":{\"kind\":\"cache\",\"version\":{\"major\":2,\"minor\":0},\"jsonFile\":\"" + cacheFile + "\"},\n"
                + "    \"cmakeFiles-v1\":{\"kind\":\"cmakeFiles\",\"version\":{\"major\":1,\"minor\":0},\"jsonFile\":\"" + cmakeFilesFile + "\"},\n"
                + "    \"codemodel-v2\":{\"kind\":\"codemodel\",\"version\":{\"major\":2,\"minor\":4},\"jsonFile\":\"" + codeModelFile + "\"}\n"
                + "  }\n"
                + "}\n";
    }

    private static List<NativeTarget> discoverTargets(File sourceDir) throws IOException {
        File cmakeLists = new File(sourceDir, "CMakeLists.txt");
        if (!cmakeLists.isFile()) {
            return new ArrayList<>();
        }
        String text = stripLineComments(readText(cmakeLists));
        Pattern pattern = Pattern.compile(
                "add_library\\s*\\(\\s*([A-Za-z0-9_\\-]+)\\s+SHARED\\s+([\\s\\S]*?)\\)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(text);
        List<NativeTarget> targets = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            List<File> sources = new ArrayList<>();
            for (String token : tokenizeCmakeArguments(matcher.group(2))) {
                if (token.startsWith("$") || token.equalsIgnoreCase("SHARED") || token.equalsIgnoreCase("STATIC")) {
                    continue;
                }
                File source = new File(token);
                if (!source.isAbsolute()) {
                    source = new File(sourceDir, token);
                }
                if (isSourceFile(source)) {
                    sources.add(canonical(source));
                }
            }
            if (sources.isEmpty()) {
                sources.addAll(sourceFiles(sourceDir));
            }
            targets.add(new NativeTarget(name, uniqueFiles(sources)));
        }
        return targets;
    }

    private static List<File> sourceFiles(File sourceDir) {
        List<File> files = new ArrayList<>();
        File[] children = sourceDir.listFiles();
        if (children == null) {
            return files;
        }
        Arrays.sort(children);
        for (File child : children) {
            if (child.isFile() && isSourceFile(child)) {
                files.add(canonical(child));
            }
        }
        return files;
    }

    private static List<String> tokenizeCmakeArguments(String value) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^\\s]+)").matcher(value);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token == null) {
                token = matcher.group(2);
            }
            if (token == null) {
                token = matcher.group(3);
            }
            if (token != null && !token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static List<File> includeDirs(File sourceDir, List<File> sources) {
        LinkedHashSet<File> dirs = new LinkedHashSet<>();
        dirs.add(sourceDir);
        File include = new File(sourceDir, "include");
        if (include.isDirectory()) {
            dirs.add(include);
        }
        for (File source : sources) {
            File parent = source.getParentFile();
            if (parent != null && parent.isDirectory()) {
                dirs.add(parent);
            }
        }
        return new ArrayList<>(dirs);
    }

    private static File outputFile(ConfigureRequest request, NativeTarget target) {
        File outputDir = request.outputDir == null ? request.buildDir : request.outputDir;
        return new File(outputDir, "lib" + target.name + ".so");
    }

    private static File linkerPath(Toolchain toolchain) {
        File linker = findLinker(toolchain);
        return linker != null ? linker : new File(toolchain.prebuiltBin, "ld.lld");
    }

    private static File findLinker(Toolchain toolchain) {
        File lld = Toolchain.findVersionedBinary(toolchain.prebuiltBin, "ld.lld");
        if (lld != null) return lld;
        lld = Toolchain.findVersionedBinary(toolchain.prebuiltBin, "ld");
        if (lld != null) return lld;
        File[] candidates = toolchain.prebuiltBin.listFiles((dir, name) ->
                name.matches("ld\\.lld-\\d+") && new File(dir, name).isFile());
        if (candidates != null && candidates.length > 0) {
            Arrays.sort(candidates);
            for (int i = candidates.length - 1; i >= 0; i--) {
                if (isArm64Elf(candidates[i])) return candidates[i];
            }
        }
        return null;
    }

    private static boolean isArm64Elf(File file) {
        if (!file.isFile() || file.length() < 20) return false;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] hdr = new byte[20];
            if (fis.read(hdr) < 20) return false;
            if (hdr[0] != 0x7f || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F') return false;
            boolean le = hdr[5] == 1;
            int machine = le ?
                (hdr[18] & 0xff) | ((hdr[19] & 0xff) << 8) :
                ((hdr[18] & 0xff) << 8) | (hdr[19] & 0xff);
            return machine == 183; // EM_AARCH64
        } catch (Exception e) {
            return false;
        }
    }

    private static void ensureCompilerRt(Toolchain toolchain) {
        File clangRoot = new File(toolchain.prebuiltDir, "lib/clang");
        File[] versions = clangRoot.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) return;
        Arrays.sort(versions, Comparator.comparing(File::getName));
        File latest = versions[versions.length - 1];
        File libDir = new File(latest, "lib");
        if (!libDir.isDirectory()) libDir.mkdirs();

        // Find/create the expected triple subdirectory
        String expected = toolchain.targetTriple + toolchain.api;
        File[] tripleDirs = libDir.listFiles(File::isDirectory);
        File tripleDir = null;
        if (tripleDirs != null) {
            for (File d : tripleDirs) {
                if (d.getName().contains(expected) || expected.contains(d.getName())) {
                    tripleDir = d;
                    break;
                }
            }
        }
        if (tripleDir == null) {
            String withUnknown = targetTripleWithUnknown(toolchain) + toolchain.api;
            tripleDir = new File(libDir, withUnknown);
        }
        tripleDir.mkdirs();
        File builtinsTarget = new File(tripleDir, "libclang_rt.builtins.a");
        File unwindTarget = new File(tripleDir, "libunwind.a");

        // Search NDK clang directories for correct-arch builtins + libunwind
        File sysrootParent = toolchain.sysroot.getParentFile();
        if (sysrootParent != null) {
            File ndkClangRoot = new File(sysrootParent, "lib/clang");
            File[] ndkVersions = ndkClangRoot.listFiles(File::isDirectory);
            if (ndkVersions != null) {
                Arrays.sort(ndkVersions, Comparator.comparing(File::getName));
                for (File ndkClang : ndkVersions) {
                    File found = findBuiltinsForArch(ndkClang, "libclang_rt.builtins.a", toolchain.targetTriple);
                    if (found != null) {
                        try {
                            copyIfSizeDiffers(found, builtinsTarget);
                        } catch (IOException e) {
                            logError("native_tool_shim: failed to copy builtins: " + e.getMessage());
                        }
                        // libunwind.a sits alongside builtins in the NDK clang dir
                        File unwindInNdk = new File(found.getParentFile(), "libunwind.a");
                        if (unwindInNdk.isFile()) {
                            try {
                                copyIfSizeDiffers(unwindInNdk, unwindTarget);
                            } catch (IOException e) {
                                logError("native_tool_shim: failed to copy libunwind: " + e.getMessage());
                            }
                        }
                        log("native_tool_shim: ensured compiler-rt from " + found.getParentFile().getAbsolutePath());
                        break;
                    }
                }
            }
        }

        // Fallback: sysroot/usr/lib for libunwind.a if still missing
        if (!unwindTarget.isFile() || unwindTarget.length() == 0) {
            for (String libPath : new String[]{
                    "usr/lib/" + toolchain.targetTriple + "/" + toolchain.api,
                    "usr/lib/" + toolchain.targetTriple,
                    "usr/lib"
            }) {
                File candidate = new File(toolchain.sysroot, libPath + "/libunwind.a");
                if (candidate.isFile()) {
                    try {
                        copyIfSizeDiffers(candidate, unwindTarget);
                    } catch (IOException e) {
                        logError("native_tool_shim: failed to copy libunwind: " + e.getMessage());
                    }
                    break;
                }
            }
        }
    }

    private static void copyIfSizeDiffers(File src, File dst) throws IOException {
        if (dst.isFile() && dst.length() == src.length()) return;
        copyFile(src, dst);
        log("native_tool_shim: copied " + dst.getName() + " from " + src.getAbsolutePath());
    }

    private static File findBuiltinsForArch(File clangDir, String name, String targetTriple) {
        String arch = targetTriple.replaceAll("-.*", "");
        // NDK path: lib/linux/<arch>/<name>
        File ndkPath = new File(clangDir, "lib/linux/" + arch + "/" + name);
        if (ndkPath.isFile()) return ndkPath;
        // NDK path: lib/<triple>/<name>
        for (String variant : new String[]{targetTriple, arch + "-unknown-linux-gnu"}) {
            File p = new File(clangDir, "lib/" + variant + "/" + name);
            if (p.isFile()) return p;
        }
        // Fallback: recursive search only in directories matching the architecture
        return findBuiltinsInDirFiltered(clangDir, name, arch);
    }

    private static File findBuiltinsInDirFiltered(File dir, String name, String archFilter) {
        if (!dir.isDirectory()) return null;
        String dn = dir.getName();
        // Prune: skip non-matching arch-specific dirs (contain a hyphen + known wrong arch)
        if (dn.contains("-") && !dn.contains(archFilter)
                && !dn.equals("lib") && !dn.equals("linux") && !dn.equals("include")) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isFile() && child.getName().equals(name)) {
                // Only accept if parent directory name contains the arch filter
                if (child.getParentFile().getName().contains(archFilter)) {
                    return child;
                }
                continue;
            }
            File found = findBuiltinsInDirFiltered(child, name, archFilter);
            if (found != null) return found;
        }
        return null;
    }

    private static String targetTripleWithUnknown(Toolchain toolchain) {
        String t = toolchain.targetTriple;
        // aarch64-linux-android -> aarch64-unknown-linux-android
        int firstDash = t.indexOf('-');
        if (firstDash > 0) {
            return t.substring(0, firstDash) + "-unknown" + t.substring(firstDash);
        }
        return "aarch64-unknown-linux-android";
    }

    private static File objectFile(File buildDir, String targetName, File source) {
        String safe = sha1(source.getAbsolutePath()).substring(0, 12) + "-" + source.getName() + ".o";
        return new File(new File(buildDir, "CMakeFiles/" + targetName + ".dir"), safe);
    }

    private static List<File> uniqueFiles(List<File> files) {
        LinkedHashMap<String, File> map = new LinkedHashMap<>();
        for (File file : files) {
            map.put(file.getAbsolutePath(), file);
        }
        return new ArrayList<>(map.values());
    }

    private static boolean isSourceFile(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".c") || name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx");
    }

    private static boolean isCpp(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx");
    }

    private static String targetJsonFile(NativeTarget target) {
        return "target-" + target.name + "-Debug-mobilestudio.json";
    }

    private static String stripLineComments(String value) {
        StringBuilder out = new StringBuilder();
        String[] lines = value.split("\\r?\\n");
        for (String line : lines) {
            int hash = line.indexOf('#');
            out.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        return out.toString();
    }

    private static String readText(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException ignored) {
            return file.getAbsoluteFile();
        }
    }

    private static int indexOf(List<String> args, String value) {
        for (int i = 0; i < args.size(); i++) {
            if (value.equals(args.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String cmakeVersionFromPath(String tool) {
        File dir = new File(tool).getParentFile();
        if (dir != null) {
            File parent = dir.getParentFile();
            if (parent != null && parent.getName().matches("\\d+\\.\\d+\\.\\d+.*")) {
                return parent.getName();
            }
        }
        return "3.22.1";
    }

    private static int intProp(Properties props, String name, int fallback) {
        try {
            return Integer.parseInt(props.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(value.split(Pattern.quote(SEP), -1)));
    }

    private static String joinFiles(List<File> files) {
        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }
        return join(paths, SEP);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append(separator);
            }
            text.append(values.get(i));
        }
        return text.toString();
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format(Locale.US, "\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    private static String escapeNinja(String value) {
        return value.replace("$", "$$").replace(" ", "$ ");
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode()) + "000000000000";
        }
    }

    private static void deletePath(File file) {
        if (file == null || file.getPath().isEmpty() || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deletePath(child);
                }
            }
        }
        file.delete();
    }

    private static final class ConfigureRequest {
        File sourceDir;
        File buildDir;
        File outputDir;
        File ndkDir;
        String abi = "arm64-v8a";
        String buildType = "Debug";
        int api = 21;

        static ConfigureRequest from(List<String> args) {
            ConfigureRequest request = new ConfigureRequest();
            Map<String, String> defs = new LinkedHashMap<>();
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if ("-H".equals(arg) && i + 1 < args.size()) {
                    request.sourceDir = new File(args.get(++i));
                } else if (arg.startsWith("-H") && arg.length() > 2) {
                    request.sourceDir = new File(arg.substring(2));
                } else if ("-S".equals(arg) && i + 1 < args.size()) {
                    request.sourceDir = new File(args.get(++i));
                } else if (arg.startsWith("-S") && arg.length() > 2) {
                    request.sourceDir = new File(arg.substring(2));
                } else if ("-B".equals(arg) && i + 1 < args.size()) {
                    request.buildDir = new File(args.get(++i));
                } else if (arg.startsWith("-B") && arg.length() > 2) {
                    request.buildDir = new File(arg.substring(2));
                } else if (arg.startsWith("-D")) {
                    String def = arg.substring(2);
                    int eq = def.indexOf('=');
                    if (eq > 0) {
                        defs.put(def.substring(0, eq), def.substring(eq + 1));
                    }
                } else if ("-G".equals(arg) && i + 1 < args.size()) {
                    i++;
                }
            }

            request.ndkDir = fileDef(defs, "ANDROID_NDK", "CMAKE_ANDROID_NDK");
            request.outputDir = fileDef(defs, "CMAKE_LIBRARY_OUTPUT_DIRECTORY", "CMAKE_RUNTIME_OUTPUT_DIRECTORY");
            request.abi = stringDef(defs, request.abi, "ANDROID_ABI", "CMAKE_ANDROID_ARCH_ABI");
            request.buildType = stringDef(defs, request.buildType, "CMAKE_BUILD_TYPE");
            request.api = parseApi(stringDef(defs, Integer.toString(request.api), "ANDROID_PLATFORM", "CMAKE_SYSTEM_VERSION"));
            return request;
        }

        private static File fileDef(Map<String, String> defs, String... keys) {
            for (String key : keys) {
                String value = defs.get(key);
                if (value != null && !value.isEmpty()) {
                    return new File(value);
                }
            }
            return null;
        }

        private static String stringDef(Map<String, String> defs, String fallback, String... keys) {
            for (String key : keys) {
                String value = defs.get(key);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
            return fallback;
        }

        private static int parseApi(String value) {
            Matcher matcher = Pattern.compile("(\\d+)").matcher(value == null ? "" : value);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return 21;
                }
            }
            return 21;
        }
    }

    private static final class NativeTarget {
        final String name;
        final List<File> sources;

        NativeTarget(String name, List<File> sources) {
            this.name = name;
            this.sources = sources;
        }

        static NativeTarget fallback(File sourceDir) {
            return new NativeTarget("native-lib", sourceFiles(sourceDir));
        }

        boolean hasCppSources() {
            for (File source : sources) {
                if (isCpp(source)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Toolchain {
        final File prebuiltDir;
        final File prebuiltBin;
        final File sysroot;
        final File clang;
        final File clangxx;
        final String abi;
        final int api;
        final String buildType;
        final String targetTriple;
        final boolean synthetic;

        Toolchain(File prebuiltDir, String abi, int api, String buildType) {
            this(prebuiltDir, findSysrootDir(prebuiltDir, null), abi, api, buildType, false);
        }

        Toolchain(File prebuiltDir, String abi, int api, String buildType, boolean synthetic) {
            this(prebuiltDir, findSysrootDir(prebuiltDir, null), abi, api, buildType, synthetic);
        }

        Toolchain(File prebuiltDir, File sysrootDir, String abi, int api, String buildType, boolean synthetic) {
            this.prebuiltDir = prebuiltDir;
            this.prebuiltBin = new File(prebuiltDir, "bin");
            this.sysroot = sysrootDir;
            this.clang = findVersionedBinary(prebuiltBin, "clang");
            this.clangxx = findVersionedBinary(prebuiltBin, "clang++");
            this.abi = abi;
            this.api = api;
            this.buildType = buildType;
            this.targetTriple = targetTriple(abi);
            this.synthetic = synthetic;
        }

        private static File findVersionedBinary(File binDir, String baseName) {
            File direct = new File(binDir, baseName);
            if (direct.isFile() && direct.length() > 0 && isArm64Elf(direct)) return direct;
            File[] candidates = binDir.listFiles((dir, name) ->
                    name.startsWith(baseName + "-") && new File(dir, name).isFile());
            if (candidates != null && candidates.length > 0) {
                Arrays.sort(candidates);
                for (int i = candidates.length - 1; i >= 0; i--) {
                    if (isArm64Elf(candidates[i])) return candidates[i];
                }
            }
            return null;
        }

        static Toolchain from(ConfigureRequest request) {
            try {
                File prebuilt = findPrebuiltDir(request.ndkDir);
                File sysroot = findSysrootDir(prebuilt, request.ndkDir);
                return new Toolchain(prebuilt, sysroot, request.abi, request.api, request.buildType, false);
            } catch (IllegalStateException exception) {
                logError("native_tool_shim: " + exception.getMessage());
                if (!"true".equalsIgnoreCase(System.getenv("MOBILESTUDIO_ALLOW_NATIVE_PLACEHOLDER"))) {
                    throw exception;
                }
                logError("native_tool_shim: using placeholder native output for this build");
                File fallback = request.ndkDir == null ? new File(".") : request.ndkDir;
                return new Toolchain(fallback, new File(fallback, "sysroot"), request.abi, request.api, request.buildType, true);
            }
        }

        static Toolchain fromProperties(Properties props) {
            File prebuilt = new File(props.getProperty("prebuiltDir", ""));
            File sysroot = new File(props.getProperty("sysrootDir", new File(prebuilt, "sysroot").getAbsolutePath()));
            String abi = props.getProperty("abi", "arm64-v8a");
            int api = intProp(props, "api", 21);
            String buildType = props.getProperty("buildType", "Debug");
            boolean synthetic = Boolean.parseBoolean(props.getProperty("synthetic", "false"));
            return new Toolchain(prebuilt, sysroot, abi, api, buildType, synthetic);
        }

        Map<String, String> environment() {
            Map<String, String> env = new LinkedHashMap<>();
            List<String> libDirs = new ArrayList<>();
            // relative to prebuiltDir (e.g. toolchain/llvm/usr/clang19/)
            libDirs.add(new File(prebuiltDir, "usr/lib64").getAbsolutePath());
            libDirs.add(new File(prebuiltDir, "usr/lib").getAbsolutePath());
            libDirs.add(new File(prebuiltDir, "lib64").getAbsolutePath());
            libDirs.add(new File(prebuiltDir, "lib").getAbsolutePath());

            //add
            File ndkLib = new File(prebuiltDir,"lib");
            if (ndkLib.isDirectory()) {
                libDirs.add(ndkLib.getAbsolutePath());

            }
            // relative to prebuiltDir's parent (e.g. toolchain/llvm/usr/)
            File parent = prebuiltDir.getParentFile();
            if (parent != null) {
                libDirs.add(new File(parent, "lib64").getAbsolutePath());
                libDirs.add(new File(parent, "lib").getAbsolutePath());
            }
            // relative to grandparent (e.g. toolchain/llvm/)
            if (parent != null) {
                File grandparent = parent.getParentFile();
                if (grandparent != null) {
                    libDirs.add(new File(grandparent, "lib64").getAbsolutePath());
                    libDirs.add(new File(grandparent, "lib").getAbsolutePath());
                }
            }
            libDirs.add(prebuiltBin.getAbsolutePath());
            libDirs.add(firstNonBlank(System.getenv("LD_LIBRARY_PATH"), ""));
            String libPath = String.join(":", libDirs);
            String tmpDir = firstNonBlank(System.getenv("TMPDIR"), System.getProperty("java.io.tmpdir"), ".");
            String homeDir = firstNonBlank(System.getenv("HOME"), System.getProperty("user.home"), ".");
            env.put("LD_LIBRARY_PATH", libPath);
            env.put("PATH", "/system/bin:/system/xbin:" + prebuiltBin.getAbsolutePath());
            env.put("ANDROID_DATA", "/data");
            env.put("ANDROID_ROOT", "/system");
            env.put("TMPDIR", tmpDir);
            env.put("HOME", homeDir);
            String execRedirect = System.getenv("MOBILESTUDIO_EXEC_REDIRECT");
            if (execRedirect != null && !execRedirect.isEmpty()) {
                env.put("LD_PRELOAD", execRedirect);
            }
            return env;
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value;
                }
            }
            return ".";
        }

        private static File findPrebuiltDir(File ndkDir) {
            if (ndkDir == null) {
                throw new IllegalStateException("ANDROID_NDK was not provided by Gradle");
            }
            File prebuiltRoot = new File(ndkDir, "toolchains/llvm/prebuilt");
            File[] dirs = prebuiltRoot.listFiles();
            if (dirs != null) {
                Arrays.sort(dirs);
                List<String> rejectedDesktopHosts = new ArrayList<>();
                for (File dir : dirs) {
                    if (!isUsableClangDir(dir)) {
                        continue;
                    }
                    String name = dir.getName().toLowerCase(Locale.US);
                    if (isAndroidHostPrebuilt(name)) {
                        return dir;
                    }
                    if (isDesktopHostPrebuilt(name)) {
                        rejectedDesktopHosts.add(dir.getName());
                    }
                }
                File external = findExternalAndroidCompilerDir(ndkDir);
                if (external != null) {
                    log("native_tool_shim: using Android-hosted LLVM from " + external.getAbsolutePath());
                    return external;
                }
                if (!rejectedDesktopHosts.isEmpty()) {
                    throw new IllegalStateException(
                            "Installed NDK is not executable on Android. Found desktop host prebuilts "
                                    + rejectedDesktopHosts
                                    + " under " + prebuiltRoot
                                    + ". Import an Android arm64/aarch64 hosted LLVM/NDK; linux-x86_64 tools cannot run on the phone.");
                }
            }
            File external = findExternalAndroidCompilerDir(ndkDir);
            if (external != null) {
                log("native_tool_shim: using Android-hosted LLVM from " + external.getAbsolutePath());
                return external;
            }
            throw new IllegalStateException("No Android-executable clang found. Put clang/clang++ in "
                    + prebuiltRoot + "/android-aarch64/bin or set MOBILESTUDIO_LLVM_SEARCH_ROOTS.");
        }

        private static File findExternalAndroidCompilerDir(File ndkDir) {
            List<File> roots = new ArrayList<>();
            roots.add(ndkDir);
            String env = System.getenv("MOBILESTUDIO_LLVM_SEARCH_ROOTS");
            if (env != null) {
                for (String path : env.split(":")) {
                    if (!path.trim().isEmpty()) {
                        roots.add(new File(path.trim()));
                    }
                }
            }
            for (File root : roots) {
                File direct = compilerDirIfUsable(root);
                if (direct != null) return direct;
                File[] rootChildren = root.listFiles();
                if (rootChildren != null) {
                    Arrays.sort(rootChildren);
                    for (File child : rootChildren) {
                        File candidate = compilerDirIfUsable(child);
                        if (candidate != null && isAndroidHostPrebuilt(child.getName().toLowerCase(Locale.US))) {
                            return candidate;
                        }
                    }
                    for (File child : rootChildren) {
                        File candidate = compilerDirIfUsable(child);
                        if (candidate != null && !isDesktopHostPrebuilt(child.getName().toLowerCase(Locale.US))) {
                            return candidate;
                        }
                    }
                }
                File llvm = compilerDirIfUsable(new File(root, "llvm"));
                if (llvm != null) return llvm;
                File usr = compilerDirIfUsable(new File(root, "usr"));
                if (usr != null) return usr;
                File prebuilt = new File(root, "toolchains/llvm/prebuilt");
                File[] children = prebuilt.listFiles();
                if (children != null) {
                    Arrays.sort(children);
                    for (File child : children) {
                        File candidate = compilerDirIfUsable(child);
                        if (candidate != null && isAndroidHostPrebuilt(child.getName().toLowerCase(Locale.US))) {
                            return candidate;
                        }
                    }
                    for (File child : children) {
                        File candidate = compilerDirIfUsable(child);
                        if (candidate != null && !isDesktopHostPrebuilt(child.getName().toLowerCase(Locale.US))) {
                            return candidate;
                        }
                    }
                }
            }
            return null;
        }

        private static File compilerDirIfUsable(File root) {
            if (root == null || !root.isDirectory()) return null;
            if (isUsableClangDir(root)) return root;
            // Check usr/clang-{version} subdirectories
            File usr = new File(root, "usr");
            if (usr.isDirectory()) {
                File[] subdirs = usr.listFiles(File::isDirectory);
                if (subdirs != null) {
                    Arrays.sort(subdirs);
                    for (int i = subdirs.length - 1; i >= 0; i--) {
                        if (isUsableClangDir(subdirs[i])) return subdirs[i];
                    }
                }
            }
            File[] llvmDirs = new File(root, "lib").listFiles();
            if (llvmDirs != null) {
                Arrays.sort(llvmDirs);
                for (File llvmDir : llvmDirs) {
                    File candidate = new File(llvmDir, "bin").getParentFile();
                    if (candidate != null && isUsableClangDir(candidate)) return candidate;
                }
            }
            return null;
        }

        private static boolean isUsableClangDir(File dir) {
            if (dir == null || !dir.isDirectory()) return false;
            File binDir = new File(dir, "bin");
            if (!binDir.isDirectory()) return false;
            return findVersionedBinary(binDir, "clang") != null
                    && findVersionedBinary(binDir, "clang++") != null;
        }

        private static File findSysrootDir(File compilerDir, File ndkDir) {
            List<File> candidates = new ArrayList<>();
            if (compilerDir != null) {
                candidates.add(new File(compilerDir, "sysroot"));
                File parent = compilerDir.getParentFile();
                if (parent != null) candidates.add(new File(parent, "sysroot"));
            }
            if (ndkDir != null) {
                File prebuiltRoot = new File(ndkDir, "toolchains/llvm/prebuilt");
                File[] dirs = prebuiltRoot.listFiles();
                if (dirs != null) {
                    Arrays.sort(dirs);
                    for (File dir : dirs) candidates.add(new File(dir, "sysroot"));
                }
                candidates.add(new File(ndkDir, "sysroot"));
            }
            for (File candidate : candidates) {
                if (candidate.isDirectory() && new File(candidate, "usr/include").isDirectory()) {
                    return candidate;
                }
            }
            throw new IllegalStateException("No Android NDK sysroot found. Keep the official NDK sysroot and add only Android-hosted LLVM binaries.");
        }

        private static boolean isAndroidHostPrebuilt(String name) {
            return name.contains("android")
                    || name.contains("termux")
                    || name.contains("aarch64")
                    || name.contains("arm64");
        }

        private static boolean isDesktopHostPrebuilt(String name) {
            return name.contains("linux-x86_64")
                    || name.contains("linux-x64")
                    || name.contains("darwin")
                    || name.contains("windows")
                    || name.contains("win32")
                    || name.contains("x86_64");
        }

        private static String targetTriple(String abi) {
            if ("armeabi-v7a".equals(abi)) {
                return "armv7a-linux-androideabi";
            }
            if ("x86".equals(abi)) {
                return "i686-linux-android";
            }
            if ("x86_64".equals(abi)) {
                return "x86_64-linux-android";
            }
            return "aarch64-linux-android";
        }
    }

    private static final class Version {
        final int major;
        final int minor;
        final int patch;

        Version(int major, int minor, int patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        static Version parse(String value) {
            Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(value == null ? "" : value);
            if (matcher.find()) {
                return new Version(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                );
            }
            return new Version(3, 22, 1);
        }
    }

    private static final class StreamPump extends Thread {
        private final InputStream input;
        private final java.io.PrintStream output;

        StreamPump(InputStream input, java.io.PrintStream output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.println(line);
                    if (logWriter != null) {
                        logWriter.println("[SUB] " + line);
                        logWriter.flush();
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }
}
