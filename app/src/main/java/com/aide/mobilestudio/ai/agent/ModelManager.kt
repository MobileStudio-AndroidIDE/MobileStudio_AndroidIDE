package com.aide.mobilestudio.ai.agent

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.aide.mobilestudio.ai.GgufInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class ModelManager(private val context: Context) {
    data class ModelInfo(
        val id: String,
        val name: String,
        val path: String,
        val sizeBytes: Long = 0,
        val downloadUrl: String? = null,
        val quantization: String = "Q4_K_M",
        val contextLength: Int = 4096,
        val contextLimit: Int? = null,
        /**
         * The model's own maximum context, read from the GGUF header.
         *
         * Null when the file does not declare one, or has not been downloaded:
         * never a guess. [contextLength] is the window the app loads, this is
         * what the model can actually hold, and conflating the two is how a
         * model got loaded at a size it was never trained for.
         */
        val maxContext: Int? = null,

        /**
         * Layers the model declares in its GGUF header (block_count).
         *
         * Null when the file does not declare one or has not been downloaded.
         * Used to report an honest offload request ("all 29 layers") and to
         * step the GPU fallback down in usable increments instead of blind
         * decrements - neither is a guess.
         */
        val layerCount: Int? = null,
    )

    /**
     * GGUF header reads are cached for the life of the manager: listModels()
     * runs on every request and the header does not change underneath us.
     */
    private val ggufCache = java.util.concurrent.ConcurrentHashMap<String, GgufInfo.Info?>()

    fun ggufInfo(path: String): GgufInfo.Info? {
        if (ggufCache.containsKey(path)) return ggufCache[path]
        val info = runCatching { GgufInfo.read(File(path)) }.getOrNull()
        ggufCache[path] = info
        return info
    }

    private val prefs = context.getSharedPreferences("ai_models", Context.MODE_PRIVATE)
    private val modelDir = File(context.filesDir, "AI/models").apply { mkdirs() }

    fun listRemoteModels(): List<ModelInfo> {
        return listOf(
            ModelInfo("qwen-1b", "1B (Qwen2.5-Coder 1.5B)", File(modelDir, "qwen2.5-coder-1.5b-q4.gguf").absolutePath, 1_100_000_000L, 
                "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"),
            ModelInfo("qwen-3b", "3B (Qwen2.5-Coder 3B)", File(modelDir, "qwen2.5-coder-3b-q4.gguf").absolutePath, 2_100_000_000L,
                "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf"),
            ModelInfo("ds-7b", "5B (DeepSeek-Coder 6.7B)", File(modelDir, "deepseek-coder-6.7b-q4.gguf").absolutePath, 4_300_000_000L,
                "https://huggingface.co/TheBloke/deepseek-coder-6.7B-instruct-GGUF/resolve/main/deepseek-coder-6.7b-instruct.Q4_K_M.gguf"),
            ModelInfo("qwen-7b", "7B (Qwen2.5-Coder 7B)", File(modelDir, "qwen2.5-coder-7b-q4.gguf").absolutePath, 4_700_000_000L,
                "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf"),
            ModelInfo("llama-8b", "10B (Llama-3.1 8B)", File(modelDir, "llama-3.1-8b-q4.gguf").absolutePath, 5_300_000_000L,
                "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf")
        )
    }

    fun isModelDownloaded(model: ModelInfo): Boolean {
        val file = File(model.path)
        return file.exists() && file.length() > (model.sizeBytes * 0.9)
    }

    fun checkStorageSpace(requiredSize: Long): Pair<Boolean, Long> {
        val stat = StatFs(context.filesDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val bufferBytes = 3L * 1024 * 1024 * 1024 // 3GB
        val success = availableBytes > (requiredSize + bufferBytes)
        return success to availableBytes
    }

    fun downloadModel(model: ModelInfo, onProgress: (Float) -> Unit, onDone: (Boolean, String?) -> Unit) {
        val (hasSpace, available) = checkStorageSpace(model.sizeBytes)
        if (!hasSpace) {
            val required = (model.sizeBytes + 3L * 1024 * 1024 * 1024) / (1024 * 1024 * 1024)
            val current = available / (1024 * 1024 * 1024)
            onDone(false, "Insufficient storage. Need at least ${required}GB free (including 3GB buffer). Current available: ${current}GB.")
            return
        }

        Thread {
            try {
                val url = URL(model.downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connect()
                
                val length = conn.contentLengthLong
                val input = conn.inputStream
                val file = File(model.path)
                file.parentFile?.mkdirs()
                
                val output = FileOutputStream(file)
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded.toFloat() / length)
                }
                
                output.close()
                input.close()
                onDone(true, null)
            } catch (e: Exception) {
                Log.e("ModelManager", "Download failed", e)
                onDone(false, e.message)
            }
        }.start()
    }

    fun listModels(): List<ModelInfo> {
        val list = savedModels().toMutableList()
        val knownPaths = list.mapTo(mutableSetOf()) { canonicalPath(it.path) }
        val knownIds = list.mapTo(mutableSetOf()) { it.id }
        val remote = listRemoteModels()
        remote.forEach { r ->
            if (isModelDownloaded(r) && list.none { it.id == r.id } && canonicalPath(r.path) !in knownPaths) {
                val entry = r.copy(
                    maxContext = ggufInfo(r.path)?.contextLength,
                    layerCount = ggufInfo(r.path)?.nLayer,
                )
                list.add(entry)
                knownPaths.add(canonicalPath(entry.path))
                knownIds.add(entry.id)
            }
        }
        // Anything else the user put in files/AI/models is a local model too.
        for (scanned in scanModelDir()) {
            if (canonicalPath(scanned.path) in knownPaths) continue
            var id = scanned.id
            var suffix = 2
            while (id in knownIds) id = scanned.id + "-" + suffix++
            list.add(if (id == scanned.id) scanned else scanned.copy(id = id))
            knownPaths.add(canonicalPath(scanned.path))
            knownIds.add(id)
        }
        return list
    }

    private fun canonicalPath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** Only the entries the app registered, never a scan of the directory. */
    private fun savedModels(): List<ModelInfo> {
        val json = prefs.getString("models", "[]") ?: "[]"
        val array = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        val list = mutableListOf<ModelInfo>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val path = obj.optString("path")
            if (path.isBlank()) continue
            list.add(ModelInfo(
                obj.optString("id", File(path).nameWithoutExtension),
                obj.optString("name", File(path).name),
                path,
                obj.optLong("sizeBytes"),
                obj.optString("downloadUrl").takeIf { it.isNotBlank() },
                obj.optString("quantization"),
                obj.optInt("contextLength", 4096),
                // Named on purpose: these used to land one slot early, so the
                // model's real maximum context was never read and its layer
                // count disappeared. Every positional insert repeated that.
                maxContext = ggufInfo(path)?.contextLength,
                layerCount = ggufInfo(path)?.nLayer,
            ))
        }
        return list
    }

    fun addModel(name: String, path: String) {
        val id = File(path).nameWithoutExtension
        val models = savedModels().toMutableList()
        // A file in the model folder is already usable through the directory
        // scan; registering it only replaces the raw file name with the
        // friendly one the download chose.
        if (models.any { it.id == id || canonicalPath(it.path) == canonicalPath(path) }) return

        models.add(
            ModelInfo(
                id, name.ifBlank { id }, path,
                sizeBytes = runCatching { File(path).length() }.getOrDefault(0L),
                maxContext = ggufInfo(path)?.contextLength,
                layerCount = ggufInfo(path)?.nLayer,
            ),
        )
        saveModels(models)
    }

    fun removeModel(id: String) {
        val models = savedModels().filter { it.id != id }
        saveModels(models)
    }

    private fun saveModels(list: List<ModelInfo>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("path", it.path)
                put("sizeBytes", it.sizeBytes)
                put("downloadUrl", it.downloadUrl)
                put("quantization", it.quantization)
                put("contextLength", it.contextLength)
            })
        }
        prefs.edit().putString("models", array.toString()).apply()
    }

    /**
     * GGUF files already in [modelDir], whether or not a download registered
     * them.
     *
     * The model folder is the user's: a file copied there with `adb push`, a
     * file manager or another downloader must be usable without an extra
     * import step. Split GGUFs are one model, so only the first shard is
     * listed and only when every shard it names is present - a half-downloaded
     * split would otherwise look loadable and fail at load time.
     *
     * These entries are not written to preferences: the folder is the source
     * of truth and a file deleted outside the app must disappear from the
     * list on the next read.
     */
    private fun scanModelDir(): List<ModelInfo> {
        val files = modelDir.listFiles()?.toList() ?: emptyList()
        return scanGgufFiles(files) { ggufInfo(it.absolutePath) }.map { entry ->
            ModelInfo(
                id = entry.id,
                name = entry.displayName,
                path = entry.file.absolutePath,
                sizeBytes = entry.sizeBytes,
                downloadUrl = null,
                // Unknown until the header/filename says otherwise: never claim Q4.
                quantization = "",
                contextLength = 8_192,
                contextLimit = null,
                maxContext = entry.header?.contextLength,
                layerCount = entry.header?.nLayer,
            )
        }
    }
}

/** One GGUF file set found on disk, before it becomes a [ModelManager.ModelInfo]. */
internal data class ScannedGguf(
    val file: File,
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val header: GgufInfo.Info?,
)

/** `model-00001-of-00004.gguf` - llama.cpp's split-GGUF naming. */
private val SPLIT_GGUF = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

/**
 * Lists the GGUFs a model folder can actually run.
 *
 * Kept free of Android types so the split rules are unit-tested: a shard other
 * than the first is data, and a set with a missing shard is not a model. Every
 * path is read from the files themselves; nothing is inferred from a name.
 */
internal fun scanGgufFiles(
    files: List<File>,
    header: (File) -> GgufInfo.Info? = { null },
): List<ScannedGguf> {
    val present = files.filter {
        it.isFile && it.name.endsWith(".gguf", ignoreCase = true)
    }
    if (present.isEmpty()) return emptyList()
    val byName = present.associateBy { it.name.lowercase() }
    val out = mutableListOf<ScannedGguf>()
    for (file in present.sortedBy { it.name.lowercase() }) {
        val split = SPLIT_GGUF.matchEntire(file.name)
        if (split != null) {
            val base = split.groupValues[1]
            val index = split.groupValues[2].toIntOrNull() ?: continue
            val count = split.groupValues[3].toIntOrNull() ?: continue
            // Only the first shard represents the model; the rest are data.
            if (index != 1 || count < 2) continue
            val shards = (1..count).map { i ->
                byName[String.format(Locale.US, "%s-%05d-of-%05d.gguf", base, i, count).lowercase()]
            }
            // A missing shard means the model cannot load; do not advertise it.
            if (shards.any { it == null }) continue
            out.add(
                ScannedGguf(
                    file = file,
                    id = base,
                    displayName = base,
                    sizeBytes = shards.filterNotNull().sumOf { it.length() },
                    header = header(file),
                ),
            )
        } else {
            val id = file.nameWithoutExtension
            out.add(
                ScannedGguf(
                    file = file,
                    id = id,
                    displayName = id,
                    sizeBytes = file.length(),
                    header = header(file),
                ),
            )
        }
    }
    return out
}
