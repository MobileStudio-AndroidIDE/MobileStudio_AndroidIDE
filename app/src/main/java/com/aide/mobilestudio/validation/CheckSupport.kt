package com.aide.mobilestudio.validation

import java.io.File

/** Shared helpers used by several checks. */
object CheckSupport {

    val SKIP_DIRS = setOf(
        "build", ".gradle", ".git", ".idea", "node_modules", ".svn", "captures",
        "generated", "outputs", "intermediates"
    )

    /** Collects files under [root] matching [predicate], skipping build/hidden dirs. */
    fun collectFiles(
        root: File,
        maxDepth: Int = 12,
        predicate: (File) -> Boolean
    ): List<File> {
        val out = mutableListOf<File>()
        if (!root.isDirectory) return out
        root.walkTopDown()
            .onEnter { dir -> dir.name !in SKIP_DIRS && !dir.name.startsWith(".") }
            .maxDepth(maxDepth)
            .filter { it.isFile && predicate(it) }
            .forEach { out.add(it) }
        return out
    }

    /** 1-based line number of the first occurrence of [needle], or 1. */
    fun findLine(text: String, needle: String, from: Int = 0): Int {
        val idx = text.indexOf(needle, from)
        if (idx < 0) return 1
        var line = 1
        for (i in 0 until idx) if (text[i] == '\n') line++
        return line
    }

    /** Groovy script brace/paren balance check (comments and strings masked). */
    fun balanceIssues(scriptFile: File, text: String, errorCode: String): List<InspectionIssue> {
        val masked = GradleCodeScanner.maskAll(text)
        val stack = ArrayDeque<Pair<Char, Int>>()
        var line = 1
        var problem: InspectionIssue? = null
        for (i in masked.indices) {
            val c = masked[i]
            if (c == '\n') { line++; continue }
            when (c) {
                '{' -> stack.addLast('{' to line)
                '(' -> stack.addLast('(' to line)
                '}', ')' -> {
                    val expected = if (c == '}') '{' else '('
                    if (stack.isEmpty() || stack.last().first != expected) {
                        problem = InspectionIssue(
                            severity = InspectionSeverity.ERROR,
                            category = InspectionCategory.BUILD_GRADLE,
                            file = scriptFile,
                            line = line,
                            column = 1,
                            message = "Unexpected '$c'",
                            errorCode = errorCode
                        )
                        break
                    }
                    stack.removeLast()
                }
            }
        }
        if (problem != null) return listOf(problem)
        if (stack.isNotEmpty()) {
            val (openChar, openLine) = stack.last()
            return listOf(
                InspectionIssue(
                    severity = InspectionSeverity.ERROR,
                    category = InspectionCategory.BUILD_GRADLE,
                    file = scriptFile,
                    line = openLine,
                    column = 1,
                    message = "Unclosed '$openChar' (block never closed)",
                    errorCode = errorCode
                )
            )
        }
        return emptyList()
    }

    /**
     * aapt2 compile for one res dir: `aapt2 compile --dir <res> -o <zip>`.
     * Returns the compiled zip on success, null otherwise; issues go to [out].
     */
    fun compileResDir(
        ctx: ValidationContext,
        resDir: File,
        out: MutableList<InspectionIssue>
    ): File? {
        ctx.compiledResourcesOf(resDir.absolutePath)?.let { return it }
        val aapt2 = ctx.toolchain.aapt2
        if (aapt2 == null || !aapt2.isFile) {
            out.add(
                ctx.toolIssue("aapt2 not found in toolchain; XML resource compilation skipped", "TOOL_AAPT2_MISSING")
            )
            return null
        }
        val members = collectFiles(resDir) { it.isFile }
        val zip = File(ctx.tempSub("res"), "res-${ValidationToolchain.sha256Hex(resDir.absolutePath).take(16)}.zip")
        val issues = ctx.cached("aapt2-compile", resDir.absolutePath, members) {
            runAapt2Compile(aapt2, resDir, zip, ctx)
        }
        out.addAll(issues)
        val hasError = issues.any { it.severity == InspectionSeverity.ERROR }
        if (!hasError) {
            // A cache hit can reference a zip that was cleaned from the temp dir.
            if (!zip.isFile && !ctx.isCancelled()) {
                val fresh = runAapt2Compile(aapt2, resDir, zip, ctx)
                val freshError = fresh.any { it.severity == InspectionSeverity.ERROR }
                if (!freshError && zip.isFile) ctx.putCompiledResource(resDir.absolutePath, zip)
                return if (freshError) null else zip.takeIf { it.isFile }
            }
            if (zip.isFile) ctx.putCompiledResource(resDir.absolutePath, zip)
        }
        return zip.takeIf { it.isFile && !hasError }
    }

    private fun runAapt2Compile(
        aapt2: File,
        resDir: File,
        zip: File,
        ctx: ValidationContext
    ): List<InspectionIssue> {
        val exec = ctx.runner.run(
            listOf(
                aapt2.absolutePath, "compile", "--dir", resDir.absolutePath, "-o", zip.absolutePath
            ),
            ctx.projectRoot,
            30_000L,
            ctx.cancel
        )
        return when {
            exec.cancelled -> emptyList()
            exec.failedToStart != null -> listOf(
                ctx.toolIssue(
                    "aapt2 failed to start: ${exec.failedToStart}", "TOOL_AAPT2_START"
                )
            )
            else -> parseAapt2CompileOutput(exec, ctx)
        }
    }

    private fun parseAapt2CompileOutput(exec: ToolExecution, ctx: ValidationContext): List<InspectionIssue> {
        if (exec.exitCode == 0 && exec.output.isBlank()) return emptyList()
        val diags = ToolOutputParser.parseAapt2(exec.output)
        if (diags.isEmpty()) {
            if (exec.exitCode == 0) return emptyList()
            return listOf(
                ctx.toolIssue(
                    "aapt2 compile failed (exit ${exec.exitCode}): ${ToolOutputParser.firstLines(exec.output)}",
                    "TOOL_AAPT2_FAILED"
                )
            )
        }
        return diags.map { d ->
            InspectionIssue(
                severity = if (d.isError) InspectionSeverity.ERROR else InspectionSeverity.WARNING,
                category = InspectionCategory.XML_RESOURCE,
                file = d.path?.let { ToolOutputParser.resolvePath(it, ctx.projectRoot) },
                line = d.line,
                column = d.column,
                message = d.message,
                errorCode = if (d.isError) "RES_COMPILE_FAILED" else "RES_COMPILE_WARNING"
            )
        }
    }

    /** Directories named `res` that belong to a source set or module. */
    fun findResDirs(root: File): List<File> {
        val out = mutableListOf<File>()
        root.walkTopDown()
            .onEnter { dir ->
                dir.name !in SKIP_DIRS && !dir.name.startsWith(".") &&
                    !(dir.name == "res" && dir.parentFile?.name == "build")
            }
            .maxDepth(10)
            .filter { it.isDirectory && it.name == "res" }
            .forEach { dir ->
                val parent = dir.parentFile
                val grand = parent?.parentFile
                val isSourceSet = grand?.name == "src"
                val isModuleRootChild = parent != null &&
                    (File(parent, "build.gradle").isFile || File(parent, "build.gradle.kts").isFile)
                if (isSourceSet || isModuleRootChild) out.add(dir)
            }
        return out.distinctBy { it.absolutePath }
    }

    fun manifestFiles(root: File): List<File> =
        collectFiles(root, maxDepth = 10) { it.name == "AndroidManifest.xml" }

    /** SAX well-formedness probe with line/column; returns null when well-formed. */
    fun xmlWellFormed(file: File): Triple<Int, Int, String>? {
        return try {
            val factory = javax.xml.parsers.SAXParserFactory.newInstance()
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val parser = factory.newSAXParser()
            parser.parse(file, object : org.xml.sax.helpers.DefaultHandler() {})
            null
        } catch (e: org.xml.sax.SAXParseException) {
            Triple(e.lineNumber ?: 0, e.columnNumber ?: 0, e.message ?: "Malformed XML")
        } catch (e: Exception) {
            Triple(1, 1, "Cannot parse XML: ${e.message}")
        }
    }
}
