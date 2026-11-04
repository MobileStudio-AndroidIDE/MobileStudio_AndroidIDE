package com.aide.mobilestudio.ai.opencode.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OcSessionStore(private val context: Context) {
    private val rootDir = File(context.filesDir, "AI/opencode/sessions").apply { mkdirs() }

    data class Session(
        val id: String,
        val title: String,
        val summary: String?,
        val agent: String,
        val model: JSONObject?,
        val parentID: String?,
        val archived: Boolean,
        val timeCreated: Long,
        val timeUpdated: Long,
        val todos: JSONArray,
        val messages: JSONArray,
        val file: File,
    )

    fun list(includeArchived: Boolean = false): List<Session> {
        return rootDir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            load(file.nameWithoutExtension, file)?.takeIf { includeArchived || !it.archived }
        }?.sortedByDescending { it.timeUpdated } ?: emptyList()
    }

    fun load(id: String): Session? = load(id, File(rootDir, "$id.json"))

    private fun load(id: String, file: File): Session? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            Session(
                id = json.optString("id", id),
                title = json.optString("title", "Untitled"),
                summary = json.optStringOrNull("summary"),
                agent = json.optString("agent", "build"),
                model = json.optJSONObject("model"),
                parentID = json.optStringOrNull("parentID"),
                archived = json.optBoolean("archived", false),
                timeCreated = json.optLong("timeCreated", file.lastModified()),
                timeUpdated = json.optLong("timeUpdated", file.lastModified()),
                todos = json.optJSONArray("todos") ?: JSONArray(),
                messages = json.optJSONArray("messages") ?: JSONArray(),
                file = file,
            )
        }.getOrNull()
    }

    fun create(agent: String, model: JSONObject?, parentID: String? = null, title: String? = null): Session {
        val id = OcMessage.nextId("ses")
        val json = JSONObject().apply {
            put("id", id)
            put("title", title ?: "New session")
            put("agent", agent)
            if (model != null) put("model", model)
            if (parentID != null) put("parentID", parentID)
            put("archived", false)
            put("timeCreated", System.currentTimeMillis())
            put("timeUpdated", System.currentTimeMillis())
            put("todos", JSONArray())
            put("messages", JSONArray())
        }
        val file = File(rootDir, "$id.json")
        file.writeText(json.toString(2))
        return load(id, file)!!
    }

    fun save(session: Session) {
        val json = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            if (session.summary != null) put("summary", session.summary)
            put("agent", session.agent)
            if (session.model != null) put("model", session.model)
            if (session.parentID != null) put("parentID", session.parentID)
            put("archived", session.archived)
            put("timeCreated", session.timeCreated)
            put("timeUpdated", System.currentTimeMillis())
            put("todos", session.todos)
            put("messages", session.messages)
        }
        session.file.writeText(json.toString(2))
    }

    fun updateMessage(session: Session, message: JSONObject) {
        val messages = session.messages
        val id = message.optString("id")
        var replaced = false
        for (i in 0 until messages.length()) {
            val existing = messages.optJSONObject(i)
            if (existing != null && existing.optString("id") == id) {
                messages.put(i, message)
                replaced = true
                break
            }
        }
        if (!replaced) messages.put(message)
        save(session)
    }

    fun updatePart(session: Session, messageID: String, part: JSONObject) {
        val messages = session.messages
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("id") != messageID) continue
            val parts = msg.optJSONArray("parts") ?: JSONArray().also { msg.put("parts", it) }
            val partId = part.optString("id")
            var replaced = false
            for (j in 0 until parts.length()) {
                val existing = parts.optJSONObject(j)
                if (existing != null && existing.optString("id") == partId) {
                    parts.put(j, part)
                    replaced = true
                    break
                }
            }
            if (!replaced) parts.put(part)
            messages.put(i, msg)
            break
        }
        save(session)
    }

    fun addPart(session: Session, messageID: String, part: JSONObject) {
        updatePart(session, messageID, part)
    }

    fun messages(session: Session): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val messages = session.messages
        for (i in 0 until messages.length()) {
            messages.optJSONObject(i)?.let { result.add(it) }
        }
        return result
    }

    fun setTodos(session: Session, todos: JSONArray) {
        session.copy(todos = todos).let { save(it) }
    }

    fun setTitle(session: Session, title: String) {
        session.copy(title = title).let { save(it) }
    }

    fun setSummary(session: Session, summary: String) {
        session.copy(summary = summary).let { save(it) }
    }

    fun fork(sessionId: String, messageID: String? = null): Session? {
        val source = load(sessionId) ?: return null
        val messages = JSONArray()
        for (msg in messages(source)) {
            messages.put(msg)
            if (messageID != null && msg.optString("id") == messageID) break
        }
        val forked = create(source.agent, source.model, parentID = source.id, title = source.title)
        forked.copy(messages = messages).let { save(it) }
        return forked
    }

    fun archive(sessionId: String): Boolean {
        val session = load(sessionId) ?: return false
        save(session.copy(archived = true))
        return true
    }

    fun unarchive(sessionId: String): Boolean {
        val session = load(sessionId) ?: return false
        save(session.copy(archived = false))
        return true
    }

    fun delete(sessionId: String): Boolean = File(rootDir, "$sessionId.json").delete()

    private fun org.json.JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }

    private fun Session.copy(
        title: String = this.title,
        summary: String? = this.summary,
        archived: Boolean = this.archived,
        todos: JSONArray = this.todos,
        messages: JSONArray = this.messages,
        agent: String = this.agent,
        model: JSONObject? = this.model,
    ): Session = Session(
        id, title, summary, agent, model, parentID, archived, timeCreated, timeUpdated, todos, messages, file
    )
}
