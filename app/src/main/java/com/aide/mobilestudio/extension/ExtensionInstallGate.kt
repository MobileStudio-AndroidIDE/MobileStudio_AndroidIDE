package com.aide.mobilestudio.extension

/**
 * Install-time validation for an extension package.
 *
 * Deliberately pure and Context-free: every decision is derived from the
 * package, the registry entry and what this build of MobileStudio can offer,
 * so the whole gate is unit-testable without an Android runtime.
 * [ExtensionManager] wires it in; the UI only ever sees the returned verdict.
 *
 * The gate exists because an installed extension is loaded by
 * [ExtensionHost] through a PathClassLoader over its `classes.dex` -- code the
 * package author controls. Everything that can be known *before* that happens
 * is decided here, and anything the user has to accept (new permissions) is
 * surfaced as [Verdict.newPermissions] so the caller can ask first.
 */
object ExtensionInstallGate {

    /** Mirrors the upload limits enforced server-side. */
    const val MAX_PACKAGE_BYTES = 50L * 1024 * 1024
    const val MAX_EXTRACTED_BYTES = 200L * 1024 * 1024

    /**
     * Compressed -> extracted ratio ceiling. A .msext holds source, an icon and
     * usually one classes.dex; anything inflating by more than this is a
     * decompression bomb rather than a real extension.
     */
    const val MAX_COMPRESSION_RATIO = 200.0

    /** What this build of MobileStudio can offer an extension. */
    data class Host(
        val appVersionName: String,
        val apiVersion: Int = ExtensionApi.API_VERSION,
        val maxPackageBytes: Long = MAX_PACKAGE_BYTES,
        val maxExtractedBytes: Long = MAX_EXTRACTED_BYTES,
        val maxCompressionRatio: Double = MAX_COMPRESSION_RATIO,
    )

    /** Facts observed while downloading and unpacking the package. */
    data class Package(
        val downloadedSha256: String,
        val sizeBytes: Long,
        val extractedBytes: Long,
        val hasDex: Boolean,
    )

    /** The gate's answer. [ok] means the install may proceed. */
    data class Verdict(
        val errors: List<String>,
        val warnings: List<String>,
        val requiredPermissions: List<String>,
        val newPermissions: List<String>,
    ) {
        val ok: Boolean get() = errors.isEmpty()

        /** Permissions the user has not granted to this extension yet. */
        val needsApproval: Boolean get() = newPermissions.isNotEmpty()
    }

    /**
     * @param registryId the id the registry entry claims
     * @param registrySha256 the hash the registry/server recorded for this
     *   version; blank when the registry does not carry one
     * @param suspended set when reports/admin action block downloads
     * @param alreadyGranted permissions previously granted to [registryId]
     */
    fun evaluate(
        host: Host,
        manifest: ExtensionManifest?,
        pkg: Package,
        registryId: String,
        registrySha256: String = "",
        suspended: Boolean = false,
        suspendedReason: String = "",
        alreadyGranted: Set<String> = emptySet(),
    ): Verdict {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ------------------------------------------------------------ trust
        if (suspended) {
            errors += if (suspendedReason.isNotBlank()) {
                "다운로드가 차단된 확장입니다 — $suspendedReason"
            } else {
                "다운로드가 차단된 확장입니다 (신고 누적 또는 관리자 차단)"
            }
        }

        // The registry entry and the package must describe the same extension.
        // Without this a .msext can be swapped under a trusted id and still
        // install, because the id the UI showed is never re-checked.
        if (manifest == null) {
            errors += "extension.json 없음 또는 손상"
            return Verdict(errors, warnings, emptyList(), emptyList())
        }
        if (registryId.isNotBlank() && manifest.id != registryId) {
            errors += "패키지 ID가 레지스트리 항목과 다릅니다 (표시: $registryId, 실제: ${manifest.id})"
        }

        // ------------------------------------------------------------ bytes
        if (pkg.sizeBytes > host.maxPackageBytes) {
            errors += "패키지가 너무 큽니다 (${pkg.sizeBytes} bytes > ${host.maxPackageBytes})"
        }
        if (pkg.extractedBytes > host.maxExtractedBytes) {
            errors += "압축 해제 크기가 너무 큽니다 (${pkg.extractedBytes} bytes > ${host.maxExtractedBytes})"
        }
        val ratio = if (pkg.sizeBytes > 0) pkg.extractedBytes.toDouble() / pkg.sizeBytes else 0.0
        if (ratio > host.maxCompressionRatio) {
            errors += "압축 비율이 비정상입니다 (${String.format(java.util.Locale.US, "%.0f", ratio)}:1 > ${"%.0f".format(host.maxCompressionRatio)}:1) — 압축 폭탄 가능성"
        }

        // ---------------------------------------------------------- integrity
        // Pin to whatever the server recorded at scan time. A package whose
        // hash differs from the scanned one is not the package that was
        // approved, even if it carries a valid manifest.
        if (registrySha256.isNotBlank() &&
            !pkg.downloadedSha256.equals(registrySha256, ignoreCase = true)
        ) {
            errors += "SHA-256 불일치 — 스캔된 파일과 다릅니다 (기대 ${registrySha256.take(12)}…, 실제 ${pkg.downloadedSha256.take(12)}…)"
        }

        // -------------------------------------------------------- compatibility
        if (manifest.extensionApiVersion > host.apiVersion) {
            errors += "Extension API ${manifest.extensionApiVersion} 필요 (이 앱은 API ${host.apiVersion})"
        }
        if (host.appVersionName.isNotBlank() &&
            ExtensionManager.isNewerVersion(manifest.minStudioVersion, host.appVersionName)
        ) {
            errors += "이 확장은 MobileStudio ${manifest.minStudioVersion} 이상이 필요합니다 (현재 ${host.appVersionName})"
        }
        val maxAllowed = manifest.maxStudioVersion
        if (maxAllowed.isNotBlank() && maxAllowed != "*" &&
            host.appVersionName.isNotBlank() &&
            ExtensionManager.isNewerVersion(host.appVersionName, maxAllowed)
        ) {
            errors += "이 확장은 MobileStudio ${maxAllowed} 이하에서만 동작합니다 (현재 ${host.appVersionName})"
        }

        // ------------------------------------------------------- code payload
        // MobileStudio resolves the entry point class out of classes.dex, so
        // either mismatch produces an extension that can never load. Catching
        // it here means a broken package is refused with a reason instead of
        // installing and then failing silently in ExtensionHost.
        val entryPoint = manifest.entryPoint.trim()
        when {
            entryPoint.isNotEmpty() && !pkg.hasDex ->
                errors += "entryPoint '$entryPoint'이 선언되었지만 classes.dex가 없습니다"
            entryPoint.isEmpty() && pkg.hasDex ->
                errors += "classes.dex가 포함되어 있지만 entryPoint가 비어 있습니다"
            entryPoint.isEmpty() ->
                warnings += "코드가 없는 메타데이터 전용 확장입니다"
        }

        // ---------------------------------------------------------- permissions
        val required = manifest.permissions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val newPerms = required.filter { it !in alreadyGranted }

        return Verdict(errors, warnings, required, newPerms)
    }
}
