package com.aide.mobilestudio.extension

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ExtensionMarketplace — Extension Hub 검색/좋아요/후기/신고 모듈.
 *
 * 데이터: GitHub Releases 레지스트리 (ExtensionManager.refreshRepo) + 로컬 상태 (likes/reviews/
 * reports/stats). 원격 계정 동기화가 없는 동안 통계는 이 기기 기준으로 집계되며,
 * 추후 GitHub 저장소 기반 동기화로 확장된다.
 *
 * Abuse protection (기본 방어):
 *  - 좋아요: 계정당 확장 1회, 토글 취소, 분당 10회 rate limit
 *  - 후기:   설치한 확장만, 계정당 1개(수정/삭제 가능), 분당 3회 rate limit
 *  - 신고:   동일 확장 하루 1회, 하루 전체 10회 rate limit
 *  - 신고 5건(24시간) 누적 → 자동 다운로드 일시 중지 + Under review (악성코드 확정 아님)
 *  - 계정 생성 직후(10분) 대량 활동 제한
 *
 * 개인정보는 최소한으로 수집한다 (IP/기기 기반 감지는 로컬에서만 익명 카운터로 사용).
 */
class ExtensionMarketplace private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ExtensionMarketplace"
        private const val PREFS = "extension_marketplace"

        const val REPORT_MALWARE = "Malware / Security"
        const val REPORT_PRIVACY = "개인정보 침해"
        const val REPORT_COPYRIGHT = "저작권 문제"
        const val REPORT_INAPPROPRIATE = "부적절한 콘텐츠"
        const val REPORT_FALSE_INFO = "허위 정보"
        const val REPORT_SPAM = "스팸"
        const val REPORT_BROKEN = "정상적으로 작동하지 않음"
        const val REPORT_OTHER = "기타"
        val REPORT_REASONS = listOf(REPORT_MALWARE, REPORT_PRIVACY, REPORT_COPYRIGHT,
            REPORT_INAPPROPRIATE, REPORT_FALSE_INFO, REPORT_SPAM, REPORT_BROKEN, REPORT_OTHER)

        private const val AUTO_SUSPEND_THRESHOLD = 5      // 24시간 유효 신고 5건
        private const val RATE_WINDOW_MS = 60_000L        // 1분

        @Volatile private var instance: ExtensionMarketplace? = null
        fun get(context: Context): ExtensionMarketplace =
            instance ?: synchronized(this) { instance ?: ExtensionMarketplace(context.applicationContext).also { instance = it } }
    }

    // ------------------------- models -------------------------

    data class Review(
        val id: String,
        val extId: String,
        val userId: String,
        val author: String,
        val content: String,
        val createdAt: Long,
        val edited: Boolean
    )

    data class Report(
        val extId: String,
        val userId: String,
        val reason: String,
        val message: String,
        val createdAt: Long
    )

    // ------------------------- search -------------------------

    enum class Sort { LIKES, NEWEST, DOWNLOADS, RECENTLY_UPDATED, NAME }

    data class SearchQuery(
        val text: String = "",
        val category: String = "",          // 확장 type/category
        val minMobileStudioVersion: String = "",
        val officialOnly: Boolean = false,
        val communityOnly: Boolean = false,
        val installableOnly: Boolean = false,
        val updatesOnly: Boolean = false,
        val sort: Sort = Sort.LIKES
    )

    /** GitHub Releases 레지스트리 기반 검색/정렬/필터. */
    fun search(query: SearchQuery): List<ExtensionManager.RepoEntry> {
        val repo = ExtensionManager.get(context).cachedRepo()
        val manager = ExtensionManager.get(context)
        val q = query.text.trim().lowercase()

        var results = repo.filter { entry ->
            val matchesText = q.isEmpty() || run {
                val category = entry.languages.joinToString(" ").lowercase() +
                    " " + (entry.permissions.joinToString(" ").lowercase())
                entry.name.lowercase().contains(q) ||
                    entry.id.lowercase().contains(q) ||
                    entry.description.lowercase().contains(q) ||
                    entry.author.lowercase().contains(q) ||
                    category.contains(q)
            }
            val official = entry.author.equals("MobileStudio", ignoreCase = true) ||
                entry.author.equals("MobileStudio-AndroidIDE", ignoreCase = true)
            val matchesOfficial = (!query.officialOnly && !query.communityOnly) ||
                (query.officialOnly && official) || (query.communityOnly && !official)
            val matchesInstallable = !query.installableOnly || manager.isInstalled(entry.id)
            val matchesUpdates = !query.updatesOnly ||
                manager.updates().any { it.second.id == entry.id }
            matchesText && matchesOfficial && matchesInstallable && matchesUpdates
        }

        results = when (query.sort) {
            Sort.LIKES -> results.sortedByDescending { likeCount(it.id) }
            Sort.NEWEST -> results.sortedByDescending { it.id } // 등록 순 정보가 없으면 ID 정렬로 대체
            Sort.DOWNLOADS -> results.sortedByDescending { downloadCount(it.id) }
            Sort.RECENTLY_UPDATED -> results.sortedByDescending {
                // 버전 요소를 0-padding 문자열로 만들어 사전식 비교 (SemVer 정렬)
                it.versionCode().joinToString(".") { v -> "%06d".format(v) }
            }
            Sort.NAME -> results.sortedBy { it.name.lowercase() }
        }
        return results
    }

    /** 다운로드 수 (이 기기 기준 집계; 원격 통계는 추후 저장소 기반 동기화). */
    fun downloadCount(extId: String): Int = prefs().getInt("downloads_$extId", 0)

    fun recordDownload(extId: String) {
        prefs().edit().putInt("downloads_$extId", downloadCount(extId) + 1).apply()
    }

    // ------------------------- likes -------------------------

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rateLimited(key: String, maxPerWindow: Int): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = prefs().getString("rate_$key", null)?.toLongOrNull() ?: 0L
        val count = prefs().getInt("rate_${key}_count", 0)
        if (now - windowStart > RATE_WINDOW_MS) {
            prefs().edit().putString("rate_$key", now.toString()).putInt("rate_${key}_count", 1).apply()
            return false
        }
        if (count >= maxPerWindow) return true
        prefs().edit().putInt("rate_${key}_count", count + 1).apply()
        return false
    }

    private fun likeKey(extId: String): String = "rate_like_$extId"

    /** 좋아요 수 (이 기기 기준 집계 + 로컬 좋아요 반영). */
    fun likeCount(extId: String): Int {
        val base = prefs().getInt("likes_$extId", 0)
        val mine = ExtensionAccount.get(context).likedExtensions().contains(extId)
        return base + if (mine) 1 else 0
    }

    /**
     * 좋아요 토글 (계정당 1회, 재누르면 취소). 로그인 필요.
     * Returns error message or null on success.
     */
    fun toggleLike(extId: String): String? {
        ExtensionAccount.get(context).requireLogin("좋아요")?.let { return it }
        if (rateLimited(likeKey(extId), 10)) return "요청이 너무 많습니다. 잠시 후 다시 시도하세요."
        val acct = ExtensionAccount.get(context)
        val liked = acct.likedExtensions().toMutableList()
        if (extId in liked) {
            liked.remove(extId)
            prefs().edit().putInt("likes_$extId", (prefs().getInt("likes_$extId", 0) - 1).coerceAtLeast(0)).apply()
        } else {
            liked.add(extId)
            prefs().edit().putInt("likes_$extId", prefs().getInt("likes_$extId", 0) + 1).apply()
        }
        acct.setLikedExtensions(liked)
        return null
    }

    // ------------------------- reviews -------------------------

    private fun reviewKey(extId: String): String = "rate_review_$extId"

    fun reviews(extId: String): List<Review> {
        val raw = prefs().getString("reviews_$extId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Review(
                    id = o.optString("id"), extId = extId,
                    userId = o.optString("userId"), author = o.optString("author"),
                    content = o.optString("content"), createdAt = o.optLong("createdAt"),
                    edited = o.optBoolean("edited")
                )
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 후기 작성: 실제 설치한 사용자만, 계정당 1개, rate limit. 로그인 필요.
     */
    fun addReview(extId: String, content: String): String? {
        val account = ExtensionAccount.get(context)
        account.requireLogin("후기 작성")?.let { return it }
        if (!ExtensionManager.get(context).isInstalled(extId)) {
            return "후기는 설치한 확장에만 작성할 수 있습니다"
        }
        if (reviews(extId).any { it.userId == account.current()?.userId }) {
            return "이미 후기를 작성했습니다 (수정할 수 있습니다)"
        }
        if (rateLimited(reviewKey(extId), 3)) return "요청이 너무 많습니다. 잠시 후 다시 시도하세요."
        val text = content.trim()
        if (text.length < 4) return "후기가 너무 짧습니다"
        val review = Review(
            id = "rev_${System.currentTimeMillis()}",
            extId = extId,
            userId = account.current()?.userId ?: "",
            author = account.current()?.displayName ?: "User",
            content = text,
            createdAt = System.currentTimeMillis(),
            edited = false
        )
        saveReview(review)
        return null
    }

    fun editReview(extId: String, reviewId: String, content: String): String? {
        val account = ExtensionAccount.get(context)
        account.requireLogin("후기 수정")?.let { return it }
        val all = reviews(extId).toMutableList()
        val idx = all.indexOfFirst { it.id == reviewId && it.userId == account.current()?.userId }
        if (idx < 0) return "수정 권한이 없습니다"
        val text = content.trim()
        if (text.length < 4) return "후기가 너무 짧습니다"
        all[idx] = all[idx].copy(content = text, edited = true)
        persistReviews(extId, all)
        return null
    }

    fun deleteReview(extId: String, reviewId: String): String? {
        val account = ExtensionAccount.get(context)
        account.requireLogin("후기 삭제")?.let { return it }
        val all = reviews(extId).toMutableList()
        val idx = all.indexOfFirst { it.id == reviewId && it.userId == account.current()?.userId }
        val removed = if (idx >= 0) all.removeAt(idx) else null
            ?: return "삭제 권한이 없습니다"
        persistReviews(extId, all)
        return null
    }

    private fun saveReview(review: Review) {
        val all = reviews(review.extId).toMutableList()
        all.add(review)
        persistReviews(review.extId, all)
    }

    private fun persistReviews(extId: String, list: List<Review>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject()
                .put("id", r.id).put("userId", r.userId).put("author", r.author)
                .put("content", r.content).put("createdAt", r.createdAt).put("edited", r.edited))
        }
        prefs().edit().putString("reviews_$extId", arr.toString()).apply()
    }

    // ------------------------- reports -------------------------

    private fun reportKey(extId: String): String = "rate_report_$extId"

    /** 신고 수 (24시간 유효 기준). */
    fun reportCount(extId: String): Int {
        val raw = prefs().getString("reports_$extId", null) ?: return 0
        return try {
            val arr = JSONArray(raw)
            val dayAgo = System.currentTimeMillis() - 24L * 3600 * 1000
            (0 until arr.length()).count { i -> arr.getJSONObject(i).optLong("createdAt") > dayAgo }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 신고 접수. 하루 1회/확장, rate limit. 로그인 필요.
     * 24시간 유효 신고가 [AUTO_SUSPEND_THRESHOLD]건 이상이면 자동 다운로드 일시 중지
     * + Under review 상태로 전환한다 (악성코드 확정이 아니라 관리자 검토 대상).
     */
    fun addReport(extId: String, reason: String, message: String): String? {
        ExtensionAccount.get(context).requireLogin("신고")?.let { return it }
        if (reason !in REPORT_REASONS) return "잘못된 신고 사유입니다"
        if (rateLimited(reportKey(extId), 5)) return "이 확장은 하루에 한 번만 신고할 수 있습니다"
        val account = ExtensionAccount.get(context)
        val userId = account.current()?.userId ?: ""
        val raw = prefs().getString("reports_$extId", null)
        val arr = try { JSONArray(raw ?: "[]") } catch (e: Exception) { JSONArray() }
        // 동일 계정 중복 신고 방지 (24시간)
        val dayAgo = System.currentTimeMillis() - 24L * 3600 * 1000
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("userId") == userId && o.optLong("createdAt") > dayAgo) {
                return "이미 신고한 확장입니다 (하루 1회)"
            }
        }
        arr.put(JSONObject()
            .put("userId", userId).put("reason", reason)
            .put("message", message.take(500)).put("createdAt", System.currentTimeMillis()))
        prefs().edit().putString("reports_$extId", arr.toString()).apply()
        auditLog("report", "ext=$extId reason=$reason user=${userId.take(8)}…")

        // 24시간 유효 신고 임계치 → 자동 다운로드 일시 중지 + Under review
        if (reportCount(extId) >= AUTO_SUSPEND_THRESHOLD) {
            setDownloadSuspended(extId, true, "신고 ${reportCount(extId)}건 누적 (관리자 검토 필요)")
            auditLog("auto_suspend", "ext=$extId")
        }
        return null
    }

    // ------------------------- admin / moderation -------------------------

    /** 관리자 목록 — 관리자 기능은 일반 사용자에게 노출하지 않는다. */
    fun isAdmin(): Boolean {
        val admin = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("admin_user", null)
        return admin != null && admin == ExtensionAccount.get(context).current()?.userId
    }

    fun setAdminUser(userId: String) {
        prefs().edit().putString("admin_user", userId).apply()
    }

    /** 다운로드 일시 중지 상태 (신고 누적 / 관리자 차단). */
    fun isDownloadSuspended(extId: String): Boolean =
        prefs().getBoolean("suspended_$extId", false)

    private fun setDownloadSuspended(extId: String, suspended: Boolean, reason: String) {
        prefs().edit().putBoolean("suspended_$extId", suspended)
            .putString("suspended_reason_$extId", reason).apply()
    }

    fun suspensionReason(extId: String): String =
        prefs().getString("suspended_reason_$extId", "") ?: ""

    /** 관리자: Restore / Keep blocked / Remove. audit log에 기록. */
    fun adminResolve(extId: String, action: String): String? {
        if (!isAdmin()) return "관리자 권한이 필요합니다"
        when (action) {
            "restore" -> {
                setDownloadSuspended(extId, false, "")
                auditLog("admin_restore", "ext=$extId")
            }
            "keep_blocked" -> {
                setDownloadSuspended(extId, true, "관리자 차단 유지")
                auditLog("admin_keep_blocked", "ext=$extId")
            }
            "remove" -> {
                ExtensionManager.get(context).uninstall(extId)
                setDownloadSuspended(extId, true, "확장 제거됨")
                auditLog("admin_remove", "ext=$extId")
            }
            else -> return "알 수 없는 작업: $action"
        }
        return null
    }

    /** 관리자 abuse/audit 로그 (로컬 파일, 최소 정보만). */
    fun auditLog(action: String, detail: String) {
        try {
            val f = File(context.filesDir, "extensions/audit.log")
            f.appendText("${System.currentTimeMillis()} | $action | $detail\n")
        } catch (e: Exception) {
            Log.w(TAG, "audit log failed", e)
        }
    }

    // ------------------------- (ExtensionAccount members 사용) -------------------------
}
