package com.aide.mobilestudio.extension

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * ExtensionUploadQueue — 확장 업로드/검사 대기열.
 *
 * 정책 (반드시 지켜진다):
 *  - FIFO: 먼저 업로드한 확장이 먼저 검사된다
 *  - 동시 검사 최대 [MAX_CONCURRENT] = 5개
 *  - 상태 머신: Uploading → Uploaded → Waiting → Scanning → Passed/Warning/Failed
 *    → (Failed/Warning → Manual Review) → Approved → Published / Rejected / Cancelled
 *
 * 진행률/예상 시간:
 *  - 대기 위치/예상 대기: 실제 대기열 상태 기반 계산 (확실하지 않으면 "estimated" 표시)
 *  - 검사 경과/예상 남은 시간: 측정된 평균 검사 시간 기반 (값을 만들어내지 않고
 *    실측이 없으면 "unknown" 표시)
 *
 * 상태는 로컬 queue 파일 + 리스너 콜백으로 실시간 갱신된다.
 */
class ExtensionUploadQueue private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ExtensionUploadQueue"
        private const val MAX_CONCURRENT = 5
        private const val PREFS = "extension_upload_queue"

        /** ?�캔 ?�계 (GitHub Actions ?�이?�라???�서?� ?�일). */
        val SCAN_STAGES = listOf(
            "Package validation",
            "Metadata validation",
            "Static analysis",
            "Dependency scan",
            "Malware scan",
            "Permission analysis",
            "Final validation"
        )

        @Volatile private var instance: ExtensionUploadQueue? = null
        fun get(context: Context): ExtensionUploadQueue =
            instance ?: synchronized(this) { instance ?: ExtensionUploadQueue(context.applicationContext).also { instance = it } }
    }

    /** 업로드/검사 상태 머신. */
    enum class Status { Uploading, Uploaded, Waiting, Scanning, Passed, Warning, Failed, ManualReview, Approved, Rejected, Published, Cancelled }

    data class UploadItem(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        var downloadUrl: String,
        var status: Status,
        var queuedAtMs: Long = 0L,
        var scanStartedAtMs: Long = 0L,
        var progressPercent: Int = 0,
        var currentStage: Int = 0,
        var message: String = ""
    )

    /** 실측된 평균 검사 시간(ms) — 예상 남은 시간 계산에만 사용. 실측 없으면 -1. */
    @Volatile private var measuredAvgScanMs: Long = -1L

    private val handler = Handler(Looper.getMainLooper())
    private val items = mutableListOf<UploadItem>()
    private val scanningCount = AtomicInteger(0)
    private var listener: ((UploadItem) -> Unit)? = null

    fun setOnUpdate(l: ((UploadItem) -> Unit)?) { listener = l }

    private fun notify_(item: UploadItem) {
        val snapshot = item.copy()
        persist()
        handler.post { listener?.invoke(snapshot) }
    }

    // ------------------------- enqueue -------------------------

    /**
     * 업로드를 대기열에 등록한다. 중복(id+동일 버전)은 거부한다.
     * 큐 순서(queuedAtMs 오름차순)로 슬롯이 배정된다.
     */
    fun enqueue(item: UploadItem): String? {
        if (items.any { it.id == item.id && it.version == item.version &&
                it.status != Status.Cancelled && it.status != Status.Rejected }) {
            return "이미 업로드된 버전입니다: ${item.id} v${item.version}"
        }
        val copy = item.copy(status = Status.Uploading, queuedAtMs = 0L)
        items.add(copy)
        notify_(copy)
        return null
    }

    /** 업로드 완료 → Waiting 슬롯 배정 (FIFO). */
    fun markUploaded(id: String, version: String, downloadUrl: String) {
        val item = items.lastOrNull { it.id == id && it.version == version } ?: return
        item.status = Status.Uploaded
        item.downloadUrl = downloadUrl
        item.queuedAtMs = System.currentTimeMillis()
        assignSlots()
        notify_(item)
    }

    fun cancel(id: String, version: String) {
        val item = items.lastOrNull { it.id == id && it.version == version } ?: return
        if (item.status in listOf(Status.Scanning)) {
            // 검사 중 취소는 GitHub Actions run 취소가 필요 — 상태만 기록
            item.message = "취소 요청됨 (검사 run 취소는 별도 처리)"
        }
        val wasScanning = item.status == Status.Scanning
        item.status = Status.Cancelled
        if (wasScanning) {
            scanningCount.decrementAndGet()
            assignSlots()
        }
        notify_(item)
    }

    /**
     * FIFO 슬롯 배정: queuedAtMs 오름차순으로 Waiting 아이템에 검사 슬롯 배정
     * (동시 실행 최대 [MAX_CONCURRENT]개). 먼저 업로드한 것이 반드시 먼저 검사된다.
     */
    private fun assignSlots() {
        val waiting = items.filter { it.status == Status.Uploaded || it.status == Status.Waiting }
            .sortedBy { if (it.queuedAtMs == 0L) Long.MAX_VALUE else it.queuedAtMs }
        var free = MAX_CONCURRENT - scanningCount.get()
        for (item in waiting) {
            if (free <= 0) {
                if (item.status == Status.Uploaded) {
                    item.status = Status.Waiting
                    notify_(item)
                }
                continue
            }
            item.status = Status.Scanning
            item.scanStartedAtMs = System.currentTimeMillis()
            item.progressPercent = 0
            item.currentStage = 0
            scanningCount.incrementAndGet()
            free--
            notify_(item)
            // 검사 트리거 (GitHub Actions workflow_dispatch + 상태 폴링)
            triggerScan(item)
        }
    }

    private fun triggerScan(item: UploadItem) {
        try {
            val client = ExtensionScanClient.get(context)
            val who = DeveloperProfile.get(context)
            if (who == null || !GitHubPublisher.hasToken(context)) {
                // PAT 없으면 GitHub Actions 트리거 불가 — 수동 검토 상태로
                item.status = Status.ManualReview
                item.message = "GitHub 토큰이 없어 자동 검사를 트리거할 수 없습니다"
                scanningCount.decrementAndGet()
                assignSlots()
                notify_(item)
                return
            }
            client.dispatchScan(item.id, item.version, item.downloadUrl) { ok, runUrl, err ->
                handler.post {
                    if (ok) {
                        item.message = runUrl ?: ""
                        pollScan(item)
                    } else {
                        item.status = Status.Failed
                        item.message = "검사 트리거 실패: ${err ?: "unknown"}"
                        scanningCount.decrementAndGet()
                        assignSlots()
                        notify_(item)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "triggerScan failed", e)
            item.status = Status.Failed
            item.message = "검사 트리거 오류: ${e.message}"
            scanningCount.decrementAndGet()
            assignSlots()
            notify_(item)
        }
    }

    /** 검사 상태 폴링 + 진행률 갱신 (실측 기반, 실측 없으면 unknown 표시). */
    private fun pollScan(item: UploadItem) {
        val client = ExtensionScanClient.get(context)
        client.pollResult(item.id, item.version) { result, err ->
            handler.post {
                when {
                    err != null -> {
                        // 일시적 폴링 오류 — 재시도 (최대 제한 없이 폴링 간격 증가)
                        handler.postDelayed({ if (item.status == Status.Scanning) pollScan(item) }, 15_000)
                    }
                    result != null -> {
                        applyResult(item, result)
                    }
                    else -> {
                        // 결과 아직 없음 — Scanning 유지, 진행률은 경과 기반 추정
                        item.progressPercent = estimatedProgress(item)
                        notify_(item)
                        handler.postDelayed({ if (item.status == Status.Scanning) pollScan(item) }, 10_000)
                    }
                }
            }
        }
    }

    private fun applyResult(item: UploadItem, result: ExtensionScanClient.ScanResult) {
        item.progressPercent = 100
        item.currentStage = ExtensionScanClient.SCAN_STAGES.size
        item.status = when {
            result.status == "Failed" -> Status.Failed
            result.status == "Warning" -> Status.ManualReview
            else -> Status.Passed
        }
        val elapsed = if (item.scanStartedAtMs > 0) System.currentTimeMillis() - item.scanStartedAtMs else -1L
        if (elapsed > 0) recordScanDuration(elapsed)
        item.message = "검사 ${result.status} (SHA-256: ${result.sha256.take(12)}…)"
        scanningCount.decrementAndGet()
        assignSlots()
        notify_(item)
    }

    /** 실측된 검사 시간 기록 (최근 값의 이동 평균). */
    private fun recordScanDuration(ms: Long) {
        measuredAvgScanMs = if (measuredAvgScanMs < 0) ms else (measuredAvgScanMs + ms) / 2
    }

    // ------------------------- progress / estimates -------------------------

    private fun estimatedProgress(item: UploadItem): Int {
        if (item.scanStartedAtMs <= 0) return 0
        val elapsed = System.currentTimeMillis() - item.scanStartedAtMs
        val expected = if (measuredAvgScanMs > 0) measuredAvgScanMs else 8 * 60 * 1000L // 기본 추정치
        return ((elapsed * 100) / expected).toInt().coerceIn(0, 95)
    }

    /** 대기열 위치 (1-based). 대기열에 없으면 null. */
    fun queuePosition(id: String, version: String): Int? {
        val waiting = items.filter { it.status == Status.Waiting }
            .sortedBy { if (it.queuedAtMs == 0L) Long.MAX_VALUE else it.queuedAtMs }
        val idx = waiting.indexOfFirst { it.id == id && it.version == version }
        return if (idx >= 0) idx + 1 else null
    }

    /** 예상 대기 시간(분). 실측 부족 시 null ("예상 시간: unknown" 표시용). */
    fun estimatedWaitMinutes(id: String, version: String): Int? {
        val pos = queuePosition(id, version) ?: return null
        val avg = if (measuredAvgScanMs > 0) measuredAvgScanMs else return null
        val ahead = pos - 1 + scanningCount.get()
        return ((ahead * avg) / 60000L).toInt().coerceAtLeast(1)
    }

    /**
     * 사용자 표시용 상태 텍스트. 실측 불가능한 값은 만들어내지 않고
     * "estimated"/"unknown"으로 명확히 표시한다.
     */
    fun statusText(item: UploadItem): String = buildString {
        append("Status: ${item.status}")
        when (item.status) {
            Status.Waiting -> {
                queuePosition(item.id, item.version)?.let { append("\nQueue position: $it") }
                estimatedWaitMinutes(item.id, item.version)?.let {
                    append("\nEstimated wait: $it min")
                } ?: append("\nEstimated wait: unknown")
            }
            Status.Scanning -> {
                val stage = SCAN_STAGES.getOrNull(item.currentStage) ?: "Scanning"
                append("\nStage: $stage")
                append("\nProgress: ${item.progressPercent}% (estimated)")
                if (item.scanStartedAtMs > 0) {
                    val elapsed = System.currentTimeMillis() - item.scanStartedAtMs
                    append("\nElapsed: ${elapsed / 60000}m ${(elapsed % 60000) / 1000}s")
                    if (measuredAvgScanMs > 0) {
                        val remaining = (measuredAvgScanMs - elapsed).coerceAtLeast(0)
                        append("\nEstimated remaining: ${remaining / 60000}m ${(remaining % 60000) / 1000}s")
                    } else {
                        append("\nEstimated remaining: unknown")
                    }
                }
            }
            else -> if (item.message.isNotBlank()) append("\n${item.message}")
        }
    }

    // ------------------------- queries / persistence -------------------------

    fun items(): List<UploadItem> = items.toList()

    fun myUploads(author: String): List<UploadItem> = items.filter { it.author == author }

    private fun queueFile(): File = File(context.filesDir, "extensions/upload_queue.json")

    private fun persist() {
        try {
            val arr = JSONArray()
            for (it in items) {
                arr.put(JSONObject()
                    .put("id", it.id).put("name", it.name).put("version", it.version)
                    .put("author", it.author).put("downloadUrl", it.downloadUrl)
                    .put("status", it.status.name).put("queuedAtMs", it.queuedAtMs)
                    .put("scanStartedAtMs", it.scanStartedAtMs)
                    .put("progressPercent", it.progressPercent)
                    .put("currentStage", it.currentStage)
                    .put("message", it.message))
            }
            queueFile().parentFile?.mkdirs()
            queueFile().writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "persist failed", e)
        }
    }

    /** 앱 재시작 시 복원. 진행 중(Scanning)이던 항목은 수동 검토로 전환한다. */
    fun restore() {
        try {
            val f = queueFile()
            if (!f.isFile) return
            val arr = JSONArray(f.readText())
            items.clear()
            scanningCount.set(0)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.Uploaded)
                val item = UploadItem(
                    id = o.optString("id"), name = o.optString("name"),
                    version = o.optString("version"), author = o.optString("author"),
                    downloadUrl = o.optString("downloadUrl"),
                    status = if (status == Status.Scanning) Status.ManualReview else status,
                    queuedAtMs = o.optLong("queuedAtMs"),
                    scanStartedAtMs = o.optLong("scanStartedAtMs"),
                    progressPercent = o.optInt("progressPercent"),
                    currentStage = o.optInt("currentStage"),
                    message = o.optString("message")
                )
                items.add(item)
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore failed", e)
        }
    }
}
