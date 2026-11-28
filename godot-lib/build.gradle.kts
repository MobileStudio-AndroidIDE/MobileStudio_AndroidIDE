plugins {
    id("com.android.library")
}

android {
    namespace = "org.godotengine.godot"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 26
        // targetSdk is no longer configurable on library modules in AGP 9+;
        // it is declared by the app module.
        manifestPlaceholders["godotLibraryVersion"] = "4.7.2.stable"
        missingDimensionStrategy("products", "editor")
    }

    flavorDimensions += "products"
    productFlavors {
        create("editor") {
            dimension = "products"
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
        aidl = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.directories += "src/main/java"
            res.directories += "src/main/res"
            jniLibs.directories += "../third_party/godot/native"
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }

    // Release builds run Android lint's "vital" check, which crashes on
    // Godot.kt (lint/K2 FIR bug: 'findFirCompiledSymbol' on a companion
    // object). The code itself compiles fine — skip the release lint gate.
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.9.1")
}
