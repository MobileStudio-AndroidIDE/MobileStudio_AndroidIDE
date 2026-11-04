package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

/**
 * Dedicated SQL highlighter — case-insensitive keywords, `--` comments,
 * string literals, numbers, functions and types with a distinct color scheme.
 */
class SqlHighlighter : LanguageHighlighter {

    private val keywords = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
        "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "TRIGGER", "PROCEDURE",
        "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "ON", "AND", "OR",
        "NOT", "NULL", "IS", "IN", "LIKE", "GLOB", "BETWEEN", "EXISTS", "HAVING",
        "GROUP", "BY", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "UNION", "ALL",
        "DISTINCT", "AS", "CASE", "WHEN", "THEN", "ELSE", "END", "IF",
        "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "GRANT", "REVOKE", "PRAGMA",
        "VACUUM", "REPLACE", "USING", "WITH", "RECURSIVE", "RETURNING", "CONFLICT",
        "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "UNIQUE", "CHECK",
        "DEFAULT", "AUTOINCREMENT", "CASCADE", "TEMP", "TEMPORARY", "EXPLAIN", "ANALYZE",
        "ATTACH", "DETACH", "DATABASE"
    )

    private val types = setOf(
        "VARCHAR", "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "FLOAT",
        "DOUBLE", "REAL", "DECIMAL", "NUMERIC", "DATE", "DATETIME", "TIMESTAMP",
        "TIME", "TEXT", "BLOB", "BOOLEAN", "BOOL", "CHAR", "CHARACTER", "CLOB",
        "JSON", "UUID", "SERIAL", "BIGSERIAL", "BYTEA"
    )

    private val functions = setOf(
        "COUNT", "SUM", "AVG", "MIN", "MAX", "ROUND", "ABS", "COALESCE", "IFNULL",
        "NULLIF", "LENGTH", "LOWER", "UPPER", "SUBSTR", "SUBSTRING", "TRIM", "LTRIM",
        "RTRIM", "REPLACE", "INSTR", "CAST", "CONVERT", "CONCAT", "CONCAT_WS",
        "NOW", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "STRFTIME",
        "JULIANDAY", "DATE", "TIME", "DATETIME", "RANDOM", "LAST_INSERT_ROWID",
        "CHANGES", "TOTAL_CHANGES", "SQLITE_VERSION", "TYPEOF", "HEX", "QUOTE",
        "RANK", "DENSE_RANK", "ROW_NUMBER", "NTILE", "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE"
    )

    private val operators = setOf("==", "!=", "<>", "<=", ">=", "||", "->", "->>")

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()

        // `--` line comments and /* */ block comments
        val commentLinePattern = Regex("--[^\n]*")
        val commentBlockPattern = Regex("/\\*[\\s\\S]*?\\*/")
        val stringPattern = Regex("'(?:[^'\\\\]|\\\\.|'')*'")
        val quotedIdPattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f]+|\\d+\\.\\d+([eE][+-]?\\d+)?|\\.\\d+|\\d+)\\b")

        val excludedRanges = mutableListOf<IntRange>()

        commentBlockPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }
        commentLinePattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }

        fun inExcluded(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        stringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }
        quotedIdPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synLabel)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        // Functions: NAME( — uppercase function names highlighted
        val functionPattern = Regex("\\b([A-Z_][A-Z0-9_]*)\\s*\\(")
        functionPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synFunc)
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        val operatorPattern = Regex("(==|!=|<>|<=|>=|\\|\\||->)")
        operatorPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synOperator)
            }
        }

        // Keywords: case-insensitive word match
        val keywordPattern = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\b")
        keywordPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                val upper = it.value.uppercase()
                when {
                    upper in types -> spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synType, bold = true)
                    upper in keywords -> spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synKw, bold = true)
                    upper == "NULL" -> spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synBoolean)
                }
            }
        }

        return spans.sortedBy { it.start }
    }
}
