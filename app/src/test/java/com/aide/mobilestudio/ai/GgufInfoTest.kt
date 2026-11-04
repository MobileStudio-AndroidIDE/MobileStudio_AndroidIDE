package com.aide.mobilestudio.ai

import com.aide.mobilestudio.ai.opencode.session.OcContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * GGUF 헤더/메타데이터 판독.
 *
 * 로컬 모델의 컨텍스트 상한과 KV 기하(레이어/헤드/차원)는 이 파일에서
 * 나온다. 없으면 null이지 추정이 아니어야 한다: 없는 값을 지어내면
 * 모델이 못 버티는 창을 잡거나 메모리 계산을 틀리게 만든다.
 */
class GgufInfoTest {

    // ------------------------------------------------------------- fixture --

    private fun stringEntry(key: String, value: String): ByteArray {
        val kb = key.toByteArray(Charsets.UTF_8)
        val vb = value.toByteArray(Charsets.UTF_8)
        return concat(u64(kb.size.toLong()), kb, u32(8), u64(vb.size.toLong()), vb)
    }

    private fun u32Entry(key: String, value: Int): ByteArray =
        concat(u64(key.toByteArray().size.toLong()), key.toByteArray(), u32(4), u32(value))

    private fun u32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun u64(v: Long): ByteArray = (0 until 8).map { i -> ((v shr (8 * i)) and 0xFF).toByte() }.toByteArray()

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(out, pos); pos += p.size }
        return out
    }

    private fun header(version: Int, tensorCount: Long, kvCount: Long): ByteArray =
        concat(byteArrayOf(0x47, 0x47, 0x55, 0x46), u32(version), u64(tensorCount), u64(kvCount))

    private fun fixture(name: String, bytes: ByteArray): File {
        val dir = java.nio.file.Files.createTempDirectory("ms-gguf").toFile()
        dir.deleteOnExit()
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }

    /** 컨텍스트/기하 정보를 모두 가진 llama.cpp 스타일 메타데이터. */
    private fun fullMetadata(contextLengthLast: Boolean = true): List<ByteArray> {
        val body = mutableListOf(
            stringEntry("general.name", "Test Model"),
            stringEntry("general.architecture", "qwen2"),
            u32Entry("qwen2.block_count", 28),
            u32Entry("qwen2.attention.head_count", 16),
            u32Entry("qwen2.attention.head_count_kv", 4),
            u32Entry("qwen2.embedding_length", 1536),
            u32Entry("qwen2.attention.key_length", 128),
            u32Entry("qwen2.attention.value_length", 128),
            u32Entry("general.file_type", 15),
        )
        val context = u32Entry("qwen2.context_length", 32_768)
        return if (contextLengthLast) body + context else listOf(context) + body
    }

    // --------------------------------------------------------------- tests --

    @Test
    fun 컨텍스트와_KV_기하를_파싱한다() {
        val entries = fullMetadata()
        val file = fixture(
            "full.gguf",
            concat(header(3, 0, entries.size.toLong()), *entries.toTypedArray()),
        )
        val info = GgufInfo.read(file)
        assertNotNull(info)
        info!!
        assertEquals("Test Model", info.generalName)
        assertEquals("qwen2", info.architecture)
        assertEquals(28, info.nLayer)
        assertEquals(32_768, info.contextLength)
        assertEquals(16, info.headCount)
        assertEquals(4, info.headCountKv)
        assertEquals(1_536, info.embeddingLength)
        assertEquals(128, info.keyLength)
        assertEquals(128, info.valueLength)
        assertEquals(15, info.fileType)
    }

    @Test
    fun 없는_키는_추정하지_않는다() {
        val entries = listOf(
            stringEntry("general.name", "Sparse"),
            stringEntry("general.architecture", "llama"),
            u32Entry("llama.block_count", 22),
            u32Entry("llama.attention.head_count", 16),
            u32Entry("llama.embedding_length", 1_536),
            u32Entry("llama.context_length", 4_096),
            // head_count_kv / key_length / value_length 없음
        )
        val file = fixture(
            "sparse.gguf",
            concat(header(3, 0, entries.size.toLong()), *entries.toTypedArray()),
        )
        val info = GgufInfo.read(file)
        assertNotNull(info)
        info!!
        assertEquals(4_096, info.contextLength)
        assertEquals(16, info.headCount)
        assertNull(info.headCountKv)
        assertNull(info.keyLength)
        assertNull(info.valueLength)
        // 기하를 모르면 KV 비용도 계산하지 않는다
        assertNull(
            OcContextBudget.kvBytesPerToken(info.nLayer, info.headCountKv, info.keyLength, info.valueLength),
        )
        // 헤드 길이는 embedding/head로 유도할 수 있다
        assertEquals(96, OcContextBudget.headLength(info.keyLength, info.embeddingLength, info.headCount))
    }

    @Test
    fun 파싱한_기하로_KV_메모리를_계산한다() {
        val entries = fullMetadata()
        val file = fixture(
            "kv.gguf",
            concat(header(3, 0, entries.size.toLong()), *entries.toTypedArray()),
        )
        val info = GgufInfo.read(file)!!
        val perToken = OcContextBudget.kvBytesPerToken(
            info.nLayer, info.headCountKv, info.keyLength, info.valueLength,
        )
        assertNotNull(perToken)
        //28 레이어 * 4 KV헤드 * (128 + 128) * 2byte
        assertEquals(28L * 4 * 256 * 2, perToken)
    }

    @Test
    fun 모델_선언치가_컨텍스트_선택의_상한이_된다() {
        val entries = fullMetadata()
        val file = fixture(
            "cap.gguf",
            concat(header(3, 0, entries.size.toLong()), *entries.toTypedArray()),
        )
        val info = GgufInfo.read(file)!!
        assertNotNull(info.contextLength)
        // 설정2048 → 파생 상한(min(32768, 8192))으로8192
        assertEquals(8_192, OcContextBudget.chooseLocalContext(2_048, info.contextLength).tokens)
        // 설정65536 → 선언32768을 못 넘는다
        assertEquals(32_768, OcContextBudget.chooseLocalContext(65_536, info.contextLength).tokens)
    }

    @Test
    fun 머신_토크나이저_앞에서_조기_종료한다() {
        // tokenizer.* 앞에서 멈추면 이후 키는 읽지 않는다 (용어집이 최대150k항목)
        val entries = listOf(
            stringEntry("general.name", "Early"),
            stringEntry("general.architecture", "llama"),
            u32Entry("llama.block_count", 22),
            stringEntry("tokenizer.ggml.model", "gpt2"),
            u32Entry("llama.context_length", 4_096),
        )
        val file = fixture(
            "early.gguf",
            concat(header(3, 0, entries.size.toLong()), *entries.toTypedArray()),
        )
        val info = GgufInfo.read(file)
        assertNotNull(info)
        info!!
        assertEquals(22, info.nLayer)
        // context_length가 tokenizer 뒤에 있었으므로 여기서는 읽지 않는다
        assertNull(info.contextLength)
    }

    @Test
    fun 매직이_다르면_null() {
        val file = fixture("bad.gguf", byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64))
        assertNull(GgufInfo.read(file))
    }

    @Test
    fun 지원_버전이_아니면_null() {
        val file = fixture("v4.gguf", concat(header(4, 0, 0), ByteArray(16)))
        assertNull(GgufInfo.read(file))
    }

    @Test
    fun 잘린_파일은_null() {
        val entries = fullMetadata()
        val bytes = concat(header(3, 0, entries.size.toLong()), *entries.toTypedArray())
        val file = fixture("trunc.gguf", bytes.copyOf(bytes.size - 20))
        assertNull(GgufInfo.read(file))
    }

    @Test
    fun 없는_경로는_null() {
        assertNull(GgufInfo.read(File("/nonexistent/model.gguf")))
    }
}
