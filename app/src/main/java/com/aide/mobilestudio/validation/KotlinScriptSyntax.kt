package com.aide.mobilestudio.validation

import java.io.File

/** Builds and runs org.jetbrains.kotlin.cli.jvm.K2JVMCompiler invocations. */
object KotlinTool {

    fun compilerCommand(ctx: ValidationContext, extraArgs: List<String>): List<String>? {
        val tc = ctx.toolchain
        val java = tc.javaBin?.takeIf { it.isFile } ?: return null
        val cp = tc.kotlinCompilerClasspath()
        if (cp.isEmpty()) return null
        return listOf(
            java.absolutePath,
            "-Xmx768m",
            "-cp", cp.joinToString(File.pathSeparator) { it.absolutePath },
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        ) + extraArgs
    }

    /** Writes an @argfile (one argument per line, quoted when needed). */
    fun writeArgFile(ctx: ValidationContext, name: String, args: List<String>): File {
        val file = File(ctx.tempSub("args"), name)
        file.writeText(args.joinToString("\n") { arg ->
            if (arg.any { it == ' ' || it == '\t' }) "\"$arg\"" else arg
        })
        return file
    }

    fun stdlibArg(ctx: ValidationContext): String? =
        ctx.toolchain.kotlinSourceClasspath()
            .joinToString(File.pathSeparator) { it.absolutePath }
            .takeIf { it.isNotEmpty() }
}

/**
 * Syntax-only checking of *.gradle.kts scripts through the real Kotlin parser.
 * Only messages that are actual parser ("syntax error") diagnostics are kept:
 * unresolved Gradle API references are expected noise without a Gradle classpath.
 */
object KotlinScriptSyntax {

    fun check(
        ctx: ValidationContext,
        scriptFile: File,
        category: InspectionCategory,
        errorCode: String
    ): List<InspectionIssue> {
        if (ctx.isCancelled()) return emptyList()
        if (!ctx.toolchain.hasKotlinCompiler()) {
            return listOf(
                ctx.toolIssue("Kotlin compiler not available; could not check ${scriptFile.name}", "TOOL_KOTLIN_MISSING")
            )
        }
        return ctx.cached("script-syntax", scriptFile.absolutePath, listOf(scriptFile)) {
            if (ctx.isCancelled()) return@cached emptyList()
            val outDir = ctx.tempSub("script-syntax")
            val args = mutableListOf(
                "-no-stdlib", "-no-reflect",
                "-d", outDir.absolutePath
            )
            KotlinTool.stdlibArg(ctx)?.let { args += listOf("-cp", it) }
            args += scriptFile.absolutePath
            val command = KotlinTool.compilerCommand(ctx, args) ?: return@cached listOf(
                ctx.toolIssue("Kotlin compiler not available; could not check ${scriptFile.name}", "TOOL_KOTLIN_MISSING")
            )
            val exec = ctx.runner.run(command, scriptFile.parentFile, 60_000L, ctx.cancel)
            when {
                exec.cancelled -> emptyList()
                exec.failedToStart != null -> listOf(
                    ctx.toolIssue(
                        "Kotlin compiler failed to start: ${exec.failedToStart}", "TOOL_KOTLIN_START"
                    )
                )
                else -> {
                    val diags = ToolOutputParser.parseK2(exec.output)
                    if (diags.isEmpty() && exec.exitCode != 0) {
                        listOf(
                            ctx.toolIssue(
                                "Script check failed (exit ${exec.exitCode}): ${ToolOutputParser.firstLines(exec.output)}",
                                "TOOL_SCRIPT_CHECK_FAILED"
                            )
                        )
                    } else {
                        diags.filter { isSyntaxMessage(it.message) }.map { d ->
                            InspectionIssue(
                                severity = InspectionSeverity.ERROR,
                                category = category,
                                file = scriptFile,
                                line = d.line,
                                column = d.column,
                                message = d.message,
                                errorCode = errorCode
                            )
                        }
                    }
                }
            }
        }
    }

    fun isSyntaxMessage(message: String): Boolean {
        val m = message.trim().lowercase()
        return m.contains("syntax error") ||
            m.contains("unexpected token") ||
            (m.startsWith("expecting ") && !m.contains("expected '"))
    }
}
