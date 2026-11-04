package com.aide.mobilestudio.ai.lang

import java.io.File

/**
 * One entry in the universal language registry.
 *
 * This is an *extensible* description of a language: how to recognise it, what
 * external tools *may* be able to work with it, and how those tools are invoked.
 * Registering a language here does **not** claim the toolchain is installed —
 * actual availability is probed at runtime (see [LanguageRegistry.capabilities]).
 */
data class LanguageSpec(
    val id: String,
    val displayName: String,
    val extensions: List<String> = emptyList(),
    val filenames: List<String> = emptyList(),
    val shebangs: List<String> = emptyList(),
    val configFiles: List<String> = emptyList(),
    /** External formatter executable, if one is well known. */
    val formatter: String? = null,
    /** External compiler / syntax checker executable, if one is well known. */
    val compiler: String? = null,
    /** Linter / static analysis executable. */
    val linter: String? = null,
    /** Test runner executable. */
    val testRunner: String? = null,
    /** Language server executables that can serve this language. */
    val lspServers: List<String> = emptyList(),
    /** Default build invocation, e.g. ["gradle", "assemble"]. Empty when unknown. */
    val buildCommand: List<String> = emptyList(),
    /** Default test invocation. Empty when unknown. */
    val testCommand: List<String> = emptyList(),
    /** True when a source file can be executed directly by the runtime. */
    val runnable: Boolean = false,
) {
    fun matchesExtension(ext: String): Boolean = extensions.any { it.equals(ext, ignoreCase = true) }

    fun matchesFilename(name: String): Boolean =
        filenames.any { it.equals(name, ignoreCase = true) }
}

enum class LanguageToolKind { COMPILER, FORMATTER, LINTER, TEST, LSP }

/** Result of probing whether an external tool is usable in the current environment. */
data class ToolAvailability(
    val kind: LanguageToolKind,
    val tool: String?,
    val available: Boolean,
    val resolvedPath: String? = null,
    val reason: String? = null,
)

/** Per-language, per-tool availability. Null tool => the language declares no such tool. */
data class LanguageCapabilities(
    val language: LanguageSpec,
    val compile: ToolAvailability,
    val format: ToolAvailability,
    val lint: ToolAvailability,
    val test: ToolAvailability,
    val lsp: List<ToolAvailability>,
) {
    fun anyAvailable(): Boolean =
        compile.available || format.available || lint.available || test.available || lsp.any { it.available }

    /** Human-readable lines, always distinguishing available vs unavailable and why. */
    fun describe(): List<String> {
        fun line(label: String, a: ToolAvailability): String {
            val tool = a.tool ?: return "$label: not configured"
            return if (a.available) "$label: $tool (${a.resolvedPath})"
            else "$label: $tool unavailable" + (a.reason?.let { " — $it" } ?: "")
        }
        val out = mutableListOf(
            line("compile", compile),
            line("format", format),
            line("lint", lint),
            line("test", test),
        )
        if (lsp.isEmpty()) out.add("lsp: no server configured")
        else lsp.forEach { out.add(line("lsp", it)) }
        return out
    }
}

/** Locates executables. Injectable so tests are deterministic and host-independent. */
interface ToolProbe {
    /** Returns the absolute path of [name] if it can be executed, else null. */
    fun which(name: String): String?
}

/**
 * Probes the process `PATH` (plus `/system/bin` on Android). This is deliberately
 * conservative: a tool is only reported available when a real executable exists.
 */
object PathToolProbe : ToolProbe {
    private val cache = HashMap<String, String?>()

    override fun which(name: String): String? = synchronized(cache) {
        cache.getOrPut(name) { resolve(name) }
    }

    private fun resolve(name: String): String? {
        if (name.isBlank()) return null
        val direct = File(name)
        if (direct.isAbsolute && direct.canExecute()) return direct.absolutePath
        val dirs = (System.getenv("PATH")?.split(File.pathSeparator).orEmpty() + "/system/bin")
            .filter { it.isNotBlank() }
        for (dir in dirs) {
            val f = File(dir, name)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }
}

/** A fixed probe, used by tests and by "what would be available" previews. */
class FixedToolProbe(private val available: Set<String>) : ToolProbe {
    override fun which(name: String): String? = if (name in available) "/usr/bin/$name" else null
}

data class LanguageDetection(
    val spec: LanguageSpec?,
    /** 1.0 = unambiguous, lower when multiple candidates share the signal. */
    val confidence: Double,
    val reason: String,
    val candidates: List<LanguageSpec> = emptyList(),
) {
    val ambiguous: Boolean get() = candidates.size > 1
}

/**
 * Universal, extensible language registry.
 *
 * Recognition uses, in priority order: exact filename, extension, shebang, then
 * project configuration files. When a signal is ambiguous (the same extension or
 * shebang maps to several languages) the result is flagged as [LanguageDetection.ambiguous]
 * instead of being silently pinned to one language, matching the requirement not
 * to decide a language from a single extension alone.
 */
object LanguageRegistry {

    val all: List<LanguageSpec> = listOf(
        // ---- Android / JVM ----
        spec("kotlin", "Kotlin", exts = "kt kts", config = "build.gradle.kts settings.gradle.kts", compiler = "kotlinc", formatter = "ktlint", linter = "ktlint", test = "gradle", build = listOf("gradle", "build"), testCmd = listOf("gradle", "test"), lsp = listOf("kotlin-language-server")),
        spec("java", "Java", exts = "java", config = "pom.xml build.gradle", compiler = "javac", formatter = "google-java-format", linter = "checkstyle", test = "gradle", build = listOf("gradle", "build"), testCmd = listOf("gradle", "test"), lsp = listOf("jdtls")),
        spec("groovy", "Groovy", exts = "groovy gvy gy gsh", filenames = "Jenkinsfile", compiler = "groovyc", linter = "codenarc", lsp = listOf("groovy-language-server")),
        spec("gradle", "Gradle", exts = "gradle", config = "build.gradle settings.gradle", build = listOf("gradle", "build")),
        spec("scala", "Scala", exts = "scala sc", compiler = "scalac", build = listOf("sbt", "compile"), testCmd = listOf("sbt", "test"), lsp = listOf("metals")),

        // ---- native / systems ----
        spec("c", "C", exts = "c h", compiler = "clang", formatter = "clang-format", linter = "clang-tidy", lsp = listOf("clangd")),
        spec("cpp", "C++", exts = "cpp cxx cc hpp hh hxx h++", compiler = "clang++", formatter = "clang-format", linter = "clang-tidy", lsp = listOf("clangd")),
        spec("rust", "Rust", exts = "rs", config = "Cargo.toml", compiler = "rustc", formatter = "rustfmt", linter = "clippy", test = "cargo", build = listOf("cargo", "build"), testCmd = listOf("cargo", "test"), lsp = listOf("rust-analyzer")),
        spec("assembly", "Assembly", exts = "s S asm", compiler = "gcc", lsp = listOf("asm-lsp")),
        spec("objective-c", "Objective-C", exts = "m mm", compiler = "clang", formatter = "clang-format", lsp = listOf("clangd")),
        spec("swift", "Swift", exts = "swift", config = "Package.swift", compiler = "swiftc", formatter = "swift-format", build = listOf("swift", "build"), testCmd = listOf("swift", "test"), lsp = listOf("sourcekit-lsp")),
        spec("csharp", "C#", exts = "cs", config = "*.csproj *.sln", compiler = "csc", formatter = "dotnet-format", build = listOf("dotnet", "build"), testCmd = listOf("dotnet", "test"), lsp = listOf("omnisharp")),

        // ---- web ----
        spec("javascript", "JavaScript", exts = "js mjs cjs jsx", shebangs = "node", compiler = "node", formatter = "prettier", linter = "eslint", test = "jest", lsp = listOf("typescript-language-server", "vscode-eslint-language-server")),
        spec("typescript", "TypeScript", exts = "ts tsx mts cts", compiler = "tsc", formatter = "prettier", linter = "eslint", test = "jest", build = listOf("tsc"), lsp = listOf("typescript-language-server")),
        spec("html", "HTML", exts = "html htm xhtml", formatter = "prettier", lsp = listOf("vscode-html-language-server")),
        spec("css", "CSS", exts = "css", formatter = "prettier", linter = "stylelint", lsp = listOf("vscode-css-language-server")),
        spec("scss", "SCSS", exts = "scss sass", formatter = "prettier", linter = "stylelint", lsp = listOf("vscode-css-language-server")),
        spec("vue", "Vue", exts = "vue", formatter = "prettier", lsp = listOf("vue-language-server")),
        spec("json", "JSON", exts = "json jsonc", formatter = "prettier", lsp = listOf("vscode-json-language-server")),

        // ---- scripting ----
        spec("python", "Python", exts = "py pyw pyi", shebangs = "python python3 python2", config = "pyproject.toml setup.py requirements.txt", compiler = "python3", formatter = "black", linter = "ruff", test = "pytest", testCmd = listOf("pytest"), runnable = true, lsp = listOf("pyright", "pylsp")),
        spec("bash", "Bash", exts = "sh bash zsh ksh", shebangs = "sh bash zsh ksh dash", compiler = "bash", formatter = "shfmt", linter = "shellcheck", test = "bats", runnable = true, lsp = listOf("bash-language-server")),
        spec("powershell", "PowerShell", exts = "ps1 psm1 psd1", shebangs = "pwsh powershell", linter = "PSScriptAnalyzer", lsp = listOf("powershell-editor-services")),
        spec("lua", "Lua", exts = "lua", shebangs = "lua luajit", compiler = "luac", formatter = "stylua", linter = "luacheck", test = "busted", runnable = true, lsp = listOf("lua-language-server")),
        spec("ruby", "Ruby", exts = "rb rake gemspec", filenames = "Gemfile Rakefile Vagrantfile Podfile", shebangs = "ruby", config = "Gemfile", compiler = "ruby", formatter = "rubocop", linter = "rubocop", test = "rspec", runnable = true, lsp = listOf("solargraph")),
        spec("php", "PHP", exts = "php phtml", shebangs = "php", config = "composer.json", compiler = "php", formatter = "php-cs-fixer", linter = "phpstan", test = "phpunit", runnable = true, lsp = listOf("intelephense")),
        spec("perl", "Perl", exts = "pl pm t", shebangs = "perl", compiler = "perl", linter = "perlcritic", test = "prove", runnable = true, lsp = listOf("perlnavigator")),

        // ---- application / server ----
        spec("go", "Go", exts = "go", config = "go.mod", compiler = "go", formatter = "gofmt", linter = "go vet", test = "go", build = listOf("go", "build"), testCmd = listOf("go", "test"), lsp = listOf("gopls")),
        spec("dart", "Dart", exts = "dart", config = "pubspec.yaml", compiler = "dart", formatter = "dart", test = "dart", build = listOf("dart", "compile"), testCmd = listOf("dart", "test"), lsp = listOf("dart")),
        spec("sql", "SQL", exts = "sql", formatter = "sqlformat", linter = "sqlfluff", lsp = listOf("sqls")),
        spec("r", "R", exts = "r R rmd", shebangs = "Rscript", compiler = "Rscript", formatter = "styler", linter = "lintr", test = "testthat", lsp = listOf("r-languageserver")),
        spec("julia", "Julia", exts = "jl", shebangs = "julia", compiler = "julia", formatter = "JuliaFormatter", test = "julia", runnable = true, lsp = listOf("julia-language-server")),
        spec("elixir", "Elixir", exts = "ex exs", config = "mix.exs", compiler = "elixirc", formatter = "mix", test = "mix", build = listOf("mix", "compile"), testCmd = listOf("mix", "test"), lsp = listOf("elixir-ls")),
        spec("erlang", "Erlang", exts = "erl hrl", compiler = "erlc", formatter = "erlfmt", test = "rebar3", lsp = listOf("erlang_ls")),
        spec("haskell", "Haskell", exts = "hs lhs", compiler = "ghc", formatter = "fourmolu", linter = "hlint", build = listOf("cabal", "build"), testCmd = listOf("cabal", "test"), lsp = listOf("haskell-language-server-wrapper")),
        spec("ocaml", "OCaml", exts = "ml mli", config = "dune-project", compiler = "ocamlc", formatter = "ocamlformat", build = listOf("dune", "build"), lsp = listOf("ocamllsp")),
        spec("fsharp", "F#", exts = "fs fsi fsx", config = "*.fsproj", compiler = "fsc", build = listOf("dotnet", "build"), lsp = listOf("fsautocomplete")),
        spec("visualbasic", "Visual Basic", exts = "vb", config = "*.vbproj", compiler = "vbc", build = listOf("dotnet", "build"), lsp = listOf("omnisharp")),

        // ---- config / markup / other ----
        spec("yaml", "YAML", exts = "yml yaml", linter = "yamllint", lsp = listOf("yaml-language-server")),
        spec("xml", "XML", exts = "xml xsd xsl xslt", linter = "xmllint", lsp = listOf("lemminx")),
        spec("toml", "TOML", exts = "toml", lsp = listOf("taplo")),
        spec("markdown", "Markdown", exts = "md markdown mdx", formatter = "prettier", linter = "markdownlint", lsp = listOf("marksman")),
        spec("dockerfile", "Dockerfile", filenames = "Dockerfile", exts = "dockerfile", linter = "hadolint", lsp = listOf("dockerfile-language-server-nodejs")),
        spec("makefile", "Makefile", filenames = "Makefile makefile GNUmakefile", exts = "mk", build = listOf("make")),
        spec("cmake", "CMake", filenames = "CMakeLists.txt", exts = "cmake", formatter = "cmake-format", build = listOf("cmake"), lsp = listOf("cmake-language-server")),
        spec("protobuf", "Protocol Buffers", exts = "proto", compiler = "protoc", formatter = "clang-format", lsp = listOf("protols")),
        spec("solidity", "Solidity", exts = "sol", compiler = "solc", formatter = "forge", lsp = listOf("solidity-language-server")),
        spec("fortran", "Fortran", exts = "f f90 f95 f03 f08 for", compiler = "gfortran", linter = "fortls", lsp = listOf("fortls")),
        spec("cobol", "COBOL", exts = "cob cbl cpy", compiler = "cobc"),
        spec("verilog", "Verilog", exts = "v vh sv svh", compiler = "iverilog", formatter = "verible-verilog-format", lsp = listOf("verible-verilog-ls")),
        spec("vhdl", "VHDL", exts = "vhd vhdl", compiler = "ghdl", lsp = listOf("vhdl-ls")),
        spec("matlab", "MATLAB", exts = "m mat", compiler = "octave", lsp = listOf("matlab-language-server")),
    )

    private val byId: Map<String, LanguageSpec> = all.associateBy { it.id }
    private val byExtension: Map<String, List<LanguageSpec>> = buildMap {
        for (spec in all) for (ext in spec.extensions) {
            put(ext.lowercase(), (get(ext.lowercase()) ?: emptyList()) + spec)
        }
    }
    private val byFilename: Map<String, LanguageSpec> = buildMap {
        for (spec in all) for (name in spec.filenames) putIfAbsent(name.lowercase(), spec)
    }
    private val byConfigFile: Map<String, List<LanguageSpec>> = buildMap {
        for (spec in all) for (name in spec.configFiles) {
            put(name.lowercase(), (get(name.lowercase()) ?: emptyList()) + spec)
        }
    }

    fun byId(id: String): LanguageSpec? = byId[id]

    fun byExtension(ext: String): List<LanguageSpec> = byExtension[ext.removePrefix(".").lowercase()] ?: emptyList()

    fun byFilename(name: String): LanguageSpec? = byFilename[name.lowercase()]

    /** Languages whose config files are present in [projectFiles] (names, not paths). */
    fun byProjectFiles(projectFiles: Collection<String>): List<LanguageSpec> {
        val out = LinkedHashSet<LanguageSpec>()
        for (name in projectFiles) {
            byConfigFile[name.lowercase()]?.let { out.addAll(it) }
        }
        return out.toList()
    }

    private fun shebangLanguage(firstLine: String?): LanguageSpec? {
        val line = firstLine?.trim() ?: return null
        if (!line.startsWith("#!")) return null
        var rest = line.removePrefix("#!").trim().substringAfterLast('/')
        if (rest.startsWith("env ")) rest = rest.removePrefix("env ").trim()
        val name = rest.substringBefore(' ').trim().lowercase()
        if (name.isEmpty()) return null
        for (spec in all) {
            for (token in spec.shebangs) {
                val t = token.lowercase()
                if (name == t) return spec
                if (t.length >= 3 && name.startsWith(t)) return spec
            }
        }
        return null
    }

    /**
     * Detects the language of a file. [firstLine] (a shebang) and [projectConfigFiles]
     * (e.g. `Cargo.toml`, `build.gradle.kts`) refine the guess; ambiguity is surfaced
     * rather than hidden.
     */
    fun detect(
        fileName: String,
        firstLine: String? = null,
        projectConfigFiles: Collection<String> = emptyList(),
    ): LanguageDetection {
        byFilename(fileName)?.let { return LanguageDetection(it, 1.0, "filename") }

        val extension = fileName.substringAfterLast('.', "")
        val extMatches = if (extension.isNotEmpty()) byExtension(extension) else emptyList()

        if (extMatches.size == 1) return LanguageDetection(extMatches[0], 1.0, "extension .$extension")

        if (extMatches.size > 1) {
            val projectMatches = byProjectFiles(projectConfigFiles)
            val narrowed = extMatches.filter { it in projectMatches }
            if (narrowed.size == 1) {
                return LanguageDetection(narrowed[0], 0.9, "extension .$extension refined by project files")
            }
            val candidates = if (narrowed.isNotEmpty()) narrowed else extMatches
            return LanguageDetection(candidates.first(), 0.4, "ambiguous extension .$extension", candidates)
        }

        shebangLanguage(firstLine)?.let { return LanguageDetection(it, 0.8, "shebang") }

        return LanguageDetection(null, 0.0, "unknown extension")
    }

    /**
     * Resolves which tools for [spec] actually exist in the environment. A declared
     * tool that is not present is reported unavailable *with a reason* rather than
     * being treated as working.
     */
    fun capabilities(spec: LanguageSpec, probe: ToolProbe = PathToolProbe): LanguageCapabilities {
        fun probeTool(kind: LanguageToolKind, tool: String?): ToolAvailability {
            if (tool.isNullOrBlank()) {
                return ToolAvailability(kind, null, false, null, "no ${kind.name.lowercase()} tool declared")
            }
            val path = probe.which(tool)
            return if (path != null) ToolAvailability(kind, tool, true, path, null)
            else ToolAvailability(kind, tool, false, null, "not found on PATH")
        }
        return LanguageCapabilities(
            language = spec,
            compile = probeTool(LanguageToolKind.COMPILER, spec.compiler),
            format = probeTool(LanguageToolKind.FORMATTER, spec.formatter),
            lint = probeTool(LanguageToolKind.LINTER, spec.linter),
            test = probeTool(LanguageToolKind.TEST, spec.testRunner),
            lsp = spec.lspServers.map { probeTool(LanguageToolKind.LSP, it) },
        )
    }

    private fun spec(
        id: String,
        displayName: String,
        exts: String = "",
        filenames: String = "",
        shebangs: String = "",
        config: String = "",
        formatter: String? = null,
        compiler: String? = null,
        linter: String? = null,
        test: String? = null,
        testCmd: List<String> = emptyList(),
        build: List<String> = emptyList(),
        lsp: List<String> = emptyList(),
        runnable: Boolean = false,
    ): LanguageSpec = LanguageSpec(
        id = id,
        displayName = displayName,
        extensions = exts.split(' ').filter { it.isNotBlank() },
        filenames = filenames.split(' ').filter { it.isNotBlank() },
        shebangs = shebangs.split(' ').filter { it.isNotBlank() },
        configFiles = config.split(' ').filter { it.isNotBlank() },
        formatter = formatter,
        compiler = compiler,
        linter = linter,
        testRunner = test,
        testCommand = testCmd,
        buildCommand = build,
        lspServers = lsp,
        runnable = runnable,
    )
}
