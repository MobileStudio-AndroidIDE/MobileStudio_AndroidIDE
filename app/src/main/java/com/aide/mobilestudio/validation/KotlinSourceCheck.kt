package com.aide.mobilestudio.validation

import java.io.File

/**
 * Compiles every .kt file with the real Kotlin compiler (K2JVMCompiler),
 * grouped per module, against kotlin-stdlib + android.jar + resolved
 * dependencies. Results are cached per module fingerprint.
 */
class KotlinSourceCheck : ProjectCheck() {
    override val id = "kotlin"
    override val displayName = "Kotlin Sources"
    override val progressLabel = "Checking Kotlin..."

    override fun run(ctx: ValidationContext): CheckOutcome {
        val files = CheckSupport.collectFiles(ctx.projectRoot) { it.extension == "kt" }
        if (files.isEmpty()) return CheckOutcome(skipped = true)
        if (!ctx.toolchain.hasKotlinCompiler()) {
            return CheckOutcome(
                listOf(
                    ctx.toolIssue(
                        "Kotlin compiler not found in toolchain; Kotlin sources were not checked",
                        "TOOL_KOTLIN_MISSING"
                    )
                )
            )
        }
        val groups = files.groupBy { ctx.model.moduleFor(it)?.dir ?: ctx.projectRoot }
        val issues = mutableListOf<InspectionIssue>()
        for (moduleDir in groups.keys.sortedBy { it.absolutePath }) {
            if (ctx.isCancelled()) break
            val group = groups[moduleDir] ?: continue
            val groupPath = ValidationCache.relPath(ctx.projectRoot, moduleDir)
            issues += ctx.cached("kotlin-src", groupPath, group) {
                compileGroup(ctx, group)
            }
        }
        return CheckOutcome(issues)
    }

    private fun compileGroup(ctx: ValidationContext, files: List<File>): List<InspectionIssue> {
        if (ctx.isCancelled()) return emptyList()
        val tc = ctx.toolchain
        val outDir = ctx.tempSub("kotlin-" + ValidationToolchain.sha256Hex(files.joinToString { it.name }).take(12))
        val args = mutableListOf("-no-stdlib", "-no-reflect", "-d", outDir.absolutePath)
        val sourceCp = tc.kotlinSourceClasspath()
        if (sourceCp.isNotEmpty()) {
            args += listOf("-cp", sourceCp.joinToString(File.pathSeparator) { it.absolutePath })
        }
        val argFile = KotlinTool.writeArgFile(
            ctx, "kotlin-src.args", files.map { it.absolutePath }
        )
        args += "@${argFile.absolutePath}"
        val command = KotlinTool.compilerCommand(ctx, args)
            ?: return listOf(
                ctx.toolIssue("Kotlin compiler not available; Kotlin sources were not checked", "TOOL_KOTLIN_MISSING")
            )
        val exec = ctx.runner.run(command, ctx.projectRoot, 120_000L, ctx.cancel)
        if (exec.cancelled) return emptyList()
        if (exec.failedToStart != null) {
            return listOf(
                ctx.toolIssue(
                    "Kotlin compiler failed to start: ${exec.failedToStart}", "TOOL_KOTLIN_START"
                )
            )
        }
        val diags = ToolOutputParser.parseK2(exec.output)
        if (diags.isEmpty() && exec.exitCode != 0) {
            return listOf(
                ctx.toolIssue(
                    "Kotlin compilation failed (exit ${exec.exitCode}): ${ToolOutputParser.firstLines(exec.output)}",
                    "TOOL_KOTLIN_FAILED"
                )
            )
        }
        val depsEmpty = tc.dependencyJars.isEmpty()
        return diags.map { d ->
            val severity = if (d.isError) InspectionSeverity.ERROR else InspectionSeverity.WARNING
            val code = classify(d.message)
            val unresolved = "unresolved reference" in d.message
            when {
                d.isError && unresolved && depsEmpty -> InspectionIssue(
                    InspectionSeverity.WARNING, InspectionCategory.KOTLIN,
                    d.path?.let { ToolOutputParser.resolvePath(it, ctx.projectRoot) },
                    d.line, d.column,
                    d.message + " (no cached dependencies; may resolve after Gradle sync)",
                    code
                )
                else -> InspectionIssue(
                    severity, InspectionCategory.KOTLIN,
                    d.path?.let { ToolOutputParser.resolvePath(it, ctx.projectRoot) },
                    d.line, d.column, d.message, code
                )
            }
        }
    }

    private fun classify(message: String): String = when {
        "syntax error" in message || "unexpected token" in message -> "KOTLIN_SYNTAX"
        "unresolved reference" in message -> "KOTLIN_UNRESOLVED"
        "type mismatch" in message || "expected" in message && "actual" in message -> "KOTLIN_TYPE"
        "overload resolution" in message || "cannot infer" in message -> "KOTLIN_OVERLOAD"
        else -> "KOTLIN_ERROR"
    }
}
