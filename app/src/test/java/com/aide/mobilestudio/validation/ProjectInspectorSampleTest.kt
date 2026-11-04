package com.aide.mobilestudio.validation

import com.aide.mobilestudio.validation.SampleProjects.PKG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end validation runs against 10 real sample projects, using the real
 * tools (kotlinc from the Gradle dist, javac, aapt2 from the local SDK).
 */
class ProjectInspectorSampleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var toolchain: ValidationToolchain

    @Before
    fun setUp() {
        val host = HostToolchain.toolchain()
        Assume.assumeTrue(
            "host toolchain (JDK + Android SDK + Gradle dist Kotlin) required",
            host != null
        )
        toolchain = host!!
    }

    private fun inspect(root: File): InspectionReport {
        val inspector = ProjectInspector(
            projectRoot = root,
            toolchain = toolchain,
            runner = SystemProcessRunner(),
            cache = ValidationCache(tmp.newFolder("cache")),
            tempDir = tmp.newFolder("tmp")
        )
        return inspector.inspect()
    }

    private fun describe(report: InspectionReport): String =
        report.issues.joinToString("\n") {
            "${it.severity} ${it.category} ${it.errorCode} " +
                "${it.file?.name}:${it.line} ${it.message}"
        }

    /** JUnit4 order (condition, message) for readable call sites. */
    private fun assertTrue(condition: Boolean, message: String) {
        org.junit.Assert.assertTrue(message, condition)
    }

    private fun assertNoErrors(report: InspectionReport) {
        val errors = report.issues.filter { it.severity == InspectionSeverity.ERROR }
        assertTrue(errors.isEmpty(), "Unexpected errors:\n${describe(report)}")
    }

    private fun assertNoErrorsOrWarnings(report: InspectionReport) {
        val bad = report.issues.filter {
            it.severity == InspectionSeverity.ERROR || it.severity == InspectionSeverity.WARNING
        }
        assertTrue(bad.isEmpty(), "Unexpected errors/warnings:\n${describe(report)}")
    }

    // 1. Normal project ------------------------------------------------------

    @Test
    fun case1_normalProjectHasNoErrors() {
        val root = tmp.newFolder("normal")
        SampleProjects.scaffold(root)
        val report = inspect(root)
        assertNoErrors(report)
        assertEquals(ProjectInspector.defaultChecks().size, report.checks.size)
    }

    // 2. Kotlin syntax error -------------------------------------------------

    @Test
    fun case2_kotlinSyntaxErrorIsDetected() {
        val root = tmp.newFolder("kotlin-syntax")
        SampleProjects.scaffold(
            root,
            extra = mapOf(
                "app/src/main/java/$PKG/Bad.kt" to
                    "package ${SampleProjects.PKG}\n" +
                    "\n" +
                    "class Bad {\n" +
                    "    fun f() {\n" +
                    "        println(\"hello\"\n" +
                    "    }\n" +
                    "}\n"
            )
        )
        val report = inspect(root)
        val kotlinErrors = report.issues.filter {
            it.severity == InspectionSeverity.ERROR && it.category == InspectionCategory.KOTLIN
        }
        assertTrue(kotlinErrors.isNotEmpty(), "Expected a Kotlin error:\n${describe(report)}")
        assertTrue(kotlinErrors.any { it.line > 0 }, "Kotlin error should carry a line number")
    }

    // 3. Java syntax error ---------------------------------------------------

    @Test
    fun case3_javaSyntaxErrorIsDetected() {
        val root = tmp.newFolder("java-syntax")
        SampleProjects.scaffold(
            root,
            extra = mapOf(
                "app/src/main/java/${SampleProjects.PKG}/Broken.java" to
                    "package ${SampleProjects.PKG};\n" +
                    "public class Broken { void f( { } }\n"
            )
        )
        val report = inspect(root)
        val javaErrors = report.issues.filter {
            it.severity == InspectionSeverity.ERROR && it.category == InspectionCategory.JAVA
        }
        assertTrue(javaErrors.isNotEmpty(), "Expected a Java error:\n${describe(report)}")
        assertTrue(javaErrors.any { it.line > 0 }, "Java error should carry a line number")
    }

    // 4. Malformed XML -------------------------------------------------------

    @Test
    fun case4_malformedXmlIsDetected() {
        val root = tmp.newFolder("bad-xml")
        SampleProjects.scaffold(
            root,
            extra = mapOf(
                "app/src/main/res/layout/activity_main.xml" to
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                    "    <TextView android:layout_width=\"wrap_content\" />\n" +
                    "</Bad>\n"
            )
        )
        val report = inspect(root)
        val xmlErrors = report.issues.filter {
            it.severity == InspectionSeverity.ERROR && it.category == InspectionCategory.XML_RESOURCE
        }
        assertTrue(xmlErrors.isNotEmpty(), "Expected an XML error:\n${describe(report)}")
        assertTrue(
            xmlErrors.any { it.errorCode == "RES_XML_MALFORMED" || it.errorCode == "RES_COMPILE_FAILED" },
            "Expected malformed-XML detection:\n${describe(report)}"
        )
    }

    // 5. Missing resource reference -----------------------------------------

    @Test
    fun case5_missingResourceReferenceIsDetected() {
        val root = tmp.newFolder("missing-res")
        SampleProjects.scaffold(
            root,
            extra = mapOf(
                "app/src/main/res/layout/activity_main.xml" to
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    android:layout_width=\"match_parent\"\n" +
                    "    android:layout_height=\"match_parent\">\n" +
                    "    <TextView\n" +
                    "        android:layout_width=\"wrap_content\"\n" +
                    "        android:layout_height=\"wrap_content\"\n" +
                    "        android:text=\"@string/does_not_exist\" />\n" +
                    "</LinearLayout>\n"
            )
        )
        val report = inspect(root)
        val linkErrors = report.issues.filter {
            it.severity == InspectionSeverity.ERROR && it.category == InspectionCategory.RESOURCE_LINK
        }
        assertTrue(
            linkErrors.any { "does_not_exist" in it.message },
            "Expected missing @string error:\n${describe(report)}"
        )
    }

    // 6. Manifest errors -----------------------------------------------------

    @Test
    fun case6_manifestErrorsAreDetected() {
        val root = tmp.newFolder("bad-manifest")
        val manifest =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "    <application android:label=\"@string/app_name\" android:weirdAttr=\"1\">\n" +
                "        <activity android:name=\".MainActivity\">\n" +
                "            <intent-filter>\n" +
                "                <action android:name=\"android.intent.action.MAIN\" />\n" +
                "            </intent-filter>\n" +
                "        </activity>\n" +
                "    </application>\n" +
                "</manifest>\n"
        SampleProjects.scaffold(root, manifest = manifest)
        val report = inspect(root)
        assertTrue(
            report.issues.any { it.errorCode == "MANIFEST_EXPORTED_MISSING" },
            "Expected missing android:exported error:\n${describe(report)}"
        )
        assertTrue(
            report.issues.any {
                it.category == InspectionCategory.RESOURCE_LINK && "weirdAttr" in it.message
            },
            "Expected unknown attribute error from aapt2 link:\n${describe(report)}"
        )
    }

    // 7. build.gradle.kts syntax error --------------------------------------

    @Test
    fun case7_buildScriptSyntaxErrorIsDetected() {
        val root = tmp.newFolder("bad-build-script")
        SampleProjects.scaffold(
            root,
            appBuildFile = "build.gradle.kts",
            appBuild =
                "plugins {\n" +
                    "    id(\"com.android.application\")\n" +
                    "\n" +
                    "android {\n" +
                    "    compileSdk = 34\n"
        )
        val report = inspect(root)
        assertTrue(
            report.issues.any {
                it.severity == InspectionSeverity.ERROR &&
                    it.category == InspectionCategory.BUILD_GRADLE &&
                    it.errorCode == "BUILD_SYNTAX"
            },
            "Expected build script syntax error:\n${describe(report)}"
        )
    }

    // 8. Wrong module path ---------------------------------------------------

    @Test
    fun case8_wrongModulePathIsDetected() {
        val root = tmp.newFolder("bad-module-path")
        SampleProjects.scaffold(
            root,
            settings = "rootProject.name = \"TestApp\"\ninclude ':app', ':missing'\n",
            appBuild =
                SampleProjects.defaultAppBuild().replace(
                    "dependencies {\n",
                    "dependencies {\n    implementation project(':missing')\n"
                )
        )
        val report = inspect(root)
        assertTrue(
            report.issues.any { it.errorCode == "SETTINGS_INCLUDE_MISSING" },
            "Expected settings include error:\n${describe(report)}"
        )
        assertTrue(
            report.issues.any { it.errorCode == "BUILD_PROJECT_NOT_INCLUDED" },
            "Expected project() dependency error:\n${describe(report)}"
        )
    }

    // 9. Bad gradle.properties ----------------------------------------------

    @Test
    fun case9_badGradlePropertiesAreDetected() {
        val root = tmp.newFolder("bad-props")
        SampleProjects.scaffold(
            root,
            gradleProperties =
                "org.gradle.parallel=maybe\n" +
                    "=nokey\n" +
                    "orphanline\n"
        )
        val report = inspect(root)
        val propErrors = report.issues.filter {
            it.severity == InspectionSeverity.ERROR && it.category == InspectionCategory.GRADLE_PROPERTIES
        }
        assertTrue(propErrors.size >= 2, "Expected gradle.properties errors:\n${describe(report)}")
        assertTrue(
            propErrors.any { it.errorCode == "PROPS_BAD_BOOLEAN" },
            "Expected bad boolean error:\n${describe(report)}"
        )
        assertTrue(
            propErrors.any { it.errorCode == "PROPS_EMPTY_KEY" },
            "Expected empty key error:\n${describe(report)}"
        )
    }

    // 10. Fully clean project -----------------------------------------------

    @Test
    fun case10_perfectProjectHasNoErrorsOrWarnings() {
        val root = tmp.newFolder("perfect")
        val appBuildKts =
            "plugins {\n" +
                "    id(\"com.android.application\")\n" +
                "    id(\"org.jetbrains.kotlin.android\")\n" +
                "}\n" +
                "\n" +
                "android {\n" +
                "    namespace = \"com.example.app\"\n" +
                "    compileSdk = 34\n" +
                "    defaultConfig {\n" +
                "        applicationId = \"com.example.app\"\n" +
                "        minSdk = 24\n" +
                "        targetSdk = 34\n" +
                "        versionCode = 1\n" +
                "        versionName = \"1.0\"\n" +
                "    }\n" +
                "}\n"
        SampleProjects.scaffold(
            root,
            appBuildFile = "build.gradle.kts",
            appBuild = appBuildKts,
            extra = mapOf(
                "app/src/main/java/${SampleProjects.PKG}/Util.java" to
                    "package ${SampleProjects.PKG};\n" +
                    "public class Util {\n" +
                    "    public static int twice(int x) { return x * 2; }\n" +
                    "}\n",
                "app/src/main/res/layout/activity_main.xml" to
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    android:layout_width=\"match_parent\"\n" +
                    "    android:layout_height=\"match_parent\">\n" +
                    "    <TextView\n" +
                    "        android:layout_width=\"wrap_content\"\n" +
                    "        android:layout_height=\"wrap_content\"\n" +
                    "        android:text=\"@string/app_name\" />\n" +
                    "</LinearLayout>\n"
            )
        )
        val report = inspect(root)
        assertNoErrorsOrWarnings(report)
    }
}
