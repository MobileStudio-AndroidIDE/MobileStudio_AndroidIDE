import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Interposer in vs out: fork the same wrapper script with and without LD_PRELOAD. */
public class PBChild3 {
    private static int run(String[] cmd, java.util.Map<String, String> env, StringBuilder out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (env != null) {
            pb.environment().clear();
            pb.environment().putAll(env);
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return p.waitFor();
    }

    public static void main(String[] args) throws Exception {
        String jh = System.getProperty("java.home");
        String wrapper = new File(jh, "bin/java").getAbsolutePath();
        java.util.Map<String, String> full = System.getenv();

        // T3: wrapper script, env inherited (interposer ACTIVE via LD_PRELOAD)
        try {
            StringBuilder o = new StringBuilder();
            int rc = run(new String[]{wrapper, "-version"}, full, o);
            System.out.println("T3_RC=" + rc + " OUT=" + o.toString().replace('\n', '|').trim());
        } catch (Throwable t) {
            System.out.println("T3_EXC=" + t.getMessage());
        }

        // T4: wrapper script, LD_PRELOAD stripped (interposer OUT)
        try {
            StringBuilder o = new StringBuilder();
            java.util.Map<String, String> clean = new java.util.HashMap<>(full);
            clean.remove("LD_PRELOAD");
            int rc = run(new String[]{wrapper, "-version"}, clean, o);
            System.out.println("T4_RC=" + rc + " OUT=" + o.toString().replace('\n', '|').trim());
        } catch (Throwable t) {
            System.out.println("T4_EXC=" + t.getMessage());
        }
        System.out.println("PB3_DONE");
    }
}
