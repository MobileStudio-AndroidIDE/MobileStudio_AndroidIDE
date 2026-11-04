package com.aide.mobilestudio.extension

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ExtensionScanClient — GitHub Actions 보안 검사 상태 연동.
 *
 *  - dispatchScan: workflow_dispatch로 보안 검사 트리거 (FIFO 큐의 슬롯 배정 후 호출)
 *  - pollResult: scan-results/<id>/<version>.json 읽기 (검사 결과)
 *  - queueState: queue.json 읽기 (대기열/동시 실행 상태)
 *
 * GitHub API는 GitHubPublisher에 등록된 PAT로 호출한다 (토큰은 로그에 남기지 않는다).
 */
class ExtensionScanClient private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ExtensionScanClient"
        private const val OWNER = "MobileStudio-AndroidIDE"
        private const val REPO = "MobileStudio-Extensions"
        private const val WORKFLOW_FILE = "validate-extension.yml"

        /** 스캔 단계 (ExtensionUploadQueue.SCAN_STAGES와 동일). */
        val SCAN_STAGES = listOf(
            "Package validation", "Metadata validation", "Static analysis",
            "Dependency scan", "Malware scan", "Permission analysis", "Final validation"
        )

        @Volatile private var instance: ExtensionScanClient? = null
        fun get(context: Context): ExtensionScanClient =
            instance ?: synchronized(this) { instance ?: ExtensionScanClient(context.applicationContext).also { instance = it } }
    }

    data class ScanResult(
        val extId: String,
        val version: String,
        val status: String,       // Passed | Warning | Failed
        val sha256: String,
        val stages: List<StageResult>,
        val note: String
    ) {
        data class StageResult(val stage: String, val status: String, val findings: List<String>)
    }

    /** GitHub API 호출 (PAT는 헤더로만 사용, 로그에 남기지 않음). */
    private fun request(url: String, method: String = "GET", body: JSONObject? = null): Pair<Int, String?> {
        var conn: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
        try {
            val token = GitHubPublisher.token(context)
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
            }
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 보안 검사 트리거 (workflow_dispatch). 성공 시 run URL 반환.
     * PAT의 workflow 스코프가 없으면 실패한다 (사용자에게 명확히 표시).
     */
    fun dispatchScan(extId: String, version: String, downloadUrl: String, onDone: (ok: Boolean, runUrl: String?, err: String?) -> Unit) {
        Thread {
            try {
                val body = JSONObject()
                    .put("ref", "main")
                    .put("inputs", JSONObject()
                        .put("ext_id", extId)
                        .put("version", version)
                        .put("download_url", downloadUrl))
                val (code, resp) = request(
                    "https://api.github.com/repos/$OWNER/$REPO/actions/workflows/$WORKFLOW_FILE/dispatches",
                    "POST", body
                )
                if (code in 200..299) {
                    // dispatch 응답은 204 (빈 응답) — run URL은 별도 조회
                    onDone(true, "https://github.com/$OWNER/$REPO/actions", null)
                } else {
                    Log.w(TAG, "dispatch failed: HTTP $code (token needs 'workflow' scope)")
                    onDone(false, null, "HTTP $code (GitHub 토큰에 'workflow' 스코프가 필요합니다)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "dispatchScan failed", e)
                onDone(false, null, e.message)
            }
        }.start()
    }

    /**
     * 최신 검사 결과를 읽는다. 결과가 아직 없으면 (null, null) — 폴링 계속.
     * 값을 만들어내지 않는다: 결과 파일이 없으면 null.
     */
    fun pollResult(extId: String, version: String, onDone: (ScanResult?, String?) -> Unit) {
        Thread {
            var result: ScanResult? = null
            var error: String? = null
            try {
                val url = "https://raw.githubusercontent.com/$OWNER/$REPO/main/scan-results/$extId/$version.json"
                val (code, resp) = request(url)
                if (code == 200 && resp != null) {
                    result = parseScanResult(resp)
                } else if (code == 404) {
                    // 아직 검사 결과 없음 — 정상 (폴링 계속)
                } else {
                    error = "HTTP $code"
                }
            } catch (e: Exception) {
                error = e.message
            }
            val r = result
            val e2 = error
            android.os.Handler(context.mainLooper).post { onDone(r, e2) }
        }.start()
    }

    private fun parseScanResult(text: String): ScanResult? {
        return try {
            val o = JSONObject(text)
            val stages = mutableListOf<ScanResult.StageResult>()
            o.optJSONArray("stages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val findings = mutableListOf<String>()
                    s.optJSONArray("findings")?.let { fa ->
                        for (j in 0 until fa.length()) findings.add(fa.getString(j))
                    }
                    stages.add(ScanResult.StageResult(s.optString("stage"), s.optString("status"), findings))
                }
            }
            ScanResult(
                extId = o.optString("extId"),
                version = o.optString("version"),
                status = o.optString("status", "Failed"),
                sha256 = o.optString("sha256"),
                stages = stages,
                note = o.optString("note")
            )
        } catch (e: Exception) {
            Log.w(TAG, "scan result parse failed", e)
            null
        }
    }

    /** 대기열 상태 (queue.json). 파일이 없으면 null. */
    fun queueState(onDone: (JSONObject?, String?) -> Unit) {
        Thread {
            var result: JSONObject? = null
            var error: String? = null
            try {
                val url = "https://raw.githubusercontent.com/$OWNER/$REPO/main/queue.json"
                val (code, resp) = request(url)
                if (code == 200 && resp != null) result = JSONObject(resp)
                else if (code != 404) error = "HTTP $code"
            } catch (e: Exception) {
                error = e.message
            }
            val r = result
            val e2 = error
            android.os.Handler(context.mainLooper).post { onDone(r, e2) }
        }.start()
    }
}
