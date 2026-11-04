package com.aide.mobilestudio.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One captured file state: what the file contained RIGHT BEFORE the agent
 * mutated it. [content] == null means the file did not exist yet (the agent
 * created it), so undoing the checkpoint deletes it again.
 */
class CheckpointEntry(
    val path: String,
    val content: String?,
    val timestamp: Long,
)

/** All pre-edit checkpoints of one agent turn (one user request → its edits). */
class CheckpointTurn(
    val turnId: String,
    val entries: List<CheckpointEntry>,
)

/** Per-file line delta shown in the /undo confirm dialog. */
class CheckpointFileDelta(
    val path: String,
    val linesBefore: Int,
    val linesAfter: Int,
    val existedBefore: Boolean,
) {
    val lineDelta: Int get() = linesAfter - linesBefore
}

/** Result of /undo and /redo - never thrown, always renderable. */
sealed class CheckpointOutcome {
    data class Undone(val turnId: String, val files: List<String>) : CheckpointOutcome()
    data class Redone(val turnId: String, val files: List<String>) : CheckpointOutcome()
    data object NothingToUndo : CheckpointOutcome()
    data object NothingToRedo : CheckpointOutcome()
    data class Failed(val message: String, val files: List<String>) : CheckpointOutcome()
}

/**
 * Bounded, persisted undo/redo ring of agent file edits.
 *
 * Every file mutation captured here is grouped by [capture] turn id, so an
 * undo rolls back ALL files an agent touched within one turn, not just one.
 * State lives under `<root>/<sessionId>/` (index.json + one JSON file per
 * turn), so undo/redo survive an app restart. The ring keeps at most
 * [maxEntries] captured files per session, pruning the oldest whole turns.
 *
 * Pure java.io + org.json: unit-testable on the JVM with a temp directory.
 * The index is read from disk on every operation, so several instances over
 * the same directory (panel + workspace) stay consistent.
 */
class CheckpointStore(
    private val rootDir: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {

    init {
        rootDir.mkdirs()
    }

    // ------------------------------------------------------------ capture

    /**
     * Captures the pre-edit content of [file] into turn [turnId]. The first
     * capture of a path within a turn wins (later edits of the same file in
     * the same turn need no new checkpoint). Returns true when captured.
     */
    @Synchronized
    fun capture(sessionId: String, turnId: String, file: File): Boolean {
        if (sessionId.isBlank() || turnId.isBlank()) return false
        val content = if (file.isFile) {
            runCatching { file.readText() }.getOrNull() ?: return false
        } else {
            null // file does not exist yet: the edit will create it
        }
        if (content != null && content.toByteArray().size > maxFileBytes) return false

        val index = loadIndex(sessionId)
        // A fresh edit invalidates any redo history (undone turns are gone).
        val staled = index.turns.filter { it.status == STATUS_UNDONE }
        for (turn in staled) {
            turn.status = STATUS_DISCARDED
            deleteTurnFile(sessionId, turn.turnId)
            deleteRedoFile(sessionId, turn.turnId)
        }
        index.turns.removeAll { it.status == STATUS_DISCARDED }

        val turn = index.turns.firstOrNull { it.turnId == turnId }
        if (turn != null) {
            val entries = readEntries(sessionId, turnId)
            if (entries.any { it.path == file.absolutePath }) return false
            entries.add(CheckpointEntry(file.absolutePath, content, System.currentTimeMillis()))
            writeEntries(sessionId, turnId, entries)
            turn.entryCount = entries.size
        } else {
            val entries = mutableListOf(CheckpointEntry(file.absolutePath, content, System.currentTimeMillis()))
            writeEntries(sessionId, turnId, entries)
            index.turns.add(TurnRecord(turnId, STATUS_PENDING, 1))
        }
        prune(sessionId, index)
        saveIndex(sessionId, index)
        return true
    }

    // ------------------------------------------------------------ inspect

    /** Newest turn that can still be undone, or null. */
    @Synchronized
    fun pendingUndoTurn(sessionId: String): CheckpointTurn? {
        val index = loadIndex(sessionId)
        val record = index.turns.lastOrNull { it.status == STATUS_PENDING } ?: return null
        return CheckpointTurn(record.turnId, readEntries(sessionId, record.turnId))
    }

    /** Newest turn that can be re-applied (was undone), or null. */
    @Synchronized
    fun pendingRedoTurn(sessionId: String): CheckpointTurn? {
        val index = loadIndex(sessionId)
        val record = index.turns.lastOrNull { it.status == STATUS_UNDONE } ?: return null
        return CheckpointTurn(record.turnId, readEntries(sessionId, record.turnId))
    }

    /**
     * What /undo would change, with line-count deltas against the CURRENT
     * file contents ([currentContentOf] may return null for missing files).
     */
    @Synchronized
    fun describeUndo(
        sessionId: String,
        currentContentOf: (String) -> String? = { path ->
            runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
        },
    ): List<CheckpointFileDelta> {
        val turn = pendingUndoTurn(sessionId) ?: return emptyList()
        return turn.entries.map { entry ->
            val current = currentContentOf(entry.path)
            val before = entry.content
            CheckpointFileDelta(
                path = entry.path,
                linesBefore = if (before == null) 0 else lineCount(before),
                linesAfter = if (current == null) 0 else lineCount(current),
                existedBefore = before != null,
            )
        }
    }

    // ------------------------------------------------------ undo / redo

    /**
     * Restores the newest pending turn: stashes the current (edited) content
     * as redo data, then writes the pre-edit content back (or deletes files
     * the agent created).
     */
    @Synchronized
    fun undo(sessionId: String, restore: (path: String, content: String?) -> Boolean = defaultRestore): CheckpointOutcome {
        val index = loadIndex(sessionId)
        val record = index.turns.lastOrNull { it.status == STATUS_PENDING }
            ?: return CheckpointOutcome.NothingToUndo
        val entries = readEntries(sessionId, record.turnId)
        if (entries.isEmpty()) {
            index.turns.remove(record)
            saveIndex(sessionId, index)
            return CheckpointOutcome.NothingToUndo
        }

        val touched = mutableListOf<String>()
        val redoEntries = JSONArray()
        for (entry in entries) {
            val current = runCatching { File(entry.path).takeIf { it.isFile }?.readText() }.getOrNull()
            val redoObj = JSONObject().put("path", entry.path)
            if (current != null) redoObj.put("content", current) else redoObj.put("missing", true)
            redoEntries.put(redoObj)
        }
        writeJsonFile(redoFile(sessionId, record.turnId), JSONObject().put("entries", redoEntries))

        for (entry in entries) {
            if (!restore(entry.path, entry.content)) {
                // Roll forward what we already restored so the tree is not
                // left half-pre-edit: re-apply the stashed redo content.
                for (done in touched) {
                    val redoEntry = findRedoEntry(sessionId, record.turnId, done)
                    if (redoEntry != null) restore(done, redoEntry.second)
                }
                return CheckpointOutcome.Failed("could not restore ${entry.path}", touched.toList())
            }
            touched.add(entry.path)
        }
        record.status = STATUS_UNDONE
        saveIndex(sessionId, index)
        return CheckpointOutcome.Undone(record.turnId, touched.toList())
    }

    /** Re-applies the most recently undone turn. */
    @Synchronized
    fun redo(sessionId: String, restore: (path: String, content: String?) -> Boolean = defaultRestore): CheckpointOutcome {
        val index = loadIndex(sessionId)
        val record = index.turns.lastOrNull { it.status == STATUS_UNDONE }
            ?: return CheckpointOutcome.NothingToRedo
        val redoEntries = readRedoEntries(sessionId, record.turnId)
        if (redoEntries.isEmpty()) return CheckpointOutcome.Failed("redo data missing for ${record.turnId}", emptyList())

        val touched = mutableListOf<String>()
        for ((path, content) in redoEntries) {
            if (!restore(path, content)) {
                return CheckpointOutcome.Failed("could not re-apply $path", touched.toList())
            }
            touched.add(path)
        }
        record.status = STATUS_PENDING
        saveIndex(sessionId, index)
        return CheckpointOutcome.Redone(record.turnId, touched.toList())
    }

    /** Drops every checkpoint of a session. */
    @Synchronized
    fun clear(sessionId: String) {
        File(rootDir, safeSegment(sessionId)).deleteRecursively()
    }

    // ------------------------------------------------------------ storage

    private class TurnRecord(
        val turnId: String,
        var status: String,
        var entryCount: Int,
    )

    private class SessionIndex(val turns: MutableList<TurnRecord>)

    private fun sessionDir(sessionId: String) = File(rootDir, safeSegment(sessionId))

    private fun turnFile(sessionId: String, turnId: String) =
        File(sessionDir(sessionId), "${safeSegment(turnId)}.json")

    private fun redoFile(sessionId: String, turnId: String) =
        File(sessionDir(sessionId), "${safeSegment(turnId)}.redo.json")

    private fun loadIndex(sessionId: String): SessionIndex {
        val turns = mutableListOf<TurnRecord>()
        val file = File(sessionDir(sessionId), "index.json")
        if (file.isFile) {
            runCatching {
                val arr = JSONObject(file.readText()).optJSONArray("turns") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    turns.add(
                        TurnRecord(
                            turnId = o.optString("turnId"),
                            status = o.optString("status", STATUS_PENDING),
                            entryCount = o.optInt("entries", 0),
                        ),
                    )
                }
            }
        }
        return SessionIndex(turns)
    }

    private fun saveIndex(sessionId: String, index: SessionIndex) {
        val arr = JSONArray()
        for (turn in index.turns) {
            arr.put(
                JSONObject()
                    .put("turnId", turn.turnId)
                    .put("status", turn.status)
                    .put("entries", turn.entryCount),
            )
        }
        writeJsonFile(File(sessionDir(sessionId), "index.json"), JSONObject().put("turns", arr))
    }

    private fun readEntries(sessionId: String, turnId: String): MutableList<CheckpointEntry> {
        val file = turnFile(sessionId, turnId)
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            val out = mutableListOf<CheckpointEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    CheckpointEntry(
                        path = o.optString("path"),
                        content = if (o.has("content") && !o.isNull("content")) o.getString("content") else null,
                        timestamp = o.optLong("ts"),
                    ),
                )
            }
            out
        }.getOrDefault(mutableListOf())
    }

    private fun writeEntries(sessionId: String, turnId: String, entries: List<CheckpointEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            val o = JSONObject().put("path", entry.path).put("ts", entry.timestamp)
            if (entry.content != null) o.put("content", entry.content)
            arr.put(o)
        }
        writeJsonFile(turnFile(sessionId, turnId), JSONObject().put("entries", arr))
    }

    private fun findRedoEntry(sessionId: String, turnId: String, path: String): Pair<String, String?>? =
        readRedoEntries(sessionId, turnId).firstOrNull { it.first == path }

    private fun readRedoEntries(sessionId: String, turnId: String): List<Pair<String, String?>> {
        val file = redoFile(sessionId, turnId)
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            val out = mutableListOf<Pair<String, String?>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val content = if (o.has("content") && !o.isNull("content")) o.getString("content") else null
                out.add(o.optString("path") to content)
            }
            out
        }.getOrDefault(emptyList())
    }

    /** Keeps at most [maxEntries] captured files; prunes oldest turns first. */
    private fun prune(sessionId: String, index: SessionIndex) {
        var total = index.turns.sumOf { it.entryCount }
        while (total > maxEntries && index.turns.size > 1) {
            val oldest = index.turns.removeAt(0)
            deleteTurnFile(sessionId, oldest.turnId)
            deleteRedoFile(sessionId, oldest.turnId)
            total -= oldest.entryCount
        }
    }

    private fun deleteTurnFile(sessionId: String, turnId: String) {
        runCatching { turnFile(sessionId, turnId).delete() }
    }

    private fun deleteRedoFile(sessionId: String, turnId: String) {
        runCatching { redoFile(sessionId, turnId).delete() }
    }

    private fun writeJsonFile(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
        const val DEFAULT_MAX_FILE_BYTES = 1024L * 1024L

        private const val STATUS_PENDING = "pending"
        private const val STATUS_UNDONE = "undone"
        private const val STATUS_DISCARDED = "discarded"

        /** Non-restore callback used by tests and engine callers. */
        val defaultRestore: (path: String, content: String?) -> Boolean = { path, content ->
            runCatching {
                val file = File(path)
                if (content == null) {
                    if (file.exists()) file.delete()
                } else {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }
                true
            }.getOrDefault(false)
        }

        /**
         * Lines in the editor sense: a trailing newline TERMINATES the last
         * line instead of starting a new empty one, so "a\nb\n" is 2 lines.
         */
        fun lineCount(text: String): Int =
            when {
                text.isEmpty() -> 0
                else -> text.removeSuffix("\n").split("\n").size
            }

        private fun safeSegment(raw: String): String =
            raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "session" }
    }
}
