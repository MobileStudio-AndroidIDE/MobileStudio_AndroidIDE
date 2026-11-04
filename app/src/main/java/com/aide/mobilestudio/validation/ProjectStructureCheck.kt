package com.aide.mobilestudio.validation

import java.io.File

/**
 * Static project skeleton checks: settings file, build scripts, Gradle wrapper
 * and source-set resources. No external tools are executed.
 */
class ProjectStructureCheck : ProjectCheck() {
    override val id = "structure"
    override val displayName = "Project Structure"
    override val progressLabel = "Checking project structure..."

    override fun run(ctx: ValidationContext): CheckOutcome {
        val issues = mutableListOf<InspectionIssue>()
        val model = ctx.model
        val root = ctx.projectRoot

        if (model.settings == null) {
            issues += InspectionIssue(
                InspectionSeverity.ERROR, InspectionCategory.PROJECT_STRUCTURE,
                null, 1, 1,
                "settings.gradle / settings.gradle.kts not found at the project root",
                "STRUCT_SETTINGS_MISSING"
            )
        }
        if (model.modules.isEmpty()) {
            issues += InspectionIssue(
                InspectionSeverity.ERROR, InspectionCategory.PROJECT_STRUCTURE,
                null, 1, 1,
                "No build.gradle / build.gradle.kts found anywhere in the project",
                "STRUCT_NO_BUILD_SCRIPT"
            )
        }
        if (!File(root, "gradle/wrapper/gradle-wrapper.properties").isFile) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.PROJECT_STRUCTURE,
                null, 1, 1,
                "Gradle wrapper missing (gradle/wrapper/gradle-wrapper.properties)",
                "STRUCT_NO_WRAPPER"
            )
        }
        for (module in model.androidModules()) {
            val moduleResDirs = ctx.resDirs.filter {
                it.absolutePath == module.dir.absolutePath ||
                    it.absolutePath.startsWith(module.dir.absolutePath + File.separator)
            }
            if (moduleResDirs.isEmpty()) {
                issues += InspectionIssue(
                    InspectionSeverity.WARNING, InspectionCategory.PROJECT_STRUCTURE,
                    module.buildFile, 1, 1,
                    "Android module '${module.dir.relativeTo(root).path}' has no res/ directory",
                    "STRUCT_NO_RES"
                )
            }
        }
        return CheckOutcome(issues)
    }
}
