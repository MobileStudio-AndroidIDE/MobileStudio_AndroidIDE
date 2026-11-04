package com.aide.mobilestudio

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 프로젝트 루트 아래 소스 파일 변경을 감지하고 디바운스 후 콜백을 호출합니다.
 * FileObserver 기반이라 예전 NIO WatchService처럼 GC finalizer에서
 * ClosedWatchServiceException이 발생하지 않습니다.
 */
class FileWatcher(
    private val rootDir: File,
    private val onChangedDebounced: (Set<File>) -> Unit
) {
    companion object {
        private const val TAG = "FileWatcher"
        const val DEFAULT_DEBOUNCE_MS = 1500L

        val SOURCE_EXTENSIONS = setOf(
            "kt", "java", "gradle", "kts",
            "c", "cpp", "h", "hpp",
            "xml", "py", "txt", "md", "json",
            "properties", "pro"
        )

        val IGNORED_DIRS = setOf(
            ".gradle", ".idea", "build", ".git",
            "node_modules", ".cxx", "captures", "out"
        )
    }

    @Volatile var debounceMs: Long = DEFAULT_DEBOUNCE_MS

    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val pendingChanges = CopyOnWriteArraySet<File>()
    private val lastChangeTime = AtomicLong(0)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var debounceJob: Runnable? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            registerRecursive(rootDir)
            Log.i(TAG, "started: $rootDir (watching ${observers.size} dirs)")
        }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        debounceJob?.let { mainHandler.removeCallbacks(it) }
        debounceJob = null
        observers.values.forEach { observer ->
            runCatching { observer.stopWatching() }
        }
        observers.clear()
        pendingChanges.clear()
        Log.i(TAG, "stopped")
    }

    private fun registerRecursive(dir: File) {
        if (shouldIgnore(dir)) return
        val path = dir.absolutePath
        if (observers.containsKey(path)) return
        val observer = object : FileObserver(path, CREATE or DELETE or CLOSE_WRITE or MOVED_TO or MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val full = File(dir, path)
                if (full.isDirectory) {
                    if (event == CREATE && !shouldIgnore(full)) registerRecursive(full)
                    return
                }
                if (full.extension.lowercase() !in SOURCE_EXTENSIONS) return
                if (shouldIgnore(full.parentFile ?: return)) return
                pendingChanges.add(full)
                lastChangeTime.set(System.currentTimeMillis())
                scheduleDebounce()
            }
        }
        runCatching { observer.startWatching() }
            .onFailure { e -> Log.w(TAG, "startWatching failed: ${dir.absolutePath}: ${e.message}") }
        observers[path] = observer
        dir.listFiles()
            ?.filter { it.isDirectory && !shouldIgnore(it) }
            ?.forEach { registerRecursive(it) }
    }

    private fun shouldIgnore(dir: File): Boolean {
        var cur: File? = dir
        while (cur != null && cur != rootDir.parentFile) {
            if (cur.name in IGNORED_DIRS) return true
            cur = cur.parentFile
        }
        return false
    }

    private fun scheduleDebounce() {
        debounceJob?.let { mainHandler.removeCallbacks(it) }
        val job = Runnable {
            val sinceLast = System.currentTimeMillis() - lastChangeTime.get()
            if (sinceLast < debounceMs) {
                scheduleDebounce()
                return@Runnable
            }
            val changes = pendingChanges.toSet()
            pendingChanges.clear()
            if (changes.isNotEmpty()) {
                Log.d(TAG, "debounce fire: ${changes.size} files")
                try {
                    onChangedDebounced(changes)
                } catch (e: Exception) {
                    Log.e(TAG, "callback error", e)
                }
            }
        }
        debounceJob = job
        mainHandler.postDelayed(job, debounceMs)
    }
}