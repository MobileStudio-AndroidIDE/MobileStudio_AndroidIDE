package com.aide.mobilestudio.validation

import java.io.File

/**
 * res/ directory checks: XML well-formedness with precise line/column plus aapt2
 * compilation of every resource directory (real Android resource compiler).
 */
class XmlResourceCheck : ProjectCheck() {
    override val id = "xmlres"
    override val displayName = "XML Resources"
    override val progressLabel = "Checking XML resources..."

    override fun run(ctx: ValidationContext): CheckOutcome {
        if (ctx.resDirs.isEmpty()) return CheckOutcome(skipped = true)
        val issues = mutableListOf<InspectionIssue>()
        var toolFailed = false
        for (resDir in ctx.resDirs) {
            if (ctx.isCancelled()) break
            if (toolFailed) break
            val files = CheckSupport.collectFiles(resDir) { it.isFile }

            for (file in files) {
                if (file.name.contains(' ') || Regex("""[A-Z]""").containsMatchIn(file.nameWithoutExtension)) {
                    issues += InspectionIssue(
                        InspectionSeverity.WARNING, InspectionCategory.XML_RESOURCE,
                        file, 1, 1,
                        "Resource file name '${file.name}' contains spaces or uppercase characters",
                        "RES_BAD_FILENAME"
                    )
                }
                if (file.extension.equals("xml", true)) {
                    val err = CheckSupport.xmlWellFormed(file)
                    if (err != null) {
                        issues += InspectionIssue(
                            InspectionSeverity.ERROR, InspectionCategory.XML_RESOURCE,
                            file, err.first, err.second, err.third, "RES_XML_MALFORMED"
                        )
                    }
                }
            }

            val before = issues.size
            CheckSupport.compileResDir(ctx, resDir, issues)
            if (issues.subList(before, issues.size).any { it.category == InspectionCategory.TOOL }) {
                toolFailed = true
            }
        }
        return CheckOutcome(issues)
    }
}
