import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Does fork+exec of ANY script on the app-data (noexec) mount work? */
public class PBChild4 {
    private static int run(String[] cmd, Map<String, String> env, StringBuilder out) throws Exception {
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
        String script = "/data/user/0/com.aide.mobilestudio/files/test-wrapper.sh";
        Map<String, String> full = System.getenv();

        // T5: trivial script, env inherited (interposer active)
        try {
            StringBuilder o = new StringBuilder();
            int rc = run(new String[]{script}, full, o);
            System.out.println("T5_RC=" + rc + " OUT=" + o.toString().replace('\n', '|').trim());
        } catch (Throwable t) {
            System.out.println("T5_EXC=" + t.getMessage());
        }

        // T6: trivial script, LD_PRELOAD stripped (interposer out)
        try {
            StringBuilder o = new StringBuilder();
            Map<String, String> clean = new HashMap<>(full);
            clean.remove("LD_PRELOAD");
            int rc = run(new String[]{script}, clean, o);
            System.out.println("T6_RC=" + rc + " OUT=" + o.toString().replace('\n', '|').trim());
        } catch (Throwable t) {
            System.out.println("T6_EXC=" + t.getMessage());
        }
        System.out.println("PB4_DONE");
    }
}
