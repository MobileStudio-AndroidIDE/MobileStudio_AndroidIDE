package com.aide.mobilestudio.analysis

import android.os.FileObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FileWatcher(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500L
) {
    private val watchers = ConcurrentHashMap<String, FileObserver>()
    private val _changes = MutableSharedFlow<FileChangeEvent>(extraBufferCapacity = 64)
    val changes: SharedFlow<FileChangeEvent> = _changes.asSharedFlow()

    data class FileChangeEvent(
        val file: File,
        val kind: ChangeKind,
        val contentHash: Int
    )

    enum class ChangeKind { MODIFIED, CREATED, DELETED }

    fun watchDirectory(dir: File) {
        if (!dir.isDirectory) return
        val path = dir.absolutePath
        if (watchers.containsKey(path)) return
        val observer = object : FileObserver(path, CREATE or DELETE or CLOSE_WRITE or MOVED_TO or MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val file = File(dir, path)
                if (!isKotlinSource(file)) return
                val kind = when (event) {
                    DELETE, MOVED_FROM -> ChangeKind.DELETED
                    CREATE, MOVED_TO -> ChangeKind.CREATED
                    CLOSE_WRITE -> ChangeKind.MODIFIED
                    else -> return
                }
                val hash = if (file.isFile) file.readText().hashCode() else 0
                scope.launch {
                    _changes.emit(FileChangeEvent(file, kind, hash))
                }
            }
        }
        observer.startWatching()
        watchers[path] = observer
    }

    fun unwatchDirectory(dir: File) {
        val path = dir.absolutePath
        watchers.remove(path)?.stopWatching()
    }

    fun unwatchAll() {
        watchers.values.forEach { it.stopWatching() }
        watchers.clear()
    }

    fun debouncedChanges(): Flow<FileChangeEvent> = changes
        .debounce(debounceMs)
        .distinctUntilChanged { old, new ->
            old.file.absolutePath == new.file.absolutePath && old.contentHash == new.contentHash
        }

    companion object {
        fun isKotlinSource(file: File): Boolean {
            val name = file.name
            return name.endsWith(".kt") || name.endsWith(".kts")
        }
    }
}
