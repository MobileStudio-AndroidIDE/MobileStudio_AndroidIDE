package com.aide.mobilestudio.ai.lsp

import com.aide.mobilestudio.ai.lang.ToolProbe

data class LspServerSpec(
    val id: String,
    val command: String,
    val args: List<String>,
    val languageIds: List<String>,
)

/**
 * Registry of known language servers. Registering one here does not claim it is
 * installed — [availability] probes the environment and reports honestly.
 */
object LspServerRegistry {

    val all: List<LspServerSpec> = listOf(
        LspServerSpec("kotlin-language-server", "kotlin-language-server", emptyList(), listOf("kotlin")),
        LspServerSpec("jdtls", "jdtls", emptyList(), listOf("java")),
        LspServerSpec("clangd", "clangd", emptyList(), listOf("c", "cpp", "objective-c")),
        LspServerSpec("rust-analyzer", "rust-analyzer", emptyList(), listOf("rust")),
        LspServerSpec("gopls", "gopls", listOf("serve"), listOf("go")),
        LspServerSpec("typescript-language-server", "typescript-language-server", listOf("--stdio"), listOf("javascript", "typescript")),
        LspServerSpec("pyright", "pyright-langserver", listOf("--stdio"), listOf("python")),
        LspServerSpec("lua-language-server", "lua-language-server", emptyList(), listOf("lua")),
        LspServerSpec("bash-language-server", "bash-language-server", listOf("start"), listOf("bash")),
        LspServerSpec("solargraph", "solargraph", listOf("stdio"), listOf("ruby")),
        LspServerSpec("intelephense", "intelephense", listOf("--stdio"), listOf("php")),
        LspServerSpec("yaml-language-server", "yaml-language-server", listOf("--stdio"), listOf("yaml")),
        LspServerSpec("lemminx", "lemminx", emptyList(), listOf("xml")),
        LspServerSpec("vscode-json-language-server", "vscode-json-language-server", listOf("--stdio"), listOf("json")),
        LspServerSpec("taplo", "taplo", listOf("lsp", "stdio"), listOf("toml")),
        LspServerSpec("marksman", "marksman", listOf("server"), listOf("markdown")),
        LspServerSpec("dart", "dart", listOf("language-server"), listOf("dart")),
    )

    fun forLanguage(languageId: String): LspServerSpec? =
        all.firstOrNull { languageId in it.languageIds }

    /** Pairs each server with whether its executable exists in the environment. */
    fun availability(probe: ToolProbe): List<Pair<LspServerSpec, Boolean>> =
        all.map { it to (probe.which(it.command) != null) }

    fun isAvailable(spec: LspServerSpec, probe: ToolProbe): Boolean = probe.which(spec.command) != null
}
