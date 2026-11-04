package com.aide.mobilestudio.ai.model

import com.aide.mobilestudio.ai.GgufInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Why a download was asked to stop. Distinct from cancellation of the job. */
enum class StopReason { RUNNING, PAUSE, CANCEL }

/** Polled by the engine between chunks; the manager owns the flag. */
fun interface StopSignal {
    fun reason(): StopReason
}

/** What a ranged GET returned. Implemented for real and by fakes in tests. */
data class FetchResponse(
    val status: Int,
    /** Size of the *whole* file when the server said so, else -1. */
    val totalBytes: Long = -1L,
    /** True when the server answered `206` and honoured our Range offset. */
    val resumed: Boolean = false,
    val stream: InputStream? = null,
) {
    val isSuccess: Boolean get() = status == 200 || status == 206
}

/**
 * Opens a URL for reading at a byte offset. Injected so the engine can be
 * tested against a fake server: resume, no-resume, mid-flight failures and
 * size mismatches are all scripted from tests instead of the internet.
 */
fun interface HttpFetcher {
    /** @param offset byte to start at; 0 for a fresh request. */
    fun open(url: String, offset: Long): FetchResponse
}

/** Why a download did not complete. Each maps to a user-visible sentence. */
enum class DownloadFailure {
    NETWORK,
    HTTP,
    STORAGE_FULL,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    INVALID_GGUF,
    QUANT_MISMATCH,
    UNSAFE_URL,
    UNSAFE_NAME,
    DUPLICATE,
    ALREADY_INSTALLED,
}

/**
 * One file of a model download.
 *
 * Most GGUF models are a single file. A split export is several files that
 * llama.cpp loads as one model, so the downloader treats the set as one task:
 * every shard is verified and placed next to the others before the model is
 * registered.
 */
data class DownloadPart(
    val url: String,
    /** Final path *after* verification. The partial file is `name.part`. */
    val destination: File,
    /** Exact size the server reported; -1 when unknown. */
    val expectedBytes: Long = -1L,
    /** SHA-256 the server reported (LFS oid); null when unknown. */
    val expectedSha256: String? = null,
)

/** Everything needed to fetch one model. Nothing here is inferred at runtime. */
data class DownloadSpec(
    /** Stable id (normally [ModelEntry.uid]) used for de-duplication. */
    val id: String,
    /**
     * First part's URL. Kept as a separate field because existing saved tasks
     * and callers predate split downloads; [resolvedParts] is the full truth.
     */
    val url: String,
    /** First part's final path. The partial file is `name.part`. */
    val destination: File,
    /** Total size of every part; -1 when unknown. */
    val expectedBytes: Long = -1L,
    /** First part's checksum; null when unknown. */
    val expectedSha256: String? = null,
    /** Quant family parsed from the real file name; null when unknown. */
    val expectedQuantFamily: QuantFamily? = null,
    val displayName: String = "",
    /** Shards of a split model, in order. Empty means a single-file model. */
    val parts: List<DownloadPart> = emptyList(),
) {
    /** Every file this task owns, including the legacy single-file fallback. */
    val resolvedParts: List<DownloadPart>
        get() = parts.ifEmpty {
            listOf(DownloadPart(url, destination, expectedBytes, expectedSha256))
        }
}

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    /** Only measured over windows of at least a second; null before that. */
    val bytesPerSecond: Long? = null,
    val etaMs: Long? = null,
    val verifying: Boolean = false,
)

sealed class DownloadOutcome {
    data class Completed(
        val file: File,
        val bytes: Long,
        val sha256: String,
        /** True only when the checksum was provided *and* matched. */
        val checksumVerified: Boolean,
        /** From the file's own `general.file_type` when it declared one. */
        val quant: QuantFamily?,
    ) : DownloadOutcome()

    /** User paused; the `.part` file is kept for a ranged resume. */
    data class Paused(val bytes: Long) : DownloadOutcome()

    /** User cancelled; the `.part` file is removed. */
    data class Cancelled(val bytes: Long) : DownloadOutcome()

    data class Failed(val failure: DownloadFailure, val message: String) : DownloadOutcome()
}

/** One file's result inside a possibly multi-file download. */
private sealed class PartOutcome {
    data class Completed(
        val file: File,
        val bytes: Long,
        val sha256: String,
        val checksumVerified: Boolean,
        val quant: QuantFamily?,
    ) : PartOutcome()

    data class Paused(val bytes: Long) : PartOutcome()
    data class Cancelled(val bytes: Long) : PartOutcome()
    data class Failed(val failure: DownloadFailure, val message: String) : PartOutcome()
}

/**
 * The download core: stream to `.part`, resume with HTTP Range when the server
 * supports it, verify size + SHA-256 + GGUF metadata, then rename atomically.
 *
 * Android-free by construction: the fetcher, the free-space probe and the GGUF
 * reader are injected, so every branch is exercised by JVM unit tests. All
 * credentials are irrelevant here — the URL only ever points at a public
 * Hugging Face file, checked against the allow-list.
 */
class DownloadEngine(
    private val fetcher: HttpFetcher,
    private val freeSpaceBytes: () -> Long,
    private val ggufReader: (File) -> GgufInfo.Info? = { GgufInfo.read(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val urlAllowed: (String) -> Boolean = { HuggingFaceApi.isAllowedDownloadUrl(it) },
) {

    private val running = ConcurrentHashMap.newKeySet<String>()

    /** True while [download] holds the id; used by the UI for de-dup. */
    fun isRunning(id: String): Boolean = id in running

    suspend fun download(
        spec: DownloadSpec,
        stop: StopSignal,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutcome {
        if (!running.add(spec.id)) {
            return DownloadOutcome.Failed(DownloadFailure.DUPLICATE, "이미 진행 중인 다운로드입니다")
        }
        try {
            return runDownload(spec, stop, onProgress)
        } finally {
            running.remove(spec.id)
        }
    }

    private fun runDownload(
        spec: DownloadSpec,
        stop: StopSignal,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutcome {
        val parts = spec.resolvedParts
        if (parts.isEmpty()) {
            return DownloadOutcome.Failed(DownloadFailure.NETWORK, "다운로드할 파일이 없습니다")
        }
        // Validate the whole set before touching the network: a split model is
        // only usable when every shard passes the same checks.
        for (part in parts) {
            if (!part.url.startsWith("https://") || !urlAllowed(part.url)) {
                return DownloadOutcome.Failed(DownloadFailure.UNSAFE_URL, "허용되지 않은 다운로드 주소입니다")
            }
            if (!HuggingFaceApi.isSafeFileName(part.destination.name)) {
                return DownloadOutcome.Failed(DownloadFailure.UNSAFE_NAME, "안전하지 않은 파일 이름입니다")
            }
            part.destination.parentFile?.mkdirs()
        }
        if (parts.map { it.destination.absolutePath }.distinct().size != parts.size) {
            return DownloadOutcome.Failed(DownloadFailure.UNSAFE_NAME, "다운로드 파일 이름이 서로 겹칩니다")
        }

        val expectedTotal = when {
            spec.expectedBytes > 0 -> spec.expectedBytes
            parts.all { it.expectedBytes > 0 } -> parts.sumOf { it.expectedBytes }
            else -> -1L
        }
        val alreadyComplete = parts.count { part ->
            part.destination.isFile &&
                part.expectedBytes > 0 &&
                part.destination.length() == part.expectedBytes
        }
        if (alreadyComplete == parts.size) {
            return DownloadOutcome.Failed(DownloadFailure.ALREADY_INSTALLED, "이미 설치된 파일입니다")
        }

        var needed = 0L
        for (part in parts) {
            if (part.destination.isFile && part.expectedBytes > 0 &&
                part.destination.length() == part.expectedBytes
            ) continue
            val partial = partFile(part)
            val offset = if (partial.isFile) partial.length().coerceAtMost(part.expectedBytes.coerceAtLeast(0)) else 0L
            needed += if (part.expectedBytes > 0) {
                (part.expectedBytes - offset).coerceAtLeast(0L)
            } else {
                MIN_FREE_MARGIN
            }
        }
        if (freeSpaceBytes() < needed + MIN_FREE_MARGIN) {
            return DownloadOutcome.Failed(
                DownloadFailure.STORAGE_FULL,
                "저장 공간이 부족합니다. 필요한 공간: 약 ${humanBytes(needed + MIN_FREE_MARGIN)}",
            )
        }

        var completedBytes = 0L
        var completedFiles = 0
        var firstSha: String? = null
        var allChecksumVerified = parts.all { it.expectedSha256 != null }
        var quant: QuantFamily? = null
        val created = mutableListOf<File>()

        for ((index, part) in parts.withIndex()) {
            // A shard that finished on an earlier attempt is not downloaded
            // again; its bytes still count toward the task's progress.
            if (part.destination.isFile && part.expectedBytes > 0 &&
                part.destination.length() == part.expectedBytes
            ) {
                completedBytes += part.destination.length()
                completedFiles++
                if (index == 0) {
                    firstSha = null
                    allChecksumVerified = false
                    quant = runCatching { ggufReader(part.destination) }
                        .getOrNull()?.let { Quant.familyOfGgmlType(it.fileType) }
                }
                continue
            }

            val baseBytes = completedBytes
            val result = downloadPart(part, spec.expectedQuantFamily, stop) { written, totalPart, speed, eta, verifying ->
                val total = if (expectedTotal > 0) expectedTotal else baseBytes + totalPart
                onProgress(
                    DownloadProgress(
                        downloadedBytes = baseBytes + written,
                        totalBytes = total,
                        bytesPerSecond = speed,
                        etaMs = eta,
                        verifying = verifying,
                    ),
                )
            }
            when (result) {
                is PartOutcome.Completed -> {
                    completedBytes += result.bytes
                    completedFiles++
                    created.add(result.file)
                    if (index == 0) {
                        firstSha = result.sha256
                        quant = result.quant
                    }
                    if (!result.checksumVerified) allChecksumVerified = false
                }
                is PartOutcome.Paused -> {
                    onProgress(DownloadProgress(completedBytes + result.bytes, expectedTotal))
                    return DownloadOutcome.Paused(completedBytes + result.bytes)
                }
                is PartOutcome.Cancelled -> {
                    // Cancelling means the user does not want the model. Remove
                    // partials and shards this task created, but never a file
                    // that was already present before the task began.
                    for (file in created) runCatching { file.delete() }
                    for (p in parts) runCatching { partFile(p).delete() }
                    return DownloadOutcome.Cancelled(completedBytes + result.bytes)
                }
                is PartOutcome.Failed -> return DownloadOutcome.Failed(result.failure, result.message)
            }
        }

        val first = parts.first().destination
        if (quant == null) {
            quant = runCatching { ggufReader(first) }.getOrNull()?.let { Quant.familyOfGgmlType(it.fileType) }
        }
        return DownloadOutcome.Completed(
            file = first,
            bytes = completedBytes,
            sha256 = firstSha ?: "",
            checksumVerified = allChecksumVerified && completedFiles == parts.size,
            quant = quant,
        )
    }

    private fun partFile(part: DownloadPart): File =
        File(part.destination.parentFile, part.destination.name + ".part")

    /** Downloads and verifies one file. The caller owns the multi-file loop. */
    private fun downloadPart(
        partSpec: DownloadPart,
        expectedQuantFamily: QuantFamily?,
        stop: StopSignal,
        onProgress: (Long, Long, Long?, Long?, Boolean) -> Unit,
    ): PartOutcome {
        val destination = partSpec.destination
        val url = partSpec.url
        val expectedBytes = partSpec.expectedBytes
        val expectedSha256 = partSpec.expectedSha256
        val part = partFile(partSpec)
        var offset = if (part.isFile) part.length() else 0L
        if (offset > 0 && expectedBytes > 0 && offset >= expectedBytes) {
            // A stale full-size part: verify it instead of re-downloading.
            offset = 0
            part.delete()
        }

        var response = fetcher.open(url, offset)
        if (!response.isSuccess) {
            response.stream?.close()
            return PartOutcome.Failed(DownloadFailure.HTTP, "다운로드 실패 (HTTP ${response.status})")
        }
        if (offset > 0 && !response.resumed) {
            // The server ignored Range (answered 200 with the whole file) or
            // resumed something else. Restart from zero with this same body.
            part.delete()
            offset = 0L
        }
        val stream = response.stream
            ?: return PartOutcome.Failed(DownloadFailure.NETWORK, "응답 스트림이 없습니다")
        val totalBytes = when {
            response.totalBytes > 0 -> response.totalBytes
            expectedBytes > 0 -> expectedBytes
            else -> -1L
        }
        val resumed = response.resumed && offset > 0

        val digest = MessageDigest.getInstance("SHA-256")
        if (resumed) {
            // Hash the bytes already on disk so the checksum is of the whole file.
            try {
                FileInputStream(part).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                part.delete()
                return PartOutcome.Failed(DownloadFailure.NETWORK, "이어받기 파일을 읽지 못했습니다")
            }
        }

        var written = offset
        var lastReportAt = 0L
        var windowStart = nowMs()
        var windowBytes = written
        var speed: Long? = null
        var eta: Long? = null
        var nextSpaceCheck = written + SPACE_CHECK_INTERVAL_BYTES

        val output = try {
            FileOutputStream(part, resumed)
        } catch (e: Exception) {
            stream.close()
            return PartOutcome.Failed(
                DownloadFailure.STORAGE_FULL,
                "파일을 열 수 없습니다: ${e.message ?: "알 수 없는 오류"}",
            )
        }

        try {
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                when (stop.reason()) {
                    StopReason.PAUSE -> {
                        onProgress(written, totalBytes, speed, eta, false)
                        return PartOutcome.Paused(written)
                    }
                    StopReason.CANCEL -> {
                        // Cancelling means the user does not want the file:
                        // drop the partial so disk space is returned at once.
                        part.delete()
                        return PartOutcome.Cancelled(written)
                    }
                    StopReason.RUNNING -> Unit
                }
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                written += read

                val now = nowMs()
                if (now - lastReportAt >= PROGRESS_INTERVAL_MS) {
                    val elapsed = now - windowStart
                    if (elapsed >= 1_000) {
                        val measured = (written - windowBytes) * 1_000 / elapsed
                        speed = measured
                        eta = if (totalBytes > 0 && measured > 0) {
                            (totalBytes - written) * 1_000 / measured
                        } else {
                            null
                        }
                        windowStart = now
                        windowBytes = written
                    }
                    onProgress(written, totalBytes, speed, eta, false)
                    lastReportAt = now
                }
                if (written >= nextSpaceCheck) {
                    nextSpaceCheck = written + SPACE_CHECK_INTERVAL_BYTES
                    if (freeSpaceBytes() < MIN_FREE_MARGIN) {
                        return PartOutcome.Failed(
                            DownloadFailure.STORAGE_FULL,
                            "저장 공간이 부족해 다운로드를 멈췄습니다. 이어받기로 재개할 수 있습니다.",
                        )
                    }
                }
            }
            output.flush()
        } catch (e: Exception) {
            // Network/disk failure: keep the part file so a resume can finish it.
            return PartOutcome.Failed(
                DownloadFailure.NETWORK,
                "다운로드 중 연결이 끊겼습니다: ${e.message ?: e.javaClass.simpleName}",
            )
        } finally {
            runCatching { output.close() }
            runCatching { stream.close() }
        }

        if (totalBytes > 0 && written != totalBytes) {
            if (written > totalBytes) part.delete()
            return PartOutcome.Failed(
                DownloadFailure.SIZE_MISMATCH,
                "크기가 다릅니다: 받은 ${humanBytes(written)} / 서버 ${humanBytes(totalBytes)}",
            )
        }
        if (expectedBytes > 0 && written != expectedBytes) {
            if (written > expectedBytes) part.delete()
            return PartOutcome.Failed(
                DownloadFailure.SIZE_MISMATCH,
                "크기가 다릅니다: 받은 ${humanBytes(written)} / 예상 ${humanBytes(expectedBytes)}",
            )
        }

        val sha256 = hex(digest.digest())
        val checksumVerified = expectedSha256 != null
        if (checksumVerified && !sha256.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            return PartOutcome.Failed(
                DownloadFailure.CHECKSUM_MISMATCH,
                "무결성 검사 실패: SHA-256이 서버 값과 다릅니다",
            )
        }

        onProgress(written, totalBytes, speed, 0L, true)
        val info = try {
            ggufReader(part)
        } catch (e: Exception) {
            null
        }
        if (info == null) {
            part.delete()
            return PartOutcome.Failed(DownloadFailure.INVALID_GGUF, "GGUF 파일이 아닙니다")
        }
        val fileFamily = Quant.familyOfGgmlType(info.fileType)
        if (expectedQuantFamily != null && fileFamily != null && fileFamily != expectedQuantFamily) {
            part.delete()
            return PartOutcome.Failed(
                DownloadFailure.QUANT_MISMATCH,
                "양자화 불일치: 파일은 ${fileFamily.label}, 파일 이름은 ${expectedQuantFamily.label}",
            )
        }

        if (destination.exists() && !destination.delete()) {
            return PartOutcome.Failed(DownloadFailure.NETWORK, "기존 파일을 지우지 못했습니다")
        }
        if (!part.renameTo(destination)) {
            // Fall back to a copy when the filesystem refuses a rename (rare).
            return try {
                part.copyTo(destination, overwrite = true)
                part.delete()
                PartOutcome.Completed(destination, written, sha256, checksumVerified, fileFamily)
            } catch (e: Exception) {
                PartOutcome.Failed(DownloadFailure.NETWORK, "파일을 저장하지 못했습니다: ${e.message ?: ""}")
            }
        }
        return PartOutcome.Completed(destination, written, sha256, checksumVerified, fileFamily)
    }

    companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
        /** Space that must remain untouched beyond the file itself. */
        const val MIN_FREE_MARGIN = 256L * 1024 * 1024
        const val SPACE_CHECK_INTERVAL_BYTES = 16L * 1024 * 1024

        fun humanBytes(bytes: Long): String = when {
            bytes < 0 -> "?"
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }

        fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append("0123456789abcdef"[v ushr 4])
                out.append("0123456789abcdef"[v and 0x0F])
            }
            return out.toString()
        }
    }
}
