package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CMake 버전 리스트 정리 + 포팅 런타임 상수 검증.
 *
 * 단일 배포 정책: 포팅된 CMake 3.22.1 (arm64-v8a)만 배포하므로
 * AVAILABLE_CMAKE 와 NDK별 권장 버전은 항상 3.22.1 이어야 한다.
 */
class ToolchainManagerCmakeVersionsTest {

    @Test
    fun availableCmakeListIsSinglePortedVersion() {
        assertEquals(listOf("3.22.1"), ToolchainManager.AVAILABLE_CMAKE)
    }

    @Test
    fun cmakeVersionForNdkAlwaysReturnsDistributedVersion() {
        for (ndk in ToolchainManager.AVAILABLE_NDK) {
            assertEquals("NDK $ndk must recommend the distributed CMake", "3.22.1", ToolchainManager.cmakeVersionForNdk(ndk))
        }
        // 맵에 없는 NDK / 빈 버전도 기본값 3.22.1 로 수렴해야 한다.
        assertEquals("3.22.1", ToolchainManager.cmakeVersionForNdk("26.0.00000000"))
        assertEquals("3.22.1", ToolchainManager.cmakeVersionForNdk(""))
    }

    @Test
    fun cmakeRuntimeConstantsPointAtPortedRelease() {
        assertEquals("3.22.1", CmakeRuntimeManager.CMAKE_VERSION)
        assertTrue(
            "download URL must point at the cmake-android-v3.22.1 release asset",
            CmakeRuntimeManager.DOWNLOAD_URL.contains("cmake-android-v3.22.1") &&
                CmakeRuntimeManager.DOWNLOAD_URL.endsWith("cmake-3.22.1-android-arm64.tar.gz")
        )
        assertTrue(
            "SHA-256 must be 64 lowercase hex chars",
            CmakeRuntimeManager.EXPECTED_SHA256.matches(Regex("[0-9a-f]{64}"))
        )
        assertEquals(12_919_431L, CmakeRuntimeManager.ESTIMATED_ARCHIVE_BYTES)
    }
}
