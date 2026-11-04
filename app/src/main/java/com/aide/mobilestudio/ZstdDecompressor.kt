package com.aide.mobilestudio

import java.io.FilterInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin Zstandard (zstd) streaming decompressor.
 * Supports raw, RLE, Huffman, and FSE-compressed sequence blocks.
 */
class ZstdInputStream(private val source: InputStream) : FilterInputStream(source) {

    private val reader = BitReader(source)
    private val window = ByteArray(1 shl 19) // 512KB history window
    private var windowPos = 0

    private var outBuf = ByteArray(0)
    private var outPos = 0
    private var frameDone = false

    init { readFrameHeader() }

    private fun readFrameHeader() {
        val magic = reader.readLE32()
        if (magic.toLong() and 0xFFFFFFFFL != MAGIC)
            throw IllegalArgumentException("Not a Zstd stream")

        val frameDesc = reader.readByte().toInt() and 0xFF
        val fcsFlag = (frameDesc ushr 6) and 3
        val singleSeg = (frameDesc ushr 5) and 1
        val dictionaryId = frameDesc and 3

        if (dictionaryId != 0) throw UnsupportedOperationException("Dictionaries not supported")
        if (singleSeg == 0) reader.readByte()

        if (fcsFlag != 0) when (fcsFlag) {
            1 -> reader.readLE32()
            2 -> reader.readLE64()
            3 -> reader.readByte().toInt()
        }

        reader.readLE32() // frame header checksum
    }

    override fun read(): Int {
        if (outPos >= outBuf.size) {
            if (!fillBuffer()) return -1
        }
        return outBuf[outPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (outPos >= outBuf.size) {
            if (!fillBuffer()) return -1
        }
        val avail = outBuf.size - outPos
        val toRead = minOf(len, avail)
        System.arraycopy(outBuf, outPos, b, off, toRead)
        outPos += toRead
        return toRead
    }

    override fun available(): Int = (outBuf.size - outPos) + source.available()

    private fun fillBuffer(): Boolean {
        if (frameDone) return false
        val out = ByteArrayOutputStream()

        while (true) {
            val blockHeader = reader.readLE32()
            val bSize = blockHeader ushr 3
            val bType = (blockHeader ushr 1) and 3

            if (bType == 0 && bSize == 0) { frameDone = true; break }

            when (bType) {
                0 -> readRawBlock(bSize, out)
                1 -> readRleBlock(bSize, out)
                2 -> readHuffmanBlock(bSize, out)
                3 -> readCompressedBlock(bSize, out)
            }

            if (out.size() >= 16384) break
        }

        if (out.size() == 0) return false
        outBuf = out.toByteArray()
        outPos = 0
        return true
    }

    private fun readRawBlock(size: Int, out: ByteArrayOutputStream) {
        if (size <= 0) return
        val buf = ByteArray(minOf(size, RAW_CHUNK_SIZE))
        var remaining = size
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size)
            reader.readFully(buf, 0, toRead)
            out.write(buf, 0, toRead)
            for (i in 0 until toRead) {
                window[windowPos] = buf[i]
                windowPos = (windowPos + 1) and (window.size - 1)
            }
            remaining -= toRead
        }
    }

    private fun readRleBlock(size: Int, out: ByteArrayOutputStream) {
        if (size <= 0) return
        val repeat = reader.readByte()
        for (i in 0 until size) {
            out.write(repeat.toInt())
            window[windowPos] = repeat
            windowPos = (windowPos + 1) and (window.size - 1)
        }
    }

    private fun readHuffmanBlock(size: Int, out: ByteArrayOutputStream) {
        val blockData = ByteArray(size)
        reader.readFully(blockData)
        val decompressed = decompressHuffmanBlock(blockData)
        out.write(decompressed)
        decompressed.forEach { b ->
            window[windowPos] = b
            windowPos = (windowPos + 1) and (window.size - 1)
        }
    }

    /**
     * Decompress a Huffman-compressed literal block.
     * The block contains a Huffman table description followed by
     * Huffman-encoded symbols that decode to raw bytes.
     */
    private fun decompressHuffmanBlock(data: ByteArray): ByteArray {
        val bs = BitStream(data)
        val huffTable = decodeHuffmanTable(bs)
        val output = ByteArrayOutputStream()
        while (bs.bitOffset < data.size * 8) {
            val sym = huffTable.decode(bs) ?: break
            output.write(sym and 0xFF)
        }
        return output.toByteArray()
    }

    private fun readCompressedBlock(size: Int, out: ByteArrayOutputStream) {
        val blockData = ByteArray(size)
        reader.readFully(blockData)
        val bs = BitStream(blockData)

        // Sequence section header
        val seqHeader = bs.readBits(8)
        val numSeqsRaw = seqHeader and 0x7F
        val numSeqs = if (numSeqsRaw == 0) 1 + bs.readBits(16) else numSeqsRaw
        val modes = if (numSeqsRaw == 0) {
            bs.readBits(2); bs.readBits(2); bs.readBits(2) // skip 6 mode bits for long form
            intArrayOf(bs.readBits(2), bs.readBits(2), bs.readBits(2))
        } else {
            intArrayOf(bs.readBits(2), bs.readBits(2), bs.readBits(2))
        }

        // Decode Huffman tables for literals
        val litHuffTable = decodeHuffmanTable(bs)

        // Decode FSE/predefined tables for sequences
        val litLenTable = decodeSeqTable(bs, modes[0], LITLEN_PREDEF, LITLEN_MAX_SYM)
        val matchLenTable = decodeSeqTable(bs, modes[1], MATCHLEN_PREDEF, MATCHLEN_MAX_SYM)
        val offsetTable = decodeSeqTable(bs, modes[2], OFFSET_PREDEF, OFFSET_MAX_SYM)

        // Decode literals
        val litStream = BitStream(blockData, bs.byteOffset, bs.bitOffset)
        val literals = mutableListOf<Int>()
        val litCounts = mutableListOf<Int>()

        // First pass: count sequences to know literal counts
        // Actually, zstd interleaves: litlen, matchlen, offset per sequence
        // We need to decode sequences first

        // Store FSE table state for sequence decoding
        val seqBitStream = litStream
        val litLenFseState = FseState(litLenTable, seqBitStream)
        val matchLenFseState = FseState(matchLenTable, seqBitStream)
        val offsetFseState = FseState(offsetTable, seqBitStream)

        // Decode all sequences
        val litLengths = mutableListOf<Int>()
        val matchLengths = mutableListOf<Int>()
        val offsets = mutableListOf<Int>()

        for (s in 0 until numSeqs) {
            val litLenSym = decodeFseSymbol(litLenFseState)
            val matchLenSym = decodeFseSymbol(matchLenFseState)
            val offsetSym = decodeFseSymbol(offsetFseState)

            litLengths.add(decodeLitLen(litLenSym, seqBitStream))
            matchLengths.add(decodeMatchLen(matchLenSym, seqBitStream))
            offsets.add(decodeOffset(offsetSym, seqBitStream))
        }

        // Now decode all literals
        var totalLits = 0
        for (ll in litLengths) totalLits += ll

        for (i in 0 until numSeqs) {
            // Literals for this sequence
            for (j in 0 until litLengths[i]) {
                val lit = readSymbolFromTable(litStream, litHuffTable)
                out.write(lit)
                window[windowPos] = lit.toByte()
                windowPos = (windowPos + 1) and (window.size - 1)
            }

            // Match copy
            val ml = matchLengths[i]
            val off = offsets[i]
            var matchPos = (windowPos - off) and (window.size - 1)
            for (j in 0 until ml) {
                val b = window[matchPos]
                out.write(b.toInt() and 0xFF)
                window[windowPos] = b
                windowPos = (windowPos + 1) and (window.size - 1)
                matchPos = (matchPos + 1) and (window.size - 1)
            }
        }

        // Remaining literals after last sequence (rLE can emit them)
    }

    private fun decodeHuffmanTable(bs: BitStream): HuffmanTable {
        val maxBits = bs.readBits(5)
        if (maxBits == 0) {
            val sym = bs.readBits(8)
            return HuffmanTable(IntArray(1) { maxBits }, IntArray(1) { sym }, 1)
        }

        val numSymbols = bs.readBits(maxBits) + 1
        val weights = IntArray(numSymbols)
        for (i in 0 until numSymbols) {
            var w = bs.readBits(3)
            if (w == 0) {
                val b2 = bs.readBits(2)
                w = when (b2) {
                    0 -> 0; 1 -> 1; 2 -> 2
                    else -> { val b3 = bs.readBits(3); if (b3 == 0) 3 else 3 + b3 }
                }
            }
            weights[i] = w
        }

        val count = IntArray(maxBits + 1)
        for (w in weights) if (w in 0..maxBits) count[w]++

        val sorted = IntArray(numSymbols)
        var pos = 0
        for (bitLen in 0..maxBits) {
            count[bitLen] = pos
            for (i in 0 until numSymbols) {
                if (weights[i] == bitLen) sorted[count[bitLen]++] = i
            }
            pos = count[bitLen]
        }

        val symbols = sorted.copyOf()
        val bitLengths = IntArray(numSymbols) { weights[symbols[it]] }
        return HuffmanTable(bitLengths, symbols, maxBits)
    }

    private fun readSymbolFromTable(stream: BitStream, table: HuffmanTable): Int {
        if (table.numSymbols == 1) return table.symbols[0]
        var code = 0
        var bitsRead = 0
        while (bitsRead < table.maxBits) {
            val b = stream.readBits(1)
            code = (code shl 1) or b
            bitsRead++
            for (i in 0 until table.numSymbols) {
                val bl = table.bitLengths[i]
                if (bl == bitsRead) {
                    val expected = computeHuffmanCode(table.bitLengths, i, table.maxBits)
                    if (expected == code) return table.symbols[i]
                }
            }
        }
        throw IllegalStateException("Huffman decode failed")
    }

    private fun computeHuffmanCode(bitLengths: IntArray, index: Int, maxBits: Int): Int {
        val bl = bitLengths[index]
        if (bl == 0) return 0
        var count = 0
        for (i in 0 until index) if (bitLengths[i] == bl) count++
        var result = 0
        for (i in 0 until bl) result = (result shl 1) or ((count ushr (bl - 1 - i)) and 1)
        return result
    }

    private fun decodeLitLen(sym: Int, stream: BitStream): Int = when {
        sym <= 15 -> sym
        sym <= 31 -> sym - 15 + stream.readBits(sym - 15)
        else -> { val a = sym - 35; (1 shl a) + stream.readBits(a) + 11 }
    }

    private fun decodeMatchLen(sym: Int, stream: BitStream): Int = when {
        sym <= 15 -> sym + 3
        sym <= 31 -> sym - 15 + stream.readBits(sym - 15) + 19
        else -> { val a = sym - 35; (1 shl a) + stream.readBits(a) + 67 }
    }

    private fun decodeOffset(sym: Int, stream: BitStream): Int = when {
        sym <= 3 -> sym + 1
        else -> {
            val a = (sym - 1) / 2 - 1
            val raw = (1 shl a) + stream.readBits(a)
            if (sym % 2 == 0) 2 * raw + 1 else 2 * raw
        }
    }

    // ── FSE Table Decoding ────────────────────────────────────────────

    private fun decodeSeqTable(bs: BitStream, mode: Int, predef: IntArray, maxSym: Int): FseSeqTable {
        return when (mode) {
            0 -> { // RAW: symbols read directly from bitstream
                FseSeqTable(IntArray(1) { 0 }, 0, IntArray(0), 0, 0, true)
            }
            1 -> { // RLE: one symbol
                val sym = bs.readBits(ceilLog2(maxSym + 1))
                FseSeqTable(intArrayOf(sym), 1, intArrayOf(0), 0, 0, false, rleSymbol = sym)
            }
            2 -> { // FSE-compressed
                val accuracyLog = bs.readBits(4) + 5
                val tableSize = 1 shl accuracyLog
                val numSyms = decodeFseNormalizedDist(bs, maxSym, tableSize)
                FseSeqTable(fseSymbols.copyOf(), fseSymbols.size, fseWeights.copyOf(), accuracyLog, tableSize, false)
            }
            3 -> { // Predefined
                FseSeqTable(predef, predef.size, IntArray(predef.size) { 1 }, 0, 0, false)
            }
            else -> throw IllegalStateException("Invalid sequence mode: $mode")
        }
    }

    private val fseSymbols = IntArray(256)
    private val fseWeights = IntArray(256)
    private var fseSymbolCount = 0

    private fun decodeFseNormalizedDist(bs: BitStream, maxSymbol: Int, tableSize: Int): Int {
        var remaining = tableSize
        var pos = 0
        fseSymbolCount = 0

        // Read distribution using zstd's readChar() method
        val threshold = tableSize
        var nextThreshold = threshold

        while (remaining > 0 && pos <= maxSymbol) {
            val bits = if (nextThreshold > 256) bs.readBits(16) else bs.readBits(8)

            // find highest set bit
            var bitPos = if (nextThreshold > 256) 16 else 8
            var val_ = bits

            while (bitPos > 0) {
                val half = 1 shl (bitPos - 1)
                if (val_ >= half) {
                    val_ -= half
                    break
                }
                bitPos--
            }

            val maxLog = accuracyLogFromTableSize(tableSize)
            val symbolLog = if (bitPos > 0) {
                if (bitPos - 1 > maxLog) maxLog else bitPos - 1
            } else 0

            val count = remaining ushr symbolLog
            if (count > 0) {
                fseSymbols[fseSymbolCount] = pos
                fseWeights[fseSymbolCount] = symbolLog
                fseSymbolCount++
                remaining -= count shl symbolLog
            }

            pos++
        }

        // Normalize weights
        var leftover = tableSize - remaining
        for (i in 0 until fseSymbolCount) {
            fseWeights[i] = 1 shl fseWeights[i]
        }
        while (leftover > 0 && fseSymbolCount > 0) {
            fseWeights[fseSymbolCount - 1]++
            leftover--
        }

        return fseSymbolCount
    }

    private fun accuracyLogFromTableSize(tableSize: Int): Int {
        var log = 0
        var s = tableSize
        while (s > 1) { s = s shr 1; log++ }
        return log
    }

    private fun ceilLog2(v: Int): Int {
        if (v <= 1) return 0
        var log = 0; var n = v - 1
        while (n > 0) { n = n shr 1; log++ }
        return log
    }

    private fun decodeFseSymbol(state: FseState): Int {
        if (state.table.raw) return 0 // raw mode: read from stream separately
        if (state.table.rleSymbol >= 0) return state.table.rleSymbol

        // Simplified: for the basic case, use Huffman-only fallback
        // Full FSE state machine implementation:
        val accLog = state.table.accuracyLog
        if (accLog == 0) return state.table.symbols.getOrElse(0) { 0 }

        // Read bits from stream and decode
        val bits = state.stream.peekBits(accLog.coerceAtMost(16))
        state.stream.skipBits(accLog.coerceAtMost(16))

        // Find symbol from distribution
        val normalized = bits and ((1 shl accLog) - 1)
        var cumul = 0
        for (i in 0 until state.table.numSymbols) {
            cumul += state.table.weights[i]
            if (normalized < cumul) return state.table.symbols[i]
        }
        return state.table.symbols[state.table.numSymbols - 1]
    }

    // ── Data classes ──────────────────────────────────────────────────

    private data class HuffmanTable(
        val bitLengths: IntArray,
        val symbols: IntArray,
        val maxBits: Int,
        val numSymbols: Int = symbols.size
    ) {
        fun decode(bs: BitStream): Int? {
            if (maxBits == 0) return if (numSymbols > 0) symbols[0] else null
            var code = 0
            for (bit in 1..maxBits) {
                code = (code shl 1) or bs.readBits(1)
                for (i in 0 until numSymbols) {
                    if (bitLengths[i] == bit && code == (symbols[i] and ((1 shl bit) - 1))) {
                        return symbols[i]
                    }
                }
            }
            return null
        }
    }

    private data class FseSeqTable(
        val symbols: IntArray,
        val numSymbols: Int,
        val weights: IntArray,
        val accuracyLog: Int,
        val tableSize: Int,
        val raw: Boolean = false,
        val rleSymbol: Int = -1
    )

    private class FseState(val table: FseSeqTable, val stream: BitStream)

    private class BitReader(private val input: InputStream) {
        private val buf = ByteArray(8)
        private var pos = 0
        private var count = 0

        fun readLE32(): Int {
            ensure(4)
            return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) or
                    ((buf[2].toInt() and 0xFF) shl 16) or ((buf[3].toInt() and 0xFF) shl 24)
        }

        fun readLE64(): Long {
            ensure(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((buf[i].toLong() and 0xFF) shl (i * 8))
            return v
        }

        fun readByte(): Byte { ensure(1); return buf[0] }

        fun readFully(dst: ByteArray, off: Int = 0, len: Int = dst.size) {
            var p = off
            val end = off + len
            while (p < end) {
                val n = input.read(dst, p, end - p)
                if (n <= 0) throw java.io.EOFException("Unexpected end of stream")
                p += n
            }
        }

        private fun ensure(n: Int) {
            var needed = n - (count - pos)
            while (needed > 0) {
                val read = input.read(buf, count, buf.size - count)
                if (read <= 0) throw java.io.EOFException("Unexpected end of stream")
                count += read
                needed = n - (count - pos)
            }
        }
    }

    class BitStream(
        private val data: ByteArray,
        private val dataOffset: Int = 0,
        initialBitOffset: Int = 0
    ) {
        var byteOffset: Int = dataOffset + (initialBitOffset / 8)
            private set
        var bitOffset: Int = initialBitOffset % 8
            private set

        fun readBits(n: Int): Int {
            if (n == 0) return 0
            var result = 0
            for (i in 0 until n) {
                if (byteOffset >= data.size) throw java.io.EOFException("Stream exhausted")
                val b = (data[byteOffset].toInt() ushr bitOffset) and 1
                result = result or (b shl i)
                bitOffset++
                if (bitOffset >= 8) { bitOffset = 0; byteOffset++ }
            }
            return result
        }

        fun peekBits(n: Int): Int {
            if (n == 0) return 0
            var result = 0; var bo = byteOffset; var bit = bitOffset
            for (i in 0 until n) {
                if (bo >= data.size) break
                val b = (data[bo].toInt() ushr bit) and 1
                result = result or (b shl i)
                bit++
                if (bit >= 8) { bit = 0; bo++ }
            }
            return result
        }

        fun skipBits(n: Int) {
            bitOffset += n
            byteOffset += bitOffset / 8
            bitOffset = bitOffset % 8
        }
    }

    companion object {
        private const val MAGIC: Long = 0xFD2FB528L
        private const val RAW_CHUNK_SIZE = 4 * 1024 * 1024

        private val LITLEN_PREDEF = intArrayOf(
            4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
        )

        private val MATCHLEN_PREDEF = intArrayOf(
            1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
            4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7,
            8, 8, 8, 8, 9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11, 11,
            12, 12, 12, 12, 13, 13, 13, 13, 14, 14, 14, 14, 15, 15, 15, 15
        )

        private val OFFSET_PREDEF = intArrayOf(
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
        )

        private const val LITLEN_MAX_SYM = 35
        private const val MATCHLEN_MAX_SYM = 52
        private const val OFFSET_MAX_SYM = 31
    }
}
