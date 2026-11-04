package com.aide.mobilestudio.extension

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app GitHub publishing: creates Releases in the extensions repository and
 * uploads the .msext (+ .msextinf) assets. The Release body carries the
 * extension.json metadata as JSON plus sha256/size/download - that Release IS
 * the registry entry, so no index file is committed anymore.
 *
 * Conventions (shared with the server publish pipeline):
 *   tag   = <full-extension-id>-v<version>
 *   asset = <full-extension-id>-v<version>.msext
 *
 * Requires a GitHub Personal Access Token (classic, scopes: repo) entered once in
 * the Extension Manager developer section. The token is stored in app prefs and
 * NEVER logged.
 */
object GitHubPublisher {

    private const val TAG = "GitHubPublisher"
    private const val PREFS = "extensions"
    private const val SECURE_PREFS = "extensions_secure"
    private const val KEY_TOKEN = "github_token"
    private const val API = "https://api.github.com"
    /**
     * Topic stamped on every publisher's extension repository. GitHub Search
     * (`?q=topic:mobilestudio-extension`) is readable without authentication,
     * so this topic alone turns every publisher's Releases into a globally
     * discoverable registry — no marketplace server required.
     */
    const val REGISTRY_TOPIC = "mobilestudio-extension"
    const val REGISTRY_REPO = "MobileStudio-Extensions"

    data class Result(val success: Boolean, val message: String, val url: String? = null)

    private fun prefs(context: Context) =
        com.aide.mobilestudio.SecurePrefs.get(context, SECURE_PREFS).also {
            com.aide.mobilestudio.SecurePrefs.migrateKeys(context, PREFS, it, setOf(KEY_TOKEN))
        }

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun hasToken(context: Context): Boolean =
        prefs(context).getString(KEY_TOKEN, null)?.isNotBlank() == true

    fun token(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.trim()

    // ------------------------- low-level API -------------------------

    private fun request(
        context: Context,
        urlStr: String,
        method: String,
        body: JSONObject? = null,
        isUpload: Boolean = false
    ): Pair<Int, String?> {
        val t = token(context) ?: throw Exception("GitHub 토큰이 설정되지 않았습니다")
        var conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $t")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.connectTimeout = 20_000
        conn.readTimeout = if (isUpload) 120_000 else 30_000
        conn.doOutput = body != null || isUpload
        (body ?: if (isUpload) JSONObject() else null)?.let { b ->
            if (isUpload) {
                // uploadReleaseAsset passes a raw file body via writeBodyOverride
            } else {
                val bytes = b.toString().toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { out: OutputStream -> out.write(bytes) }
            }
        }
        val code = conn.responseCode
        val responseBody = try {
            if (code < 400) conn.inputStream.bufferedReader().readText() else conn.errorStream.bufferedReader().readText()
        } catch (_: Exception) { null }
        val location = conn.getHeaderField("Location")
        conn.disconnect()
        return code to (responseBody ?: location)
    }

    private fun requestRaw(
        context: Context,
        urlStr: String,
        contentType: String,
        file: File
    ): Pair<Int, String?> {
        val t = token(context) ?: throw Exception("GitHub 토큰이 설정되지 않았습니다")
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $t")
        conn.setRequestProperty("Content-Type", contentType)
        conn.setRequestProperty("Content-Length", file.length().toString())
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(file.length())
        file.inputStream().use { input -> conn.outputStream.use { out -> input.copyTo(out) } }
        val code = conn.responseCode
        val body = try {
            if (code < 400) conn.inputStream.bufferedReader().readText() else conn.errorStream.bufferedReader().readText()
        } catch (_: Exception) { null }
        conn.disconnect()
        return code to body
    }

    // ------------------------- high-level flows -------------------------

    /** Creates the extensions repository under the token owner's account. */
    fun createRepo(context: Context, repoName: String, description: String): Result {
        return try {
            val body = JSONObject()
                .put("name", repoName)
                .put("description", description)
                .put("private", false)
                .put("auto_init", true)
                // topic 이 있어야 GitHub Search 가 이 저장소를 전역 레지스트리 인덱스에 넣는다.
                .put("topics", JSONArray().put(REGISTRY_TOPIC))
            val (code, resp) = request(context, "$API/user/repos", "POST", body)
            if (code in 200..201) {
                val url = JSONObject(resp ?: "{}").optString("html_url")
                Result(true, "저장소 생성됨: $repoName", url)
            } else {
                val msg = try { JSONObject(resp ?: "{}").optJSONArray("errors")?.getJSONObject(0)?.optString("message") } catch (_: Exception) { null }
                Result(false, "저장소 생성 실패 (HTTP $code): ${msg ?: resp?.take(120) ?: ""}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "createRepo failed", e)
            Result(false, "저장소 생성 실패: ${e.message}")
        }
    }

    /** Commits (creates/updates) a text file in the repository via the contents API. */
    fun commitFile(context: Context, owner: String, repo: String, path: String, content: String, message: String): Result {
        return try {
            val body = JSONObject()
                .put("message", message)
                .put("content", android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            // Existing file? Need its sha for update
            val (getCode, getResp) = request(context, "$API/repos/$owner/$repo/contents/$path", "GET")
            if (getCode == 200) {
                val sha = JSONObject(getResp ?: "{}").optString("sha")
                if (sha.isNotBlank()) body.put("sha", sha)
            }
            val (code, resp) = request(context, "$API/repos/$owner/$repo/contents/$path", "PUT", body)
            if (code in 200..200) {
                Result(true, "커밋됨: $path")
            } else {
                Result(false, "커밋 실패 (HTTP $code): ${resp?.take(120) ?: ""}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "commitFile failed", e)
            Result(false, "커밋 실패: ${e.message}")
        }
    }

    /** Creates a release (tag) and returns its asset upload URL. */
    fun createRelease(context: Context, owner: String, repo: String, tag: String, title: String, bodyText: String): Result {
        return try {
            val body = JSONObject()
                .put("tag_name", tag)
                .put("name", title)
                .put("body", bodyText)
            val (code, resp) = request(context, "$API/repos/$owner/$repo/releases", "POST", body)
            if (code in 200..201) {
                val json = JSONObject(resp ?: "{}")
                val uploadUrl = json.optString("upload_url").substringBefore("{")
                Result(true, "Release 생성됨: $tag", uploadUrl)
            } else {
                Result(false, "Release 생성 실패 (HTTP $code): ${resp?.take(120) ?: ""}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "createRelease failed", e)
            Result(false, "Release 생성 실패: ${e.message}")
        }
    }

    /** Uploads a file as a release asset. */
    fun uploadAsset(context: Context, uploadUrl: String, fileName: String, file: File): Result {
        return try {
            val (code, resp) = requestRaw(context, "$uploadUrl?name=$fileName", "application/octet-stream", file)
            if (code in 200..201) {
                Result(true, "업로드됨: $fileName")
            } else {
                Result(false, "업로드 실패 (HTTP $code): ${resp?.take(120) ?: ""}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadAsset failed", e)
            Result(false, "업로드 실패: ${e.message}")
        }
    }

    /** Resolves the token owner's login. */
    fun whoAmI(context: Context): Result {
        return try {
            val (code, resp) = request(context, "$API/user", "GET")
            if (code == 200) {
                Result(true, JSONObject(resp ?: "{}").optString("login"))
            } else {
                Result(false, "토큰 인증 실패 (HTTP $code)")
            }
        } catch (e: Exception) {
            Result(false, "토큰 인증 실패: ${e.message}")
        }
    }

    /** True when <owner>/<repo> is reachable with the current token. */
    fun repoExists(context: Context, owner: String, repo: String): Boolean = try {
        val (code, _) = request(context, "$API/repos/$owner/$repo", "GET")
        code == 200
    } catch (e: Exception) {
        false
    }

    /**
     * Ensures a PUBLIC <owner>/<repo> exists under the token owner's account,
     * creating it (auto_init) when missing. Used to give every logged-in user a
     * public extension repository without any server.
     */
    fun ensurePublicRepo(context: Context, owner: String, repo: String): Result {
        val exists = repoExists(context, owner, repo)
        if (exists) {
            // 이미 있는 저장소에도 topic 을 붙여 검색 인덱스에 들어가게 한다.
            ensureTopic(context, owner, repo)
            return Result(true, "저장소 확인됨: $owner/$repo")
        }
        return createRepo(context, repo, "MobileStudio Extension Repository")
    }

    /** Stamps [REGISTRY_TOPIC] on an existing repo (idempotent; best effort). */
    fun ensureTopic(context: Context, owner: String, repo: String) {
        try {
            val body = JSONObject().put("names", JSONArray().put(REGISTRY_TOPIC))
            request(context, "$API/repos/$owner/$repo/topics", "PUT", body)
        } catch (e: Exception) {
            Log.w(TAG, "ensureTopic failed for $owner/$repo", e)
        }
    }

    /**
     * Publishes to the logged-in user's own account as a PUBLIC release: ensures
     * the repo, then creates the release and uploads the .msext / .msextinf assets.
     */
    fun publishToUserRepo(
        context: Context,
        owner: String,
        repo: String,
        msextFile: File,
        infFile: File,
        onProgress: (String) -> Unit,
        onDone: (Result) -> Unit
    ) {
        Thread {
            val ensured = ensurePublicRepo(context, owner, repo)
            if (!ensured.success) { onDone(ensured); return@Thread }
            publishExtension(context, owner, repo, msextFile, infFile, onProgress, onDone)
        }.start()
    }

    /**
     * Full extension publish flow: create release (body = extension.json JSON
     * + sha256/size/download) -> upload .msext asset -> upload .msextinf.
     * The Release is the registry entry; nothing is committed to the repo.
     */
    fun publishExtension(
        context: Context,
        owner: String,
        repo: String,
        msextFile: File,
        infFile: File,
        onProgress: (String) -> Unit,
        onDone: (Result) -> Unit
    ) {
        Thread {
            try {
                val inf = infFile.readText()
                val id = Regex("id=(.+)").find(inf)?.groupValues?.getOrNull(1)?.trim() ?: msextFile.name
                val name = Regex("name=(.+)").find(inf)?.groupValues?.getOrNull(1)?.trim() ?: id
                val version = Regex("version=(.+)").find(inf)?.groupValues?.getOrNull(1)?.trim() ?: "1.0.0"
                val desc = Regex("description=(.+)").find(inf)?.groupValues?.getOrNull(1)?.trim() ?: ""
                val developer = DeveloperProfile.get(context)
                val tag = "$id-v$version"                       // full extension id
                val assetName = "$tag.msext"
                val repoUrl = "https://github.com/$owner/$repo"

                onProgress("인증 확인 중…")
                val who = whoAmI(context)
                if (!who.success) { onDone(who); return@Thread }
                val githubUser = who.message

                // Release body: extension.json from the package + distribution fields
                val bodyJson = readManifestFromPackage(msextFile) ?: JSONObject()
                    .put("id", id).put("name", name).put("version", version)
                    .put("description", desc)
                if (!bodyJson.has("author") || bodyJson.optString("author").isBlank()) {
                    bodyJson.put("author", developer?.name ?: githubUser)
                }
                bodyJson.put("download", "$repoUrl/releases/download/$tag/$assetName")
                bodyJson.put("size", msextFile.length())
                bodyJson.put("sha256", sha256(msextFile))

                onProgress("Release 생성 중: $tag")
                val rel = createRelease(context, owner, repo, tag, "$name v$version",
                    bodyJson.toString(2))
                if (!rel.success) { onDone(rel); return@Thread }
                val uploadUrl = rel.url ?: run { onDone(Result(false, "upload URL 없음")); return@Thread }

                onProgress("업로드 중: $assetName")
                val up1 = uploadAsset(context, uploadUrl, assetName, msextFile)
                if (!up1.success) { onDone(up1); return@Thread }

                onProgress("업로드 중: ${infFile.name}")
                val up2 = uploadAsset(context, uploadUrl, infFile.name, infFile)
                if (!up2.success) { onDone(up2); return@Thread }

                // The topic is what puts this release into the GitHub Search index,
                // so stamp it right before finishing — indexing starts immediately.
                ensureTopic(context, owner, repo)
                onDone(Result(true, "게시 완료: $name v$version\n$repoUrl/releases/tag/$tag", "$repoUrl/releases/tag/$tag"))
            } catch (e: Exception) {
                Log.e(TAG, "publishExtension failed", e)
                onDone(Result(false, "게시 실패: ${e.message}"))
            }
        }.start()
    }

    /** Reads the extension.json metadata from inside a .msext (ZIP) package. */
    private fun readManifestFromPackage(pkg: File): JSONObject? {
        return try {
            java.util.zip.ZipInputStream(pkg.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.replace('\\', '/')
                    if (n == "extension.json" || n == "./extension.json") {
                        return JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    }
                    entry = zip.nextEntry
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
