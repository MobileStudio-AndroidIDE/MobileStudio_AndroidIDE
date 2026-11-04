package com.aide.mobilestudio

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class CommunityAttachment(
    val name: String,
    val url: String,
    val size: Long,
    val mime: String
)

data class CommunityRoom(
    val id: String,
    val name: String,
    val description: String,
    val ownerId: String,
    val memberCount: Int,
    val role: String?,
    val joined: Boolean,
    val banned: Boolean
)

data class CommunityMessage(
    val id: String,
    val roomId: String,
    val userId: String,
    val author: String,
    val body: String,
    val createdAt: String,
    val attachments: List<CommunityAttachment>
)

data class CommunityPollOption(
    val id: String,
    val text: String,
    val votes: Int
)

data class CommunityPoll(
    val id: String,
    val roomId: String,
    val question: String,
    val closesAt: String,
    val closed: Boolean,
    val options: List<CommunityPollOption>,
    val myVoteOptionId: String?
)

class SupabaseCommunityClient(private val context: Context) {
    private val prefs = SecurePrefs.get(context, "supabase_community_secure").also {
        SecurePrefs.migrateAll(context, "supabase_community", it)
    }

    val projectUrl: String get() = prefs.getString("url", DEFAULT_PROJECT_URL).orEmpty()
    val anonKey: String get() = prefs.getString("anon_key", DEFAULT_ANON_KEY).orEmpty()
    val accessToken: String get() = prefs.getString("access_token", "").orEmpty()
    val refreshToken: String get() = prefs.getString("refresh_token", "").orEmpty()
    val userId: String get() = prefs.getString("user_id", "").orEmpty()
    val userEmail: String get() = prefs.getString("user_email", "").orEmpty()
    val displayName: String get() = prefs.getString("display_name", "").orEmpty().ifBlank { googleNameFromEmail() }
    val configured: Boolean get() = validateConfigOrNull(projectUrl, anonKey) == null
    val signedIn: Boolean get() = accessToken.isNotBlank() && userId.isNotBlank()

    fun saveConfig(url: String, key: String, name: String) {
        val normalizedUrl = normalizeProjectUrl(url)
        validateConfig(normalizedUrl, key)
        prefs.edit()
            .putString("url", normalizedUrl)
            .putString("anon_key", key.trim())
            .putString("display_name", cleanDisplayName(name))
            .apply()
    }

    fun saveNickname(name: String) {
        prefs.edit().putString("display_name", cleanDisplayName(name)).apply()
        if (signedIn) {
            runCatching { upsertProfile() }
        }
    }

    fun signOut() {
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("user_id")
            .remove("user_email")
            .apply()
    }

    fun googleSignInUrl(): String {
        require(configured) { "Supabase URL/anon key is not configured." }
        val redirect = OAUTH_REDIRECT_URI
        return "${projectUrl.trimEnd('/')}/auth/v1/authorize" +
            "?provider=google" +
            "&redirect_to=${qp(redirect)}" +
            "&scopes=${qp("email profile")}" +
            "&response_type=token"
    }

    fun googleProviderSetupErrorOrNull(): String? {
        require(configured) { "Supabase URL/anon key is not configured." }
        val conn = (URL(googleSignInUrl()).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        val code = conn.responseCode
        val location = conn.getHeaderField("Location").orEmpty()
        val body = readResponse(conn)
        return when {
            code in 300..399 && location.isNotBlank() -> null
            body.contains("unsupported_provider", ignoreCase = true) ->
                    "Supabase Auth에서 Google Provider가 꺼져 있습니다.\n\nSupabase Dashboard > Authentication > Providers > Google을 Enable한 후 다시 로그인하세요."
            body.contains("validation_failed", ignoreCase = true) ->
                    "Supabase Google 로그인 설정이 완료되지 않았나요?\n\nGoogle Provider 설정에서 Redirect URL($OAUTH_REDIRECT_URI) 등록을 확인하세요."
            code in 200..399 -> null
                else -> "Google 로그인 준비실패 ($code): ${body.ifBlank { location }.take(240)}"
        }
    }

    fun handleOAuthRedirect(uri: Uri): Boolean {
        if (uri.scheme != "mobilestudio" || uri.host != "community-auth") return false
        val params = parseOAuthParams(uri)
        params["error_description"]?.let { error(it) }
        params["error"]?.let { error(it) }
        val token = params["access_token"].orEmpty()
        require(token.isNotBlank()) {
                    "Google 로그인 토큰을 받지 못했나요? Supabase Auth redirect URL에 $OAUTH_REDIRECT_URI 추가를 확인하세요."
        }
        val claims = jwtPayload(token)
        prefs.edit()
            .putString("access_token", token)
            .putString("refresh_token", params["refresh_token"].orEmpty())
            .putString("user_id", claims.optString("sub"))
            .putString("user_email", claims.optString("email"))
            .apply()
        upsertProfile()
        return true
    }

    fun fetchRooms(limit: Int = 60): List<CommunityRoom> {
        require(configured) { "Supabase URL/anon key is not configured." }
        val rooms = JSONArray(request("GET", "/rest/v1/community_rooms?select=*&order=created_at.desc&limit=$limit"))
        val members = JSONArray(request("GET", "/rest/v1/community_room_members?select=room_id,user_id,role,left_at&left_at=is.null"))
        val bans = if (signedIn) {
            JSONArray(request("GET", "/rest/v1/community_room_bans?select=room_id&user_id=eq.${qp(userId)}"))
        } else {
            JSONArray()
        }
        val counts = mutableMapOf<String, Int>()
        val myRoles = mutableMapOf<String, String>()
        for (i in 0 until members.length()) {
            val item = members.getJSONObject(i)
            val roomId = item.optString("room_id")
            counts[roomId] = (counts[roomId] ?: 0) + 1
            if (item.optString("user_id") == userId) myRoles[roomId] = item.optString("role", "member")
        }
        val banned = mutableSetOf<String>()
        for (i in 0 until bans.length()) banned += bans.getJSONObject(i).optString("room_id")
        return (0 until rooms.length()).map { index ->
            val item = rooms.getJSONObject(index)
            val id = item.optString("id")
            CommunityRoom(
                id = id,
                name = item.optString("name", "Open Chat"),
                description = item.optString("description"),
                ownerId = item.optString("owner_id"),
                memberCount = counts[id] ?: 0,
                role = myRoles[id],
                joined = myRoles.containsKey(id),
                banned = banned.contains(id)
            )
        }
    }

    fun createRoom(name: String, description: String): CommunityRoom {
        requireSignedIn()
        upsertProfile()
        val payload = JSONObject().apply {
            put("name", cleanRoomName(name))
            put("description", description.trim().take(120))
            put("owner_id", userId)
        }
        val created = JSONArray(request("POST", "/rest/v1/community_rooms", payload.toString())).getJSONObject(0)
        val roomId = created.getString("id")
        joinRoom(roomId, role = "owner")
        return fetchRooms().first { it.id == roomId }
    }

    fun joinRoom(roomId: String, role: String = "member") {
        requireSignedIn()
        upsertProfile()
        val payload = JSONObject().apply {
            put("room_id", roomId)
            put("user_id", userId)
            put("nickname", displayName)
            put("role", role)
            put("joined_at", java.time.Instant.now().toString())
            put("left_at", JSONObject.NULL)
        }
        request(
            "POST",
            "/rest/v1/community_room_members?on_conflict=room_id,user_id",
            payload.toString(),
            prefer = "resolution=merge-duplicates,return=representation"
        )
    }

    fun leaveRoom(roomId: String) {
        requireSignedIn()
        request("DELETE", "/rest/v1/community_room_members?room_id=eq.${qp(roomId)}&user_id=eq.${qp(userId)}")
    }

    fun kickUser(roomId: String, targetUserId: String) {
        requireSignedIn()
        request("DELETE", "/rest/v1/community_room_members?room_id=eq.${qp(roomId)}&user_id=eq.${qp(targetUserId)}")
    }

    fun banUser(roomId: String, targetUserId: String) {
        requireSignedIn()
        val payload = JSONObject().apply {
            put("room_id", roomId)
            put("user_id", targetUserId)
            put("banned_by", userId)
        }
        request("POST", "/rest/v1/community_room_bans?on_conflict=room_id,user_id", payload.toString(), prefer = "resolution=merge-duplicates")
        kickUser(roomId, targetUserId)
    }

    fun setMemberRole(roomId: String, targetUserId: String, role: String) {
        requireSignedIn()
        val payload = JSONObject().apply { put("role", role) }
        request("PATCH", "/rest/v1/community_room_members?room_id=eq.${qp(roomId)}&user_id=eq.${qp(targetUserId)}", payload.toString())
    }

    fun fetchMessages(roomId: String, limit: Int = 80): List<CommunityMessage> {
        require(configured) { "Supabase URL/anon key is not configured." }
        val response = request(
            method = "GET",
            path = "/rest/v1/community_messages?select=*&room_id=eq.${qp(roomId)}&order=created_at.asc&limit=$limit"
        )
        val array = JSONArray(response)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val attachments = item.optJSONArray("attachments") ?: JSONArray()
            CommunityMessage(
                id = item.optString("id"),
                roomId = item.optString("room_id"),
                userId = item.optString("user_id"),
                author = item.optString("author", "MobileStudio User"),
                body = item.optString("body"),
                createdAt = item.optString("created_at"),
                attachments = (0 until attachments.length()).mapNotNull { attachmentIndex ->
                    attachments.optJSONObject(attachmentIndex)?.let {
                        CommunityAttachment(
                            name = it.optString("name", "file"),
                            url = it.optString("url"),
                            size = it.optLong("size", 0L),
                            mime = it.optString("mime", "application/octet-stream")
                        )
                    }
                }
            )
        }
    }

    fun sendMessage(roomId: String, body: String, attachments: List<CommunityAttachment>) {
        requireSignedIn()
        require(body.length <= MAX_MESSAGE_CHARS) { "1000자 이하로 입력해주세요." }
        require(attachments.size <= MAX_ATTACHMENTS) { "Too many attachments. Max $MAX_ATTACHMENTS files." }
        val attachmentsJson = JSONArray()
        attachments.forEach {
            attachmentsJson.put(JSONObject().apply {
                put("name", it.name)
                put("url", it.url)
                put("size", it.size)
                put("mime", it.mime)
            })
        }
        val payload = JSONObject().apply {
            put("room_id", roomId)
            put("channel", roomId)
            put("user_id", userId)
            put("author", displayName)
            put("body", body)
            put("attachments", attachmentsJson)
        }
        request("POST", "/rest/v1/community_messages", payload.toString())
    }

    fun deleteMessage(messageId: String) {
        requireSignedIn()
        request("DELETE", "/rest/v1/community_messages?id=eq.${qp(messageId)}")
    }

    fun fetchPolls(roomId: String): List<CommunityPoll> {
        if (!signedIn) return emptyList()
        val polls = JSONArray(request("GET", "/rest/v1/community_polls?select=*&room_id=eq.${qp(roomId)}&order=created_at.desc&limit=20"))
        val options = JSONArray(request("GET", "/rest/v1/community_poll_options?select=*&poll_id=in.(${idsForFilter(polls)})&order=position.asc"))
        val votes = JSONArray(request("GET", "/rest/v1/community_poll_votes?select=poll_id,option_id,voter_id&poll_id=in.(${idsForFilter(polls)})"))
        val voteCounts = mutableMapOf<String, Int>()
        val myVotes = mutableMapOf<String, String>()
        for (i in 0 until votes.length()) {
            val vote = votes.getJSONObject(i)
            val optionId = vote.optString("option_id")
            voteCounts[optionId] = (voteCounts[optionId] ?: 0) + 1
            if (vote.optString("voter_id") == userId) myVotes[vote.optString("poll_id")] = optionId
        }
        val optionsByPoll = mutableMapOf<String, MutableList<CommunityPollOption>>()
        for (i in 0 until options.length()) {
            val option = options.getJSONObject(i)
            val pollId = option.optString("poll_id")
            optionsByPoll.getOrPut(pollId) { mutableListOf() }.add(
                CommunityPollOption(
                    id = option.optString("id"),
                    text = option.optString("option_text"),
                    votes = voteCounts[option.optString("id")] ?: 0
                )
            )
        }
        return (0 until polls.length()).map { index ->
            val poll = polls.getJSONObject(index)
            val id = poll.optString("id")
            CommunityPoll(
                id = id,
                roomId = poll.optString("room_id"),
                question = poll.optString("question"),
                closesAt = poll.optString("closes_at"),
                closed = poll.optBoolean("closed") || poll.optString("closes_at") <= nowIsoHint(),
                options = optionsByPoll[id].orEmpty(),
                myVoteOptionId = myVotes[id]
            )
        }
    }

    fun createPoll(roomId: String, question: String, options: List<String>, durationMinutes: Int) {
        requireSignedIn()
        require(options.size in 2..5) { "태그는 공백 포함 2~5개까지 가능합니다." }
        val payload = JSONObject().apply {
            put("room_id", roomId)
            put("creator_id", userId)
            put("question", question.trim().take(MAX_MESSAGE_CHARS))
            put("closes_at", java.time.Instant.now().plusSeconds(durationMinutes * 60L).toString())
        }
        val created = JSONArray(request("POST", "/rest/v1/community_polls", payload.toString())).getJSONObject(0)
        val pollId = created.getString("id")
        options.take(5).forEachIndexed { index, option ->
            val optionPayload = JSONObject().apply {
                put("poll_id", pollId)
                put("option_text", option.trim().take(80))
                put("position", index)
            }
            request("POST", "/rest/v1/community_poll_options", optionPayload.toString())
        }
    }

    fun vote(pollId: String, optionId: String) {
        requireSignedIn()
        val payload = JSONObject().apply {
            put("poll_id", pollId)
            put("option_id", optionId)
            put("voter_id", userId)
        }
        request("POST", "/rest/v1/community_poll_votes", payload.toString())
    }

    fun uploadAttachment(
        uri: Uri,
        name: String,
        mime: String,
        size: Long,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): CommunityAttachment {
        requireSignedIn()
        require(size > 0) { "Cannot upload a file with unknown size: $name" }
        require(size <= MAX_TOTAL_BYTES) { "Attachment is too large. Max total policy is ${MAX_TOTAL_BYTES / 1024 / 1024}MB." }
        val safeName = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "attachment.bin" }
        val fingerprint = uploadFingerprint(uri, safeName, size)
        val path = prefs.getString("${fingerprint}_path", null)
            ?: "uploads/$userId/${System.currentTimeMillis()}_$safeName".also {
                prefs.edit().putString("${fingerprint}_path", it).apply()
            }
        val uploaded = if (size > RESUMABLE_THRESHOLD_BYTES) {
            uploadResumable(fingerprint, uri, path, name, mime, size, onProgress)
        } else {
            uploadStandard(uri, path, name, mime, size, onProgress)
        }
        prefs.edit()
            .remove("${fingerprint}_path")
            .remove("${fingerprint}_url")
            .remove("${fingerprint}_offset")
            .apply()
        return uploaded
    }

    private fun uploadStandard(
        uri: Uri,
        path: String,
        name: String,
        mime: String,
        size: Long,
        onProgress: (sent: Long, total: Long) -> Unit
    ): CommunityAttachment {
        val url = URL("${projectUrl.trimEnd('/')}/storage/v1/object/community-attachments/${encodePath(path)}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            authHeaders()
            setRequestProperty("Content-Type", mime.ifBlank { "application/octet-stream" })
            setRequestProperty("x-upsert", "true")
            setFixedLengthStreamingMode(size)
        }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open attachment: $name" }
            conn.outputStream.use { output ->
                val buffer = ByteArray(STANDARD_CHUNK_BYTES)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    sent += read
                    onProgress(sent, size)
                }
            }
        }
        val code = conn.responseCode
        val text = readResponse(conn)
        if (code !in 200..299) error("Storage upload failed ($code): $text")
        return CommunityAttachment(
            name = name,
            url = "${projectUrl.trimEnd('/')}/storage/v1/object/public/community-attachments/${encodePath(path)}",
            size = size,
            mime = mime
        )
    }

    private fun uploadResumable(
        fingerprint: String,
        uri: Uri,
        path: String,
        name: String,
        mime: String,
        size: Long,
        onProgress: (sent: Long, total: Long) -> Unit
    ): CommunityAttachment {
        var uploadUrl = prefs.getString("${fingerprint}_url", null)
        var offset = uploadUrl?.let { runCatching { queryTusOffset(it) }.getOrNull() } ?: -1L
        if (uploadUrl == null || offset < 0L || offset > size) {
            uploadUrl = createTusUpload(path, mime, size)
            offset = 0L
            prefs.edit()
                .putString("${fingerprint}_url", uploadUrl)
                .putLong("${fingerprint}_offset", 0L)
                .apply()
        }
        val activeUploadUrl = requireNotNull(uploadUrl) { "Resumable upload URL was not created." }

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open attachment: $name" }
            input.skipFully(offset)
            onProgress(offset, size)
            val buffer = ByteArray(RESUMABLE_CHUNK_BYTES)
            while (offset < size) {
                val wanted = minOf(buffer.size.toLong(), size - offset).toInt()
                val read = input.read(buffer, 0, wanted)
                if (read < 0) break
                val nextOffset = patchTusChunk(activeUploadUrl, offset, buffer, read)
                offset = nextOffset
                prefs.edit().putLong("${fingerprint}_offset", offset).apply()
                onProgress(offset, size)
            }
        }
        check(offset >= size) { "Upload stopped early: $offset / $size bytes" }
        return CommunityAttachment(
            name = name,
            url = "${projectUrl.trimEnd('/')}/storage/v1/object/public/community-attachments/${encodePath(path)}",
            size = size,
            mime = mime
        )
    }

    private fun createTusUpload(path: String, mime: String, size: Long): String {
        val conn = (URL(storageResumableEndpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            authHeaders()
            setRequestProperty("Tus-Resumable", "1.0.0")
            setRequestProperty("Upload-Length", size.toString())
            setRequestProperty("Upload-Metadata", tusMetadata(path, mime))
            setRequestProperty("x-upsert", "true")
            instanceFollowRedirects = false
        }
        val code = conn.responseCode
        val text = readResponse(conn)
        if (code !in 200..299) error("Resumable upload create failed ($code): $text")
        val location = conn.getHeaderField("Location").orEmpty()
        require(location.isNotBlank()) { "Resumable upload did not return a Location header." }
        return if (location.startsWith("http")) location else URL(URL(storageResumableEndpoint()), location).toString()
    }

    private fun queryTusOffset(uploadUrl: String): Long {
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 20_000
            readTimeout = 30_000
            authHeaders()
            setRequestProperty("Tus-Resumable", "1.0.0")
        }
        val code = conn.responseCode
        if (code !in 200..299) return -1L
        return conn.getHeaderField("Upload-Offset")?.toLongOrNull() ?: -1L
    }

    private fun patchTusChunk(uploadUrl: String, offset: Long, buffer: ByteArray, length: Int): Long {
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            authHeaders()
            setRequestProperty("Tus-Resumable", "1.0.0")
            setRequestProperty("Content-Type", "application/offset+octet-stream")
            setRequestProperty("Upload-Offset", offset.toString())
            setFixedLengthStreamingMode(length)
        }
        conn.outputStream.use { it.write(buffer, 0, length) }
        val code = conn.responseCode
        val text = readResponse(conn)
        if (code !in 200..299) error("Resumable upload failed ($code): $text")
        return conn.getHeaderField("Upload-Offset")?.toLongOrNull() ?: (offset + length)
    }

    private fun upsertProfile() {
        requireSignedIn()
        val payload = JSONObject().apply {
            put("id", userId)
            put("nickname", displayName)
            put("email", userEmail)
        }
        request("POST", "/rest/v1/community_profiles?on_conflict=id", payload.toString(), prefer = "resolution=merge-duplicates")
    }

    private fun request(method: String, path: String, body: String? = null, prefer: String = "return=representation"): String {
        val conn = (URL("${projectUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            authHeaders()
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (method != "GET") setRequestProperty("Prefer", prefer)
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = readResponse(conn)
        if (code !in 200..299) error("Supabase request failed ($code): $text")
        return text.ifBlank { "[]" }
    }

    private fun HttpURLConnection.authHeaders() {
        setRequestProperty("apikey", anonKey)
        setRequestProperty("Authorization", "Bearer ${accessToken.ifBlank { anonKey }}")
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val input = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (input == null) return ""
        return BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { qp(it) }

    private fun qp(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun normalizeProjectUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val uri = URI(trimmed)
        require(uri.scheme == "https") { "Project URL must start with https://." }
        require(uri.host?.endsWith(".supabase.co") == true) { "Only Supabase project URLs are allowed." }
        require(uri.path.isNullOrBlank()) { "Use the root Project URL, not a path." }
        return "https://${uri.host.lowercase(Locale.US)}"
    }

    private fun validateConfig(url: String, key: String) {
        validateConfigOrNull(url, key)?.let { error(it) }
    }

    private fun validateConfigOrNull(url: String, key: String): String? {
        return runCatching {
            normalizeProjectUrl(url)
            val cleanKey = key.trim()
            when {
                cleanKey.length < 20 -> "Anon public key is too short."
                cleanKey.startsWith("sb_secret_", ignoreCase = true) -> "Never use a secret/service key in the app."
                jwtRole(cleanKey).equals("service_role", ignoreCase = true) -> "Never use the service_role key in the app."
                else -> null
            }
        }.getOrElse { it.message ?: "Invalid Supabase configuration." }
    }

    private fun requireSignedIn() {
        require(signedIn) { "Google 로그인이 필요합니다." }
    }

    private fun jwtRole(key: String): String? = jwtPayload(key).optString("role", null)

    private fun jwtPayload(key: String): JSONObject {
        val parts = key.split('.')
        if (parts.size < 2) return JSONObject()
        return runCatching {
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            JSONObject(payload)
        }.getOrDefault(JSONObject())
    }

    private fun parseOAuthParams(uri: Uri): Map<String, String> {
        val pairs = mutableMapOf<String, String>()
        uri.queryParameterNames.forEach { key -> pairs[key] = uri.getQueryParameter(key).orEmpty() }
        uri.fragment?.split('&')?.forEach { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            if (key.isNotBlank()) pairs[key] = java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        }
        return pairs
    }

    private fun idsForFilter(array: JSONArray): String {
        if (array.length() == 0) return UUID.randomUUID().toString()
        return (0 until array.length()).joinToString(",") { array.getJSONObject(it).optString("id") }
    }

    private fun nowIsoHint(): String = java.time.Instant.now().toString()

    private fun storageResumableEndpoint(): String {
        val host = URI(projectUrl).host.orEmpty()
        val projectId = host.substringBefore(".supabase.co")
        return if (projectId.isBlank()) {
            "${projectUrl.trimEnd('/')}/storage/v1/upload/resumable"
        } else {
            "https://$projectId.storage.supabase.co/storage/v1/upload/resumable"
        }
    }

    private fun tusMetadata(path: String, mime: String): String {
        fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return listOf(
            "bucketName ${encode("community-attachments")}",
            "objectName ${encode(path)}",
            "contentType ${encode(mime.ifBlank { "application/octet-stream" })}",
            "cacheControl ${encode("3600")}"
        ).joinToString(",")
    }

    private fun uploadFingerprint(uri: Uri, safeName: String, size: Long): String {
        val raw = "${uri}|$safeName|$size|$projectUrl|$userId"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "community_upload_" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun cleanDisplayName(name: String): String =
        name.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_AUTHOR_CHARS).ifBlank { googleNameFromEmail() }

    private fun cleanRoomName(name: String): String =
        name.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_ROOM_NAME_CHARS).ifBlank { "Private Chat" }

    private fun googleNameFromEmail(): String =
        userEmail.substringBefore('@').ifBlank { "MobileStudio User" }.take(MAX_AUTHOR_CHARS)

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) error("Cannot resume upload: file ended before stored offset.")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    companion object {
        const val DEFAULT_PROJECT_URL = "https://bgojdyquljsulukeuvox.supabase.co"
        const val DEFAULT_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJnb2pkeXF1bGpzdWx1a2V1dm94Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODExODcyMzgsImV4cCI6MjA5Njc2MzIzOH0.-Nx82m0-77v5KOio1-S-fDn8RbNha_-6ZMbD_G3Ympc"
        const val OAUTH_REDIRECT_URI = "mobilestudio://community-auth"
        const val MAX_ATTACHMENTS = 5
        const val MAX_MESSAGE_CHARS = 1000
        const val MAX_AUTHOR_CHARS = 40
        const val MAX_ROOM_NAME_CHARS = 40
        const val MAX_TOTAL_BYTES = 300L * 1024L * 1024L
        const val FREE_PLAN_OBJECT_LIMIT_BYTES = 50L * 1024L * 1024L
        private const val RESUMABLE_THRESHOLD_BYTES = 6L * 1024L * 1024L
        private const val STANDARD_CHUNK_BYTES = 64 * 1024
        private const val RESUMABLE_CHUNK_BYTES = 1024 * 1024
        val ALLOWED_EXTENSIONS = setOf(
            "zip", "kt", "kts", "java", "xml", "gradle", "json", "txt", "md",
            "c", "cpp", "h", "hpp", "jpg", "jpeg", "png", "webp"
        )

        val setupSql: String = """
            create extension if not exists pgcrypto;

            create table if not exists public.community_profiles (
              id uuid primary key,
              nickname text not null check (char_length(nickname) <= 40),
              email text,
              created_at timestamptz not null default now(),
              updated_at timestamptz not null default now()
            );

            create table if not exists public.community_rooms (
              id uuid primary key default gen_random_uuid(),
              name text not null check (char_length(name) between 1 and 40),
              description text not null default '' check (char_length(description) <= 120),
              owner_id uuid references public.community_profiles(id) on delete set null,
              created_at timestamptz not null default now()
            );

            insert into public.community_rooms (id, name, description, owner_id)
        values ('00000000-0000-0000-0000-000000000001', '안내 팔팔채팅', 'MobileStudio 안내 커뮤니티', null)
            on conflict (id) do nothing;

            create table if not exists public.community_room_members (
              room_id uuid not null references public.community_rooms(id) on delete cascade,
              user_id uuid not null references public.community_profiles(id) on delete cascade,
              nickname text not null check (char_length(nickname) <= 40),
              role text not null default 'member' check (role in ('owner','admin','member')),
              joined_at timestamptz not null default now(),
              left_at timestamptz,
              primary key (room_id, user_id)
            );

            create table if not exists public.community_room_bans (
              room_id uuid not null references public.community_rooms(id) on delete cascade,
              user_id uuid not null,
              banned_by uuid,
              created_at timestamptz not null default now(),
              primary key (room_id, user_id)
            );

            create table if not exists public.community_messages (
              id uuid primary key default gen_random_uuid(),
              room_id uuid not null references public.community_rooms(id) on delete cascade,
              channel text not null default 'global',
              user_id uuid not null references public.community_profiles(id) on delete cascade,
              author text not null default 'MobileStudio User' check (char_length(author) <= 40),
              body text not null check (char_length(body) <= 1000),
              attachments jsonb not null default '[]'::jsonb
                check (jsonb_typeof(attachments) = 'array' and jsonb_array_length(attachments) <= 5),
              created_at timestamptz not null default now()
            );

            alter table public.community_messages
              add column if not exists room_id uuid references public.community_rooms(id) on delete cascade;
            alter table public.community_messages
              add column if not exists user_id uuid references public.community_profiles(id) on delete cascade;
            update public.community_messages
              set room_id = '00000000-0000-0000-0000-000000000001'
              where room_id is null;

            create table if not exists public.community_polls (
              id uuid primary key default gen_random_uuid(),
              room_id uuid not null references public.community_rooms(id) on delete cascade,
              creator_id uuid not null references public.community_profiles(id) on delete cascade,
              question text not null check (char_length(question) between 1 and 1000),
              closes_at timestamptz not null,
              closed boolean not null default false,
              created_at timestamptz not null default now()
            );

            create table if not exists public.community_poll_options (
              id uuid primary key default gen_random_uuid(),
              poll_id uuid not null references public.community_polls(id) on delete cascade,
              option_text text not null check (char_length(option_text) between 1 and 80),
              position int not null check (position between 0 and 4)
            );

            create table if not exists public.community_poll_votes (
              poll_id uuid not null references public.community_polls(id) on delete cascade,
              option_id uuid not null references public.community_poll_options(id) on delete cascade,
              voter_id uuid not null references public.community_profiles(id) on delete cascade,
              created_at timestamptz not null default now(),
              primary key (poll_id, voter_id)
            );

            create index if not exists community_messages_room_created_idx on public.community_messages (room_id, created_at desc);
            create index if not exists community_members_room_idx on public.community_room_members (room_id, left_at);
            create index if not exists community_polls_room_idx on public.community_polls (room_id, closes_at desc);

            alter table public.community_profiles enable row level security;
            alter table public.community_rooms enable row level security;
            alter table public.community_room_members enable row level security;
            alter table public.community_room_bans enable row level security;
            alter table public.community_messages enable row level security;
            alter table public.community_polls enable row level security;
            alter table public.community_poll_options enable row level security;
            alter table public.community_poll_votes enable row level security;

            drop policy if exists "profiles_read" on public.community_profiles;
            create policy "profiles_read" on public.community_profiles for select using (true);
            drop policy if exists "profiles_upsert_self" on public.community_profiles;
            create policy "profiles_upsert_self" on public.community_profiles for insert with check (auth.uid() = id);
            drop policy if exists "profiles_update_self" on public.community_profiles;
            create policy "profiles_update_self" on public.community_profiles for update using (auth.uid() = id) with check (auth.uid() = id);

            drop policy if exists "rooms_read" on public.community_rooms;
            create policy "rooms_read" on public.community_rooms for select using (true);
            drop policy if exists "rooms_insert_auth" on public.community_rooms;
            create policy "rooms_insert_auth" on public.community_rooms for insert with check (auth.uid() = owner_id);
            drop policy if exists "rooms_update_owner_admin" on public.community_rooms;
            create policy "rooms_update_owner_admin" on public.community_rooms for update using (
              owner_id = auth.uid() or exists (
                select 1 from public.community_room_members m
                where m.room_id = id and m.user_id = auth.uid() and m.role in ('owner','admin') and m.left_at is null
              )
            );

            drop policy if exists "members_read" on public.community_room_members;
            create policy "members_read" on public.community_room_members for select using (true);
            drop policy if exists "members_join_self" on public.community_room_members;
            create policy "members_join_self" on public.community_room_members for insert with check (
              auth.uid() = user_id
              and (
                role = 'member'
                or exists (
                  select 1 from public.community_rooms r
                  where r.id = room_id and r.owner_id = auth.uid() and role = 'owner'
                )
              )
              and not exists (
                select 1 from public.community_room_bans b
                where b.room_id = room_id and b.user_id = auth.uid()
              )
            );
            drop policy if exists "members_delete_self_or_mod" on public.community_room_members;
            create policy "members_delete_self_or_mod" on public.community_room_members for delete using (
              auth.uid() = user_id or exists (
                select 1 from public.community_room_members m
                where m.room_id = community_room_members.room_id and m.user_id = auth.uid()
                  and m.role in ('owner','admin') and m.left_at is null
              )
            );
            drop policy if exists "members_update_mod" on public.community_room_members;
            create policy "members_update_mod" on public.community_room_members for update using (
              exists (
                select 1 from public.community_room_members m
                where m.room_id = community_room_members.room_id and m.user_id = auth.uid()
                  and m.role in ('owner','admin') and m.left_at is null
              )
            );

            drop policy if exists "bans_read_self_or_mod" on public.community_room_bans;
            create policy "bans_read_self_or_mod" on public.community_room_bans for select using (
              user_id = auth.uid() or exists (
                select 1 from public.community_room_members m
                where m.room_id = room_id and m.user_id = auth.uid() and m.role in ('owner','admin') and m.left_at is null
              )
            );
            drop policy if exists "bans_insert_mod" on public.community_room_bans;
            create policy "bans_insert_mod" on public.community_room_bans for insert with check (
              exists (
                select 1 from public.community_room_members m
                where m.room_id = room_id and m.user_id = auth.uid() and m.role in ('owner','admin') and m.left_at is null
              )
            );

            drop policy if exists "messages_read_members" on public.community_messages;
            create policy "messages_read_members" on public.community_messages for select using (
              exists (
                select 1 from public.community_room_members m
                where m.room_id = community_messages.room_id
                  and m.user_id = auth.uid()
                  and m.left_at is null
                  and community_messages.created_at >= m.joined_at
              )
            );
            drop policy if exists "messages_insert_members" on public.community_messages;
            create policy "messages_insert_members" on public.community_messages for insert with check (
              auth.uid() = user_id and char_length(body) <= 1000 and exists (
                select 1 from public.community_room_members m
                where m.room_id = community_messages.room_id and m.user_id = auth.uid() and m.left_at is null
              )
            );
            drop policy if exists "messages_delete_self_or_mod" on public.community_messages;
            create policy "messages_delete_self_or_mod" on public.community_messages for delete using (
              auth.uid() = user_id or exists (
                select 1 from public.community_room_members m
                where m.room_id = community_messages.room_id
                  and m.user_id = auth.uid()
                  and m.role in ('owner','admin')
                  and m.left_at is null
              )
            );

            create or replace function public.community_trim_messages()
            returns trigger
            language plpgsql
            security definer
            set search_path = public
            as $$
            begin
              delete from public.community_messages old_messages
              where old_messages.room_id = new.room_id
                and old_messages.id in (
                  select overflow.id
                  from public.community_messages overflow
                  where overflow.room_id = new.room_id
                  order by overflow.created_at desc, overflow.id desc
                  offset 500
                );
              return new;
            end;
            $$;

            drop trigger if exists community_trim_messages_after_insert on public.community_messages;
            create trigger community_trim_messages_after_insert
              after insert on public.community_messages
              for each row
              execute function public.community_trim_messages();

            drop policy if exists "polls_read_members" on public.community_polls;
            create policy "polls_read_members" on public.community_polls for select using (
              exists (
                select 1 from public.community_room_members m
                where m.room_id = room_id and m.user_id = auth.uid() and m.left_at is null
              )
            );
            drop policy if exists "polls_insert_mod" on public.community_polls;
            create policy "polls_insert_mod" on public.community_polls for insert with check (
              auth.uid() = creator_id and exists (
                select 1 from public.community_room_members m
                where m.room_id = room_id and m.user_id = auth.uid() and m.role in ('owner','admin') and m.left_at is null
              )
            );

            drop policy if exists "poll_options_read_members" on public.community_poll_options;
            create policy "poll_options_read_members" on public.community_poll_options for select using (
              exists (
                select 1 from public.community_polls p
                join public.community_room_members m on m.room_id = p.room_id
                where p.id = poll_id and m.user_id = auth.uid() and m.left_at is null
              )
            );
            drop policy if exists "poll_options_insert_creator" on public.community_poll_options;
            create policy "poll_options_insert_creator" on public.community_poll_options for insert with check (
              exists (select 1 from public.community_polls p where p.id = poll_id and p.creator_id = auth.uid())
            );

            drop policy if exists "poll_votes_read_members" on public.community_poll_votes;
            create policy "poll_votes_read_members" on public.community_poll_votes for select using (
              exists (
                select 1 from public.community_polls p
                join public.community_room_members m on m.room_id = p.room_id
                where p.id = poll_id and m.user_id = auth.uid() and m.left_at is null
              )
            );
            drop policy if exists "poll_votes_insert_once" on public.community_poll_votes;
            create policy "poll_votes_insert_once" on public.community_poll_votes for insert with check (
              auth.uid() = voter_id and exists (
                select 1 from public.community_polls p
                join public.community_room_members m on m.room_id = p.room_id
                where p.id = poll_id and p.closed = false and p.closes_at > now()
                  and m.user_id = auth.uid() and m.left_at is null
              )
            );

            insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
            values (
              'community-attachments',
              'community-attachments',
              true,
              314572800,
              array[
                'application/zip','application/x-zip','application/x-zip-compressed',
                'text/plain','text/markdown','application/json','text/xml','application/xml',
                'text/x-kotlin','text/x-java-source','text/x-csrc','text/x-c++src',
                'image/jpeg','image/png','image/webp','application/octet-stream'
              ]
            )
            on conflict (id) do update set
              public = excluded.public,
              file_size_limit = excluded.file_size_limit,
              allowed_mime_types = excluded.allowed_mime_types;

            drop policy if exists "community_attachments_read" on storage.objects;
            create policy "community_attachments_read"
              on storage.objects for select
              using (bucket_id = 'community-attachments');

            drop policy if exists "community_attachments_insert_own" on storage.objects;
            create policy "community_attachments_insert_own"
              on storage.objects for insert
              with check (
                bucket_id = 'community-attachments'
                and name like ('uploads/' || auth.uid()::text || '/%')
              );
        """.trimIndent()
    }
}
