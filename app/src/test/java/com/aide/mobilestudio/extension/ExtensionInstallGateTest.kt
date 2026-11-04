package com.aide.mobilestudio.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Install-time gate.
 *
 * An installed extension is loaded through a PathClassLoader over its
 * classes.dex, so whatever reaches that point is code the package author
 * controls. These tests pin every reason the gate has to refuse a package, and
 * the permission accounting that decides whether the user is asked first.
 */
class ExtensionInstallGateTest {

    // ---------------------------------------------------------------- helpers
    private fun host(
        appVersion: String = "1.2.0",
        api: Int = ExtensionApi.API_VERSION,
    ) = ExtensionInstallGate.Host(appVersionName = appVersion, apiVersion = api)

    private fun manifest(
        id: String = "com.test.demo",
        minStudio: String = "1.0.0",
        maxStudio: String = "*",
        entryPoint: String = "com.test.demo.Main",
        permissions: List<String> = listOf("editor"),
        apiVersion: Int = 1,
    ) = ExtensionManifest(
        id = id,
        name = "Demo",
        version = "1.0.0",
        author = "dev",
        description = "A demo extension.",
        minStudioVersion = minStudio,
        maxStudioVersion = maxStudio,
        entryPoint = entryPoint,
        permissions = permissions,
        languages = listOf("any"),
        extensionApiVersion = apiVersion,
    )

    private fun pkg(
        sha256: String = "a".repeat(64),
        size: Long = 4_000L,
        extracted: Long = 12_000L,
        hasDex: Boolean = true,
    ) = ExtensionInstallGate.Package(
        downloadedSha256 = sha256, sizeBytes = size,
        extractedBytes = extracted, hasDex = hasDex,
    )

    private fun evaluate(
        m: ExtensionManifest? = manifest(),
        p: ExtensionInstallGate.Package = pkg(),
        registryId: String = "com.test.demo",
        registrySha256: String = "a".repeat(64),
        suspended: Boolean = false,
        granted: Set<String> = emptySet(),
        h: ExtensionInstallGate.Host = host(),
    ) = ExtensionInstallGate.evaluate(
        host = h, manifest = m, pkg = p,
        registryId = registryId, registrySha256 = registrySha256,
        suspended = suspended, alreadyGranted = granted,
    )

    // ------------------------------------------------------------ happy path
    @Test
    fun `a well formed code extension passes`() {
        val verdict = evaluate(granted = setOf("editor"))
        assertTrue(verdict.errors.joinToString("\n"), verdict.ok)
        assertFalse(verdict.needsApproval)
        assertTrue(verdict.warnings.isEmpty())
    }

    @Test
    fun `a code extension asks for permissions it does not have yet`() {
        val verdict = evaluate(granted = emptySet())
        assertTrue(verdict.ok)
        assertEquals(listOf("editor"), verdict.newPermissions)
        assertTrue(verdict.needsApproval)
    }

    @Test
    fun `permissions already granted are not asked for again`() {
        val verdict = evaluate(granted = setOf("editor"))
        assertTrue(verdict.ok)
        assertTrue(verdict.newPermissions.isEmpty())
        assertFalse(verdict.needsApproval)
    }

    @Test
    fun `only the missing permissions are surfaced`() {
        val verdict = evaluate(
            m = manifest(permissions = listOf("editor", "network", "terminal")),
            granted = setOf("editor"),
        )
        assertTrue(verdict.ok)
        assertEquals(listOf("network", "terminal"), verdict.newPermissions)
    }

    // ------------------------------------------------------------- suspension
    @Test
    fun `a suspended extension cannot be installed`() {
        val verdict = evaluate(suspended = true)
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("차단") })
    }

    @Test
    fun `the suspension reason is surfaced to the user`() {
        val verdict = ExtensionInstallGate.evaluate(
            host = host(), manifest = manifest(), pkg = pkg(),
            registryId = "com.test.demo", suspended = true,
            suspendedReason = "신고 5건 누적",
        )
        assertTrue(verdict.errors.any { it.contains("신고 5건 누적") })
    }

    @Test
    fun `a lifted suspension no longer blocks`() {
        assertTrue(evaluate(suspended = false).ok)
    }

    // -------------------------------------------------------- identity spoofing
    @Test
    fun `a package whose id differs from the registry entry is refused`() {
        // The UI shows the registry id; without this check a different .msext
        // could be served under a trusted id and still install.
        val verdict = evaluate(m = manifest(id = "com.evil.other"))
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("레지스트리 항목과 다릅니다") })
    }

    @Test
    fun `an unreadable manifest is refused before anything else`() {
        val verdict = evaluate(m = null)
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("extension.json") })
    }

    // ----------------------------------------------------------- integrity pin
    @Test
    fun `a package that differs from the scanned hash is refused`() {
        val verdict = evaluate(p = pkg(sha256 = "b".repeat(64)))
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("SHA-256 불일치") })
    }

    @Test
    fun `hash comparison is case insensitive`() {
        val verdict = evaluate(p = pkg(sha256 = "A".repeat(64)), registrySha256 = "a".repeat(64))
        assertTrue(verdict.errors.joinToString("\n"), verdict.ok)
    }

    @Test
    fun `an unpinned registry skips the hash comparison`() {
        val verdict = evaluate(p = pkg(sha256 = "b".repeat(64)), registrySha256 = "")
        assertTrue(verdict.errors.joinToString("\n"), verdict.ok)
    }

    // -------------------------------------------------------- compatibility
    @Test
    fun `an extension needing a newer api version is refused`() {
        val verdict = evaluate(
            m = manifest(apiVersion = ExtensionApi.API_VERSION + 1),
            h = host(api = ExtensionApi.API_VERSION),
        )
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("Extension API") })
    }

    @Test
    fun `an extension requiring a newer studio version is refused`() {
        val verdict = evaluate(m = manifest(minStudio = "9.9.9"), h = host(appVersion = "1.0.0"))
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("9.9.9") })
    }

    @Test
    fun `an extension capped below the current studio version is refused`() {
        val verdict = evaluate(m = manifest(maxStudio = "0.9.0"), h = host(appVersion = "1.0.0"))
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("0.9.0") })
    }

    @Test
    fun `an uncapped star max version accepts any build`() {
        assertTrue(evaluate(m = manifest(maxStudio = "*"), h = host(appVersion = "99.0.0")).ok)
    }

    @Test
    fun `an unknown host version does not block on version checks`() {
        val verdict = evaluate(
            m = manifest(minStudio = "9.9.9"),
            h = host(appVersion = ""),
        )
        assertTrue(verdict.errors.joinToString("\n"), verdict.ok)
    }

    // ---------------------------------------------------------- code payload
    @Test
    fun `an entry point without a dex payload is refused`() {
        val verdict = evaluate(m = manifest(entryPoint = "com.test.demo.Main"), p = pkg(hasDex = false))
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("classes.dex가 없습니다") })
    }

    @Test
    fun `a dex payload without an entry point is refused`() {
        val verdict = evaluate(m = manifest(entryPoint = ""), p = pkg(hasDex = true))
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("entryPoint가 비어 있습니다") })
    }

    @Test
    fun `a metadata only extension is allowed but flagged`() {
        val verdict = evaluate(m = manifest(entryPoint = ""), p = pkg(hasDex = false))
        assertTrue(verdict.errors.joinToString("\n"), verdict.ok)
        assertTrue(verdict.warnings.any { it.contains("메타데이터 전용") })
    }

    // ------------------------------------------------------------------ sizes
    @Test
    fun `an oversized package is refused`() {
        val verdict = evaluate(p = pkg(size = ExtensionInstallGate.MAX_PACKAGE_BYTES + 1))
        assertFalse(verdict.ok)
    }

    @Test
    fun `an oversized extraction is refused`() {
        val verdict = evaluate(p = pkg(extracted = ExtensionInstallGate.MAX_EXTRACTED_BYTES + 1))
        assertFalse(verdict.ok)
    }

    @Test
    fun `a decompression bomb is refused`() {
        // 4 KB expanding to 40 MB is ~10000:1, far past any real extension.
        val verdict = evaluate(p = pkg(size = 4_000L, extracted = 40_000_000L))
        assertFalse(verdict.ok)
        assertTrue(verdict.errors.any { it.contains("압축 폭탄") })
    }

    @Test
    fun `a realistic compression ratio is allowed`() {
        // 3 KB package expanding to ~5 KB (dex + sources).
        val verdict = evaluate(p = pkg(size = 3_000L, extracted = 5_000L))
        assertTrue(verdict.errors.joinToString("\n"), verdict.ok)
    }

    @Test
    fun `a zero byte package does not divide by zero`() {
        val verdict = evaluate(p = pkg(size = 0L, extracted = 0L))
        assertTrue(verdict.errors.toString(), verdict.errors.none { it.contains("압축 비율") })
    }

    // ------------------------------------------------------------- aggregation
    @Test
    fun `every failing check is reported at once`() {
        val verdict = ExtensionInstallGate.evaluate(
            host = host(appVersion = "1.0.0", api = 1),
            manifest = manifest(
                id = "com.evil.other",
                minStudio = "9.9.9",
                permissions = listOf("network"),
            ),
            pkg = pkg(sha256 = "b".repeat(64), hasDex = false),
            registryId = "com.test.demo",
            registrySha256 = "a".repeat(64),
            suspended = true,
        )
        // identity + suspension + min version at minimum
        assertTrue(verdict.errors.toString(), verdict.errors.size >= 3)
        assertFalse(verdict.ok)
    }
}
