package com.aide.mobilestudio.ai.orchestrator

data class TaskAnalysis(
    val difficulty: TaskDifficulty,
    val requiredCapabilities: Set<ModelCapability>,
    val reasoning: String,
)

/**
 * Deterministic enough to test and explain. This is a baseline, not a claim to
 * understand the task: the model's suggested difficulty can raise it, and the
 * real outcome stats can steer later attempts.
 */
object TaskClassifier {

    private val expertWords = listOf(
        "architecture", "migration", "concurrency", "race condition", "security",
        "performance regression", "compiler", "ndk", "native crash", "deadlock",
        "distributed", "protocol design", "아키텍처", "마이그레이션", "동시성",
        "경쟁 조건", "보안", "성능", "교착", "네이티브", "전체 설계",
    )
    private val hardWords = listOf(
        "gradle", "compile error", "compilation", "refactor", "build error",
        "test failure", "stack trace", "multiple files", "debug", "analy",
        "review", "implement", "fix", "오류", "빌드", "컴파일", "리팩터",
        "테스트", "분석", "수정", "구현", "검증", "실패",
    )
    private val simpleWords = listOf(
        "summarize", "summary", "classify", "rename", "format", "list ",
        "what is", "translate", "count ", "요약", "분류", "이름", "목록",
        "번역", "몇 개", "간단히",
    )

    fun analyze(spec: TaskSpec, availableTools: Set<String> = emptySet()): TaskAnalysis {
        val text = buildString {
            append(spec.description).append('\n').append(spec.prompt)
            if (!spec.inputContext.isNullOrBlank()) append('\n').append(spec.inputContext)
        }
        val lower = text.lowercase()
        val hasError = Regex(
            "(^|\\n)\\s*(e:|error:|fatal:|exception|traceback|caused by:)",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(text)
        val codeFences = Regex("```").findAll(text).count() / 2
        val lineCount = text.count { it == '\n' } + 1

        var difficulty = when {
            expertWords.any(lower::contains) ||
                (hasError && codeFences > 0 && text.length > 2_500) ||
                lineCount > 220 ||
                spec.filesHint.size >= 5 -> TaskDifficulty.EXPERT

            hardWords.any(lower::contains) ||
                hasError ||
                codeFences >= 2 ||
                spec.filesHint.size >= 3 ||
                text.length > 1_500 -> TaskDifficulty.HARD

            simpleWords.any(lower::contains) &&
                codeFences == 0 &&
                spec.filesHint.isEmpty() &&
                text.length <= 600 -> TaskDifficulty.SIMPLE

            else -> TaskDifficulty.NORMAL
        }

        // A model's proposal is input, not authority. It may escalate, never
        // silently downgrade a task that the deterministic baseline sees as
        // hard (that is how "just rename this" hides a migration).
        val suggested = spec.difficulty
        if (suggested != null && suggested.rank > difficulty.rank) difficulty = suggested

        val capabilities = linkedSetOf(ModelCapability.CHAT)
        if (codeFences > 0 || lower.contains("code") || lower.contains("코드") ||
            spec.filesHint.isNotEmpty() || availableTools.isNotEmpty()
        ) {
            capabilities.add(ModelCapability.CODING)
        }
        if (lower.contains("review") || lower.contains("리뷰") || lower.contains("검토")) {
            capabilities.add(ModelCapability.CODING)
            capabilities.add(ModelCapability.REASONING)
        }
        if (lower.contains("summar") || lower.contains("요약")) {
            capabilities.add(ModelCapability.SUMMARIZATION)
        }
        if (difficulty.rank >= TaskDifficulty.HARD.rank ||
            lower.contains("why") || lower.contains("root cause") || lower.contains("원인")
        ) {
            capabilities.add(ModelCapability.REASONING)
        }
        val needsTools = spec.filesHint.isNotEmpty() ||
            lower.contains("build") || lower.contains("gradle") || lower.contains("test") ||
            lower.contains("file") || lower.contains("read") || lower.contains("edit") ||
            lower.contains("write") || lower.contains("run") || lower.contains("검색") ||
            lower.contains("파일") || lower.contains("실행")
        if (needsTools) capabilities.add(ModelCapability.TOOL_CALLING)
        // A delegated coding agent always gets the tool bridge; a model that
        // cannot emit tool calls can only chat and would silently fail to edit
        // or inspect the project. Do not assign it regardless of the wording.
        capabilities.add(ModelCapability.TOOL_CALLING)
        capabilities.addAll(spec.requiredCapabilities)

        val reason = "baseline=$difficulty" +
            (if (suggested != null) ", suggested=${suggested.name}" else "") +
            ", chars=${text.length}, files=${spec.filesHint.size}, errors=$hasError"
        return TaskAnalysis(difficulty, capabilities, reason)
    }
}
