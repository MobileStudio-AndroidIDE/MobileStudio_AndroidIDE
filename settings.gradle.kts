pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // PREFER_PROJECT로 설정하여 build.gradle에서도 주소를 찾을 수 있게 허용합니다.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MobileStudio"
include(":app")
include(":godot-lib")
include(":godot-editor")
