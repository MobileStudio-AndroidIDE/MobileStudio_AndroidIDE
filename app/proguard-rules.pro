# proguard-rules.pro
#
# MobileStudio ProGuard / R8 rules.
#
# NOTE: release minification is currently DISABLED in app/build.gradle.kts
# (isMinifyEnabled = false) because the app drives Gradle/AGP through reflection
# (AndroidGradleCachePatcher, GradleToolingConnector, extension loading). These
# rules are correct and ready, but MUST be validated on-device before enabling
# minification (set isMinifyEnabled/isShrinkResources = true and run a native +
# extension install end-to-end).
#
# Package = com.aide.mobilestudio (the old com.example.mobilestudio rules matched
# nothing and silently left every JNI entry point unprotected).

# ---- JNI entry points (external fun / System.loadLibrary) ----
-keep class com.aide.mobilestudio.NativeBridge { *; }
-keep class com.aide.mobilestudio.NativeExec { *; }
-keep class com.aide.mobilestudio.ForkLoader { *; }
-keep class com.aide.mobilestudio.LuaNativeBridge { *; }
-keep class com.aide.mobilestudio.terminal.NativePty { *; }
-keep class com.aide.mobilestudio.ai.api.LlamaRuntimeBridge { *; }
-keep class com.aide.mobilestudio.ai.api.LlamaAndroid { *; }

# JNI callback types referenced from C++ via FindClass().
-keep class com.aide.mobilestudio.ai.api.LlamaRuntimeBridge$ModelStats { *; }
-keep class com.aide.mobilestudio.ai.api.LlamaAndroid$ModelStats { *; }

# Every class holding a native method must keep those members.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- Singletons / enums referenced by name ----
-keep class com.aide.mobilestudio.StorageHelper { *; }
-keepclassmembers enum com.aide.mobilestudio.LineLevel {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Extension platform (loaded reflectively at runtime) ----
-keep class com.aide.mobilestudio.extension.** { *; }
-keep interface com.aide.mobilestudio.extension.** { *; }

# ---- Gson model classes (field names are serialized) ----
-keep class com.aide.mobilestudio.extension.ExtensionManifest { *; }
-keep class com.aide.mobilestudio.extension.ExtensionManager$RepoEntry { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- Bundled interpreters (reflection-heavy) ----
-keep class org.luaj.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.tukaani.xz.** { *; }

# ---- Kotlin coroutines ----
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ---- Kotlin metadata (reflection) ----
-keep class kotlin.Metadata { *; }
