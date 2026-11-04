package com.aide.mobilestudio.validation

/** Result of parsing gradle.properties with Java Properties semantics. */
data class GradlePropertiesEntry(val key: String, val value: String, val line: Int)

data class GradlePropertiesIssue(
    val line: Int,
    val column: Int,
    val message: String,
    val errorCode: String,
    val severity: InspectionSeverity,
    val key: String? = null
)

object GradlePropertiesParser {

    fun parse(text: String): Pair<List<GradlePropertiesEntry>, List<GradlePropertiesIssue>> {
        val entries = mutableListOf<GradlePropertiesEntry>()
        val issues = mutableListOf<GradlePropertiesIssue>()
        val rawLines = text.split('\n')
        var i = 0
        while (i < rawLines.size) {
            val lineNo = i + 1
            var line = rawLines[i].removeSuffix("\r")
            // Logical line: join continuations (odd number of trailing backslashes).
            while (endsWithOddBackslashes(line) && i + 1 < rawLines.size) {
                line = line.substring(0, line.length - 1) + rawLines[i + 1].removeSuffix("\r")
                i++
            }
            i++
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed[0] == '#' || trimmed[0] == '!') continue

            val sepIdx = findSeparator(line)
            if (sepIdx < 0) {
                val key = unescape(line.trim())
                if (key.isEmpty()) {
                    issues.add(
                        GradlePropertiesIssue(
                            lineNo, 1, "Empty key (line is not a valid property)",
                            "PROPS_EMPTY_KEY", InspectionSeverity.ERROR
                        )
                    )
                } else {
                    issues.add(
                        GradlePropertiesIssue(
                            lineNo, 1,
                            "Property '$key' has no value separator; the value will be empty",
                            "PROPS_NO_VALUE", InspectionSeverity.WARNING, key
                        )
                    )
                    entries.add(GradlePropertiesEntry(key, "", lineNo))
                }
                continue
            }
            val keyRaw = line.substring(0, sepIdx)
            if (keyRaw.trim().isEmpty()) {
                issues.add(
                    GradlePropertiesIssue(
                        lineNo, 1, "Missing key before separator - malformed gradle.properties line",
                        "PROPS_EMPTY_KEY", InspectionSeverity.ERROR
                    )
                )
                continue
            }
            val key = unescape(keyRaw.trimStart())
            var vStart = sepIdx + 1
            while (vStart < line.length && (line[vStart] == ' ' || line[vStart] == '\t')) vStart++
            val value = unescape(line.substring(vStart).trim())
            entries.add(GradlePropertiesEntry(key, value, lineNo))
        }

        // Duplicate keys (last one wins) are suspicious but not fatal.
        val seen = mutableMapOf<String, Int>()
        for (entry in entries) {
            val firstLine = seen.put(entry.key, entry.line)
            if (firstLine != null) {
                issues.add(
                    GradlePropertiesIssue(
                        entry.line, 1,
                        "Duplicate key '${entry.key}' (first defined at line $firstLine); the later value wins",
                        "PROPS_DUPLICATE", InspectionSeverity.WARNING, entry.key
                    )
                )
            }
        }
        return entries to issues
    }

    private fun endsWithOddBackslashes(line: String): Boolean {
        var bs = 0
        var i = line.length - 1
        while (i >= 0 && line[i] == '\\') {
            bs++; i--
        }
        return bs % 2 == 1
    }

    private fun findSeparator(line: String): Int {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' -> i += 2
                c == '=' || c == ':' -> return i
                c == ' ' || c == '\t' -> {
                    // Whitespace separates only if a real value/separator follows.
                    var j = i
                    while (j < line.length && (line[j] == ' ' || line[j] == '\t')) j++
                    if (j < line.length && line[j] != '=' && line[j] != ':') return i
                }
            }
            i++
        }
        return -1
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) sb.append(code.toChar())
                            i += 6
                            continue
                        }
                        sb.append(n)
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
