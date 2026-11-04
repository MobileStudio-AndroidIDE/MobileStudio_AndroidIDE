package com.aide.mobilestudio.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

/**
 * Guards the compile-time stub of the extension API against drifting away from
 * the real one.
 *
 * Extensions are compiled on-device against `assets/extensions/extension-api.jar`,
 * but at runtime [ExtensionHost] casts the loaded class to the app's own
 * `MobileStudioExtension`. If the two ever disagree, every extension compiles
 * cleanly and then fails at load time with a linkage error that no amount of
 * on-device feedback can explain.
 */
class ExtensionApiStubTest {

    private val stubJar: File by lazy { locateAsset("extensions/extension-api.jar") }

    /**
     * Loads the stub classes ahead of the app's own ones.
     *
     * A plain URLClassLoader with no parent cannot resolve android.* or
     * kotlin.* (they are not in the jar), and one with the app's loader as
     * parent would simply return the app's classes for every lookup — hiding
     * the very divergence this test exists to catch.
     */
    private fun stubLoader(): ClassLoader {
        val own = java.util.zip.ZipFile(stubJar).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .toSet()
        }
        return object : URLClassLoader(arrayOf(stubJar.toURI().toURL()), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name !in own) return super.loadClass(name, resolve)
                synchronized(this) {
                    findLoadedClass(name)?.let { return it }
                    val loaded = findClass(name)
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
            }
        }
    }

    private fun signature(clazz: Class<*>): Set<String> =
        clazz.declaredMethods
            .filterNot { it.isSynthetic }
            .map { method ->
                method.name + "(" + method.parameterTypes.joinToString(",") { it.name } + "):" +
                    method.returnType.name
            }
            .toSet()

    /** The primary constructor: Kotlin adds shorter synthetic overloads. */
    private fun primaryConstructorParams(clazz: Class<*>): List<String> =
        clazz.declaredConstructors.maxByOrNull { it.parameterTypes.size }!!
            .parameterTypes.map { it.name }

    @Test
    fun `stub jar exists and is not empty`() {
        assertTrue("extension-api.jar missing at ${stubJar.absolutePath}", stubJar.isFile)
        assertTrue(stubJar.length() > 0)
    }

    @Test
    fun `MobileStudioExtension in the stub matches the app`() {
        val loader = stubLoader()
        val stub = loader.loadClass("com.aide.mobilestudio.extension.MobileStudioExtension")
        val real = MobileStudioExtension::class.java
        assertEquals(
            "stub and real MobileStudioExtension declare different methods",
            signature(real),
            signature(stub),
        )
        (loader as? java.io.Closeable)?.close()
    }

    @Test
    fun `ExtensionContext in the stub matches the app`() {
        val loader = stubLoader()
        val stub = loader.loadClass("com.aide.mobilestudio.extension.ExtensionContext")
        val real = ExtensionContext::class.java
        assertEquals(
            "stub and real ExtensionContext declare different methods",
            signature(real),
            signature(stub),
        )
        (loader as? java.io.Closeable)?.close()
    }

    @Test
    fun `ExtensionManifest constructor in the stub matches the app`() {
        // Kotlin data classes are constructed positionally, so argument order is
        // part of the contract: swapping two String fields compiles everywhere
        // and silently swaps their values at runtime.
        val loader = stubLoader()
        val stub = loader.loadClass("com.aide.mobilestudio.extension.ExtensionManifest")
        val real = ExtensionManifest::class.java
        assertEquals(
            "stub and real ExtensionManifest constructors take different arguments",
            primaryConstructorParams(real),
            primaryConstructorParams(stub),
        )
        (loader as? java.io.Closeable)?.close()
    }

    @Test
    fun `stub jar carries no classes outside the extension API`() {
        java.util.zip.ZipFile(stubJar).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList()
            assertTrue(entries.isNotEmpty())
            entries.forEach { name ->
                assertTrue(
                    "stub jar should not carry $name — it is compile-time only, " +
                        "and anything outside the API is dead weight in every package",
                    name.startsWith("com/aide/mobilestudio/extension/"),
                )
            }
        }
    }

    private fun locateAsset(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/assets/$relative")
            if (candidate.isFile) return candidate
            val moduleCandidate = File(dir, "app/src/main/assets/$relative")
            if (moduleCandidate.isFile) return moduleCandidate
            dir = dir.parentFile
        }
        return File("src/main/assets/$relative")
    }
}

/**
 * Pure parts of the on-device extension build: which sources are picked up and
 * how they reach javac/d8.
 */
class ExtensionBuilderTest {

    private val builder = ExtensionBuilder(javac = null, androidJar = null, d8 = null)

    @Test
    fun `sources are discovered under src and sorted for reproducible builds`() {
        withProject { root ->
            File(root, "src/a/One.java").apply { parentFile.mkdirs(); writeText("class One {}") }
            File(root, "src/b/Two.java").apply { parentFile.mkdirs(); writeText("class Two {}") }
            val found = builder.collectJavaSources(root).map { it.name }
            assertEquals(listOf("One.java", "Two.java"), found)
        }
    }

    @Test
    fun `build output and VCS directories are skipped`() {
        withProject { root ->
            File(root, "src/Keep.java").apply { parentFile.mkdirs(); writeText("class Keep {}") }
            File(root, "build/Skipped.java").apply { parentFile.mkdirs(); writeText("class Skipped {}") }
            File(root, "out/Skipped.java").apply { parentFile.mkdirs(); writeText("class Skipped {}") }
            File(root, ".git/Skipped.java").apply { parentFile.mkdirs(); writeText("class Skipped {}") }
            File(root, ".gradle/Skipped.java").apply { parentFile.mkdirs(); writeText("class Skipped {}") }
            val found = builder.collectJavaSources(root).map { it.name }
            assertEquals(listOf("Keep.java"), found)
        }
    }

    @Test
    fun `non java files are not treated as sources`() {
        withProject { root ->
            File(root, "src/Notes.md").apply { parentFile.mkdirs(); writeText("# notes") }
            File(root, "src/Main.kt").apply { parentFile.mkdirs(); writeText("fun main() {}") }
            assertTrue(builder.collectJavaSources(root).isEmpty())
        }
    }

    @Test
    fun `arg file quotes every path so spaces survive`() {
        withProject { root ->
            val argFile = File(root, "args.txt")
            val paths = listOf("/tmp/a b/One.class", "/tmp/c/Tow.class", "/tmp/quote\".class")
            builder.writeArgFile(argFile, paths)
            val lines = argFile.readText().trim().lines()
            assertEquals(3, lines.size)
            assertEquals("\"/tmp/a b/One.class\"", lines[0])
            assertTrue(lines[1].startsWith("\"") && lines[1].endsWith("\""))
            assertTrue(lines[2].contains("\\\""))
        }
    }

    @Test
    fun `build fails clearly when sources exist but entryPoint cannot be satisfied`() {
        // No tools installed -> the toolchain error, not a confusing javac crash.
        withProject { root ->
            File(root, "src/Main.java").apply { parentFile.mkdirs(); writeText("class Main {}") }
            val result = builder.build(root, File(root, "no-api.jar"), entryPoint = "Main") { }
            assertFalse(result.ok)
            assertNotNull(result.errors)
            assertTrue(result.errors.isNotEmpty())
        }
    }

    @Test
    fun `metadata-only project needs no sources and no toolchain`() {
        withProject { root ->
            val result = builder.build(root, File(root, "no-api.jar"), entryPoint = "") { }
            assertTrue(result.ok)
            assertTrue("nothing to compile", result.errors.isEmpty())
        }
    }

    @Test
    fun `entryPoint without any source is rejected instead of packaging a broken dex`() {
        withProject { root ->
            val result = builder.build(root, File(root, "no-api.jar"), entryPoint = "com.x.Y") { }
            assertFalse(result.ok)
            assertTrue(result.errors.single().contains("소스"))
        }
    }

    private fun withProject(block: (File) -> Unit) {
        val root = File.createTempFile("extproj", "").apply { delete(); mkdirs() }
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

/**
 * The bundled sample must survive the very gate an install goes through, or the
 * one-tap "샘플 설치" button fails on first use for everyone.
 */
class SampleExtensionPackageTest {

    private val sample: File by lazy { locate("examples/com.mobilestudio.linecounter-v1.0.0.msext") }

    @Test
    fun `bundled sample passes the install gate`() {
        val dir = extract(sample)
        try {
            val manifest = readManifest(dir)
            assertNotNull("sample has no readable extension.json", manifest)
            assertTrue(
                "sample carries no classes.dex",
                File(dir, "classes.dex").isFile,
            )

            val verdict = ExtensionInstallGate.evaluate(
                host = ExtensionInstallGate.Host(appVersionName = ""),
                manifest = manifest,
                pkg = ExtensionInstallGate.Package(
                    downloadedSha256 = "unused",
                    sizeBytes = sample.length(),
                    extractedBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    hasDex = true,
                ),
                registryId = manifest!!.id,
            )
            assertTrue("sample rejected by the install gate:\n" + verdict.errors.joinToString("\n"), verdict.ok)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bundled sample declares the permission its code actually uses`() {
        val dir = extract(sample)
        try {
            val manifest = readManifest(dir)!!
            // LineCounter reads files through context.readFile, which is gated on
            // project.read; declaring anything else would either be dead or, worse,
            // ask for more than the sample needs.
            assertEquals(listOf("project.read"), manifest.permissions)
            assertTrue("entryPoint must point at the sample class", manifest.entryPoint.endsWith("LineCounter"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bundled sample satisfies the server's required members`() {
        // server/app/msext.py: REQUIRED_MEMBERS = ("extension.json", "README.md")
        java.util.zip.ZipFile(sample).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("extension.json missing", "extension.json" in names)
            assertTrue(
                "README.md missing — the server refuses to publish without it",
                "README.md" in names,
            )
            assertTrue("classes.dex missing", "classes.dex" in names)
        }
    }

    private fun extract(pkg: File): File {
        val dir = File.createTempFile("sample", "").apply { delete(); mkdirs() }
        java.util.zip.ZipFile(pkg).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val out = File(dir, entry.name)
                require(out.canonicalPath.startsWith(dir.canonicalPath)) { "zip slip: ${entry.name}" }
                out.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        return dir
    }

    private fun readManifest(dir: File): ExtensionManifest? = try {
        val o = org.json.JSONObject(File(dir, "extension.json").readText())
        val perms = mutableListOf<String>()
        o.optJSONArray("permissions")?.let { p -> for (j in 0 until p.length()) perms.add(p.getString(j)) }
        val langs = mutableListOf<String>()
        o.optJSONArray("languages")?.let { l -> for (j in 0 until l.length()) langs.add(l.getString(j)) }
        ExtensionManifest(
            id = o.getString("id"),
            name = o.getString("name"),
            version = o.getString("version"),
            author = o.optString("author"),
            description = o.optString("description"),
            minStudioVersion = o.optString("minStudioVersion", "1.0.0"),
            maxStudioVersion = o.optString("maxStudioVersion", "*"),
            entryPoint = o.optString("entryPoint"),
            permissions = perms,
            languages = langs,
            extensionApiVersion = o.optInt("extensionApiVersion", ExtensionApi.API_VERSION),
        )
    } catch (e: Exception) {
        null
    }

    private fun locate(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return File(relative)
    }
}
