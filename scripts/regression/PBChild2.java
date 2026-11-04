import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Isolate which leg of ProcessBuilder fails: a plain /system binary vs the wrapper script. */
public class PBChild2 {
    private static int run(String[] cmd, StringBuilder out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
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
        // T1: plain native binary on the exec-permitted /system mount
        try {
            StringBuilder o1 = new StringBuilder();
            int rc = run(new String[]{"/system/bin/sh", "-c", "echo T1_OK"}, o1);
            System.out.println("T1_RC=" + rc + " OUT=" + o1.toString().replace('\n', '|').trim());
        } catch (Throwable t) {
            System.out.println("T1_EXC=" + t.getClass().getSimpleName() + " " + t.getMessage());
        }

        // T2: the JDK wrapper script on the app-data (noexec) mount
        try {
            StringBuilder o2 = new StringBuilder();
            String jh = System.getProperty("java.home");
            int rc = run(new String[]{new File(jh, "bin/java").getAbsolutePath(), "-version"}, o2);
            System.out.println("T2_RC=" + rc + " OUT=" + o2.toString().replace('\n', '|').trim());
        } catch (Throwable t) {
            System.out.println("T2_EXC=" + t.getClass().getSimpleName() + " " + t.getMessage());
            Throwable c = t.getCause();
            if (c != null) System.out.println("T2_CAUSE=" + c.getMessage());
        }
        System.out.println("PB2_DONE");
    }
}
