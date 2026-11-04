import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Regression C: the JVM must be able to fork its own java child through
 * ProcessBuilder (this is exactly what Gradle does when it starts a daemon).
 * argv[0] of the child is $JAVA_HOME/bin/java, i.e. the linker-wrapper script.
 */
public class PBChild {
    public static void main(String[] args) throws Exception {
        // args[0] = the executable to fork (default: the real tree's bin/java)
        String target = args.length > 0
                ? args[0]
                : new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        System.out.println("PB_EXEC=" + target);

        ProcessBuilder pb = new ProcessBuilder(target, "-version");
        pb.redirectErrorStream(true);
        long t0 = System.currentTimeMillis();
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        int rc = p.waitFor();
        long dt = System.currentTimeMillis() - t0;
        System.out.println("PB_CHILD_EXIT=" + rc + " (" + dt + "ms)");
        System.out.println("PB_CHILD_OUT=" + out.toString().replace('\n', '|').trim());
        System.exit(rc == 0 ? 0 : 2);
    }
}
