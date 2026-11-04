package com.aide.mobilestudio.ai.model

/**
 * GGUF quantisation families this app understands.
 *
 * The family is only ever a *label for a real value*: it comes from a file
 * name the server actually returned, or from the `general.file_type` inside a
 * downloaded file. It is never invented from a size guess.
 */
enum class QuantFamily(val label: String, val bytesPerWeight: Double) {
    Q2("Q2", 0.35),
    Q3("Q3", 0.45),
    Q4("Q4", 0.60),
    Q5("Q5", 0.72),
    Q6("Q6", 0.85),
    Q8("Q8", 1.05),
    OTHER("기타", 0.70),
    ;

    /** Q4/Q5/Q6 are the families worth offering for a phone download. */
    val recommended: Boolean get() = this == Q4 || this == Q5 || this == Q6
}

/** Where a parameter count came from. The UI must show the difference. */
enum class ParamsSource {
    /** Declared by the model provider (safetensors totals, config). */
    DECLARED,

    /** Parsed from the repository name, e.g. "…-7B-…". Shown with an ≈. */
    NAME_INFERRED,

    /** Nowhere. The value is null, not a guess. */
    UNKNOWN,
}

/** Size bands for filtering a catalogue. Bounds are exclusive-high in billions. */
enum class ParamBand(val label: String, val lowBillions: Double, val highBillions: Double) {
    B0_5_1("500M – 1B", 0.0, 1.0),
    B1_3("1B – 3B", 1.0, 3.0),
    B3_5("3B – 5B", 3.0, 5.0),
    B5_8("5B – 8B", 5.0, 8.0),
    B8_10("8B – 10B", 8.0, 10.0),
    ;

    fun contains(params: Long?): Boolean {
        if (params == null) return false
        val b = params / 1_000_000_000.0
        if (b <= 0.0) return false
        if (b >= highBillions) return false
        return b >= lowBillions
    }

    companion object {
        fun of(params: Long?): ParamBand? = entries.firstOrNull { it.contains(params) }
    }
}

/** A memory estimate. [estimated] marks the parts that are inference, not facts. */
data class MemoryEstimate(
    /** Weights only: params × bytes-per-weight. */
    val weightBytes: Long,
    /** KV cache for [contextTokens], or null when the geometry is unknown. */
    val kvCacheBytes: Long?,
    val contextTokens: Int,
    /** True because every number here is derived, never read from the file. */
    val estimated: Boolean = true,
) {
    val totalBytes: Long get() = weightBytes + (kvCacheBytes ?: 0L)
}

/**
 * Pure helpers turning real file names and GGUF headers into quant labels and
 * parameter counts. Everything here is deterministic and unit-testable.
 */
object Quant {

    /** Matches a quant token like `Q4_K_M`, `Q5_0`, `IQ4_XS`, `F16`, `BF16`. */
    private val EXACT = Regex("""(?i)\b((?:i?q[2-8]|f16|bf16|f32)(?:_[a-z0-9]+)*)\b""")

    /** Matches a parameter size token like `1.5B`, `7b`, `8B`. */
    private val PARAMS = Regex("""(?i)(?:^|[^0-9a-z.])([0-9]+(?:\.[0-9]+)?)\s*b(?![0-9a-z])""")

    /** `qwen2.5-coder-1.5b-instruct-q4_k_m.gguf` → `Q4_K_M`. Null when absent. */
    fun exactOfFileName(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.', fileName)
        val match = EXACT.find(stem) ?: return null
        return match.groupValues[1].uppercase()
    }

    /** `…-Q5_K_M.gguf` → [QuantFamily.Q5]. Unknown tokens land in [QuantFamily.OTHER]. */
    fun familyOfFileName(fileName: String): QuantFamily? {
        val exact = exactOfFileName(fileName) ?: return null
        return familyOf(exact)
    }

    /** `Q6_K` → [QuantFamily.Q6]; `IQ4_XS` counts as Q4; F16/BF16 → OTHER. */
    fun familyOf(key: String?): QuantFamily? {
        val token = key?.trim()?.uppercase() ?: return null
        if (token.isEmpty()) return null
        val digits = token.removePrefix("I").removePrefix("Q").takeWhile { it.isDigit() }
        return when (digits) {
            "2" -> QuantFamily.Q2
            "3" -> QuantFamily.Q3
            "4" -> QuantFamily.Q4
            "5" -> QuantFamily.Q5
            "6" -> QuantFamily.Q6
            "8" -> QuantFamily.Q8
            else -> QuantFamily.OTHER
        }
    }

    /**
     * GGML file type ordinals from `general.file_type` (GGUF `LlamaFileType`):
     * 0=F32 1=F16 2=Q4_0 3=Q4_1 4=Q4_1_F16 7=Q8_0 8=Q5_0 9=Q5_1 10=Q2_K
     * 11=Q3_K_S 12=Q3_K_M 13=Q3_K_L 14=Q4_K_S 15=Q4_K_M 16=Q5_K_S 17=Q5_K_M
     * 18=Q6_K 19=IQ2_XXS … 22=IQ3_XXS 23=IQ1_S 24=IQ4_NL 25=IQ3_S 26=IQ2_S
     * 27=IQ4_XS 28=IQ1_M 29=BF16
     *
     * Returns null for ordinals this table cannot name — a missing value is
     * better than a wrong one.
     */
    fun familyOfGgmlType(fileType: Int?): QuantFamily? = when (fileType) {
        2, 3, 4 -> QuantFamily.Q4
        7 -> QuantFamily.Q8
        8, 9 -> QuantFamily.Q5
        10 -> QuantFamily.Q2
        11, 12, 13, 22, 25, 26 -> QuantFamily.Q3
        14, 15, 24, 27 -> QuantFamily.Q4
        16, 17 -> QuantFamily.Q5
        18 -> QuantFamily.Q6
        23, 28 -> QuantFamily.Q2
        29 -> QuantFamily.OTHER
        0, 1, 5, 6 -> QuantFamily.OTHER
        else -> null
    }

    /**
     * Parses a parameter count from a name like `Meta-Llama-3.1-8B-Instruct` or
     * `Qwen2.5-Coder-1.5B`. Version numbers are avoided by taking the largest
     * `Nb`/`N.Mb` token, which is how model names are actually written.
     */
    fun paramsFromName(name: String): Long? {
        var best: Double? = null
        for (match in PARAMS.findAll(name)) {
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            if (value <= 0.0 || value > 1000.0) continue
            if (best == null || value > best!!) best = value
        }
        val billions = best ?: return null
        return (billions * 1_000_000_000.0).toLong()
    }

    /**
     * Rough resident-memory estimate: weights at [family]'s bytes-per-weight
     * plus a KV cache when the geometry is known. Always [MemoryEstimate.estimated].
     */
    fun estimateMemoryBytes(
        params: Long?,
        family: QuantFamily,
        contextTokens: Int,
        kvBytesPerToken: Long? = null,
    ): MemoryEstimate? {
        if (params == null || params <= 0) return null
        val weights = (params * family.bytesPerWeight).toLong()
        val kv = if (kvBytesPerToken != null && contextTokens > 0) {
            kvBytesPerToken * contextTokens
        } else {
            null
        }
        return MemoryEstimate(weights, kv, contextTokens)
    }
}
