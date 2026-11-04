package com.aide.mobilestudio.validation

import java.io.File

/**
 * aapt2 link per manifest: merges the module's (and the project's) compiled
 * resources with android.jar and the manifest, exactly like AGP's link step,
 * so unresolved @string/@drawable/type references and manifest attribute
 * errors surface with file and line.
 */
class ResourceLinkCheck : ProjectCheck() {
    override val id = "reslink"
    override val displayName = "Resource Linking"
    override val progressLabel = "Running aapt2 resource linking..."

    override fun run(ctx: ValidationContext): CheckOutcome {
        val manifests = CheckSupport.manifestFiles(ctx.projectRoot)
        if (manifests.isEmpty()) return CheckOutcome(skipped = true)
        val aapt2 = ctx.toolchain.aapt2
        val androidJar = ctx.toolchain.androidJar
        if (aapt2 == null || !aapt2.isFile || androidJar == null || !androidJar.isFile) {
            return CheckOutcome(
                listOf(
                    ctx.toolIssue(
                        "aapt2 / android.jar not available in toolchain; resource linking skipped",
                        "TOOL_LINK_UNAVAILABLE"
                    )
                )
            )
        }

        val issues = mutableListOf<InspectionIssue>()
        // Make sure every res dir is compiled (normally done by the XML check).
        for (resDir in ctx.resDirs) {
            if (ctx.isCancelled()) break
            CheckSupport.compileResDir(ctx, resDir, issues)
        }
        if (issues.any { it.category == InspectionCategory.TOOL }) return CheckOutcome(issues)

        val allResFiles = ctx.resDirs.flatMap { CheckSupport.collectFiles(it) { f -> f.isFile } }
        val zips = ctx.allCompiledResources.values
            .sortedBy { it.absolutePath }
            .map { it.absolutePath }

        for (manifest in manifests) {
            if (ctx.isCancelled()) break
            val module = ctx.model.moduleFor(manifest)
            val manifestText = runCatching { manifest.readText() }.getOrNull() ?: ""
            val minSdk = module?.intProp("minSdk") ?: module?.intProp("minSdkVersion")
                ?: attrValue(manifestText, "android:minSdkVersion")
            val targetSdk = module?.intProp("targetSdk") ?: module?.intProp("targetSdkVersion")
                ?: attrValue(manifestText, "android:targetSdkVersion")

            val groupPath = ValidationCache.relPath(ctx.projectRoot, manifest)
            val issuesForManifest = ctx.cached(
                "aapt2-link",
                groupPath,
                listOf(manifest) + allResFiles
            ) {
                runLink(ctx, aapt2, androidJar, manifest, minSdk, targetSdk, zips)
            }
            issues += issuesForManifest
        }
        return CheckOutcome(issues)
    }

    private fun runLink(
        ctx: ValidationContext,
        aapt2: File,
        androidJar: File,
        manifest: File,
        minSdk: Int?,
        targetSdk: Int?,
        zips: List<String>
    ): List<InspectionIssue> {
        if (ctx.isCancelled()) return emptyList()
        val outApk = File(
            ctx.tempSub("link"),
            "link-${ValidationToolchain.sha256Hex(manifest.absolutePath).take(12)}.apk"
        )
        // AGP always feeds aapt2 a manifest carrying a package attribute. When
        // the project relies on android.namespace instead, inject it into a
        // temp copy (same line layout, so reported line numbers stay valid).
        val manifestText = runCatching { manifest.readText() }.getOrDefault("")
        val manifestModule = ctx.model.moduleFor(manifest)
        val namespace = manifestModule?.strProp("namespace") ?: manifestModule?.strProp("applicationId")
        val hasPackage = Regex("""<manifest\b[^>]*\bpackage\s*=""").containsMatchIn(manifestText)
        val linkManifest = if (!hasPackage && namespace != null) {
            File(ctx.tempSub("link"), "AndroidManifest-${ValidationToolchain.sha256Hex(manifest.absolutePath).take(12)}.xml")
                .apply {
                    writeText(
                        manifestText.replaceFirst(
                            Regex("""<manifest\b"""),
                            "<manifest package=\"$namespace\""
                        )
                    )
                }
        } else {
            manifest
        }

        val command = mutableListOf(
            aapt2.absolutePath, "link",
            "-o", outApk.absolutePath,
            "-I", androidJar.absolutePath,
            "--manifest", linkManifest.absolutePath,
            "--auto-add-overlay"
        )
        if (minSdk != null) command += listOf("--min-sdk-version", minSdk.toString())
        if (targetSdk != null) command += listOf("--target-sdk-version", targetSdk.toString())
        command += zips

        val exec = ctx.runner.run(command, ctx.projectRoot, 60_000L, ctx.cancel)
        if (exec.cancelled) return emptyList()
        if (exec.failedToStart != null) {
            return listOf(
                ctx.toolIssue("aapt2 link failed to start: ${exec.failedToStart}", "TOOL_AAPT2_START")
            )
        }
        val diags = ToolOutputParser.parseAapt2(exec.output)
        if (diags.isEmpty()) {
            if (exec.exitCode == 0) return emptyList()
            if (ToolOutputParser.looksLikeToolFailure(exec.output, 0)) {
                return listOf(
                    ctx.toolIssue(
                        "aapt2 link crashed: ${ToolOutputParser.firstLines(exec.output)}",
                        "TOOL_AAPT2_LINK_CRASH"
                    )
                )
            }
            return emptyList()
        }
        val injected = linkManifest.absolutePath
        val original = manifest.absolutePath
        return diags.map { d ->
            val reportedPath = d.path
            val resolved = when {
                reportedPath == null -> manifest
                reportedPath == injected && injected != original -> manifest
                else -> ToolOutputParser.resolvePath(reportedPath, ctx.projectRoot)
            }
            InspectionIssue(
                severity = if (d.isError) InspectionSeverity.ERROR else InspectionSeverity.WARNING,
                category = InspectionCategory.RESOURCE_LINK,
                file = resolved,
                line = d.line,
                column = d.column,
                message = d.message,
                errorCode = if (d.isError) "RES_LINK_FAILED" else "RES_LINK_WARNING"
            )
        }
    }

    private fun attrValue(text: String, attr: String): Int? {
        val m = Regex("""$attr\s*=\s*"(\d+)"""").find(text) ?: return null
        return m.groupValues[1].toIntOrNull()
    }
}
