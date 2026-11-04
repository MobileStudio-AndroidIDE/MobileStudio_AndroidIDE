package com.aide.mobilestudio.ai.model

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class DownloadState { QUEUED, RUNNING, PAUSED, CANCELLED, COMPLETED, FAILED }

/** One download, from first tap to verified file. Immutable snapshot. */
data class DownloadTask(
    val id: String,
    val spec: DownloadSpec,
    val state: DownloadState = DownloadState.QUEUED,
    /** Bytes on disk right now (partial bytes count). */
    val progress: Long = 0L,
    val totalBytes: Long = -1L,
    val sha256: String? = null,
    /** True only when a server checksum was present and matched. */
    val checksumVerified: Boolean = false,
    /** Read from the downloaded file's own GGUF header. */
    val quant: QuantFamily? = null,
    val lastError: String? = null,
    val failure: DownloadFailure? = null,
    val finishedFile: File? = null,
    val updatedAtMs: Long = 0L,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (progress.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val isActive: Boolean get() = state == DownloadState.RUNNING || state == DownloadState.QUEUED
}

/**
 * Owns every download: launches the [DownloadEngine], keeps the observable
 * task list, persists progress across process death, and answers pause /
 * resume / cancel / retry.
 *
 * Honest recovery: after a restart, tasks that were RUNNING come back as
 * PAUSED (a download cannot have continued while the process was gone) with
 * their progress re-read from the `.part` file on disk. Nothing claims to have
 * finished that has not been verified.
 */
class ModelDownloadManager(
    private val context: Context,
    private val engine: DownloadEngine,
    private val modelDir: File = File(context.filesDir, "AI/models"),
) {

    private val prefs = context.getSharedPreferences("ai_model_downloads", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stopFlags = ConcurrentHashMap<String, StopReason>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    /**
     * Set by the catalogue: register a verified file as an installed model.
     *
     * Kept for the workspace's existing single listener. Additional listeners
     * (for example the AI's model-download tool) use [addCompletionListener]
     * so one feature can no longer silently replace another's callback.
     */
    @Volatile
    var onCompleted: ((DownloadTask) -> Unit)? = null

    private val completionListeners = CopyOnWriteArrayList<(DownloadTask) -> Unit>()

    /** Registers another completion observer; returns a removal function. */
    fun addCompletionListener(listener: (DownloadTask) -> Unit): () -> Unit {
        completionListeners.add(listener)
        return { completionListeners.remove(listener) }
    }

    init {
        modelDir.mkdirs()
        restore()
    }

    fun task(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    /** Starts (or returns the already-active) download for [spec]. */
    fun start(spec: DownloadSpec): DownloadTask {
        val existing = task(spec.id)
        if (existing != null && existing.state in ACTIVE_STATES) return existing
        val task = DownloadTask(
            id = spec.id,
            spec = spec,
            state = DownloadState.QUEUED,
            progress = partialBytes(spec),
            totalBytes = spec.expectedBytes,
            updatedAtMs = System.currentTimeMillis(),
        )
        put(task)
        launch(task)
        persist()
        return task
    }

    /** Asks the engine to stop at the next chunk; the `.part` file is kept. */
    fun pause(id: String) {
        val current = task(id) ?: return
        when (current.state) {
            DownloadState.RUNNING, DownloadState.QUEUED -> {
                stopFlags[id] = StopReason.PAUSE
                put(current.copy(state = DownloadState.PAUSED, updatedAtMs = System.currentTimeMillis()))
                persist()
            }
            else -> Unit
        }
    }

    /** Stops and deletes the partial file; cancelling means "not wanted". */
    fun cancel(id: String) {
        val current = task(id) ?: return
        when (current.state) {
            DownloadState.RUNNING, DownloadState.QUEUED -> {
                stopFlags[id] = StopReason.CANCEL
                // The engine deletes the part on its way out; a paused task has
                // no running engine, so the part is removed here.
                put(current.copy(state = DownloadState.CANCELLED, lastError = "취소됨", updatedAtMs = System.currentTimeMillis()))
                persist()
            }
            DownloadState.PAUSED -> {
                for (part in current.spec.resolvedParts) {
                    File(part.destination.parentFile, part.destination.name + ".part").delete()
                }
                put(current.copy(state = DownloadState.CANCELLED, lastError = "취소됨", updatedAtMs = System.currentTimeMillis()))
                persist()
            }
            else -> Unit
        }
    }

    /** Resumes a paused/failed/cancelled task; the engine picks up from `.part`. */
    fun resume(id: String) {
        val current = task(id) ?: return
        if (current.state in ACTIVE_STATES || current.state == DownloadState.COMPLETED) return
        launch(current)
        persist()
    }

    fun retry(id: String) = resume(id)

    /** Forgets a finished task (the downloaded file, if any, is kept). */
    fun remove(id: String) {
        jobs[id]?.cancel()
        stopFlags[id] = StopReason.CANCEL
        _tasks.value = _tasks.value.filterNot { it.id == id }
        persist()
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter { it.state in ACTIVE_STATES || it.state == DownloadState.PAUSED }
        persist()
    }

    private fun launch(task: DownloadTask) {
        stopFlags[task.id] = StopReason.RUNNING
        val fresh = task.copy(
            state = DownloadState.RUNNING,
            lastError = null,
            failure = null,
            updatedAtMs = System.currentTimeMillis(),
        )
        put(fresh)
        val job = scope.launch {
            val id = task.id
            val base = fresh
            val outcome = try {
                engine.download(
                    spec = base.spec,
                    stop = { stopFlags[id] ?: StopReason.RUNNING },
                    onProgress = { p ->
                        put(
                            base.copy(
                                progress = p.downloadedBytes,
                                totalBytes = if (p.totalBytes > 0) p.totalBytes else base.totalBytes,
                            )
                        )
                    },
                )
            } catch (e: CancellationException) {
                put(base.copy(state = DownloadState.CANCELLED, lastError = "취소됨", updatedAtMs = System.currentTimeMillis()))
                persist()
                throw e
            } catch (e: Throwable) {
                DownloadOutcome.Failed(DownloadFailure.NETWORK, e.message ?: "다운로드 실패")
            }
            val done = when (outcome) {
                is DownloadOutcome.Completed -> base.copy(
                    state = DownloadState.COMPLETED,
                    progress = outcome.bytes,
                    totalBytes = maxOf(base.totalBytes, outcome.bytes),
                    sha256 = outcome.sha256,
                    checksumVerified = outcome.checksumVerified,
                    quant = outcome.quant,
                    finishedFile = outcome.file,
                    updatedAtMs = System.currentTimeMillis(),
                )
                is DownloadOutcome.Paused -> base.copy(
                    state = DownloadState.PAUSED,
                    progress = outcome.bytes,
                    updatedAtMs = System.currentTimeMillis(),
                )
                is DownloadOutcome.Cancelled -> base.copy(
                    state = DownloadState.CANCELLED,
                    progress = 0L,
                    lastError = "취소됨",
                    updatedAtMs = System.currentTimeMillis(),
                )
                is DownloadOutcome.Failed -> base.copy(
                    state = DownloadState.FAILED,
                    lastError = outcome.message,
                    failure = outcome.failure,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
            put(done)
            persist()
            if (done.state == DownloadState.COMPLETED) {
                onCompleted?.invoke(done)
                for (listener in completionListeners) {
                    runCatching { listener(done) }
                }
            }
        }
        jobs[task.id] = job
        job.invokeOnCompletion { jobs.remove(task.id) }
    }

    // ------------------------------------------------------------ task table

    private fun put(task: DownloadTask) {
        val list = _tasks.value
        val index = list.indexOfFirst { it.id == task.id }
        _tasks.value = if (index >= 0) {
            list.toMutableList().also { it[index] = task }
        } else {
            list + task
        }
    }

    private fun partialBytes(spec: DownloadSpec): Long =
        spec.resolvedParts.sumOf { part ->
            val partial = File(part.destination.parentFile, part.destination.name + ".part")
            if (partial.isFile) partial.length() else 0L
        }

    private fun allPartsComplete(spec: DownloadSpec): Boolean =
        spec.resolvedParts.all { part ->
            part.destination.isFile &&
                (part.expectedBytes <= 0 || part.destination.length() == part.expectedBytes)
        }

    // ----------------------------------------------------------- persistence

    private fun persist() {
        val array = JSONArray()
        for (t in _tasks.value) {
            array.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("name", t.spec.displayName)
                    put("url", t.spec.url)
                    put("dest", t.spec.destination.absolutePath)
                    put("expected", t.spec.expectedBytes)
                    put("sha256", t.spec.expectedSha256 ?: JSONObject.NULL)
                    put("quant", t.spec.expectedQuantFamily?.label ?: JSONObject.NULL)
                    put("parts", JSONArray().apply {
                        for (part in t.spec.parts) {
                            put(JSONObject().apply {
                                put("url", part.url)
                                put("dest", part.destination.absolutePath)
                                put("expected", part.expectedBytes)
                                put("sha256", part.expectedSha256 ?: JSONObject.NULL)
                            })
                        }
                    })
                    put("state", t.state.name)
                    put("total", t.totalBytes)
                    put("progress", t.progress)
                    put("error", t.lastError ?: JSONObject.NULL)
                    put("updated", t.updatedAtMs)
                }
            )
        }
        prefs.edit().putString("tasks", array.toString()).apply()
    }

    private fun restore() {
        val raw = prefs.getString("tasks", null) ?: return
        val restored = try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> parseTask(array.optJSONObject(i) ?: return@mapNotNull null) }
        } catch (e: Exception) {
            emptyList()
        }
        if (restored.isEmpty()) return
        val now = System.currentTimeMillis()
        _tasks.value = restored.map { task ->
            when (task.state) {
                // A process that died mid-download cannot have kept downloading.
                DownloadState.RUNNING, DownloadState.QUEUED -> {
                    task.copy(
                        // Resume needs the partial files; a task that had not
                        // written any has nothing to resume, and says PAUSED
                        // rather than pretending it is still running.
                        state = DownloadState.PAUSED,
                        progress = partialBytes(task.spec),
                        updatedAtMs = now,
                    )
                }
                DownloadState.COMPLETED -> {
                    if (allPartsComplete(task.spec)) task else task.copy(
                        state = DownloadState.FAILED,
                        lastError = "파일이 삭제되었거나 일부 샤드가 없습니다",
                        updatedAtMs = now,
                    )
                }
                else -> task
            }
        }
    }

    private fun parseTask(obj: JSONObject): DownloadTask? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        val dest = obj.optString("dest").takeIf { it.isNotBlank() } ?: return null
        val state = runCatching { DownloadState.valueOf(obj.optString("state")) }.getOrDefault(DownloadState.PAUSED)
        val parts = obj.optJSONArray("parts")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val part = array.optJSONObject(i) ?: return@mapNotNull null
                val partUrl = part.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val partDest = part.optString("dest").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DownloadPart(
                    url = partUrl,
                    destination = File(partDest),
                    expectedBytes = part.optLong("expected", -1L),
                    expectedSha256 = part.optString("sha256").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.orEmpty()
        return DownloadTask(
            id = id,
            spec = DownloadSpec(
                id = id,
                url = url,
                destination = File(dest),
                expectedBytes = obj.optLong("expected", -1L),
                expectedSha256 = obj.optString("sha256").takeIf { it.isNotBlank() && it != "null" },
                expectedQuantFamily = obj.optString("quant").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { Quant.familyOf(it) },
                displayName = obj.optString("name"),
                parts = parts,
            ),
            state = state,
            progress = obj.optLong("progress", 0L),
            totalBytes = obj.optLong("total", -1L),
            lastError = obj.optString("error").takeIf { it.isNotBlank() && it != "null" },
            updatedAtMs = obj.optLong("updated", 0L),
        )
    }

    companion object {
        private val ACTIVE_STATES = setOf(DownloadState.QUEUED, DownloadState.RUNNING)
    }
}

/**
 * Process-wide holder. The workspace creates it once; the foreground service
 * and any other screen get the same instance, so there is exactly one task
 * list and one set of jobs.
 */
object ModelDownloads {

    @Volatile
    private var instance: ModelDownloadManager? = null

    fun get(context: Context): ModelDownloadManager {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val manager = ModelDownloadManager(
                    context = app,
                    engine = DownloadEngine(
                        fetcher = HttpFetcherImpl(),
                        freeSpaceBytes = { freeSpace(app) },
                    ),
                )
                instance = manager
                manager
            }
        }
    }

    private fun freeSpace(context: Context): Long = runCatching {
        val stat = StatFs(context.filesDir.path)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)
}
