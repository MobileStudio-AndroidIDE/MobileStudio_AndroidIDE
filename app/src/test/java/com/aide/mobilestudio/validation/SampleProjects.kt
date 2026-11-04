package com.aide.mobilestudio.validation

import java.io.File

/** Builders for the 10 sample Android projects exercised by the tests. */
object SampleProjects {

    const val PKG = "com.example.app"

    fun write(root: File, relPath: String, content: String) {
        val file = File(root, relPath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun defaultSettings(): String =
        "rootProject.name = \"TestApp\"\ninclude ':app'\n"

    fun defaultProps(): String =
        "org.gradle.jvmargs=-Xmx2048m\n" +
            "android.useAndroidX=true\n" +
            "org.gradle.parallel=true\n" +
            "kotlin.code.style=official\n"

    fun defaultAppBuild(): String =
        "plugins {\n" +
            "    id 'com.android.application'\n" +
            "    id 'org.jetbrains.kotlin.android'\n" +
            "}\n" +
            "\n" +
            "android {\n" +
            "    namespace 'com.example.app'\n" +
            "    compileSdk 34\n" +
            "\n" +
            "    defaultConfig {\n" +
            "        applicationId \"com.example.app\"\n" +
            "        minSdk 21\n" +
            "        targetSdk 34\n" +
            "        versionCode 1\n" +
            "        versionName \"1.0\"\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "dependencies {\n" +
            "    implementation 'androidx.core:core-ktx:1.13.1'\n" +
            "}\n"

    fun defaultManifest(): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
            "    <application android:label=\"@string/app_name\">\n" +
            "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n" +
            "            <intent-filter>\n" +
            "                <action android:name=\"android.intent.action.MAIN\" />\n" +
            "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
            "            </intent-filter>\n" +
            "        </activity>\n" +
            "    </application>\n" +
            "</manifest>\n"

    fun stringsXml(): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<resources>\n" +
            "    <string name=\"app_name\">TestApp</string>\n" +
            "</resources>\n"

    fun mainActivityKt(): String =
        "package $PKG\n" +
            "\n" +
            "import android.app.Activity\n" +
            "\n" +
            "class MainActivity : Activity()\n"

    fun wrapperProperties(): String =
        "distributionBase=GRADLE_USER_HOME\n" +
            "distributionPath=wrapper/dists\n" +
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip\n" +
            "networkTimeout=10000\n"

    fun scaffold(
        root: File,
        settings: String = defaultSettings(),
        gradleProperties: String = defaultProps(),
        appBuildFile: String = "build.gradle",
        appBuild: String = defaultAppBuild(),
        manifest: String = defaultManifest(),
        extra: Map<String, String> = emptyMap()
    ) {
        write(root, "settings.gradle", settings)
        write(root, "build.gradle", "// root project\n")
        write(root, "gradle.properties", gradleProperties)
        write(root, "gradle/wrapper/gradle-wrapper.properties", wrapperProperties())
        write(root, "app/$appBuildFile", appBuild)
        write(root, "app/src/main/AndroidManifest.xml", manifest)
        write(root, "app/src/main/res/values/strings.xml", stringsXml())
        write(
            root,
            "app/src/main/java/$PKG/MainActivity.kt",
            mainActivityKt()
        )
        extra.forEach { (path, content) -> write(root, path, content) }
    }
}
