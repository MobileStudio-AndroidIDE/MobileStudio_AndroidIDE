package com.aide.mobilestudio.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Focused unit tests for the pure (tool-free) validation components. */
class ValidationParsersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- GradlePropertiesParser -------------------------------------------

    @Test
    fun propertiesParserHandlesCommentsContinuationsAndErrors() {
        val text = buildString {
            appendLine("# comment line")
            appendLine("! bang comment")
            appendLine("org.gradle.parallel=true")
            appendLine("continued.key=first \\")
            appendLine("second")
            appendLine("orphanline")
            appendLine("=novaluekey")
            appendLine("org.gradle.parallel=false")
        }
        val (entries, issues) = GradlePropertiesParser.parse(text)
        val map = entries.associate { it.key to it.value }
        assertEquals(
            listOf("true", "false"),
            entries.filter { it.key == "org.gradle.parallel" }.map { it.value }
        )
        assertEquals("first second", map["continued.key"])
        assertEquals("", map["orphanline"])
        assertTrue(issues.any { it.errorCode == "PROPS_NO_VALUE" })
        assertTrue(issues.any { it.errorCode == "PROPS_EMPTY_KEY" && it.severity == InspectionSeverity.ERROR })
        assertTrue(issues.any { it.errorCode == "PROPS_DUPLICATE" })
    }

    @Test
    fun propertiesParserUnescapesValues() {
        val (entries, _) = GradlePropertiesParser.parse("msg=hello\\nworld\\u0041")
        assertEquals("hello\nworldA", entries.first().value)
    }

    // ---- GradleCodeScanner -------------------------------------------------

    @Test
    fun maskCommentsRemovesCommentsButKeepsStrings() {
        val text = "val a = \"x // not a comment\" // real comment\n/* block */ val b = 1"
        val masked = GradleCodeScanner.maskComments(text)
        assertEquals(text.length, masked.length)
        assertTrue("// real comment" !in masked)
        assertTrue("/* block */" !in masked)
        assertTrue("x // not a comment" in masked)
    }

    @Test
    fun maskAllBlanksBracesInsideStrings() {
        val text = "val s = \"{ not a block\"\nandroid {\n  compileSdk = 34\n}"
        val masked = GradleCodeScanner.maskAll(text)
        val blocks = GradleCodeScanner.findBlocks(masked, "android")
        assertEquals(1, blocks.size)
        val body = text.substring(blocks[0])
        assertTrue("compileSdk" in body)
    }

    @Test
    fun findBlocksHandlesNestedBlocksAndParens() {
        val text = "android {\n  defaultConfig {\n    minSdk = 21\n  }\n}\ndependencies {\n  implementation(\"a:b:1\")\n}"
        val masked = GradleCodeScanner.maskAll(text)
        val android = GradleCodeScanner.findBlocks(masked, "android")
        assertEquals(1, android.size)
        assertTrue("defaultConfig" in text.substring(android[0]))
        val deps = GradleCodeScanner.findBlocks(masked, "dependencies")
        assertEquals(1, deps.size)
    }

    @Test
    fun balanceIssuesReportsUnclosedBraceWithLine() {
        val file = File(tmp.root, "build.gradle")
        file.writeText("android {\n  compileSdk 34\n")
        val issues = CheckSupport.balanceIssues(file, file.readText(), "BUILD_SYNTAX")
        assertEquals(1, issues.size)
        assertEquals("BUILD_SYNTAX", issues[0].errorCode)
        assertEquals(1, issues[0].line)
    }

    @Test
    fun balanceIssuesAcceptsBalancedScript() {
        val file = File(tmp.root, "build.gradle")
        file.writeText("android {\n  compileSdk 34\n  defaultConfig {\n    minSdk 21\n  }\n}\n")
        val issues = CheckSupport.balanceIssues(file, file.readText(), "BUILD_SYNTAX")
        assertTrue(issues.isEmpty())
    }

    // ---- ToolOutputParser --------------------------------------------------

    @Test
    fun parsesK2Diagnostics() {
        val output = """
            /p/Bad.kt:2:16: error: initializer type mismatch: expected 'Int', actual 'String'.
                val x: Int = "not an int"
                           ^
            /p/Bad.kt:3:14: error: syntax error: Expecting ')'.
        """.trimIndent()
        val diags = ToolOutputParser.parseK2(output)
        assertEquals(2, diags.size)
        assertEquals(2, diags[0].line)
        assertEquals(16, diags[0].column)
        assertTrue(diags[0].isError)
        assertTrue("syntax error" in diags[1].message)
    }

    @Test
    fun parsesJavacDiagnosticsWithCaretColumn() {
        val output = """
            /p/B.java:1: error: illegal start of type
            public class B { void f( { } }
                                       ^
            1 error
        """.trimIndent()
        val diags = ToolOutputParser.parseJavac(output)
        assertEquals(1, diags.size)
        assertEquals(1, diags[0].line)
        assertTrue("caret column should be recovered", diags[0].column > 1)
        assertTrue(diags[0].isError)
    }

    @Test
    fun parsesJavacSymbolDetailLines() {
        val output = """
            /p/Main.java:7: error: cannot find symbol
                UndefinedClass.foo();
                ^
              symbol:   variable UndefinedClass
              location: class Main
            1 error
        """.trimIndent()
        val diags = ToolOutputParser.parseJavac(output)
        assertEquals(1, diags.size)
        assertTrue("symbol:" in diags[0].message)
        assertTrue("location:" in diags[0].message)
    }

    @Test
    fun parsesAapt2PerFileAndAggregate() {
        val perFile = "/p/AndroidManifest.xml:4: error: attribute android:weirdAttr not found."
        val d1 = ToolOutputParser.parseAapt2(perFile)
        assertEquals(1, d1.size)
        assertEquals(4, d1[0].line)
        assertTrue(d1[0].isError)

        val aggregate = "error: failed linking file resources."
        val d2 = ToolOutputParser.parseAapt2(aggregate)
        assertEquals(1, d2.size)
        assertNull(d2[0].path)
        assertTrue(d2[0].isError)

        // Per-file diagnostics win over aggregates when both are present.
        val mixed = perFile + "\n" + aggregate
        assertEquals(1, ToolOutputParser.parseAapt2(mixed).size)
    }

    @Test
    fun looksLikeToolFailureDetectsCrashes() {
        assertTrue(ToolOutputParser.looksLikeToolFailure("Exception in thread \"main\" java.lang.NoClassDefFoundError: x", 0))
        assertTrue(!ToolOutputParser.looksLikeToolFailure("error: cannot find symbol", 1))
    }

    // ---- GradleProjectScanner ---------------------------------------------

    @Test
    fun settingsParsesGroovyIncludesAndRootName() {
        val file = File(tmp.root, "settings.gradle")
        file.writeText(
            "rootProject.name = \"MyApp\"\n" +
                "include ':app', ':feature:home'\n" +
                "includeFlat 'flatmod'\n"
        )
        val info = GradleProjectScanner.parseSettings(file)!!
        assertEquals("MyApp", info.rootProjectName)
        val paths = info.includes.map { it.path }
        assertTrue(":app" in paths)
        assertTrue(":feature:home" in paths)
        assertTrue("flatmod" in paths)
        val appDir = info.includes.first { it.path == ":app" }.dir
        assertEquals(File(tmp.root, "app").absolutePath, appDir.absolutePath)
    }

    @Test
    fun settingsParsesKtsIncludeCall() {
        val file = File(tmp.root, "settings.gradle.kts")
        file.writeText("rootProject.name = \"MyApp\"\ninclude(\":app\", \":lib\")\n")
        val info = GradleProjectScanner.parseSettings(file)!!
        assertEquals(listOf(":app", ":lib"), info.includes.map { it.path })
    }

    @Test
    fun buildModuleParsesGroovyAndroidBlockAndDependencies() {
        val dir = File(tmp.root, "app")
        dir.mkdirs()
        val build = File(dir, "build.gradle")
        build.writeText(
            "plugins {\n" +
                "    id 'com.android.application'\n" +
                "    id 'org.jetbrains.kotlin.android'\n" +
                "}\n" +
                "android {\n" +
                "    namespace 'com.example.app'\n" +
                "    compileSdk 34\n" +
                "    defaultConfig {\n" +
                "        minSdk 21\n" +
                "        targetSdk 34\n" +
                "    }\n" +
                "}\n" +
                "dependencies {\n" +
                "    implementation 'androidx.core:core-ktx:1.13.1'\n" +
                "    implementation project(':missing')\n" +
                "    implementation files('libs/a.jar')\n" +
                "}\n"
        )
        val module = GradleProjectScanner.parseBuildModule(dir, build)!!
        assertEquals("com.example.app", module.strProp("namespace"))
        assertEquals(34, module.intProp("compileSdk"))
        assertEquals(21, module.intProp("minSdk"))
        assertEquals(34, module.intProp("targetSdk"))
        assertTrue(module.hasAndroidBlock)
        assertTrue("com.android.application" in module.pluginIds)
        assertTrue(module.kotlinPluginText)
        val projectDeps = module.dependencies.filter { it.kind == ParsedDependency.Kind.PROJECT }
        assertEquals(":missing", projectDeps.single().projectPath)
        val coords = module.dependencies.filter { it.kind == ParsedDependency.Kind.COORDINATE }
        assertTrue(coords.any { it.coordinate == listOf("androidx.core", "core-ktx", "1.13.1") })
        val files = module.dependencies.filter { it.kind == ParsedDependency.Kind.FILES }
        assertEquals(listOf("libs/a.jar"), files.single().filePaths)
    }

    @Test
    fun buildModuleParsesKtsAssignmentsAndCompose() {
        val dir = File(tmp.root, "appkts")
        dir.mkdirs()
        val build = File(dir, "build.gradle.kts")
        build.writeText(
            "plugins {\n" +
                "    id(\"com.android.application\")\n" +
                "}\n" +
                "android {\n" +
                "    namespace = \"com.example.k\"\n" +
                "    compileSdk = 35\n" +
                "    buildFeatures {\n" +
                "        compose = true\n" +
                "    }\n" +
                "}\n"
        )
        val module = GradleProjectScanner.parseBuildModule(dir, build)!!
        assertEquals("com.example.k", module.strProp("namespace"))
        assertEquals(35, module.intProp("compileSdk"))
        assertTrue(module.composeEnabled)
        assertTrue(module.hasAndroidBlock)
    }

    // ---- ValidationCache ---------------------------------------------------

    @Test
    fun cacheRoundTripsIssuesAndInvalidatesOnChange() {
        val cacheDir = tmp.newFolder("cache")
        val projectRoot = tmp.newFolder("proj")
        val src = File(projectRoot, "A.kt")
        src.writeText("val a = 1\n")

        val cache = ValidationCache(cacheDir)
        val key = cache.computeKey(
            "toolsig", projectRoot,
            ValidationCache.GroupKey("kotlin-src", "app", listOf(src))
        )
        assertNull("no entry before first store", cache.load(key))

        val issues = listOf(
            InspectionIssue(
                InspectionSeverity.ERROR, InspectionCategory.KOTLIN,
                src, 1, 5, "message with | pipes\nand newlines", "KOTLIN_ERROR"
            ),
            InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.JAVA,
                null, 2, 1, "file-less warning", "JAVA_ERROR"
            )
        )
        cache.store(key, issues)
        val loaded = cache.load(key)
        assertNotNull(loaded)
        assertEquals(issues, loaded)

        // Content change must change the key (incremental invalidation).
        Thread.sleep(10) // ensure mtime granularity differs
        src.writeText("val a = 2\n")
        val key2 = cache.computeKey(
            "toolsig", projectRoot,
            ValidationCache.GroupKey("kotlin-src", "app", listOf(src))
        )
        assertNotEquals(key, key2)
        assertNull(cache.load(key2))

        // Toolchain change must change the key too.
        val key3 = cache.computeKey(
            "other-sig", projectRoot,
            ValidationCache.GroupKey("kotlin-src", "app", listOf(src))
        )
        assertNotEquals(key, key3)
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }
}
