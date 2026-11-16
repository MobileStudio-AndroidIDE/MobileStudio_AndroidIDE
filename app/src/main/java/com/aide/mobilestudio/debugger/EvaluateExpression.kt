package com.aide.mobilestudio.debugger

/**
 * Evaluate Expression - evaluates expressions in the debug context.
 */
class EvaluateExpression(
    private val session: DebugSession
) {
    private val history = mutableListOf<String>()

    /**
     * Evaluates an expression in the debug context.
     */
    fun evaluate(expression: String): String {
        history.add(expression)
        return doEvaluate(expression)
    }

    private fun doEvaluate(expression: String): String {
        return when (expression.trim()) {
            "help" -> "Available commands: print <var>, eval <expr>"
            "this" -> "Current frame: <frame info>"
            else -> if (expression.startsWith("print ")) {
                val varName = expression.substring(6).trim()
                "Variable $varName: <value>"
            } else {
                "Unknown expression: $expression"
            }
        }
    }

    /**
     * Returns evaluation history.
     */
    fun getHistory(): List<String> = history
}
