package com.aide.mobilestudio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO = "MobileStudio-AndroidIDE/MobileStudio_AndroidIDE"
    private const val GITHUB_API = "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=100"

    private val mainHandler = Handler(Looper.getMainLooper())

    data class UpdateInfo(
        val latestVersion: String,
        val latestTag: String,
        val apkUrl: String,
        val releaseNotes: String,
        val apkSize: Long,
        val publishedAt: String
    )

    @Volatile
    private var cachedUpdate: UpdateInfo? = null

    @Volatile
    private var isChecking = false

    fun getCachedUpdate(): UpdateInfo? = cachedUpdate

    fun clearCache() { cachedUpdate = null }

    /**
     * Scans every release asset that looks like an APK (any file name) and
     * reports the newest build — see [AppUpdateVersionLogic] for the rules.
     * The current build is resolved from PackageManager unless
     * [installedOverride] is supplied, which lets callers (and tests) inject
     * the installed version instead. Never throws; any failure is a silent
     * onResult(null). Results are posted on the main looper.
     */
    fun checkForUpdates(
        context: Context,
        currentVersionName: String,
        installedOverride: AppUpdateVersionLogic.BuildVersion? = null,
        onResult: (UpdateInfo?) -> Unit
    ) {
        if (isChecking) { mainHandler.post { onResult(null) }; return }
        isChecking = true

        Thread {
            var result: UpdateInfo? = null
            try {
                val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "MobileStudio-AndroidIDE")

                if (conn.responseCode != 200) {
                    Log.w(TAG, "releases list HTTP ${conn.responseCode}")
                    conn.disconnect()
                } else {
                    val json = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()

                    val releases = JSONArray(json)
                    val best = AppUpdateVersionLogic.selectBestApk(collectApkCandidates(releases))
                    if (best != null) {
                        val installed = installedOverride
                            ?: resolveInstalledBuild(context, currentVersionName)
                        if (AppUpdateVersionLogic.isNewer(installed, best.version)) {
                            result = UpdateInfo(
                                latestVersion = AppUpdateVersionLogic.displayVersion(best.version),
                                latestTag = best.tag,
                                apkUrl = best.downloadUrl,
                                releaseNotes = best.releaseBody,
                                apkSize = best.sizeBytes,
                                publishedAt = best.releasePublishedAtIso
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkForUpdates failed: ${e.message}")
            } finally {
                isChecking = false
                val info = result
                cachedUpdate = info
                mainHandler.post { onResult(info) }
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * The installed build as a comparable version: the yy.M.d token of
     * [versionNameHint] (or PackageInfo.versionName when the hint is blank)
     * when one is present, otherwise lastUpdateTime as the timestamp
     * baseline. PackageManager failures degrade to an epoch baseline rather
     * than crashing.
     */
    fun resolveInstalledBuild(
        context: Context,
        versionNameHint: String? = null
    ): AppUpdateVersionLogic.BuildVersion {
        val pi = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val text = versionNameHint?.takeIf { it.isNotBlank() } ?: pi?.versionName
        return AppUpdateVersionLogic.buildVersion(text, pi?.lastUpdateTime ?: 0L)
    }

    /** Every .apk asset of every published (non-draft, non-prerelease) release. */
    private fun collectApkCandidates(releases: JSONArray): List<AppUpdateVersionLogic.ApkCandidate> {
        val candidates = ArrayList<AppUpdateVersionLogic.ApkCandidate>()
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            if (release.optBoolean("prerelease", false)) continue

            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (!AppUpdateVersionLogic.isApkAsset(name) || url.isBlank()) continue
                candidates.add(
                    AppUpdateVersionLogic.ApkCandidate(
                        assetName = name,
                        downloadUrl = url,
                        sizeBytes = asset.optLong("size", 0),
                        tag = release.optString("tag_name", ""),
                        releaseName = release.optString("name", ""),
                        assetUpdatedAtIso = asset.optString("updated_at", ""),
                        releasePublishedAtIso = release.optString("published_at", ""),
                        releaseBody = release.optString("body", "")
                    )
                )
            }
        }
        return candidates
    }

    /**
     * Legacy numeric dotted-version comparison, kept for compatibility;
     * new code should compare [AppUpdateVersionLogic.BuildVersion] values.
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        val curParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(curParts.size, latParts.size)) {
            val c = curParts.getOrElse(i) { 0 }
            val l = latParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * Downloads [info]'s APK into the app's external files dir (cacheDir when
     * external storage is unavailable), reporting progress as 0-100. When the
     * download completes the system installer is launched via a FileProvider
     * ACTION_VIEW intent (authority "${packageName}.provider", no extra
     * manifest entries needed); [onInstallStarted] fires on the main thread
     * right after that intent is fired so the caller can dismiss progress UI,
     * and [onDone] always fires exactly once on the main thread.
     */
    fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onInstallStarted: () -> Unit = {},
        onDone: (Boolean, String?) -> Unit
    ) {
        Thread {
            var apkFile: File? = null
            try {
                val dir = context.getExternalFilesDir(null) ?: context.cacheDir
                val versionLabel = info.latestVersion.ifBlank { info.latestTag }
                val fileName = "update_" +
                    versionLabel.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
                val file = File(dir, fileName)
                apkFile = file
                file.delete()

                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "MobileStudio-AndroidIDE")
                conn.connect()

                if (conn.responseCode !in 200..299) {
                    val code = conn.responseCode
                    conn.disconnect()
                    mainHandler.post { onDone(false, "HTTP $code") }
                    return@Thread
                }

                val total = conn.contentLengthLong
                val input = conn.inputStream
                val output = FileOutputStream(file)
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded * 100 / total).toInt())
                    }
                } finally {
                    output.close()
                    input.close()
                    conn.disconnect()
                }

                if (total > 0 && downloaded != total) {
                    file.delete()
                    mainHandler.post { onDone(false, "Incomplete download ($downloaded/$total bytes)") }
                    return@Thread
                }

                onProgress(100)

                mainHandler.post {
                    try {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(intent)
                        onInstallStarted()
                        onDone(true, null)
                    } catch (e: Exception) {
                        onDone(false, e.message)
                    }
                }
            } catch (e: Exception) {
                apkFile?.delete()
                mainHandler.post { onDone(false, e.message) }
            }
        }.apply { isDaemon = true; start() }
    }
}
