package com.aide.mobilestudio.ai.opencode.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OcSnapshot(private val context: Context) {
    private val rootDir = File(context.filesDir, "AI/opencode/snapshots").apply { mkdirs() }

    fun snapshot(sessionId: String, messageID: String, file: File) {
        if (!file.isFile) return
        val dir = File(rootDir, sessionId).apply { mkdirs() }
        val manifest = readManifest(dir)
        val seq = manifest.length()
        val backup = File(dir, "snap_${seq}_${file.name}")
        runCatching {
            file.copyTo(backup, overwrite = true)
            manifest.put(JSONObject().apply {
                put("messageID", messageID)
                put("originalPath", file.absolutePath)
                put("backup", backup.name)
            })
            writeManifest(dir, manifest)
        }
    }

    fun restore(sessionId: String, untilMessageID: String? = null): List<String> {
        val dir = File(rootDir, sessionId)
        if (!dir.isDirectory) return emptyList()
        val manifest = readManifest(dir)
        val restored = mutableListOf<String>()
        val keep = mutableListOf<JSONObject>()
        for (i in manifest.length() - 1 downTo 0) {
            val entry = manifest.optJSONObject(i) ?: continue
            val messageId = entry.optString("messageID")
            if (untilMessageID != null && messageId > untilMessageID) {
                keep.add(entry)
                continue
            }
            val backup = File(dir, entry.optString("backup"))
            val original = File(entry.optString("originalPath"))
            if (backup.isFile) {
                runCatching {
                    original.parentFile?.mkdirs()
                    backup.copyTo(original, overwrite = true)
                    restored.add(original.absolutePath)
                }
            }
        }
        if (restored.isNotEmpty()) {
            val newManifest = JSONArray()
            for (j in keep.indices.reversed()) newManifest.put(keep[j])
            writeManifest(dir, newManifest)
        }
        return restored
    }

    fun clear(sessionId: String) {
        File(rootDir, sessionId).deleteRecursively()
    }

    /** Most recent pre-edit backup for [originalPath], or null when never snapshotted. */
    fun latestBackup(sessionId: String, originalPath: String): File? {
        val dir = File(rootDir, sessionId)
        if (!dir.isDirectory) return null
        val manifest = readManifest(dir)
        for (i in manifest.length() - 1 downTo 0) {
            val entry = manifest.optJSONObject(i) ?: continue
            if (entry.optString("originalPath") == originalPath) {
                val backup = File(dir, entry.optString("backup"))
                if (backup.isFile) return backup
            }
        }
        return null
    }

    private fun readManifest(dir: File): JSONArray {
        val file = File(dir, "manifest.json")
        if (!file.isFile) return JSONArray()
        return runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
    }

    private fun writeManifest(dir: File, manifest: JSONArray) {
        File(dir, "manifest.json").writeText(manifest.toString(2))
    }
}
