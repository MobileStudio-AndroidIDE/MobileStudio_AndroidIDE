package com.aide.mobilestudio.web

/**
 * A single completion suggestion produced by [WebCompletionEngine].
 *
 * @param label  Text shown in the popup (may carry type decorations such as `Partial<T>`).
 * @param insert Text actually inserted when the item is accepted. Defaults to [label].
 *               The host editor replaces the trigger prefix with this text.
 * @param detail Short helper string shown next to the label (e.g. "void tag", "utility type").
 * @param kind   Visual category used for the badge icon/color in the popup.
 * @param cursorOffsetFromEnd Number of characters counted from the end of [insert] where the
 *               caret should land after insertion. 0 means "end of inserted text";
 *               >0 means the caret ends inside a placeholder (between quotes, braces, tags...).
 */
data class WebCompletion(
    val label: String,
    val insert: String = label,
    val detail: String = "",
    val kind: Kind = Kind.KEYWORD,
    val cursorOffsetFromEnd: Int = 0,
) {
    enum class Kind { KEYWORD, TAG, ATTRIBUTE, VALUE, SNIPPET, FUNCTION, GLOBAL, TYPE }

    /** True when the caret should end inside a placeholder rather than at the end of the insert. */
    val cursorInPlaceholder: Boolean get() = cursorOffsetFromEnd > 0
}

/**
 * Result of a completion query: the items plus the trigger prefix they were filtered by.
 * The prefix lets a UI replace exactly the token being completed.
 */
data class WebQueryResult(
    /** Trailing token in `textBeforeCursor` that the items replace (may be empty). */
    val prefix: String,
    val items: List<WebCompletion>,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        val NONE = WebQueryResult("", emptyList())
    }
}
