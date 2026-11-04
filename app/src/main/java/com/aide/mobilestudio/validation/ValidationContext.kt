package com.aide.mobilestudio.validation

import java.io.File

/** Shared state handed to every check. */
class ValidationContext(
    val projectRoot: File,
    val toolchain: ValidationToolchain,
    val runner: ProcessRunner,
    val cache: ValidationCache?,
    val tempDir: File,
    val cancel: CancellationSignal,
    val model: GradleProjectModel,
    val resDirs: List<File>,
    val onStage: (String) -> Unit
) {
    fun isCancelled(): Boolean = cancel.isCancelled()

    // Compiled res zips shared between the XML resource check and the link check.
    private val compiledResources = mutableMapOf<String, File>()

    fun compiledResourcesOf(resDirPath: String): File? =
        compiledResources[resDirPath]?.takeIf { it.isFile }

    fun putCompiledResource(resDirPath: String, zip: File) {
        compiledResources[resDirPath] = zip
    }

    val allCompiledResources: Map<String, File> get() = compiledResources.toMap()

    fun tempSub(name: String): File {
        val dir = File(tempDir, name)
        runCatching { dir.mkdirs() }
        return dir
    }

    /**
     * Runs [block] unless a cached result for the same inputs exists.
     * The cache key mixes the toolchain signature, project root, group identity
     * and member file fingerprints, so only changed files re-run the tool.
     */
    fun cached(
        checkId: String,
        groupPath: String,
        members: List<File>,
        block: () -> List<InspectionIssue>
    ): List<InspectionIssue> {
        val cache = cache ?: return block()
        val key = cache.computeKey(
            toolchain.toolSignature(),
            projectRoot,
            ValidationCache.GroupKey(checkId, groupPath, members)
        )
        cache.load(key)?.let { return it }
        val issues = block()
        if (!cancel.isCancelled()) cache.store(key, issues)
        return issues
    }

    fun toolIssue(message: String, errorCode: String): InspectionIssue = InspectionIssue(
        severity = InspectionSeverity.WARNING,
        category = InspectionCategory.TOOL,
        file = null,
        line = 1,
        column = 1,
        message = message,
        errorCode = errorCode
    )
}

data class CheckOutcome(
    val issues: List<InspectionIssue> = emptyList(),
    val skipped: Boolean = false
)

abstract class ProjectCheck {
    abstract val id: String
    abstract val displayName: String
    abstract val progressLabel: String
    abstract fun run(ctx: ValidationContext): CheckOutcome
}
