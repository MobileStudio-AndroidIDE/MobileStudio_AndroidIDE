package com.aide.mobilestudio.ai.opencode.session

/**
 * Token counts used to size a request before it is sent.
 *
 * Every number here is an estimate. The authoritative count comes from the
 * model's own tokenizer, which only the provider can run, and the providers
 * that report real usage only do so after a request has already been sent.
 * The estimate therefore has to be wrong in the safe direction - too high,
 * never too low - because a prompt that overflows the window is refused
 * outright and the turn is lost, while a prompt that comes in slightly under
 * budget only leaves a little of the window unused.
 *
 * The previous estimate was `length / 4` for every string. That is close
 * enough for English and badly wrong for Korean: UTF-8 byte-level BPE spends
 * roughly one token per Hangul syllable, so a Korean conversation was
 * estimated at a quarter of its real cost and overflowed windows the engine
 * believed had room to spare. Splitting ASCII from the rest fixes the
 * direction of that error without pretending to be exact.
 */
object OcTokenEstimate {

    /** True only when a count came from the model's tokenizer. */
    const val IS_EXACT = false

    /** English prose and source average a little over three characters per token. */
    private const val ASCII_CHARS_PER_TOKEN = 3.5

    /** Hangul, kana and CJK land near one token per character. */
    private const val WIDE_CHARS_PER_TOKEN = 1.0

    /** Human-readable marker for numbers that are estimates rather than counts. */
    const val ESTIMATE_SUFFIX = "~"

    fun tokens(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var wide = 0
        for (ch in text) {
            if (ch.code < 0x80) ascii++ else wide++
        }
        val estimate = ascii / ASCII_CHARS_PER_TOKEN + wide / WIDE_CHARS_PER_TOKEN
        return Math.max(1, Math.ceil(estimate).toInt())
    }

    fun tokens(value: Any?): Int = if (value == null) 0 else tokens(value.toString())

    /** Formats a count so callers never present an estimate as a measurement. */
    fun label(tokens: Int): String = if (IS_EXACT) "$tokens" else "$ESTIMATE_SUFFIX$tokens"
}
