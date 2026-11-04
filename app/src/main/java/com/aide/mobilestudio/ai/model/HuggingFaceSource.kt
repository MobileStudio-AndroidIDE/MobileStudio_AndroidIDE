package com.aide.mobilestudio.ai.model

import com.aide.mobilestudio.ai.provider.HttpRequest
import com.aide.mobilestudio.ai.provider.HttpResponse
import com.aide.mobilestudio.ai.provider.HttpTransport

/**
 * The networked half of the Hugging Face catalogue.
 *
 * The transport is injected ([HttpTransport]), so tests drive it with canned
 * JSON instead of the internet. All calls are synchronous and must run off the
 * main thread.
 *
 * Search results are real: a repository appears only if the API returned it,
 * and its quantisation options only if the tree listed the files. Repos whose
 * tree is empty or unreachable are dropped rather than shown as "unavailable".
 */
class HuggingFaceSource(
    private val transport: HttpTransport,
    private val host: String = HuggingFaceApi.DEFAULT_HOST,
    private val timeoutMs: Int = 20_000,
) {

    /** Repositories matching [query], most downloaded first (the API sorts). */
    fun search(query: String, limit: Int = HuggingFaceApi.DEFAULT_SEARCH_LIMIT): List<HfRepo> {
        val url = HuggingFaceApi.searchUrl(query, limit, host)
        val response = transport.send(
            HttpRequest("GET", url, mapOf("Accept" to "application/json"), null, timeoutMs)
        )
        if (response.status !in 200..299) {
            throw HuggingFaceException(
                "Hugging Face 검색 실패 (HTTP ${response.status})" +
                    if (response.status == 429) " — 잠시 후 다시 시도하세요" else ""
            )
        }
        return HuggingFaceApi.parseSearch(response.body)
    }

    /** Real GGUF files of one repository, smallest first. */
    fun ggufFiles(repoId: String): List<HfGgufFile> {
        val url = HuggingFaceApi.treeUrl(repoId, host = host)
        val response = transport.send(
            HttpRequest("GET", url, mapOf("Accept" to "application/json"), null, timeoutMs)
        )
        if (response.status !in 200..299) {
            throw HuggingFaceException("파일 목록 조회 실패 (HTTP ${response.status})")
        }
        return HuggingFaceApi.parseTree(repoId, response.body)
    }

    /**
     * Search plus per-repository file trees, ready for the picker.
     *
     * Only the first [maxRepos] repositories with a parseable tree are
     * expanded, so a single search costs a bounded number of requests. Entries
     * keep the server's sizes, checksums and quant labels untouched.
     * [maxParams] drops declared models above a phone-sized ceiling; a model
     * whose parameter count is unknown is kept.
     */
    fun searchEntries(
        query: String,
        limit: Int = HuggingFaceApi.DEFAULT_SEARCH_LIMIT,
        maxRepos: Int = 8,
        /** Skip repositories whose declared parameter count is larger. */
        maxParams: Long? = null,
    ): List<ModelEntry> {
        val repos = search(query, limit)
        val out = ArrayList<ModelEntry>()
        var expanded = 0
        for (repo in repos) {
            if (expanded >= maxRepos) break
            // A GGUF filter alone also returns TTS, vision and audio models.
            // The local runtime is for text-generation models, so do not spend
            // a tree request (or show a download row) for the others.
            if (!HuggingFaceApi.isTextGenerationRepo(repo)) continue
            if (maxParams != null && repo.params != null && repo.params > maxParams) continue
            val files = try {
                ggufFiles(repo.id)
            } catch (e: Exception) {
                continue
            }
            val entries = groupEntries(repo.id, files, repo)
            if (entries.isNotEmpty()) {
                out.addAll(entries)
                expanded++
            }
        }
        return out
    }

    /**
     * Every real downloadable model in one repository, with split shards
     * grouped into a single entry. Used by the direct `owner/repo` path, which
     * intentionally skips the text-generation search filter: the user named
     * the repository, so its files are what they asked for.
     */
    fun entriesForRepo(repoId: String): List<ModelEntry> =
        groupEntries(repoId, ggufFiles(repoId), repo = null, enforceSizeLimit = false)

    /**
     * Turns a repository's tree into model rows.
     *
     * A complete split set becomes one row; an incomplete set is not offered,
     * because llama.cpp cannot load it. Side files (projectors, calibration
     * matrices, adapters) are filtered out first.
     */
    internal fun groupEntries(
        repoId: String,
        files: List<HfGgufFile>,
        repo: HfRepo?,
        enforceSizeLimit: Boolean = true,
    ): List<ModelEntry> {
        val groups = LinkedHashMap<String, MutableList<HfGgufFile>>()
        for (file in files) {
            if (file.sizeBytes <= 0) continue
            if (enforceSizeLimit && file.sizeBytes > MAX_FILE_BYTES) continue
            if (!HuggingFaceApi.isCandidateGguf(file.path)) continue
            val key = HuggingFaceApi.splitGroupKey(file.path) ?: "single:${file.path}"
            groups.getOrPut(key) { mutableListOf() }.add(file)
        }

        val out = ArrayList<ModelEntry>(groups.size)
        for ((key, group) in groups) {
            if (key.startsWith("single:")) {
                out.add(ModelEntry.fromRemoteFile(repoId, group.first(), repo))
                continue
            }
            val declared = HuggingFaceApi.splitPart(group.first().path) ?: continue
            val indices = group.mapNotNull { HuggingFaceApi.splitPart(it.path)?.index }.toSet()
            // Every shard named by the file name must be present. A partial
            // set is a broken download, not a smaller model.
            if (group.size != declared.count || indices.size != declared.count) continue
            if ((1..declared.count).any { it !in indices }) continue
            val total = group.sumOf { it.sizeBytes }
            // A pasted owner/repo is an explicit choice: list every complete
            // model, even one larger than the phone can comfortably run. The
            // download itself still checks free space before writing.
            if (enforceSizeLimit && total > MAX_MODEL_BYTES) continue
            out.add(ModelEntry.fromRemoteModel(repoId, group, repo))
        }
        return out.sortedBy { it.fileSizeBytes ?: Long.MAX_VALUE }
    }

    companion object {
        /**
         * 20 GB browse cap per file. Search and family shortcuts use it so a
         * casual tap cannot queue an impractical download; an explicit
         * `owner/repo` intentionally bypasses it.
         */
        const val MAX_FILE_BYTES = 20L * 1024 * 1024 * 1024

        /** 20 GB browse cap for a complete split model, for the same reason. */
        const val MAX_MODEL_BYTES = 20L * 1024 * 1024 * 1024
    }
}
