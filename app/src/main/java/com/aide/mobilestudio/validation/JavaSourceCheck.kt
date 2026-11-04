package com.aide.mobilestudio.validation

import java.io.File

/**
 * Compiles every .java file with the JDK's javac against android.jar +
 * resolved dependencies. Syntax and symbol errors carry file/line/column
 * (column recovered from javac's caret line). Results are cached per module.
 */
class JavaSourceCheck : ProjectCheck() {
    override val id = "java"
    override val displayName = "Java Sources"
    override val progressLabel = "Checking Java..."

    override fun run(ctx: ValidationContext): CheckOutcome {
        val files = CheckSupport.collectFiles(ctx.projectRoot) { it.extension == "java" }
        if (files.isEmpty()) return CheckOutcome(skipped = true)
        val javac = ctx.toolchain.javacBin
        if (javac == null || !javac.isFile) {
            return CheckOutcome(
                listOf(
                    ctx.toolIssue(
                        "javac not found in toolchain; Java sources were not checked",
                        "TOOL_JAVAC_MISSING"
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
            issues += ctx.cached("java-src", groupPath, group) {
                compileGroup(ctx, javac, group)
            }
        }
        return CheckOutcome(issues)
    }

    private fun compileGroup(
        ctx: ValidationContext,
        javac: File,
        files: List<File>
    ): List<InspectionIssue> {
        if (ctx.isCancelled()) return emptyList()
        val outDir = ctx.tempSub("java-" + ValidationToolchain.sha256Hex(files.joinToString { it.name }).take(12))
        val args = mutableListOf(
            "-nowarn", "-proc:none", "-encoding", "UTF-8",
            "-d", outDir.absolutePath
        )
        val cp = ctx.toolchain.javacClasspath()
        if (cp.isNotEmpty()) {
            args += listOf("-classpath", cp.joinToString(File.pathSeparator) { it.absolutePath })
        }
        val argFile = KotlinTool.writeArgFile(ctx, "java-src.args", files.map { it.absolutePath })
        args += "@${argFile.absolutePath}"

        val exec = ctx.runner.run(
            listOf(javac.absolutePath) + args,
            ctx.projectRoot,
            60_000L,
            ctx.cancel
        )
        if (exec.cancelled) return emptyList()
        if (exec.failedToStart != null) {
            return listOf(
                ctx.toolIssue(
                    "javac failed to start: ${exec.failedToStart}", "TOOL_JAVAC_START"
                )
            )
        }
        val diags = ToolOutputParser.parseJavac(exec.output)
        if (diags.isEmpty() && exec.exitCode != 0) {
            return listOf(
                ctx.toolIssue(
                    "javac failed (exit ${exec.exitCode}): ${ToolOutputParser.firstLines(exec.output)}",
                    "TOOL_JAVAC_FAILED"
                )
            )
        }
        val depsEmpty = ctx.toolchain.dependencyJars.isEmpty()
        return diags.map { d ->
            val code = classify(d.message)
            val symbolNoise = code == "JAVA_SYMBOL"
            when {
                d.isError && symbolNoise && depsEmpty -> InspectionIssue(
                    InspectionSeverity.WARNING, InspectionCategory.JAVA,
                    d.path?.let { ToolOutputParser.resolvePath(it, ctx.projectRoot) },
                    d.line, d.column,
                    d.message + " (no cached dependencies; may resolve after Gradle sync)",
                    code
                )
                else -> InspectionIssue(
                    if (d.isError) InspectionSeverity.ERROR else InspectionSeverity.WARNING,
                    InspectionCategory.JAVA,
                    d.path?.let { ToolOutputParser.resolvePath(it, ctx.projectRoot) },
                    d.line, d.column, d.message, code
                )
            }
        }
    }

    private fun classify(message: String): String = when {
        "illegal start" in message ||
            "expected" in message && (";" in message || "'" in message || "class" in message) ||
            "not a statement" in message ||
            "reached end of file" in message ||
            "class, interface, enum, or record expected" in message -> "JAVA_SYNTAX"
        "cannot find symbol" in message ||
            "package " in message && "does not exist" in message ||
            "does not override" in message ||
            "incompatible types" in message -> "JAVA_SYMBOL"
        else -> "JAVA_ERROR"
    }
}
