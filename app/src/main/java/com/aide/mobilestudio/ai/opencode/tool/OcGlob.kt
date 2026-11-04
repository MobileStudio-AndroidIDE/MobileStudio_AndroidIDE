package com.aide.mobilestudio.ai.opencode.tool

object OcGlob {

    fun expand(pattern: String): List<String> {
        val open = pattern.indexOf('{')
        if (open < 0) return listOf(pattern)
        val close = pattern.indexOf('}', open)
        if (close < 0) return listOf(pattern)
        val prefix = pattern.slice(0 until open)
        val suffix = pattern.slice(close + 1 until pattern.length)
        val alternatives = pattern.slice(open + 1 until close).split(',')
        val result = mutableListOf<String>()
        for (alt in alternatives) {
            result.addAll(expand(prefix + alt + suffix))
        }
        return result
    }

    fun match(relativePath: String, pattern: String): Boolean {
        for (p in expand(pattern)) {
            if (toRegex(p).matches(relativePath)) return true
        }
        return false
    }

    fun toRegex(pattern: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                    if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                        sb.append("(?:.*/)?")
                        i += 3
                    } else {
                        sb.append(".*")
                        i += 2
                    }
                }
                c == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                c == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                else -> {
                    if (!c.isLetterOrDigit() && c != '_' && c != '-' && c != '/' && c != '.') sb.append('\\')
                    sb.append(c)
                    i++
                }
            }
        }
        return Regex("^$sb$")
    }
}
