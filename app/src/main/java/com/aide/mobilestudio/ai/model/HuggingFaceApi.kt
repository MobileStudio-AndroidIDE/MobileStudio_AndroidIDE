package com.aide.mobilestudio.ai.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * URL building and JSON parsing for the Hugging Face API. No Android, no
 * network here: every branch is unit-testable from a fixture.
 *
 * Two endpoints are used, because neither alone is enough:
 *  - `GET /api/models?search=…&filter=gguf` — repositories and licences
 *  - `GET /api/models/{repo}/tree/{rev}?recursive=true` — real file names,
 *    byte sizes and LFS SHA-256 digests, which is where quantisation options
 *    and checksums come from
 *
 * Nothing here invents a model: a repository only appears after the API
 * returned it, and a quantisation option only after its file appeared in the
 * tree.
 */
object HuggingFaceApi {

    const val DEFAULT_HOST = "https://huggingface.co"

    const val DEFAULT_SEARCH_LIMIT = 30
    const val HARD_SEARCH_LIMIT = 100

    /** `model-00001-of-00002.gguf`, the naming used by split GGUF exports. */
    private val SPLIT_PART = Regex(
        """^(.*)-(\d{5})-of-(\d{5})\.gguf$""",
        RegexOption.IGNORE_CASE,
    )

    /** Task tags that mean a GGUF is not a local text-generation model. */
    private val NON_TEXT_TAGS = setOf(
        "text-to-speech",
        "automatic-speech-recognition",
        "text-to-audio",
        "audio",
        "audio-to-audio",
        "voice-conversion",
        "music-generation",
        "text-to-music",
        "text-to-video",
        "image-to-video",
        "video-to-video",
        "text-to-image",
        "image-to-image",
        "text-to-3d",
        "image-to-3d",
        "object-detection",
        "image-segmentation",
        "image-text-to-text",
        "visual-question-answering",
        "feature-extraction",
        "text2text-generation",
    )

    fun searchUrl(
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
        host: String = DEFAULT_HOST,
    ): String {
        val capped = limit.coerceIn(1, HARD_SEARCH_LIMIT)
        val builder = StringBuilder("$host/api/models?")
        builder.append("filter=gguf&sort=downloads&direction=-1&limit=").append(capped)
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            builder.append("&search=").append(encode(trimmed))
        }
        return builder.toString()
    }

    fun treeUrl(repoId: String, revision: String = "main", host: String = DEFAULT_HOST): String =
        // The tree endpoint rejects limit values above 1000 with HTTP 400.
        "$host/api/models/${encodePath(repoId)}/tree/${encodePath(revision)}?recursive=true&expand=false&limit=1000"

    fun resolveUrl(repoId: String, filePath: String, host: String = DEFAULT_HOST): String =
        "$host/${encodePath(repoId)}/resolve/main/${encodePath(filePath)}"

    /**
     * Only https URLs on Hugging Face hosts may be fetched. This is the single
     * allow-list the downloader and the redirect check both use.
     */
    fun isAllowedDownloadUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("https://")) return false
        if (lower.contains('@')) return false // no credentials in the authority
        val host = lower.removePrefix("https://").substringBefore('/').substringBefore(':')
        if (host.isEmpty()) return false
        return host == "huggingface.co" || host == "hf.co" ||
            host.endsWith(".huggingface.co") || host.endsWith(".hf.co")
    }

    /**
     * True for a GGUF file that is a text-model candidate.
     *
     * This includes split shards: they are real model files, but only a
     * complete set is loadable. The downloader groups them and checks the set
     * before offering it. Projectors, calibration matrices, adapters and
     * tokenizer-only files are excluded.
     */
    fun isCandidateGguf(fileName: String): Boolean {
        val path = fileName.lowercase()
        val name = path.substringAfterLast('/')
        if (!name.endsWith(".gguf")) return false
        val dirs = path.substringBeforeLast('/', "").split('/')
        if (dirs.any { it.contains("mmproj") || it.contains("imatrix") || it == "mtp" }) return false
        if (name.contains("mmproj")) return false
        if (name.contains("imatrix")) return false
        if (name.startsWith("mtp-")) return false
        if (name.contains("lora") || name.contains("adapter")) return false
        if (name.contains("vocab")) return false
        return true
    }

    /**
     * True for a GGUF that can be loaded on its own.
     *
     * A repository also contains `mmproj-*.gguf` vision projectors and, for
     * large models, split shards such as `model-00001-of-00002.gguf`. Neither
     * is a standalone text model; offering a shard alone would produce a file
     * the local runtime cannot load.
     */
    fun isStandaloneGguf(fileName: String): Boolean =
        isCandidateGguf(fileName) && splitPart(fileName) == null

    /** `model-00001-of-00002.gguf` → base, index and total part count. */
    fun splitPart(fileName: String): SplitPart? {
        val name = fileName.substringAfterLast('/')
        val match = SPLIT_PART.matchEntire(name) ?: return null
        val index = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        if (index < 1 || count < 1 || index > count) return null
        return SplitPart(match.groupValues[1], index, count)
    }

    /** Stable grouping key: directory + base + declared part count. */
    fun splitGroupKey(fileName: String): String? {
        val part = splitPart(fileName) ?: return null
        val dir = fileName.substringBeforeLast('/', "")
        return "$dir/${part.base}-of-${part.count}"
    }

    /** Human-facing base for a split group, used as its stable id. */
    fun splitBase(fileName: String): String? {
        val part = splitPart(fileName) ?: return null
        val dir = fileName.substringBeforeLast('/', "")
        return "$dir/${part.base}"
    }

    /** Zero-based-ish order of a shard; null for a single file. */
    fun splitIndex(fileName: String): Int? = splitPart(fileName)?.index

    /** A file name that cannot escape the model directory. */
    fun isSafeFileName(name: String): Boolean {
        if (name.isBlank() || name.length > 200) return false
        if (name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name.startsWith(".")) return false
        return true
    }

    /**
     * Keeps text-generation repositories and drops audio, vision and other
     * GGUF models the local llama.cpp path cannot run.
     *
     * A missing pipeline tag is kept unless another task tag proves it is not
     * text generation; new model repos often omit the tag but still declare
     * `text-generation` in their tags or simply have no contradictory tag.
     */
    fun isTextGenerationRepo(repo: HfRepo): Boolean {
        if (repo.tags.any { it.lowercase() in NON_TEXT_TAGS }) return false
        val pipeline = repo.pipelineTag?.lowercase()
        if (pipeline != null) return pipeline == "text-generation"
        return true
    }

    fun parseSearch(body: String): List<HfRepo> {
        val array = parseArray(body) ?: return emptyList()
        val out = ArrayList<HfRepo>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").ifBlank { obj.optString("modelId") }
            if (id.isBlank() || !id.contains('/')) continue
            val tags = obj.optJSONArray("tags")
            val tagList = buildList {
                if (tags != null) {
                    for (t in 0 until tags.length()) {
                        tags.optString(t).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            val declared = declaredParams(obj)
            val inferred = Quant.paramsFromName(id)
            val params = declared ?: inferred
            out.add(
                HfRepo(
                    id = id,
                    author = id.substringBefore('/'),
                    name = id.substringAfter('/'),
                    downloads = obj.optLong("downloads", 0L),
                    license = licenseOf(obj, tags),
                    params = params,
                    paramsSource = when {
                        declared != null -> ParamsSource.DECLARED
                        inferred != null -> ParamsSource.NAME_INFERRED
                        else -> ParamsSource.UNKNOWN
                    },
                    libraryName = (obj.opt("library_name") as? String)?.takeIf { it.isNotBlank() },
                    pipelineTag = (obj.opt("pipeline_tag") as? String)?.takeIf { it.isNotBlank() },
                    tags = tagList,
                )
            )
        }
        return out
    }

    fun parseTree(repoId: String, body: String): List<HfGgufFile> {
        val array = parseArray(body) ?: return emptyList()
        val out = ArrayList<HfGgufFile>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("type").let { it.isNotEmpty() && it != "file" }) continue
            val path = obj.optString("path")
            if (!path.endsWith(".gguf", ignoreCase = true)) continue
            // Reject traversal outright: a path is only ever used to build the
            // download URL and to name the file on disk.
            if (path.contains("..") || path.contains('\\') || path.startsWith("/")) continue
            if (!isSafeFileName(path.substringAfterLast('/'))) continue
            val lfs = obj.optJSONObject("lfs")
            val sha = lfs?.optString("oid")?.takeIf {
                it.length == 64 && it.all { c -> c in "0123456789abcdef" }
            }
            val lfsSize = lfs?.optLong("size", -1L) ?: -1L
            val size = if (lfsSize > 0) lfsSize else obj.optLong("size", -1L)
            if (size <= 0) continue
            out.add(HfGgufFile(repoId = repoId, path = path, sizeBytes = size, sha256 = sha))
        }
        return out.sortedBy { it.sizeBytes }
    }

    /**
     * `owner/repo` from a raw id or a Hugging Face model URL.
     *
     * A full URL may include `/tree/main/...` or `/resolve/main/file.gguf`;
     * only the first two path segments are the repository. A bare id still
     * has to be exactly two segments. Null when malformed.
     */
    fun parseRepoId(raw: String): String? {
        val input = raw.trim().substringBefore('?').substringBefore('#')
        val isUrl = input.startsWith("https://huggingface.co/") ||
            input.startsWith("http://huggingface.co/") ||
            input.startsWith("https://hf.co/")
        val path = input
            .removePrefix("https://huggingface.co/")
            .removePrefix("http://huggingface.co/")
            .removePrefix("https://hf.co/")
            .trim('/')
        val parts = path.split('/')
        if (parts.size < 2) return null
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        if (!isUrl && parts.size != 2) return null
        return parts.take(2).joinToString("/")
    }

    private fun licenseOf(obj: JSONObject, tags: JSONArray?): String? {
        val declared = obj.optJSONObject("cardData")?.opt("license") as? String
        if (!declared.isNullOrBlank()) return declared
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val tag = tags.optString(i)
                if (tag.startsWith("license:")) return tag.removePrefix("license:")
            }
        }
        return null
    }

    private fun declaredParams(obj: JSONObject): Long? {
        val safetensors = obj.optJSONObject("safetensors") ?: return null
        val total = safetensors.optLong("total", -1L)
        return total.takeIf { it > 0 }
    }

    private fun parseArray(body: String): JSONArray? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) return null
        return runCatching { JSONArray(trimmed) }.getOrNull()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Encodes each path segment so `owner/repo` keeps its slash. */
    private fun encodePath(value: String): String =
        value.split('/').joinToString("/") { encode(it) }
}

/** One shard's parsed name: `base-00001-of-00002.gguf`. */
data class SplitPart(val base: String, val index: Int, val count: Int)

/** A model repository discovered through the Hugging Face API. */
data class HfRepo(
    val id: String,
    val author: String,
    val name: String,
    val downloads: Long,
    val license: String?,
    val params: Long?,
    val paramsSource: ParamsSource,
    val libraryName: String?,
    /** Hugging Face task, e.g. `text-generation`; null when omitted. */
    val pipelineTag: String? = null,
    val tags: List<String> = emptyList(),
)

/** One GGUF file inside a repository, with the sizes the server reported. */
data class HfGgufFile(
    val repoId: String,
    val path: String,
    val sizeBytes: Long,
    /** LFS object id, which for LFS blobs *is* the SHA-256. Null when absent. */
    val sha256: String?,
) {
    val fileName: String get() = path.substringAfterLast('/')

    /**
     * Flat name used on disk. Repositories sometimes keep quants in
     * subdirectories; flattening the path keeps two files with the same
     * basename from overwriting each other in the model directory.
     */
    val storageName: String get() = path.replace('/', '_')
    val quant: String? get() = Quant.exactOfFileName(fileName)
    val quantFamily: QuantFamily? get() = Quant.familyOfFileName(fileName)
}

/** Raised when the API itself refuses; carries no key, ever. */
class HuggingFaceException(message: String) : Exception(message)
