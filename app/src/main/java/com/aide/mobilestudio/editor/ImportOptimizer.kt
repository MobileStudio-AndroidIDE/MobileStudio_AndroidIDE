package com.aide.mobilestudio.editor

/**
 * Import Optimizer - import statement optimization.
 */
class ImportOptimizer(
    private val language: String
) {
    fun optimize(imports: List<String>): List<String> {
        return imports.sorted()
    }
}
