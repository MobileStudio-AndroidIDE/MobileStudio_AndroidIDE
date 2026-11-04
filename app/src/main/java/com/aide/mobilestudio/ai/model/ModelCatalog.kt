package com.aide.mobilestudio.ai.model

import android.content.Context
import com.aide.mobilestudio.ai.AiSettings
import com.aide.mobilestudio.ai.agent.ModelManager
import com.aide.mobilestudio.ai.opencode.config.OpencodeConfig
import com.aide.mobilestudio.ai.opencode.provider.OcProviderRegistry
import com.aide.mobilestudio.ai.provider.AuthStyle
import org.json.JSONArray
import java.io.File

/**
 * The single place the model picker talks to.
 *
 * Three real sources, never mixed up:
 *  - API models the provider registry knows about (configured, with keys),
 *  - local GGUF files already installed (from [ModelManager] through the registry),
 *  - download candidates the Hugging Face API reported (live, only what exists).
 *
 * Live API model lists (`GET /models`) are cached in ordinary preferences — a
 * cache of public model ids is not a secret, and losing it must never lose the
 * user's actual configuration. When a refresh fails, the cached list is shown
 * and marked as cached.
 */
class ModelCatalog(
    private val context: Context,
    private val modelManager: ModelManager,
    /** The project directory whose opencode.json may add providers. */
    private val worktreeProvider: () -> File?,
    private val hfSource: HuggingFaceSource,
    private val apiFetcher: ApiModelFetcher,
) {

    private val cachePrefs = context.getSharedPreferences("ai_model_cache", Context.MODE_PRIVATE)

    /** Where downloaded GGUFs live: `filesDir/AI/models`. */
    val modelDir: File = File(context.filesDir, "AI/models").apply { mkdirs() }

    private fun config(): OpencodeConfig = OpencodeConfig.load(context, worktreeProvider())

    private fun registry(): OcProviderRegistry =
        OcProviderRegistry(context, { config() }, modelManager)

    /** Everything already known locally — no network, safe on the main thread. */
    fun knownEntries(): List<ModelEntry> {
        val out = ArrayList<ModelEntry>()
        val reg = registry()
        for ((id, label) in reg.listProviders()) {
            val models = try {
                reg.listModels(id)
            } catch (e: Exception) {
                emptyList()
            }
            for (info in models) {
                out.add(
                    ModelEntry.fromRegistryModel(
                        ref = info.ref.toString(),
                        providerId = id,
                        providerLabel = label,
                        name = info.name,
                        local = info.local,
                        ggufPath = info.ggufPath,
                        contextTokens = info.contextLimit,
                        maxContext = info.maxContext,
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ live list

    sealed class LiveResult {
        data class Ok(val models: List<String>, val fetchedAtMs: Long, val cached: Boolean) : LiveResult()
        data class Failure(val message: String) : LiveResult()
    }

    /**
     * `GET {baseUrl}/models` for a provider that supports it. Falls back to the
     * cached list when the request fails; only reports failure when there is
     * nothing to show at all.
     */
    fun liveModels(providerId: String): LiveResult {
        val baseUrl: String
        val apiKey: String?
        when (providerId) {
            "openai" -> {
                val settings = AiSettings(context)
                baseUrl = settings.openaiBaseUrl
                apiKey = settings.openaiApiKey
            }
            else -> {
                val options = config().providers[providerId]?.optJSONObject("options")
                    ?: return failureOrCache(providerId, "이 제공자는 모델 목록 조회를 지원하지 않습니다")
                baseUrl = options.optString("baseURL", "")
                apiKey = options.optString("apiKey").takeIf { it.isNotBlank() }
            }
        }
        if (baseUrl.isBlank()) return failureOrCache(providerId, "Base URL이 설정되지 않았습니다")
        return try {
            val models = apiFetcher.listModels(baseUrl, apiKey, AuthStyle.BEARER)
            if (models.isEmpty()) {
                failureOrCache(providerId, "서버가 모델 목록을 반환하지 않았습니다")
            } else {
                saveCache(providerId, models)
                LiveResult.Ok(models, System.currentTimeMillis(), cached = false)
            }
        } catch (e: Exception) {
            failureOrCache(providerId, e.message ?: "모델 목록 조회 실패")
        }
    }

    private fun failureOrCache(providerId: String, message: String): LiveResult {
        val cached = loadCache(providerId) ?: return LiveResult.Failure(message)
        return LiveResult.Ok(cached.first, cached.second, cached = true)
    }

    private fun saveCache(providerId: String, models: List<String>) {
        cachePrefs.edit()
            .putString("models:$providerId", JSONArray(models).toString())
            .putLong("at:$providerId", System.currentTimeMillis())
            .apply()
    }

    private fun loadCache(providerId: String): Pair<List<String>, Long>? {
        val raw = cachePrefs.getString("models:$providerId", null) ?: return null
        val at = cachePrefs.getLong("at:$providerId", 0L)
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val list = (0 until array.length()).mapNotNull {
            array.optString(it).takeIf { s -> s.isNotBlank() }
        }
        return if (list.isEmpty()) null else list to at
    }

    // ------------------------------------------------------- download search

    sealed class SearchResult {
        data class Ok(val entries: List<ModelEntry>) : SearchResult()
        data class Failure(val message: String) : SearchResult()
    }

    /**
     * Live Hugging Face search, expanded per repository. Blocking; call off the
     * main thread. Failures are reported, not shown as an empty catalogue.
     */
    fun searchDownloadable(query: String, limit: Int = 20, maxRepos: Int = 12): SearchResult {
        if (query.isBlank()) return SearchResult.Failure("검색어를 입력하세요")
        return try {
            SearchResult.Ok(hfSource.searchEntries(query, limit, maxRepos))
        } catch (e: Exception) {
            SearchResult.Failure(e.message ?: "검색에 실패했습니다")
        }
    }

    /**
     * The most-downloaded GGUF repositories, without a search term.
     *
     * Hugging Face returns the repositories, and the tree endpoint supplies
     * the real files. This is the browse path for users who do not know a
     * model name yet. Repositories that declare more than [maxParams] are
     * skipped so the first screen stays practical for a phone.
     */
    fun popularDownloadable(
        limit: Int = 30,
        maxRepos: Int = 10,
        maxParams: Long = 14_000_000_000L,
    ): SearchResult = try {
        SearchResult.Ok(hfSource.searchEntries("", limit, maxRepos, maxParams))
    } catch (e: Exception) {
        SearchResult.Failure(e.message ?: "인기 모델을 불러오지 못했습니다")
    }

    /**
     * Every standalone GGUF file in one repository, for a pasted `owner/repo`
     * or Hugging Face model URL.
     */
    fun downloadableFromRepo(rawRepo: String): SearchResult {
        val repoId = HuggingFaceApi.parseRepoId(rawRepo)
            ?: return SearchResult.Failure("owner/repo 형식의 Hugging Face 주소를 입력하세요")
        return try {
            val entries = hfSource.entriesForRepo(repoId)
            if (entries.isEmpty()) {
                SearchResult.Failure(
                    "이 리포지토리에는 내려받을 수 있는 GGUF 모델이 없습니다. " +
                        "프로젝터·어댑터·보정 파일은 실행 모델이 아니며, 분할 모델은 모든 샤드가 있어야 표시됩니다",
                )
            } else {
                SearchResult.Ok(entries)
            }
        } catch (e: Exception) {
            SearchResult.Failure(e.message ?: "리포지토리 파일 목록을 불러오지 못했습니다")
        }
    }

    /** One repository's real files, for the quantisation step. */
    fun ggufFiles(repoId: String): List<HfGgufFile> = hfSource.ggufFiles(repoId)

    /** Turns a download candidate into a spec, or null when it is not safe. */
    fun downloadSpec(entry: ModelEntry): DownloadSpec? {
        val parts = if (entry.parts.isNotEmpty()) {
            entry.parts.map { part ->
                if (!HuggingFaceApi.isAllowedDownloadUrl(part.url)) return null
                if (!HuggingFaceApi.isSafeFileName(part.fileName)) return null
                if (part.sizeBytes <= 0) return null
                DownloadPart(
                    url = part.url,
                    destination = File(modelDir, part.fileName),
                    expectedBytes = part.sizeBytes,
                    expectedSha256 = part.sha256,
                )
            }
        } else {
            val url = entry.downloadUrl ?: return null
            val name = entry.fileName ?: return null
            if (!HuggingFaceApi.isAllowedDownloadUrl(url)) return null
            if (!HuggingFaceApi.isSafeFileName(name)) return null
            listOf(
                DownloadPart(
                    url = url,
                    destination = File(modelDir, name),
                    expectedBytes = entry.fileSizeBytes ?: -1L,
                    expectedSha256 = entry.sha256,
                ),
            )
        }
        if (parts.isEmpty()) return null
        if (parts.map { it.destination.absolutePath }.distinct().size != parts.size) return null
        val first = parts.first()
        return DownloadSpec(
            id = entry.uid,
            url = first.url,
            destination = first.destination,
            expectedBytes = entry.fileSizeBytes ?: parts.sumOf { it.expectedBytes },
            expectedSha256 = if (parts.size == 1) first.expectedSha256 else null,
            expectedQuantFamily = entry.quantFamily,
            displayName = entry.displayName.ifBlank { first.destination.name },
            parts = if (parts.size > 1) parts else emptyList(),
        )
    }

    /** Registers a verified download with the model manager. */
    fun registerCompleted(task: DownloadTask): Boolean {
        val file = task.finishedFile ?: return false
        if (!file.isFile) return false
        val name = task.spec.displayName.ifBlank { file.name }
        return try {
            modelManager.addModel(name, file.absolutePath)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes an installed model file and forgets it. Returns the failure text. */
    fun deleteInstalled(entry: ModelEntry): String? {
        val path = entry.localPath ?: return "파일 경로를 알 수 없습니다"
        val file = File(path)
        if (!file.isFile) {
            entry.modelRef?.substringAfter('/')?.let { modelManager.removeModel(it) }
            return null
        }
        return try {
            // A split model is one entry but several files. Delete the whole
            // shard set, otherwise the remaining shards look like a broken
            // model in the folder scan forever.
            val group = HuggingFaceApi.splitGroupKey(file.name)
            val siblings = if (group != null) {
                file.parentFile?.listFiles()?.filter {
                    HuggingFaceApi.splitGroupKey(it.name) == group
                }.orEmpty()
            } else {
                listOf(file)
            }
            if (!file.delete()) return "파일을 삭제하지 못했습니다"
            for (sibling in siblings) runCatching { sibling.delete() }
            val id = file.nameWithoutExtension
            modelManager.removeModel(id)
            null
        } catch (e: Exception) {
            e.message ?: "삭제 실패"
        }
    }
}
