package com.aide.mobilestudio.validation

/**
 * gradle.properties parsing (Java Properties semantics) plus value sanity
 * checks for well-known keys. Unknown keys are intentionally never flagged.
 */
class GradlePropertiesCheck : ProjectCheck() {
    override val id = "gradleprops"
    override val displayName = "gradle.properties"
    override val progressLabel = "Checking gradle.properties..."

    private val strictBooleanKeys = setOf(
        "org.gradle.parallel",
        "org.gradle.caching",
        "org.gradle.daemon",
        "org.gradle.configureondemand",
        "org.gradle.configuration-cache",
        "org.gradle.unsafe.configuration-cache"
    )

    private val lenientBooleanKeys = setOf(
        "android.useAndroidX",
        "android.enableJetifier",
        "android.nonTransitiveRClass",
        "android.nonFinalResIds",
        "android.enableJetifierIsolation"
    )

    override fun run(ctx: ValidationContext): CheckOutcome {
        val file = ctx.model.gradlePropertiesFile
            ?: return CheckOutcome(
                listOf(
                    InspectionIssue(
                        InspectionSeverity.WARNING, InspectionCategory.GRADLE_PROPERTIES,
                        null, 1, 1,
                        "gradle.properties not found at the project root",
                        "PROPS_MISSING"
                    )
                )
            )
        val text = runCatching { file.readText() }.getOrElse {
            return CheckOutcome(
                listOf(
                    InspectionIssue(
                        InspectionSeverity.ERROR, InspectionCategory.GRADLE_PROPERTIES,
                        file, 1, 1, "Cannot read gradle.properties: ${it.message}", "PROPS_UNREADABLE"
                    )
                )
            )
        }
        val issues = mutableListOf<InspectionIssue>()
        val (entries, parseIssues) = GradlePropertiesParser.parse(text)
        for (pi in parseIssues) {
            issues += InspectionIssue(
                pi.severity, InspectionCategory.GRADLE_PROPERTIES,
                file, pi.line, pi.column, pi.message, pi.errorCode
            )
        }
        val map = entries.associate { it.key to it.value }
        val lines = entries.associate { it.key to it.line }

        for (key in strictBooleanKeys + lenientBooleanKeys) {
            val value = map[key]?.trim() ?: continue
            if (value.isEmpty()) continue
            if (!value.equals("true", true) && !value.equals("false", true)) {
                val strict = key in strictBooleanKeys
                issues += InspectionIssue(
                    if (strict) InspectionSeverity.ERROR else InspectionSeverity.WARNING,
                    InspectionCategory.GRADLE_PROPERTIES,
                    file, lines[key] ?: 1, 1,
                    "$key must be 'true' or 'false' (was '$value')",
                    "PROPS_BAD_BOOLEAN"
                )
            }
        }

        map["org.gradle.jvmargs"]?.let { jvmargs ->
            if ("-Xmx" in jvmargs && !Regex("""-Xmx\d+[kKmMgGtT]?""").containsMatchIn(jvmargs)) {
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.GRADLE_PROPERTIES,
                    file, lines["org.gradle.jvmargs"] ?: 1, 1,
                    "org.gradle.jvmargs has an invalid -Xmx value: '$jvmargs'",
                    "PROPS_BAD_JVMARGS"
                )
            }
        }

        map["org.gradle.workers.max"]?.let { v ->
            if (v.toIntOrNull() == null || v.toIntOrNull()!! <= 0) {
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.GRADLE_PROPERTIES,
                    file, lines["org.gradle.workers.max"] ?: 1, 1,
                    "org.gradle.workers.max must be a positive integer (was '$v')",
                    "PROPS_BAD_INT"
                )
            }
        }

        map["kotlin.code.style"]?.let { v ->
            if (v !in setOf("official", "intellij")) {
                issues += InspectionIssue(
                    InspectionSeverity.WARNING, InspectionCategory.GRADLE_PROPERTIES,
                    file, lines["kotlin.code.style"] ?: 1, 1,
                    "kotlin.code.style should be 'official' or 'intellij' (was '$v')",
                    "PROPS_BAD_KOTLIN_STYLE"
                )
            }
        }

        // androidx consistency with actual declared dependencies.
        val allDeps = ctx.model.modules.flatMap { it.dependencies }
        val usesAndroidx = allDeps.any { it.coordinate.isNotEmpty() && it.coordinate[0].startsWith("androidx.") }
        val usesSupportLib = allDeps.any {
            it.coordinate.isNotEmpty() &&
                (it.coordinate[0].startsWith("android.support") || it.coordinate[0] == "com.android.support")
        }
        if (usesAndroidx && !map.containsKey("android.useAndroidX")) {
            issues += InspectionIssue(
                InspectionSeverity.ERROR, InspectionCategory.GRADLE_PROPERTIES,
                file, 1, 1,
                "Project declares androidx.* dependencies but android.useAndroidX is not set in gradle.properties",
                "PROPS_ANDROIDX_REQUIRED"
            )
        } else if (usesAndroidx && map["android.useAndroidX"]?.equals("true", true) != true) {
            issues += InspectionIssue(
                InspectionSeverity.ERROR, InspectionCategory.GRADLE_PROPERTIES,
                file, lines["android.useAndroidX"] ?: 1, 1,
                "android.useAndroidX must be 'true' for androidx.* dependencies",
                "PROPS_ANDROIDX_REQUIRED"
            )
        }
        if (usesSupportLib && map["android.useAndroidX"]?.equals("true", true) == true) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.GRADLE_PROPERTIES,
                file, lines["android.useAndroidX"] ?: 1, 1,
                "android.useAndroidX=true but legacy android.support.* dependencies are declared",
                "PROPS_SUPPORT_LIB_MIX"
            )
        }
        return CheckOutcome(issues)
    }
}
