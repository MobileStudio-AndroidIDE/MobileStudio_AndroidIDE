package com.mobilestudio.linecounter;

import com.aide.mobilestudio.extension.ExtensionContext;
import com.aide.mobilestudio.extension.ExtensionManifest;
import com.aide.mobilestudio.extension.MobileStudioExtension;

import java.io.File;
import java.util.Collections;

/**
 * Counts lines, code lines, comment lines and blank lines of the file that was
 * just saved, and reports the result as a toast.
 *
 * <p>Serves as the end-to-end sample for the extension lifecycle: build
 * ({@code javac -> d8 -> classes.dex}), install (permission approval + SHA-256
 * pin + entryPoint/dex consistency), then run ({@code onFileSave} dispatched on
 * the ExtensionDispatch thread with error isolation).
 */
public class LineCounter implements MobileStudioExtension {

    private static final String ID = "com.mobilestudio.linecounter";

    @Override
    public ExtensionManifest getManifest() {
        return new ExtensionManifest(
                ID,
                "Line Counter",
                "1.0.0",
                "MobileStudio-AndroidIDE",
                "저장할 때 열려 있는 파일의 총/코드/주석/공백 줄 수를 토스트로 알려줍니다.",
                "1.0.0",
                "*",
                "com.mobilestudio.linecounter.LineCounter",
                Collections.singletonList("project.read"),
                Collections.singletonList("any"),
                1
        );
    }

    @Override
    public void onFileSave(ExtensionContext context, File file) {
        String path = relativeToProject(context, file);
        if (path == null) {
            // Outside the project (app data explorer, external storage): not
            // readable through the project-scoped API, and not this extension's
            // business anyway.
            return;
        }
        String text = context.readFile(path);
        if (text == null) {
            return;
        }
        int[] stats = countLines(text);
        context.showToast(file.getName() + ": 총 " + stats[0] + "줄"
                + " (코드 " + stats[1] + " / 주석 " + stats[2] + " / 공백 " + stats[3] + ")");
    }

    /**
     * {@link ExtensionContext#readFile} is project-root relative and refuses
     * anything that escapes the project, so an absolute path handed to the save
     * hook has to be turned back into a relative one — otherwise the read is
     * silently rejected.
     *
     * @return the path relative to the project root, or null when the file does
     *     not live inside the project (or no project is open)
     */
    private static String relativeToProject(ExtensionContext context, File file) {
        File root = context.projectDir();
        if (root == null) {
            return null;
        }
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (!filePath.startsWith(rootPath + File.separator)) {
            return null;
        }
        return filePath.substring(rootPath.length() + 1);
    }

    /**
     * @return {total, code, comment, blank}
     */
    static int[] countLines(String text) {
        int total = 0, code = 0, comment = 0, blank = 0;
        boolean inBlock = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            total++;
            if (line.isEmpty()) {
                blank++;
                continue;
            }
            if (inBlock) {
                comment++;
                if (line.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (line.startsWith("/*")) {
                comment++;
                inBlock = !line.contains("*/");
                continue;
            }
            if (line.startsWith("//") || line.startsWith("#") || line.startsWith("*")) {
                comment++;
                continue;
            }
            code++;
        }
        return new int[]{total, code, comment, blank};
    }
}
