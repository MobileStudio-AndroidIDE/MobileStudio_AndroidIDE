package com.aide.mobilestudio.extension

import com.aide.mobilestudio.extension.ExtensionManager.RepoEntry

/**
 * The 20 official MobileStudio extensions. Used as the "Available" list when the
 * GitHub Releases registry is unreachable. (They used to seed a committed
 * repository.json index; the Releases list is the registry now.)
 */
object OfficialExtensions {

    private fun e(
        id: String, name: String, version: String, description: String,
        perms: List<String>, langs: List<String>, size: Long
    ) = RepoEntry(
        id = id, name = name, version = version, description = description,
        author = "MobileStudio", download = "", size = size, sha256 = "",
        minStudioVersion = "1.0.0", permissions = perms, languages = langs
    )

    val all: List<RepoEntry> = listOf(
        e("com.mobilestudio.lua", "Lua Game Development", "1.0.0",
            "Lua syntax highlighting, autocomplete, diagnostics, 실행, LÖVE + Game API, 프로젝트 템플릿",
            listOf("editor", "project.read", "project.write", "runtime", "storage", "ui"),
            listOf("lua"), 8_400_000),
        e("com.mobilestudio.ndk", "NDK / C++ Development", "1.0.0",
            "C/C++ 문법 강조, CMake, NDK 프로젝트, native build, CMake autocomplete, diagnostics",
            listOf("editor", "project.read", "project.write", "terminal", "build", "storage", "ui"),
            listOf("cpp", "c"), 2_100_000),
        e("com.mobilestudio.python", "Python Development", "1.0.0",
            "Python syntax highlighting, autocomplete, 실행, 프로젝트 템플릿, diagnostics",
            listOf("editor", "project.read", "project.write", "runtime", "storage", "ui"),
            listOf("python"), 12_000_000),
        e("com.mobilestudio.web", "Web Development", "1.0.0",
            "HTML/CSS/JS/TS 문법 강조, autocomplete, 미리보기, 로컬 서버, 프로젝트 템플릿",
            listOf("editor", "project.read", "project.write", "ui"),
            listOf("html", "css", "javascript", "typescript"), 900_000),
        e("com.mobilestudio.flutter", "Flutter / Dart", "1.0.0",
            "Dart 에디터, Flutter 프로젝트, pubspec 지원, Flutter run, 기본 미리보기",
            listOf("editor", "project.read", "project.write", "terminal", "build"),
            listOf("dart"), 1_500_000),
        e("com.mobilestudio.database", "Database / SQLite", "1.0.0",
            "SQLite 브라우저, SQL 에디터, 테이블 뷰어, 쿼리 실행, DB import/export",
            listOf("editor", "filesystem", "project.read"),
            listOf("sql"), 700_000),
        e("com.mobilestudio.apkanalyzer", "APK Analyzer+", "1.0.0",
            "APK 열기, Manifest 분석, classes.dex, resources, assets, native libraries, 크기 분석",
            listOf("filesystem", "project.read"),
            listOf("apk"), 600_000),
        e("com.mobilestudio.git", "Advanced Git", "1.0.0",
            "Git graph, commit history, 브랜치 시각화, interactive diff, stash, conflict helper",
            listOf("terminal", "project.read", "filesystem"),
            listOf("git"), 800_000),
        e("com.mobilestudio.ai", "AI Coding Assistant", "1.0.0",
            "코드 설명/생성/수정, 오류 설명, 프로젝트 분석. Provider: Local AI, OpenAI 호환, Ollama",
            listOf("editor", "project.read", "network", "ui"),
            listOf("any"), 1_200_000),
        e("com.mobilestudio.markdown", "Markdown", "1.0.0",
            "Markdown 에디터, 미리보기, 문법 강조, GitHub Markdown 지원",
            listOf("editor", "ui"),
            listOf("markdown"), 300_000),
        e("com.mobilestudio.jsonyaml", "JSON / YAML Tools", "1.0.0",
            "JSON formatter/validation, YAML validation, autocomplete, tree viewer",
            listOf("editor"),
            listOf("json", "yaml"), 250_000),
        e("com.mobilestudio.shell", "Shell Script", "1.0.0",
            "Bash/sh 문법 강조, diagnostics, 터미널 실행. .sh 파일 및 shebang 감지 지원",
            listOf("editor", "terminal", "ui"),
            listOf("shell", "bash", "sh"), 200_000),
        e("com.mobilestudio.java", "Java Advanced Tools", "1.0.0",
            "고급 inspections, refactoring, import optimizer, code generation",
            listOf("editor", "project.read"),
            listOf("java"), 900_000),
        e("com.mobilestudio.kotlin", "Kotlin Advanced Tools", "1.0.0",
            "고급 inspections, Kotlin refactoring, code generation, coroutine inspection",
            listOf("editor", "project.read"),
            listOf("kotlin"), 900_000),
        e("com.mobilestudio.gradle", "Gradle Advanced", "1.0.0",
            "Gradle task browser, dependency tree/search, build scan, 성능 정보",
            listOf("terminal", "project.read", "build"),
            listOf("gradle"), 500_000),
        e("com.mobilestudio.http", "HTTP / REST Client", "1.0.0",
            "GET/POST/PUT/DELETE 요청, headers, JSON body, response viewer, history",
            listOf("network", "ui"),
            listOf("http"), 400_000),
        e("com.mobilestudio.image", "Image / Asset Tools", "1.0.0",
            "이미지 뷰어, resize, crop, 압축, drawable 변환, density 리소스 헬퍼",
            listOf("filesystem", "project.read", "project.write"),
            listOf("image"), 600_000),
        e("com.mobilestudio.protobuf", "Protobuf / gRPC", "1.0.0",
            ".proto 문법 강조, validation, code generation, Android 프로젝트 통합",
            listOf("editor", "project.read", "project.write", "terminal"),
            listOf("proto"), 400_000),
        e("com.mobilestudio.gametools", "Game Development Tools", "1.0.0",
            "asset manager, sprite/tilemap viewer, 게임 템플릿, FPS/debug, game build tools",
            listOf("project.read", "project.write", "filesystem", "ui"),
            listOf("any"), 700_000),
        e("com.mobilestudio.performance", "Performance / Memory Tools", "1.0.0",
            "CPU/메모리 사용량, APK 크기, 빌드 시간, Gradle 성능, log 분석",
            listOf("filesystem", "project.read", "device"),
            listOf("any"), 500_000)
    )

    fun byId(id: String): RepoEntry? = all.firstOrNull { it.id == id }
}
