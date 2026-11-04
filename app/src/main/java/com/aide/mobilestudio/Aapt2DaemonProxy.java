package com.aide.mobilestudio;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Aapt2DaemonProxy {
    private static final int BUFFER_SIZE = 64 * 1024;

    private Aapt2DaemonProxy() {
    }

    public static void main(String[] args) {
        String aapt2Path = args.length > 0 ? args[0] : System.getenv("MOBILESTUDIO_AAPT2_EXEC");
        if (aapt2Path == null || aapt2Path.isEmpty()) {
            System.err.println("aapt2_proxy: missing MOBILESTUDIO_AAPT2_EXEC");
            System.exit(127);
            return;
        }

        List<String> forwardedArgs = new ArrayList<>();
        if (args.length > 1) {
            for (int i = 1; i < args.length; i++) {
                forwardedArgs.add(args[i]);
            }
        }

        if (forwardedArgs.isEmpty() || (forwardedArgs.size() == 1 && "m".equals(forwardedArgs.get(0)))) {
            System.exit(runDaemon(aapt2Path));
            return;
        }

        System.exit(runSingleShot(aapt2Path, forwardedArgs));
    }

    private static int runSingleShot(String aapt2Path, List<String> args) {
        String stdoutFile = System.getenv("MOBILESTUDIO_AAPT2_STDOUT_FILE");
        String stderrFile = System.getenv("MOBILESTUDIO_AAPT2_STDERR_FILE");
        if (stdoutFile != null && !stdoutFile.isEmpty()
                && stderrFile != null && !stderrFile.isEmpty()) {
            try {
                ProcessResult result = runAapt2AndCollect(aapt2Path, args);
                writeLines(stdoutFile, result.stdout);
                writeLines(stderrFile, result.stderr);
                return result.exitCode;
            } catch (IOException e) {
                try {
                    writeLines(stderrFile, singletonLine("aapt2_proxy: start failed: " + e));
                } catch (IOException ignored) {
                }
                return 127;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try {
                    writeLines(stderrFile, singletonLine("aapt2_proxy: interrupted"));
                } catch (IOException ignored) {
                }
                return 130;
            }
        }

        Process child;
        try {
            child = startAapt2(aapt2Path, args);
        } catch (IOException e) {
            System.err.println("aapt2_proxy: start failed: " + e);
            return 127;
        }

        Process process = child;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }, "aapt2-proxy-shutdown"));

        Thread stdin = pipeAsync(System.in, child.getOutputStream(), true, "stdin");
        Thread stdout = pipeAsync(child.getInputStream(), System.out, false, "stdout");
        Thread stderr = pipeAsync(child.getErrorStream(), System.err, false, "stderr");

        int exitCode;
        try {
            exitCode = child.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
            exitCode = 130;
        }

        joinQuietly(stdout);
        joinQuietly(stderr);
        stdin.interrupt();
        return exitCode;
    }

    private static int runDaemon(String aapt2Path) {
        println(System.out, "Ready");

        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                String daemonCommand = reader.readLine();
                if (daemonCommand == null) {
                    return 0;
                }
                if (daemonCommand.isEmpty()) {
                    continue;
                }

                List<String> requestArgs = readRequestArgs(reader);
                if ("quit".equals(daemonCommand)) {
                    println(System.out, "Exiting daemon");
                    return 0;
                }

                String subcommand = mapDaemonCommand(daemonCommand);
                if (subcommand == null) {
                    println(System.err, "Error");
                    println(System.err, "aapt2_proxy: unsupported daemon command: " + daemonCommand);
                    println(System.err, "Done");
                    continue;
                }

                List<String> oneShotArgs = new ArrayList<>(requestArgs.size() + 1);
                oneShotArgs.add(subcommand);
                oneShotArgs.addAll(requestArgs);
                ProcessResult result;
                try {
                    result = runAapt2AndCollect(aapt2Path, oneShotArgs);
                } catch (IOException e) {
                    println(System.err, "Error");
                    println(System.err, "aapt2_proxy: start failed: " + e);
                    println(System.err, "Done");
                    continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    println(System.err, "Error");
                    println(System.err, "aapt2_proxy: interrupted");
                    println(System.err, "Done");
                    return 130;
                }

                if (result.exitCode == 0) {
                    printLines(System.out, result.stdout);
                    printPrefixedLines(System.err, "stderr: ", result.stderr);
                    println(System.err, "Done");
                } else {
                    println(System.err, "Error");
                    printPrefixedLines(System.err, "stdout: ", result.stdout);
                    printPrefixedLines(System.err, "stderr: ", result.stderr);
                    println(System.err, "aapt2_proxy: aapt2 " + subcommand + " exited with " + result.exitCode);
                    println(System.err, "Done");
                }
            }
        } catch (IOException e) {
            println(System.err, "aapt2_proxy: daemon failed: " + e);
            return 127;
        }
    }

    private static List<String> readRequestArgs(BufferedReader reader) throws IOException {
        List<String> requestArgs = new ArrayList<>();
        while (true) {
            String line = reader.readLine();
            if (line == null || line.isEmpty()) {
                return requestArgs;
            }
            requestArgs.add(line);
        }
    }

    private static String mapDaemonCommand(String daemonCommand) {
        if ("c".equals(daemonCommand)) return "compile";
        if ("l".equals(daemonCommand)) return "link";
        if ("convert".equals(daemonCommand)) return "convert";
        return null;
    }

    private static ProcessResult runAapt2AndCollect(String aapt2Path, List<String> args)
            throws IOException, InterruptedException {
        Process child = startAapt2(aapt2Path, args);
        try {
            child.getOutputStream().close();
        } catch (IOException ignored) {
        }

        StreamCollector stdout = new StreamCollector(child.getInputStream());
        StreamCollector stderr = new StreamCollector(child.getErrorStream());
        Thread stdoutThread = new Thread(stdout, "aapt2-proxy-collect-stdout");
        Thread stderrThread = new Thread(stderr, "aapt2-proxy-collect-stderr");
        stdoutThread.start();
        stderrThread.start();

        int exitCode;
        try {
            exitCode = child.waitFor();
        } catch (InterruptedException e) {
            child.destroyForcibly();
            throw e;
        }

        joinQuietly(stdoutThread);
        joinQuietly(stderrThread);
        return new ProcessResult(exitCode, stdout.lines, stderr.lines);
    }

    private static Process startAapt2(String aapt2Path, List<String> args) throws IOException {
        /*
         * aapt2 lives under app-data (noexec mount): executing it directly
         * fails with EACCES ("Permission denied"). Loading it through the
         * bionic linker works and keeps argv/stdio/exit-code semantics:
         *
         *   /system/bin/linker64 <AAPT2_PATH> <ARGS...>
         */
        List<String> command = new ArrayList<>(args.size() + 2);
        command.add("/system/bin/linker64");
        command.add(aapt2Path);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        java.util.Map<String, String> env = pb.environment();

        // LD_LIBRARY_PATH: nativeLibDir + system libs
        String nativeLibDir = System.getenv("MOBILESTUDIO_NATIVE_LIB_DIR");
        String sysLib = "/system/lib64:/system/lib";
        String existingLd = System.getenv("LD_LIBRARY_PATH");
        String ldPath;
        if (nativeLibDir != null && !nativeLibDir.isEmpty()) {
            ldPath = nativeLibDir + ":" + sysLib;
        } else {
            ldPath = sysLib;
        }
        if (existingLd != null && !existingLd.isEmpty()) ldPath += ":" + existingLd;
        env.put("LD_LIBRARY_PATH", ldPath);

        return pb.start();
    }

    private static Thread pipeAsync(
            InputStream input,
            OutputStream output,
            boolean closeOutput,
            String name
    ) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) break;
                    output.write(buffer, 0, read);
                    output.flush();
                }
            } catch (IOException ignored) {
            } finally {
                if (closeOutput) {
                    try {
                        output.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "aapt2-proxy-" + name);
        thread.start();
        return thread;
    }

    private static void printLines(PrintStream stream, List<String> lines) {
        for (String line : lines) {
            println(stream, line);
        }
    }

    private static void printPrefixedLines(PrintStream stream, String prefix, List<String> lines) {
        for (String line : lines) {
            println(stream, prefix + line);
        }
    }

    private static void println(PrintStream stream, String line) {
        stream.println(line);
        stream.flush();
    }

    private static List<String> singletonLine(String line) {
        List<String> lines = new ArrayList<>(1);
        lines.add(line);
        return lines;
    }

    private static void writeLines(String path, List<String> lines) throws IOException {
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8));
        try {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class StreamCollector implements Runnable {
        final List<String> lines = new ArrayList<>();
        private final InputStream input;

        StreamCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8));
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        return;
                    }
                    lines.add(line);
                }
            } catch (IOException e) {
                lines.add("aapt2_proxy: output read failed: " + e);
            }
        }
    }

    private static final class ProcessResult {
        final int exitCode;
        final List<String> stdout;
        final List<String> stderr;

        ProcessResult(int exitCode, List<String> stdout, List<String> stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
