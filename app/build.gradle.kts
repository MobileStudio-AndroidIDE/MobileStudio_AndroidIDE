plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
}

android {
    namespace = "com.aide.mobilestudio"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.aide.mobilestudio"
        minSdk = 26
        targetSdk = 34
        versionCode = 261112
        versionName = "26.11.12 Magnolia 🌸"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true
        missingDimensionStrategy("products", "editor")
        manifestPlaceholders["godotLibraryVersion"] = "4.7.2.stable"
        resConfigs("en", "ko", "ja", "b+zh+Hans", "b+zh+Hant", "ar", "fr", "de", "it", "pt", "ru", "es")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = false
            isJniDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // With built-in Kotlin (AGP 9+), jvmTarget defaults to
    // compileOptions.targetCompatibility (Java 17 here), so there is
    // no kotlinOptions / kotlin.compilerOptions block needed.

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        // Unit tests touch android.graphics (Color/Paint) through the
        // terminal classes; stubbed defaults keep them runnable on the JVM.
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories += "src/main/jniLibs"
        }
    }

    androidResources {
        noCompress += listOf(
            "gz",
            "jar",
            "tar",
            "tgz",
            "dat"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/libopenxr_loader.so"
            )
            keepDebugSymbols += setOf(
                "**/libaapt2_exec.so"
            )
        }

        resources {
            // 충돌 방지 (중복 META-INF 처리)
            pickFirsts += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/ASL2.0",
                "META-INF/INDEX.LIST",
                "module-info.class"
            )

            // 불필요한 충돌 리소스 제거
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "**/Log4j2Plugins.dat",
                "**/kotlin/**",
                "**/*.kotlin_module"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":godot-lib"))
    implementation(project(":godot-editor"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // EncryptedSharedPreferences for API keys / OAuth tokens / sessions.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.25")
    implementation("com.google.code.gson:gson:2.11.0")

    // ??XZ ??XZInputStream (.deb ?�축 ?�제), ?�으�?tukaani 미해�??�류
    implementation("org.tukaani:xz:1.9")

    implementation("org.json:json:20240303")
    implementation("commons-io:commons-io:2.17.0")
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-commons:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
    implementation(files("libs/d8.jar"))

    implementation("org.apache.commons:commons-compress:1.27.1")
    // zstd-jni removed — using pure Kotlin ZstdDecompressor instead
    implementation(files("libs/luaj-jse-3.0.2.jar"))
    implementation("org.mozilla:rhino:1.7.15")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

// The sample extension lives in examples/ as the single source of truth (it is
// also what CI and the host build.sh consume). Mirror it into assets so the app
// can offer "install sample" and "build sample" without the user creating a
// project by hand first.
val syncExtensionSamples = tasks.register<Copy>("syncExtensionSamples") {
    into(layout.projectDirectory.dir("src/main/assets/extensions/samples"))
    from(rootProject.file("examples/line-counter")) {
        into("line-counter")
        exclude("build/**")
    }
    from(rootProject.file("examples")) {
        include("*.msext")
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncExtensionSamples)
}
