package com.aide.mobilestudio.ai.model

import java.io.File

/** Whether a model runs behind an HTTP API or on the device. */
enum class ModelRuntime { API, LOCAL }

/** Where the entry came from. Never guessed: each source sets it explicitly. */
enum class ModelSource {
    /** Configured in the providers JSON / settings. */
    API_CONFIG,

    /** A GGUF file that is already on this device. */
    LOCAL_INSTALLED,

    /** A repository file the Hugging Face API reported; not downloaded yet. */
    HF_REMOTE,
}

/** Tabs of the unified model picker. */
enum class ModelTab(val label: String) {
    ALL("전체"),
    API("API"),
    LOCAL("로컬"),
    DOWNLOAD("다운로드"),
    DOWNLOADING("다운로드 중"),
}

enum class ModelSort(val label: String) {
    NAME("이름순"),
    SIZE("크기순"),
    DOWNLOADS("인기순"),
}

/**
 * One real file of a remote GGUF. Split models use several of these, and all
 * of them must land in the model directory before llama.cpp can load the
 * model.
 */
data class RemoteModelPart(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/**
 * One row of the unified model list. API models and local GGUF files are the
 * same shape here so switching between them is one code path.
 */
data class ModelEntry(
    /** Stable identity, e.g. `api:openai/gpt-4o`, `local:qwen-3b`, `hf:repo:file`. */
    val uid: String,
    val runtime: ModelRuntime,
    val source: ModelSource,
    val displayName: String,
    /** "provider/modelId" for API and local models, as OcModelRef expects. */
    val modelRef: String?,
    val providerId: String? = null,
    val providerLabel: String? = null,
    val params: Long? = null,
    val paramsSource: ParamsSource = ParamsSource.UNKNOWN,
    val fileSizeBytes: Long? = null,
    val quant: String? = null,
    val quantFamily: QuantFamily? = null,
    val installed: Boolean = false,
    val availableForDownload: Boolean = false,
    /** Direct file URL, only ever an allowed Hugging Face host. */
    val downloadUrl: String? = null,
    /** SHA-256 when the server reported one (LFS oid). */
    val sha256: String? = null,
    /** Every shard of a split GGUF, in order. Empty for a single-file model. */
    val parts: List<RemoteModelPart> = emptyList(),
    val license: String? = null,
    val repoId: String? = null,
    val fileName: String? = null,
    val localPath: String? = null,
    /** Real, displayable facts (context window, install state). Nothing implied. */
    val contextTokens: Int? = null,
    val maxContext: Int? = null,
    val note: String? = null,
) {
    val paramBand: ParamBand? get() = ParamBand.of(params)

    /** "≈7B" when inferred, "7B" when declared, "" when unknown. */
    val paramsLabel: String
        get() {
            val p = params ?: return ""
            val b = p / 1_000_000_000.0
            val text = if (b < 1.0) "${(b * 1000).toInt()}M" else {
                val rounded = Math.round(b * 10) / 10.0
                if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}B" else "${rounded}B"
            }
            return if (paramsSource == ParamsSource.NAME_INFERRED) "≈$text" else text
        }

    companion object {
        /** An API (or already configured local) model as the registry reports it. */
        fun fromRegistryModel(
            ref: String,
            providerId: String,
            providerLabel: String,
            name: String,
            local: Boolean,
            ggufPath: String?,
            contextTokens: Int?,
            maxContext: Int?,
        ): ModelEntry {
            val file = ggufPath?.let { File(it) }
            val installed = file?.isFile == true
            val params = Quant.paramsFromName(ref.substringAfter('/'))
            val size = if (local && installed) file!!.length() else null
            return ModelEntry(
                uid = (if (local) "local:" else "api:") + ref,
                runtime = if (local) ModelRuntime.LOCAL else ModelRuntime.API,
                source = if (local) ModelSource.LOCAL_INSTALLED else ModelSource.API_CONFIG,
                displayName = name,
                modelRef = ref,
                providerId = providerId,
                providerLabel = providerLabel,
                params = params,
                paramsSource = if (params != null) ParamsSource.NAME_INFERRED else ParamsSource.UNKNOWN,
                fileSizeBytes = size,
                installed = local && installed,
                localPath = ggufPath,
                contextTokens = contextTokens,
                maxContext = maxContext,
                note = when {
                    local && !installed -> "파일 없음"
                    else -> null
                },
            )
        }

        /** A GGUF that is already on disk but perhaps not in the model registry. */
        fun fromLocalFile(file: File, id: String, name: String, quant: String?): ModelEntry {
            val params = Quant.paramsFromName(name)
            return ModelEntry(
                uid = "local:$id",
                runtime = ModelRuntime.LOCAL,
                source = ModelSource.LOCAL_INSTALLED,
                displayName = name,
                modelRef = "local/$id",
                providerId = "local",
                providerLabel = "Local Models (llama.cpp)",
                params = params,
                paramsSource = if (params != null) ParamsSource.NAME_INFERRED else ParamsSource.UNKNOWN,
                fileSizeBytes = runCatching { file.length() }.getOrNull(),
                quant = quant,
                quantFamily = Quant.familyOf(quant) ?: Quant.familyOfFileName(file.name),
                installed = file.isFile,
                localPath = file.absolutePath,
                fileName = file.name,
            )
        }

        /** A downloadable file the Hugging Face tree API reported. */
        fun fromRemoteFile(repoId: String, file: HfGgufFile, repo: HfRepo?): ModelEntry {
            val params = repo?.params ?: Quant.paramsFromName(repoId.substringAfter('/'))
            val source = repo?.paramsSource
                ?: if (params != null) ParamsSource.NAME_INFERRED else ParamsSource.UNKNOWN
            return ModelEntry(
                uid = "hf:$repoId/${file.path}",
                runtime = ModelRuntime.LOCAL,
                source = ModelSource.HF_REMOTE,
                displayName = if (file.path.contains('/')) file.path else file.fileName,
                modelRef = null,
                providerId = "huggingface",
                providerLabel = "Hugging Face",
                params = params,
                paramsSource = source,
                fileSizeBytes = file.sizeBytes.takeIf { it > 0 },
                quant = file.quant,
                quantFamily = file.quantFamily,
                installed = false,
                availableForDownload = true,
                downloadUrl = HuggingFaceApi.resolveUrl(repoId, file.path),
                sha256 = file.sha256,
                license = repo?.license,
                repoId = repoId,
                fileName = file.storageName,
            )
        }

        /**
         * A split GGUF: one model made of [files] shards that must be
         * downloaded and verified together. A single shard is not loadable on
         * its own, so it is never offered as a separate row.
         */
        fun fromRemoteModel(repoId: String, files: List<HfGgufFile>, repo: HfRepo?): ModelEntry {
            require(files.isNotEmpty()) { "a remote model needs at least one file" }
            if (files.size == 1) return fromRemoteFile(repoId, files.single(), repo)
            val ordered = files.sortedBy { HuggingFaceApi.splitIndex(it.path) ?: 0 }
            val first = ordered.first()
            val total = ordered.sumOf { it.sizeBytes }
            val params = repo?.params ?: Quant.paramsFromName(repoId.substringAfter('/'))
            val source = repo?.paramsSource
                ?: if (params != null) ParamsSource.NAME_INFERRED else ParamsSource.UNKNOWN
            val parts = ordered.map { file ->
                RemoteModelPart(
                    url = HuggingFaceApi.resolveUrl(repoId, file.path),
                    fileName = file.storageName,
                    sizeBytes = file.sizeBytes,
                    sha256 = file.sha256,
                )
            }
            val name = buildString {
                append(repoId.substringAfter('/'))
                first.quant?.let { append(" · ").append(it) }
                append(" · ").append(ordered.size).append(" parts")
            }
            return ModelEntry(
                uid = "hf:$repoId/model:${HuggingFaceApi.splitBase(first.path) ?: first.path}",
                runtime = ModelRuntime.LOCAL,
                source = ModelSource.HF_REMOTE,
                displayName = name,
                modelRef = null,
                providerId = "huggingface",
                providerLabel = "Hugging Face",
                params = params,
                paramsSource = source,
                fileSizeBytes = total.takeIf { it > 0 },
                quant = first.quant,
                quantFamily = first.quantFamily,
                installed = false,
                availableForDownload = true,
                downloadUrl = parts.first().url,
                sha256 = null,
                parts = parts,
                license = repo?.license,
                repoId = repoId,
                fileName = parts.first().fileName,
            )
        }
    }
}

/** Everything the picker lets the user narrow down by. All fields are real filters. */
data class ModelFilter(
    val tab: ModelTab = ModelTab.ALL,
    val query: String = "",
    val providerId: String? = null,
    val quantFamilies: Set<QuantFamily> = emptySet(),
    val paramBands: Set<ParamBand> = emptySet(),
    val installedOnly: Boolean = false,
    val downloadableOnly: Boolean = false,
)

/** Pure search/filter/sort over [ModelEntry] lists. Unit-testable, no Android. */
object ModelQuery {

    fun matches(entry: ModelEntry, filter: ModelFilter, downloading: Set<String> = emptySet()): Boolean {
        if (!matchesTab(entry, filter.tab, downloading)) return false
        if (!matchesQuery(entry, filter.query)) return false
        if (filter.providerId != null && entry.providerId != filter.providerId) return false
        if (filter.quantFamilies.isNotEmpty() && entry.quantFamily !in filter.quantFamilies) return false
        if (filter.paramBands.isNotEmpty() && entry.paramBand !in filter.paramBands) return false
        if (filter.installedOnly && !entry.installed) return false
        if (filter.downloadableOnly && !entry.availableForDownload) return false
        return true
    }

    private fun matchesTab(entry: ModelEntry, tab: ModelTab, downloading: Set<String>): Boolean = when (tab) {
        ModelTab.ALL -> true
        ModelTab.API -> entry.runtime == ModelRuntime.API
        ModelTab.LOCAL -> entry.runtime == ModelRuntime.LOCAL && entry.source != ModelSource.HF_REMOTE
        ModelTab.DOWNLOAD -> entry.source == ModelSource.HF_REMOTE
        ModelTab.DOWNLOADING -> entry.uid in downloading
    }

    /**
     * Query matches display name, repository id, provider label and quant
     * label. Case-insensitive; every whitespace-separated token must match.
     */
    fun matchesQuery(entry: ModelEntry, query: String): Boolean {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val haystack = buildString {
            append(entry.displayName).append(' ')
            append(entry.providerLabel ?: "").append(' ')
            append(entry.providerId ?: "").append(' ')
            append(entry.repoId ?: "").append(' ')
            append(entry.quant ?: "").append(' ')
            append(entry.paramsLabel).append(' ')
            append(entry.modelRef ?: "")
        }.lowercase()
        return tokens.all { haystack.contains(it) }
    }

    fun apply(
        entries: List<ModelEntry>,
        filter: ModelFilter = ModelFilter(),
        sort: ModelSort = ModelSort.NAME,
        downloading: Set<String> = emptySet(),
    ): List<ModelEntry> {
        val filtered = entries.filter { matches(it, filter, downloading) }
        return when (sort) {
            ModelSort.NAME -> filtered.sortedBy { it.displayName.lowercase() }
            ModelSort.SIZE -> filtered.sortedBy { it.fileSizeBytes ?: Long.MAX_VALUE }
            ModelSort.DOWNLOADS -> filtered // popularity arrives pre-sorted from the API
        }
    }

    /** Provider (id, label) pairs actually present in the list. */
    fun providers(entries: List<ModelEntry>): List<Pair<String, String>> =
        entries.mapNotNull { e -> e.providerId?.let { it to (e.providerLabel ?: it) } }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

    /** Quant families actually present in the list. */
    fun quantFamilies(entries: List<ModelEntry>): List<QuantFamily> =
        entries.mapNotNull { it.quantFamily }.distinct().sortedBy { it.ordinal }

    /** Blank search results and tab-specific "why" text. Never blames the user. */
    fun emptyReason(filter: ModelFilter): String {
        if (filter.query.isNotBlank()) return "‘${filter.query}’에 해당하는 모델이 없습니다. 검색어를 지우고 다시 시도하세요."
        return when (filter.tab) {
            ModelTab.ALL -> "표시할 모델이 없습니다. API 키를 설정하거나 아래에서 모델을 검색해 다운로드하세요."
            ModelTab.API -> "설정된 API 모델이 없습니다. 설정에서 API 키와 모델 ID를 추가하세요."
            ModelTab.LOCAL -> "설치된 로컬 모델이 없습니다. ‘다운로드’ 탭에서 GGUF 모델을 받으세요."
            ModelTab.DOWNLOAD -> "다운로드할 모델이 없습니다. 위 검색창에 모델 이름을 입력하세요."
            ModelTab.DOWNLOADING -> "진행 중인 다운로드가 없습니다."
        }
    }
}
