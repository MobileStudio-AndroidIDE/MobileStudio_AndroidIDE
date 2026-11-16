package com.aide.mobilestudio.agpupgrade

import com.aide.mobilestudio.ToolchainManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AGP-Gradle-JDK-Kotlin-NDK-CMake 호환성 매트릭스.
 * Android Studio의 AGP Upgrade Assistant와 동일한 호환성 규칙을 구현.
 */
object AgpCompatibilityMatrix {

    // AGP별 최소/권장 Gradle 버전 매핑
    private val agpToGradle = mapOf(
        "9.3" to "9.0",
        "9.2" to "8.11",
        "9.1" to "8.10",
        "9.0" to "8.10",
        "8.14" to "8.11",
        "8.13" to "8.10",
        "8.12" to "8.9",
        "8.11" to "8.8",
        "8.10" to "8.7",
        "8.9" to "8.6",
        "8.8" to "8.5",
        "8.7" to "8.4",
        "8.6" to "8.3",
        "8.5" to "8.2",
        "8.4" to "8.1",
        "8.3" to "8.0",
        "8.2" to "8.0",
        "8.1" to "8.0",
        "8.0" to "8.0",
        "7.4" to "7.5",
        "7.3" to "7.4",
        "7.2" to "7.3",
        "7.1" to "7.2",
        "7.0" to "7.0",
    )

    // AGP별 최소/권장 JDK 버전 매핑
    private val agpToJdk = mapOf(
        "9.3" to "21",
        "9.2" to "21",
        "9.1" to "21",
        "9.0" to "17",
        "8.14" to "21",
        "8.13" to "17",
        "8.12" to "17",
        "8.11" to "17",
        "8.10" to "17",
        "8.9" to "17",
        "8.8" to "17",
        "8.7" to "17",
        "8.6" to "17",
        "8.5" to "17",
        "8.4" to "17",
        "8.3" to "17",
        "8.2" to "17",
        "8.1" to "17",
        "8.0" to "17",
        "7.4" to "11",
        "7.3" to "11",
        "7.2" to "11",
        "7.1" to "11",
        "7.0" to "11",
    )

    // AGP별 권장 Kotlin 버전 매핑
    private val agpToKotlin = mapOf(
        "9.3" to "2.1.0",
        "9.2" to "2.0.20",
        "9.1" to "2.0.20",
        "9.0" to "2.0.0",
        "8.14" to "2.0.20",
        "8.13" to "2.0.20",
        "8.12" to "2.0.20",
        "8.11" to "2.0.20",
        "8.10" to "2.0.20",
        "8.9" to "2.0.0",
        "8.8" to "2.0.0",
        "8.7" to "1.9.24",
        "8.6" to "1.9.20",
        "8.5" to "1.9.20",
        "8.4" to "1.9.10",
        "8.3" to "1.9.0",
        "8.2" to "1.8.20",
        "8.1" to "1.8.10",
        "8.0" to "1.8.0",
        "7.4" to "1.8.0",
        "7.3" to "1.7.20",
        "7.2" to "1.7.10",
        "7.1" to "1.6.10",
        "7.0" to "1.5.30",
    )

    // AGP별 권장 NDK 버전 매핑
    private val agpToNdk = mapOf(
        "9.3" to "27.0.12077973",
        "9.2" to "27.0.12077973",
        "9.1" to "26.1.10909125",
        "9.0" to "26.1.10909125",
        "8.14" to "27.0.12077973",
        "8.13" to "26.3.11579264",
        "8.12" to "26.1.10909125",
        "8.11" to "26.1.10909125",
        "8.10" to "25.2.9519653",
        "8.9" to "25.2.9519653",
        "8.8" to "25.2.9519653",
        "8.7" to "25.1.8937393",
        "8.6" to "25.1.8937393",
        "8.5" to "25.1.8937393",
        "8.4" to "25.1.8937393",
        "8.3" to "25.1.8937393",
        "8.2" to "25.1.8937393",
        "8.1" to "23.1.7779620",
        "8.0" to "23.1.7779620",
    )

    // AGP별 권장 CMake 버전 매핑
    private val agpToCmake = mapOf(
        "9.3" to "3.22.1",
        "9.2" to "3.22.1",
        "9.1" to "3.22.1",
        "9.0" to "3.22.1",
        "8.14" to "3.22.1",
        "8.13" to "3.22.1",
        "8.12" to "3.22.1",
        "8.11" to "3.22.1",
        "8.10" to "3.22.1",
        "8.9" to "3.22.1",
        "8.8" to "3.22.1",
        "8.7" to "3.22.1",
        "8.6" to "3.22.1",
        "8.5" to "3.22.1",
        "8.4" to "3.22.1",
        "8.3" to "3.22.1",
        "8.2" to "3.22.1",
        "8.1" to "3.22.1",
        "8.0" to "3.22.1",
    )

    // Gradle별 최소 JDK 버전 매핑
    private val gradleToMinJdk = mapOf(
        "9.0" to 21,
        "8.14" to 17,
        "8.13" to 17,
        "8.12" to 17,
        "8.11" to 17,
        "8.10" to 17,
        "8.9" to 17,
        "8.8" to 17,
        "8.7" to 17,
        "8.6" to 17,
        "8.5" to 17,
        "8.4" to 17,
        "8.3" to 17,
        "8.2" to 17,
        "8.1" to 17,
        "8.0" to 17,
        "7.6" to 11,
        "7.5" to 11,
        "7.4" to 11,
        "7.3" to 11,
        "7.2" to 11,
        "7.1" to 11,
        "7.0" to 11,
    )

    // NDK별 권장 CMake 버전 매핑
    private val ndkToCmake = mapOf(
        "27" to "3.22.1",
        "26" to "3.22.1",
        "25" to "3.22.1",
        "24" to "3.18.1",
        "23" to "3.18.1",
        "22" to "3.18.1",
        "21" to "3.18.1",
    )

    data class CompatibilityInfo(
        val agpVersion: String,
        val minGradleVersion: String,
        val recommendedGradleVersion: String,
        val minJdkVersion: Int,
        val recommendedJdkVersion: Int,
        val recommendedKotlinVersion: String,
        val recommendedNdkVersion: String,
        val recommendedCmakeVersion: String,
        val isPreview: Boolean = false,
        val isEol: Boolean = false,
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "agpVersion" to agpVersion,
            "minGradleVersion" to minGradleVersion,
            "recommendedGradleVersion" to recommendedGradleVersion,
            "minJdkVersion" to minJdkVersion,
            "recommendedJdkVersion" to recommendedJdkVersion,
            "recommendedKotlinVersion" to recommendedKotlinVersion,
            "recommendedNdkVersion" to recommendedNdkVersion,
            "recommendedCmakeVersion" to recommendedCmakeVersion,
            "isPreview" to isPreview,
            "isEol" to isEol,
        )
    }

    private val cache = ConcurrentHashMap<String, CompatibilityInfo>()

    /**
     * AGP 버전에 대한 전체 호환성 정보를 반환.
     */
    fun getCompatibilityInfo(agpVersion: String): CompatibilityInfo {
        return cache.computeIfAbsent(agpVersion) {
            val majorMinor = agpVersion.take(agpVersion.lastIndexOf('.')).takeIf { it.contains('.') } ?: agpVersion
            val minGradle = agpToGradle[majorMinor] ?: "8.0"
            val recommendedGradle = when {
                agpVersion.startsWith("9.") -> "9.0"
                agpVersion.startsWith("8.") -> "8.14"
                agpVersion.startsWith("7.") -> "7.6"
                else -> "8.14"
            }
            val minJdk = agpToJdk[majorMinor]?.toIntOrNull() ?: 17
            val recommendedJdk = when {
                agpVersion.startsWith("9.") -> 21
                agpVersion.startsWith("8.14") -> 21
                agpVersion.startsWith("8.") -> 17
                agpVersion.startsWith("7.") -> 11
                else -> 21
            }
            val recommendedKotlin = agpToKotlin[majorMinor] ?: "2.0.0"
            val recommendedNdk = agpToNdk[majorMinor] ?: "27.0.12077973"
            val recommendedCmake = agpToCmake[majorMinor] ?: "3.22.1"
            val isPreview = agpVersion.contains("rc") || agpVersion.contains("beta") || agpVersion.contains("alpha")
            val isEol = agpVersion.startsWith("7.") || agpVersion.startsWith("4.") || agpVersion.startsWith("3.")

            CompatibilityInfo(
                agpVersion = agpVersion,
                minGradleVersion = minGradle,
                recommendedGradleVersion = recommendedGradle,
                minJdkVersion = minJdk,
                recommendedJdkVersion = recommendedJdk,
                recommendedKotlinVersion = recommendedKotlin,
                recommendedNdkVersion = recommendedNdk,
                recommendedCmakeVersion = recommendedCmake,
                isPreview = isPreview,
                isEol = isEol,
            )
        }
    }

    /**
     * Gradle 버전에 대한 최소 JDK 버전 반환.
     */
    fun getMinJdkForGradle(gradleVersion: String): Int {
        val majorMinor = gradleVersion.removePrefix("gradle-").take(gradleVersion.lastIndexOf('.')).takeIf { it.contains('.') } ?: gradleVersion
        return gradleToMinJdk[majorMinor] ?: 17
    }

    /**
     * NDK 버전에 대한 권장 CMake 버전 반환.
     */
    fun getRecommendedCmakeForNdk(ndkVersion: String): String {
        val major = ndkVersion.split(".").firstOrNull() ?: "27"
        return ndkToCmake[major] ?: "3.22.1"
    }

    /**
     * 현재 프로젝트 설정과 목표 AGP 버전 간의 호환성 검사.
     */
    fun checkCompatibility(
        currentAgp: String,
        targetAgp: String,
        currentGradle: String,
        currentJdk: Int,
        currentKotlin: String,
        currentNdk: String,
        currentCmake: String,
    ): List<CompatibilityIssue> {
        val issues = mutableListOf<CompatibilityIssue>()
        val targetInfo = getCompatibilityInfo(targetAgp)
        val currentInfo = getCompatibilityInfo(currentAgp)

        // Gradle 호환성
        val currentGradleVer = currentGradle.removePrefix("gradle-")
        val minGradleVer = targetInfo.minGradleVersion
        if (compareVersions(currentGradleVer, minGradleVer) < 0) {
            issues.add(CompatibilityIssue(
                type = IssueType.GRADLE_VERSION,
                severity = Severity.ERROR,
                message = "Gradle $currentGradleVer is too old for AGP $targetAgp. Minimum required: $minGradleVer",
                currentValue = currentGradleVer,
                requiredValue = minGradleVer,
                recommendedValue = targetInfo.recommendedGradleVersion,
                autoFixable = true,
            ))
        } else if (compareVersions(currentGradleVer, targetInfo.recommendedGradleVersion) < 0) {
            issues.add(CompatibilityIssue(
                type = IssueType.GRADLE_VERSION,
                severity = Severity.WARNING,
                message = "Gradle $currentGradleVer works but $targetInfo.recommendedGradleVersion is recommended for AGP $targetAgp",
                currentValue = currentGradleVer,
                requiredValue = targetInfo.recommendedGradleVersion,
                recommendedValue = targetInfo.recommendedGradleVersion,
                autoFixable = true,
            ))
        }

        // JDK 호환성
        if (currentJdk < targetInfo.minJdkVersion) {
            issues.add(CompatibilityIssue(
                type = IssueType.JDK_VERSION,
                severity = Severity.ERROR,
                message = "JDK $currentJdk is too old for AGP $targetAgp. Minimum required: JDK ${targetInfo.minJdkVersion}",
                currentValue = currentJdk.toString(),
                requiredValue = targetInfo.minJdkVersion.toString(),
                recommendedValue = targetInfo.recommendedJdkVersion.toString(),
                autoFixable = false,
            ))
        } else if (currentJdk < targetInfo.recommendedJdkVersion) {
            issues.add(CompatibilityIssue(
                type = IssueType.JDK_VERSION,
                severity = Severity.WARNING,
                message = "JDK $currentJdk works but JDK ${targetInfo.recommendedJdkVersion} is recommended for AGP $targetAgp",
                currentValue = currentJdk.toString(),
                requiredValue = targetInfo.recommendedJdkVersion.toString(),
                recommendedValue = targetInfo.recommendedJdkVersion.toString(),
                autoFixable = false,
            ))
        }

        // Kotlin 호환성
        if (compareVersions(currentKotlin, targetInfo.recommendedKotlinVersion) < 0) {
            issues.add(CompatibilityIssue(
                type = IssueType.KOTLIN_VERSION,
                severity = Severity.WARNING,
                message = "Kotlin $currentKotlin may not be fully compatible with AGP $targetAgp. Recommended: ${targetInfo.recommendedKotlinVersion}",
                currentValue = currentKotlin,
                requiredValue = targetInfo.recommendedKotlinVersion,
                recommendedValue = targetInfo.recommendedKotlinVersion,
                autoFixable = true,
            ))
        }

        // NDK 호환성
        val recommendedNdkMajor = targetInfo.recommendedNdkVersion.split(".").first().toIntOrNull() ?: 27
        val currentNdkMajor = currentNdk.split(".").first().toIntOrNull() ?: 0
        if (currentNdkMajor < recommendedNdkMajor - 1) {
            issues.add(CompatibilityIssue(
                type = IssueType.NDK_VERSION,
                severity = Severity.WARNING,
                message = "NDK $currentNdk is outdated for AGP $targetAgp. Recommended: ${targetInfo.recommendedNdkVersion}",
                currentValue = currentNdk,
                requiredValue = targetInfo.recommendedNdkVersion,
                recommendedValue = targetInfo.recommendedNdkVersion,
                autoFixable = true,
            ))
        }

        // CMake 호환성
        if (compareVersions(currentCmake, targetInfo.recommendedCmakeVersion) < 0) {
            issues.add(CompatibilityIssue(
                type = IssueType.CMAKE_VERSION,
                severity = Severity.WARNING,
                message = "CMake $currentCmake may not work well with NDK ${targetInfo.recommendedNdkVersion}. Recommended: ${targetInfo.recommendedCmakeVersion}",
                currentValue = currentCmake,
                requiredValue = targetInfo.recommendedCmakeVersion,
                recommendedValue = targetInfo.recommendedCmakeVersion,
                autoFixable = true,
            ))
        }

        // AGP 메이저 버전 변경 시 경고
        val currentMajor = currentAgp.split(".").first().toIntOrNull() ?: 0
        val targetMajor = targetAgp.split(".").first().toIntOrNull() ?: 0
        if (targetMajor > currentMajor) {
            issues.add(CompatibilityIssue(
                type = IssueType.AGP_MAJOR_UPGRADE,
                severity = Severity.WARNING,
                message = "Major AGP upgrade from $currentAgp to $targetAgp. Breaking changes expected: namespace migration, buildToolsVersion removal, variant API changes.",
                currentValue = currentAgp,
                requiredValue = targetAgp,
                recommendedValue = targetAgp,
                autoFixable = false,
            ))
        }

        // EOL AGP 경고
        if (targetInfo.isEol) {
            issues.add(CompatibilityIssue(
                type = IssueType.AGP_EOL,
                severity = Severity.WARNING,
                message = "AGP $targetAgp is End-of-Life. No more security updates or bug fixes.",
                currentValue = targetAgp,
                requiredValue = "Latest stable AGP",
                recommendedValue = "9.3.0",
                autoFixable = false,
            ))
        }

        return issues
    }

    /**
     * 사용 가능한 AGP 버전 목록 반환 (최신순).
     */
    fun getAvailableAgpVersions(): List<String> {
        return listOf(
            "9.3.0", "9.2.0", "9.1.0", "9.0.0",
            "8.14.0", "8.13.0", "8.12.0", "8.11.0", "8.10.0",
            "8.9.0", "8.8.0", "8.7.0", "8.6.0", "8.5.0", "8.4.0", "8.3.0", "8.2.0", "8.1.0", "8.0.0",
            "7.4.2", "7.3.1", "7.2.2", "7.1.3", "7.0.4",
        )
    }

    /**
     * 사용 가능한 Gradle 버전 목록 반환.
     */
    fun getAvailableGradleVersions(): List<String> {
        return listOf(
            "9.0", "8.14", "8.13", "8.12", "8.11", "8.10", "8.9", "8.8", "8.7", "8.6", "8.5", "8.4", "8.3", "8.2", "8.1", "8.0",
            "7.6", "7.5", "7.4", "7.3", "7.2", "7.1", "7.0",
        ).map { "gradle-$it" }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    enum class IssueType {
        GRADLE_VERSION,
        JDK_VERSION,
        KOTLIN_VERSION,
        NDK_VERSION,
        CMAKE_VERSION,
        AGP_MAJOR_UPGRADE,
        AGP_EOL,
        API_DEPRECATION,
        NAMESPACE_MIGRATION,
        BUILD_TOOLS_VERSION_REMOVAL,
    }

    enum class Severity {
        ERROR, WARNING, INFO
    }

    data class CompatibilityIssue(
        val type: IssueType,
        val severity: Severity,
        val message: String,
        val currentValue: String,
        val requiredValue: String,
        val recommendedValue: String,
        val autoFixable: Boolean,
    )

    /** AGP 버전별 권장 도구 세트 */
    data class RecommendedSet(
        val agpVersion: String,
        val gradleVersion: String,
        val jdkVersion: Int,
        val kotlinVersion: String,
        val ndkVersion: String,
        val cmakeVersion: String,
    )

    fun getRecommendedSet(agpVersion: String): RecommendedSet {
        val info = getCompatibilityInfo(agpVersion)
        return RecommendedSet(
            agpVersion = agpVersion,
            gradleVersion = info.recommendedGradleVersion,
            jdkVersion = info.recommendedJdkVersion,
            kotlinVersion = info.recommendedKotlinVersion,
            ndkVersion = info.recommendedNdkVersion,
            cmakeVersion = info.recommendedCmakeVersion,
        )
    }
}