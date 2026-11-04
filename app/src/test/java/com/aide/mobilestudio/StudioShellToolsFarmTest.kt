package com.aide.mobilestudio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Pure-logic tests for the PATH/LD farm (symlink directory) that makes
 * `tools install` output usable immediately in already-running shells.
 * Works on plain directories — no Android Context involved.
 */
class StudioShellToolsFarmTest {

    private lateinit var toolsDir: File
    private lateinit var farm: File

    @Before
    fun setUp() {
        toolsDir = File.createTempFile("toolsfarm", "").apply {
            delete()
            mkdirs()
        }
        farm = File(toolsDir, "bin")
    }

    @After
    fun tearDown() {
        toolsDir.deleteRecursively()
    }

    private fun toolBin(name: String, vararg files: String): File {
        val dir = File(toolsDir, "$name/bin").apply { mkdirs() }
        for (f in files) {
            File(dir, f).writeText("content-$name-$f")
        }
        return dir
    }

    @Test
    fun installedToolBinariesAppearInTheFarm() {
        val bin = toolBin("cmake", "cmake", "ctest")
        StudioShellTools.refreshFarmDir(farm, listOf(bin))

        assertTrue(File(farm, "cmake").exists())
        assertTrue(File(farm, "ctest").exists())
        assertEquals("content-cmake-cmake", File(farm, "cmake").readText())
    }

    @Test
    fun reinstallReplacesLinksAndRemovesStaleOnes() {
        val first = toolBin("tool", "tool")
        StudioShellTools.refreshFarmDir(farm, listOf(first))
        assertTrue(File(farm, "tool").exists())

        // new version installed (target file rewritten) is reflected
        File(first, "tool").writeText("v2")
        StudioShellTools.refreshFarmDir(farm, listOf(first))
        assertEquals("v2", File(farm, "tool").readText())

        // removed: no longer in the source list -> link disappears
        StudioShellTools.refreshFarmDir(farm, emptyList())
        assertFalse(File(farm, "tool").exists())
    }

    @Test
    fun laterInstallIsVisibleWithoutLosingEarlierEntries() {
        val a = toolBin("a", "alpha")
        StudioShellTools.refreshFarmDir(farm, listOf(a))

        val b = toolBin("b", "beta")
        StudioShellTools.refreshFarmDir(farm, listOf(a, b))

        assertTrue(File(farm, "alpha").exists())
        assertTrue(File(farm, "beta").exists())
        assertEquals("content-a-alpha", File(farm, "alpha").readText())
        assertEquals("content-b-beta", File(farm, "beta").readText())
    }
}
