package com.aide.mobilestudio.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectFilesTest {

    // ------------------------------------------------------------ ignore rules

    @Test
    fun `build directories are ignored`() {
        assertTrue(ProjectFiles.isIgnored("build/output.jar"))
        assertTrue(ProjectFiles.isIgnored("app/build/tmp/x.class"))
        assertTrue(ProjectFiles.isIgnored("app/build/generated/x"))
    }

    @Test
    fun `vcs and tool directories are ignored`() {
        for (dir in listOf(".git", ".gradle", ".idea", ".cxx", ".kotlin", "node_modules", "out", "outputs", ".opencode")) {
            assertTrue(dir, ProjectFiles.isIgnored("$dir/internal/file"))
        }
    }

    @Test
    fun `any hidden directory is ignored`() {
        assertTrue(ProjectFiles.isIgnoredDir(".vscode"))
        assertTrue(ProjectFiles.isIgnored(".github/workflows/ci.yml"))
    }

    @Test
    fun `source paths are not ignored`() {
        assertFalse(ProjectFiles.isIgnored("app/src/main/java/com/example/Main.kt"))
        assertFalse(ProjectFiles.isIgnored("README.md"))
        assertFalse(ProjectFiles.isIgnored("settings.gradle.kts"))
    }

    @Test
    fun `dotfiles themselves are listed`() {
        assertFalse(ProjectFiles.isIgnored(".gitignore"))
    }

    // ------------------------------------------------------------ listing

    private fun newRoot(): File = Files.createTempDirectory("projfiles").toFile()

    @Test
    fun `list honors ignore rules and sorts shallow-first`() {
        val root = newRoot()
        File(root, "build").mkdirs()
        File(root, ".git").mkdirs()
        File(root, "app/src/main/java/com/example").mkdirs()
        File(root, "README.md").writeText("readme")
        File(root, ".gitignore").writeText("build/")
        File(root, "build/out.jar").writeText("junk")
        File(root, ".git/index").writeText("junk")
        File(root, "app/src/main/java/com/example/Main.kt").writeText("class A")
        File(root, "app/build.gradle.kts").writeText("//")

        val files = ProjectFiles.list(root)
        assertEquals(listOf(".gitignore", "README.md", "app/build.gradle.kts", "app/src/main/java/com/example/Main.kt"), files)
    }

    @Test
    fun `list respects the limit`() {
        val root = newRoot()
        for (i in 0 until 20) File(root, "f$i.txt").writeText("x")
        assertEquals(5, ProjectFiles.list(root, limit = 5).size)
    }

    @Test
    fun `list on a missing root is empty`() {
        assertTrue(ProjectFiles.list(File("/definitely/not/here")).isEmpty())
    }

    // ------------------------------------------------------------ filter

    @Test
    fun `filter ranks filename prefix matches first`() {
        val paths = listOf("src/main/Utils.kt", "UtilsTest.kt", "lib/net/Utils.kt")
        assertEquals(listOf("UtilsTest.kt", "src/main/Utils.kt", "lib/net/Utils.kt"), ProjectFiles.filter(paths, "utils"))
    }

    @Test
    fun `filter falls back to substring in path`() {
        val paths = listOf("src/main/java/Foo.kt", "docs/guide.md")
        assertEquals(listOf("src/main/java/Foo.kt"), ProjectFiles.filter(paths, "main/java"))
    }

    @Test
    fun `empty query returns the head of the list`() {
        val paths = (0 until 50).map { "f$it.kt" }
        assertEquals(30, ProjectFiles.filter(paths, "").size)
    }

    @Test
    fun `filter respects the limit`() {
        val paths = (0 until 40).map { "f$it.kt" }
        assertEquals(10, ProjectFiles.filter(paths, "f", limit = 10).size)
    }
}
