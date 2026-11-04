package com.aide.mobilestudio.validation

import java.io.File

/**
 * settings.gradle / settings.gradle.kts checks: script syntax (real Kotlin
 * parser for .kts, brace balance for Groovy), include() module paths and
 * rootProject.name.
 */
class SettingsScriptCheck : ProjectCheck() {
    override val id = "settings"
    override val displayName = "settings.gradle"
    override val progressLabel = "Checking settings.gradle..."

    override fun run(ctx: ValidationContext): CheckOutcome {
        val settings = ctx.model.settings ?: return CheckOutcome(skipped = true)
        val issues = mutableListOf<InspectionIssue>()

        if (settings.isKts) {
            issues += KotlinScriptSyntax.check(
                ctx, settings.file,
                InspectionCategory.SETTINGS_GRADLE, "SETTINGS_SYNTAX"
            )
        } else {
            issues += CheckSupport.balanceIssues(
                settings.file, settings.rawText, "SETTINGS_SYNTAX"
            ).map { it.copy(category = InspectionCategory.SETTINGS_GRADLE) }
        }
        if (ctx.isCancelled()) return CheckOutcome(issues)

        if (settings.includes.isEmpty()) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.SETTINGS_GRADLE,
                settings.file, 1, 1,
                "settings file does not include() any modules",
                "SETTINGS_NO_INCLUDES"
            )
        }
        for (inc in settings.includes) {
            if (!inc.dir.isDirectory) {
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.SETTINGS_GRADLE,
                    settings.file, inc.line, 1,
                    "Included module '${inc.path}' does not exist (expected ${inc.dir.path})",
                    "SETTINGS_INCLUDE_MISSING"
                )
            } else if (!File(inc.dir, "build.gradle").isFile && !File(inc.dir, "build.gradle.kts").isFile) {
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.SETTINGS_GRADLE,
                    settings.file, inc.line, 1,
                    "Included module '${inc.path}' has no build.gradle / build.gradle.kts",
                    "SETTINGS_MODULE_NO_BUILD"
                )
            }
        }
        if (settings.rootProjectName == null) {
            issues += InspectionIssue(
                InspectionSeverity.INFO, InspectionCategory.SETTINGS_GRADLE,
                settings.file, 1, 1,
                "rootProject.name is not set in the settings file",
                "SETTINGS_ROOT_NAME"
            )
        }
        return CheckOutcome(issues)
    }
}
