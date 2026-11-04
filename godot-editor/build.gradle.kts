plugins {
    id("com.android.library")
}

android {
    namespace = "org.godotengine.editor"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 26
        // targetSdk is no longer configurable on library modules in AGP 9+;
        // it is declared by the app module.
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
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.directories += "src/main/java"
            res.directories += "src/main/res"
            assets.directories += "src/main/assets"
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
}

dependencies {
    api(project(":godot-lib"))
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78")
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.53")
    implementation("org.godotengine:godot-openxr-vendors-androidxr:4.3.0-stable")
}
