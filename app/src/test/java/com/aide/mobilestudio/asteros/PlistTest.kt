package com.aide.mobilestudio.asteros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Info.plist 파서 (XML + binary).
 *
 * IPA의 모든 정보는 Info.plist에서 나온다. 해석 실패는 null이지
 * 지어낸 값이 아니어야 한다.
 */
class PlistTest {

    // ------------------------------------------------------------- fixture --

    private fun be64(v: Long): ByteArray =
        (0 until 8).map { i -> ((v shr (56 - 8 * i)) and 0xFF).toByte() }.toByteArray()

    /** bplist 개체 하나: ASCII 문자열 (15자 미만은 인라인, 이상은 확장 크기). */
    private fun bpAscii(s: String): ByteArray {
        val b = s.toByteArray(Charsets.US_ASCII)
        // 확장 크기:0x1n 마커는 길이 2^n 바이트 정수를 뜻한다 (0x10 = 1바이트).
        return if (b.size < 15) byteArrayOf((0x50 or b.size).toByte()) + b
        else byteArrayOf(0x5F, 0x10, b.size.toByte()) + b
    }

    /** 정수 개체 (4byte). */
    private fun bpInt(v: Int): ByteArray = byteArrayOf(0x14) + byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    /** dict 개체: 항목 수는 참조의 절반(키들...)(값들...). */
    private fun bpDict(vararg refs: Int): ByteArray =
        byteArrayOf((0xA0 or (refs.size / 2)).toByte()) + refs.map { it.toByte() }.toByteArray()

    /** 배열 개체. */
    private fun bpArray(vararg refs: Int): ByteArray =
        byteArrayOf((0x80 or refs.size).toByte()) + refs.map { it.toByte() }.toByteArray()

    /** 개체 목록을 bplist00 파일로 조립한다 (객체 참조는1byte). */
    private fun binaryPlist(objects: List<ByteArray>): ByteArray {
        val header = "bplist00".toByteArray(Charsets.US_ASCII)
        var offset = header.size
        val offsets = mutableListOf<Int>()
        val body = java.io.ByteArrayOutputStream()
        for (o in objects) {
            offsets.add(offset)
            body.write(o)
            offset += o.size
        }
        val tableStart = offset
        val table = java.io.ByteArrayOutputStream()
        for (off in offsets) {
            assertTrue("오테이블 오프셋이1byte에 들어야 함: $off", off < 256)
            table.write(off)
        }
        val trailer = java.io.ByteArrayOutputStream()
        repeat(6) { trailer.write(0) }
        trailer.write(1) // offsetIntSize
        trailer.write(1) // objectRefSize
        trailer.write(be64(objects.size.toLong()))
        trailer.write(be64(0)) // top object
        trailer.write(be64(tableStart.toLong()))
        return header + body.toByteArray() + table.toByteArray() + trailer.toByteArray()
    }

    private val xmlPlist = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>CFBundleIdentifier</key><string>com.example.demo</string>
            <key>CFBundleExecutable</key><string>Demo</string>
            <key>CFBundleShortVersionString</key><string>1.2.3</string>
            <key>CFBundleVersion</key><string>45</string>
            <key>MinimumOSVersion</key><string>15.0</string>
            <key>LSRequiresIPhoneOS</key><true/>
            <key>BuildNumber</key><integer>42</integer>
            <key>Scale</key><real>1.5</real>
            <key>UIDeviceFamily</key><array><integer>1</integer><integer>2</integer></array>
            <key>Tags</key><array><string>games</string><string>kids</string></array>
        </dict>
        </plist>
    """.trimIndent().toByteArray()

    // --------------------------------------------------------------- tests --

    @Test
    fun XML_plist의_스칼라와_배열을_읽는다() {
        val map = Plist.parse(xmlPlist)
        assertEquals("com.example.demo", map!!["CFBundleIdentifier"])
        assertEquals("Demo", map["CFBundleExecutable"])
        assertEquals("15.0", map["MinimumOSVersion"])
        assertEquals(true, map["LSRequiresIPhoneOS"])
        assertEquals(42L, map["BuildNumber"])
        assertEquals(1.5, map["Scale"] as Double, 0.0001)
        assertEquals(listOf(1L, 2L), map["UIDeviceFamily"])
        assertEquals(listOf("games", "kids"), map["Tags"])
    }

    @Test
    fun binary_plist의_같은_값을_읽는다() {
        // 개체:0=dict{CFBundleExecutable:Demo},1..4 = 문자열
        val objects = listOf(
            bpDict(1, 3, 2, 4),          // 키[1,3] 값[2,4]
            bpAscii("CFBundleExecutable"),
            bpAscii("Demo"),
            bpAscii("CFBundleIdentifier"),
            bpAscii("com.example.demo"),
        )
        val map = Plist.parse(binaryPlist(objects))
        assertEquals("Demo", map!!["CFBundleExecutable"])
        assertEquals("com.example.demo", map["CFBundleIdentifier"])
    }

    @Test
    fun binary_plist의_정수와_배열_공유_참조를_읽는다() {
        //0=dict{Count:42, List:[Demo, Demo]} — 키[1,2] 값[3,4],5는 문자열 공유
        val objects = listOf(
            bpDict(1, 2, 3, 4),
            bpAscii("Count"),
            bpAscii("List"),
            bpInt(42),
            bpArray(5, 5),
            bpAscii("Demo"),
        )
        val map = Plist.parse(binaryPlist(objects))
        assertEquals(42L, map!!["Count"])
        assertEquals(listOf("Demo", "Demo"), map["List"])
    }

    @Test
    fun binary_plist의_긴_문자열_확장_크기를_읽는다() {
        val long = "com.example.a-fairly-long-bundle-identifier" //43자
        val objects = listOf(
            bpDict(1, 2),
            bpAscii("ID"),
            bpAscii(long),
        )
        val map = Plist.parse(binaryPlist(objects))
        assertEquals(long, map!!["ID"])
    }

    @Test
    fun 해석_불가_입력은_null() {
        assertNull(Plist.parse("not a plist at all".toByteArray()))
        assertNull(Plist.parse(ByteArray(0)))
        assertNull(Plist.parse("<html><body>hello</body></html>".toByteArray()))
    }

    @Test
    fun 잘린_binary_plist는_null() {
        val objects = listOf(
            bpDict(1, 2),
            bpAscii("Key"),
            bpAscii("Value"),
        )
        val full = binaryPlist(objects)
        assertNull(Plist.parse(full.copyOf(full.size - 20))) // 트레일러 잘림
    }

    @Test
    fun 순환_참조는_무한루프에_빠지지_않는다() {
        //0=dict가 자기 자신을 키로 참조
        val objects = listOf(
            byteArrayOf(0xA1.toByte(), 0, 1),
            bpAscii("Self"),
        )
        val map = Plist.parse(binaryPlist(objects))
        // 해석 실패해도 예외 없이 끝나야 한다
        assertTrue(map == null || map["Self"] == null)
    }
}
