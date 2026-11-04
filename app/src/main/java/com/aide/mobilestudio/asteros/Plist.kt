package com.aide.mobilestudio.asteros

/**
 * IPA 안의 Info.plist를 읽기 위한 최소 plist 파서.
 *
 * 두 형식을 모두 다룬다:
 * - XML plist (일부 도구가 생성)
 * - binary plist "bplist00" (Xcode 기본 출력)
 *
 * 값은 String / Long / Double / Boolean / ByteArray / List / Map.
 * 해석할 수 없는 값은 null로 두고 지어내지 않는다.
 */
object Plist {

    private val BINARY_MAGIC = byteArrayOf(0x62, 0x70, 0x6C, 0x69, 0x73, 0x74, 0x30, 0x30) // "bplist00"

    /** 상위 dict를 반환한다. 해석 불가 시 null. */
    fun parse(bytes: ByteArray): Map<String, Any?>? {
        if (bytes.size >= BINARY_MAGIC.size &&
            BINARY_MAGIC.indices.all { bytes[it] == BINARY_MAGIC[it] }
        ) {
            return parseBinary(bytes)
        }
        return parseXml(bytes)
    }

    // ------------------------------------------------------------ binary --

    private fun parseBinary(b: ByteArray): Map<String, Any?>? {
        if (b.size < 40) return null
        val trailer = b.size - 32

        fun be(offset: Int, len: Int): Long {
            var v = 0L
            for (i in 0 until len) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
            return v
        }

        val offsetIntSize = b[trailer + 6].toInt() and 0xFF
        val objectRefSize = b[trailer + 7].toInt() and 0xFF
        val numObjects = be(trailer + 8, 8)
        val topObject = be(trailer + 16, 8)
        val offsetTableOffset = be(trailer + 24, 8)
        if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) return null
        if (numObjects <= 0 || numObjects > 100_000) return null
        if (offsetTableOffset < 0 || offsetTableOffset + numObjects * offsetIntSize > b.size - 32L) return null

        fun offsetOf(idx: Long): Int = be((offsetTableOffset + idx * offsetIntSize).toInt(), offsetIntSize).toInt()

        val cache = HashMap<Long, Any?>()
        val visiting = HashSet<Long>()

        fun readRef(at: Int): Long {
            if (at < 0 || at + objectRefSize > b.size) return -1
            return be(at, objectRefSize)
        }

        fun readObject(idx: Long, depth: Int): Any? {
            if (idx < 0 || idx >= numObjects || depth > 24) return null
            cache[idx]?.let { return it }
            if (!visiting.add(idx)) return null // 순환 참조 방지
            val off = offsetOf(idx)
            if (off < 0 || off >= b.size - 32) return null
            val marker = b[off].toInt() and 0xFF
            val type = marker shr 4
            val rawLow = marker and 0xF
            var pos = off + 1
            var size = rawLow.toLong()
            if (rawLow == 0xF) {
                if (pos >= b.size) return null
                val szMarker = b[pos].toInt() and 0xFF
                if (szMarker shr 4 != 0x1) return null
                val intLen = 1 shl (szMarker and 0xF)
                if (intLen !in 1..8 || pos + 1 + intLen > b.size) return null
                size = be(pos + 1, intLen)
                pos += 1 + intLen
            }
            if (size < 0) return null

            val result: Any? = when (type) {
                0x0 -> when (rawLow) {
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }
                0x1 -> when (size) {
                    1L -> if (pos + 1 <= b.size) (b[pos].toLong()) else null
                    2L -> if (pos + 2 <= b.size) be(pos, 2).toShort().toLong() else null
                    4L -> if (pos + 4 <= b.size) be(pos, 4).toInt().toLong() else null
                    8L -> if (pos + 8 <= b.size) be(pos, 8) else null
                    else -> null
                }
                0x2 -> when (size) {
                    4L -> if (pos + 4 <= b.size) Float.fromBits(be(pos, 4).toInt()).toDouble() else null
                    8L -> if (pos + 8 <= b.size) java.lang.Double.longBitsToDouble(be(pos, 8)) else null
                    else -> null
                }
                0x4 -> if (pos + size <= b.size) b.copyOfRange(pos, (pos + size).toInt()) else null
                0x5 -> if (pos + size <= b.size) String(b, pos, size.toInt(), Charsets.US_ASCII) else null
                0x6 -> {
                    if (pos + size * 2 <= b.size) {
                        val chars = CharArray(size.toInt())
                        for (i in chars.indices) chars[i] = be(pos + i * 2, 2).toInt().toChar()
                        String(chars)
                    } else null
                }
                0x8, 0x9 -> { // array / set
                    val items = ArrayList<Any?>(size.toInt().coerceAtMost(10_000))
                    var ok = true
                    for (i in 0 until size) {
                        val ref = readRef(pos + (i * objectRefSize).toInt())
                        if (ref < 0) { ok = false; break }
                        items.add(readObject(ref, depth + 1))
                    }
                    if (ok) items else null
                }
                0xA -> { // dict: 키 refs 뒤에 값 refs
                    val count = size.toInt()
                    val map = LinkedHashMap<String, Any?>()
                    var ok = true
                    for (i in 0 until count) {
                        val keyRef = readRef(pos + (i * objectRefSize).toInt())
                        val valRef = readRef(pos + ((count + i) * objectRefSize).toInt())
                        if (keyRef < 0 || valRef < 0) { ok = false; break }
                        val key = readObject(keyRef, depth + 1) as? String ?: continue
                        map[key] = readObject(valRef, depth + 1)
                    }
                    if (ok) map else null
                }
                else -> null // 알 수 없는 타입: 건너뛸 방법이 없으므로 null
            }
            cache[idx] = result
            return result
        }

        val top = readObject(topObject, 0)
        @Suppress("UNCHECKED_CAST")
        return top as? Map<String, Any?>
    }

    // ---------------------------------------------------------------- xml --

    private val KEY_REGEX = Regex("""<key>\s*([^<]*?)\s*</key>""")
    private val STRING_REGEX = Regex("""<string>\s*([^<]*?)\s*</string>""")
    private val INTEGER_REGEX = Regex("""<integer>\s*(-?\d+)\s*</integer>""")
    private val REAL_REGEX = Regex("""<real>\s*(-?[\d.eE+]+)\s*</real>""")
    private val ARRAY_STRING_REGEX = Regex("""<string>\s*([^<]*?)\s*</string>""")

    /**
     * Info.plist의 상위 dict만 해석한다.
     *
     * 완전한 XML 파서가 아니라 Info.plist 형태(dict + key/스칼라/문자열 배열)에
     * 한해 동작한다. 그 외 구조는 null로 두어 오해석하지 않는다.
     */
    private fun parseXml(bytes: ByteArray): Map<String, Any?>? {
        val text = String(bytes, Charsets.UTF_8)
        val dictIdx = text.indexOf("<dict>")
        if (dictIdx < 0) return null
        val body = text.substring(dictIdx + "<dict>".length)
        val map = LinkedHashMap<String, Any?>()
        var matched = false
        for (m in KEY_REGEX.findAll(body)) {
            val name = m.groupValues[1]
            val after = m.range.last + 1
            val limit = listOf(body.indexOf("<key>", after), body.indexOf("</dict>", after))
                .filter { it >= 0 }
                .minOrNull() ?: body.length
            if (limit <= after) continue
            val segment = body.substring(after, minOf(limit, body.length))
            map[name] = xmlValue(segment)
            matched = true
        }
        return if (matched) map else null
    }

    private fun xmlValue(segment: String): Any? {
        // 배열을 먼저 본다: <array> 안의 <integer>가 스칼라로 오해되기 쉽다.
        val arrayStart = segment.indexOf("<array>")
        val arrayEnd = segment.indexOf("</array>")
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            val inner = segment.substring(arrayStart + "<array>".length, arrayEnd)
            val items = ARRAY_STRING_REGEX.findAll(inner).map { it.groupValues[1] }.toList()
            if (items.isNotEmpty()) return items
            // 정수 배열 (UIDeviceFamily 등)
            return INTEGER_REGEX.findAll(inner).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
                .takeIf { it.isNotEmpty() }
        }
        STRING_REGEX.find(segment)?.let { return it.groupValues[1] }
        INTEGER_REGEX.find(segment)?.let { return it.groupValues[1].toLongOrNull() }
        REAL_REGEX.find(segment)?.let { return it.groupValues[1].toDoubleOrNull() }
        if (Regex("""<true\s*/>""").containsMatchIn(segment)) return true
        if (Regex("""<false\s*/>""").containsMatchIn(segment)) return false
        return null
    }
}
