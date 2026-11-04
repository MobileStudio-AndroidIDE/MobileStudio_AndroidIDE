package com.aide.mobilestudio.ai.model

/**
 * Discovery shortcuts for the GGUF downloader.
 *
 * These are search queries, not hard-coded model URLs. Hugging Face still
 * returns the actual repositories and files, so a shortcut can never point at
 * a file that does not exist; an empty query means "popular GGUF" (the API
 * sorts by downloads).
 */
data class GgufSuggestion(
    val id: String,
    val labelEn: String,
    val labelKo: String,
    val query: String,
    val popular: Boolean = false,
)

object GgufCatalog {

    val suggestions: List<GgufSuggestion> = listOf(
        GgufSuggestion("popular", "Popular", "인기", "", popular = true),
        GgufSuggestion("qwen", "Qwen", "Qwen", "Qwen"),
        GgufSuggestion("llama", "Llama", "Llama", "Llama"),
        GgufSuggestion("gemma", "Gemma", "Gemma", "Gemma"),
        GgufSuggestion("phi", "Phi", "Phi", "Phi"),
        GgufSuggestion("mistral", "Mistral", "Mistral", "Mistral"),
        GgufSuggestion("deepseek", "DeepSeek", "DeepSeek", "DeepSeek"),
        GgufSuggestion("glm", "GLM", "GLM", "GLM"),
        GgufSuggestion("kimi", "Kimi", "Kimi", "Kimi"),
        GgufSuggestion("minimax", "MiniMax", "MiniMax", "MiniMax"),
        GgufSuggestion("yi", "Yi", "Yi", "Yi"),
        GgufSuggestion("ernie", "ERNIE", "ERNIE", "ERNIE"),
        GgufSuggestion("command-r", "Command R", "Command R", "Command-R"),
        GgufSuggestion("code", "Code", "코드", "code"),
        GgufSuggestion("korean", "Korean", "한국어", "korean"),
        GgufSuggestion("small", "Small", "소형", "3B"),
    )
}
