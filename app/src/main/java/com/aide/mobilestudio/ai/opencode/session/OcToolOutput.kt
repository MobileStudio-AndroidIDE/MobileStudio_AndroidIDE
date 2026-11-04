package com.aide.mobilestudio.ai.opencode.session

/**
 * Shortens a tool result before it goes back into the next request.
 *
 * A build, a test run or a recursive search can return thousands of lines, and
 * quoting all of it means the next request may not fit the window at all. The
 * stored transcript is never touched - this only shapes what the model sees -
 * so anything dropped here can still be re-read with the tool that produced
 * it, and long file reads are better served by reading a range than by
 * replaying a whole log.
 *
 * What survives is chosen so the agent can still act: the head of the output
 * carries the invocation and the first failure, the tail carries the summary
 * line, and any line in between that looks like a diagnostic is kept. Nothing
 * is rewritten and no diagnostic is invented - dropped lines are counted, not
 * summarised.
 */
object OcToolOutput {

    /** Lines always kept from the top of the output. */
    private const val HEAD_LINES = 12

    /** Lines always kept from the bottom, where totals and exit status live. */
    private const val TAIL_LINES = 12

    /** Cap on kept diagnostic lines, so a fatal spam loop cannot fill the budget. */
    private const val MAX_SIGNAL_LINES = 60

    private val SIGNAL_PATTERNS = listOf(
        Regex("""(?i)\b(error|fatal|fail(ed|ure)?|exception|traceback|panic|abort|denied|refused|timed? ?out|unresolved|undefined|cannot|not found)\b"""),
        Regex("""(?i)\b(warn(ing)?|deprecat(ed|ion)|todo|fixme)\b"""),
        Regex("""(?i)\b(build|test|task|compile).{0,24}(successful|succeeded|failed)\b"""),
        Regex("""^\s*at\s+[\w.$<>]+\(.*\)\s*$"""),
        Regex("""[\w./\\-]+\.\w{1,8}:\d+"""),
        Regex("""(?i)^\s*Caused by:"""),
    )

    fun isSignal(line: String): Boolean = SIGNAL_PATTERNS.any { it.containsMatchIn(line) }

    /**
     * Returns [text] unchanged when it already fits, otherwise a shortened
     * version that is never longer than [maxChars].
     */
    fun trim(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text

        val lines = text.split('\n')
        // Few but very long lines: there is no useful line structure to keep,
        // so keep both ends of the text itself.
        if (lines.size <= HEAD_LINES + TAIL_LINES + 1) return clip(text, maxChars)

        val head = lines.subList(0, HEAD_LINES)
        val tailStart = lines.size - TAIL_LINES
        val middle = lines.subList(HEAD_LINES, tailStart)
        val signal = middle.asSequence()
            .filter { isSignal(it) }
            .distinct()
            .take(MAX_SIGNAL_LINES)
            .toList()

        val note = "[... ${middle.size} of ${lines.size} lines omitted; " +
            "${signal.size} diagnostic lines kept; full output is still in the session ...]"

        val headChars = head.sumOf { it.length + 1 }
        val tailChars = lines.subList(tailStart, lines.size).sumOf { it.length + 1 }
        val signalBudget = maxChars - headChars - tailChars - note.length - 2

        val keptSignal = ArrayList<String>()
        var used = 0
        for (line in signal) {
            if (signalBudget <= 0 || used + line.length + 1 > signalBudget) break
            keptSignal.add(line)
            used += line.length + 1
        }

        val sb = StringBuilder()
        head.forEach { sb.append(it).append('\n') }
        sb.append(note).append('\n')
        keptSignal.forEach { sb.append(it).append('\n') }
        lines.subList(tailStart, lines.size).forEach { sb.append(it).append('\n') }

        val out = sb.toString().trimEnd('\n')
        return if (out.length <= maxChars) out else clip(out, maxChars)
    }

    /** Keeps both ends of a long string, which is where the interesting parts are. */
    private fun clip(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val marker = "\n[... clipped ...]\n"
        if (maxChars <= marker.length + 2) return text.take(maxChars)
        val room = maxChars - marker.length
        val headChars = room * 3 / 5
        val tailChars = room - headChars
        return text.take(headChars) + marker + text.takeLast(tailChars)
    }
}
