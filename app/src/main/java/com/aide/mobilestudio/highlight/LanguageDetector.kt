package com.aide.mobilestudio.highlight

enum class Language(val displayName: String, vararg val extensions: String) {
    KOTLIN("Kotlin", "kt", "kts"),
    JAVA("Java", "java"),
    XML("XML", "xml"),
    GRADLE("Gradle", "gradle"),
    JSON("JSON", "json"),
    C("C", "c", "h"),
    CPP("C++", "cpp", "hpp", "cc", "cxx", "hxx"),
    MARKDOWN("Markdown", "md", "markdown"),
    SHELL("Shell", "sh", "bash", "zsh"),
    PYTHON("Python", "py", "pyw"),
    RUST("Rust", "rs"),
    GO("Go", "go"),
    SWIFT("Swift", "swift"),
    DART("Dart", "dart"),
    R("R", "r", "R"),
    LUA("Lua", "lua"),
    GDSCRIPT("GDScript", "gd", "gdscript"),
    SQL("SQL", "sql"),
    YAML("YAML", "yml", "yaml"),
    PROPERTIES("Properties", "properties"),
    TOML("TOML", "toml"),
    INI("INI", "ini", "cfg", "conf"),
    CSS("CSS", "css"),
    HTML("HTML", "html", "htm"),
    JAVASCRIPT("JavaScript", "js", "jsx"),
    TYPESCRIPT("TypeScript", "ts", "tsx"),
    ASSEMBLY("Assembly", "s", "S", "asm"),
    DIFF("Diff", "diff", "patch"),
    PROTO("Protobuf", "proto"),
    CMAKE("CMake", "cmake"),
    UNKNOWN("Text");

    companion object {
        private val extensionMap: Map<String, Language> by lazy {
            val map = mutableMapOf<String, Language>()
            entries.forEach { lang ->
                lang.extensions.forEach { ext ->
                    map[ext] = lang
                }
            }
            map
        }

        private val filenamePatterns = mapOf(
            "Makefile" to SHELL,
            "CMakeLists.txt" to CMAKE,
            ".gitignore" to SHELL,
            ".gitattributes" to SHELL,
            ".editorconfig" to SHELL,
            "Dockerfile" to SHELL,
            "Jenkinsfile" to SHELL,
            "build.gradle.kts" to GRADLE,
            "settings.gradle.kts" to GRADLE,
            "build.gradle" to GRADLE,
            "settings.gradle" to GRADLE,
            "gradle.properties" to PROPERTIES,
            "local.properties" to PROPERTIES,
            "AndroidManifest.xml" to XML,
            "proguard-rules.pro" to SHELL,
            "Gemfile" to SHELL,
            "Rakefile" to SHELL,
            "Vagrantfile" to SHELL,
            "Podfile" to SHELL,
            "Berksfile" to SHELL
        )

        fun detect(filename: String): Language {
            val lowerName = filename.lowercase()
            filenamePatterns.forEach { (pattern, lang) ->
                if (lowerName == pattern.lowercase()) return lang
            }
            val ext = filename.substringAfterLast('.', "")
            if (ext.isNotEmpty()) {
                extensionMap[ext.lowercase()]?.let { lang ->
                    if (lang == GRADLE && ext == "kts") return KOTLIN
                    return lang
                }
            }
            return UNKNOWN
        }
    }
}
