package com.music2ktv.engine

import com.music2ktv.engine.dsp.VocalCutDsp
import com.music2ktv.engine.io.ByteArraySource
import com.music2ktv.engine.lyrics.LrcParser
import com.music2ktv.engine.tags.AudioFormat
import com.music2ktv.engine.tags.TagFixtures.frame
import com.music2ktv.engine.tags.TagFixtures.id3
import com.music2ktv.engine.tags.TagFixtures.int32
import com.music2ktv.engine.tags.TagFixtures.le32
import com.music2ktv.engine.tags.TagFixtures.sylt
import com.music2ktv.engine.tags.TagFixtures.synchsafe
import com.music2ktv.engine.tags.TagFixtures.text
import com.music2ktv.engine.tags.TagReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 损坏或恶意文件的回归测试：解析器不抛异常、不按声明长度预分配。 */
class RobustnessTest {
    private fun read(
        bytes: ByteArray,
        name: String = "x.mp3",
    ) = TagReader.read(ByteArraySource(bytes), name)

    private fun flacBlock(
        type: Int,
        body: ByteArray,
    ) = "fLaC".toByteArray(Charsets.ISO_8859_1) +
        byteArrayOf((0x80 or type).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) +
        body

    @Test
    fun unsyncTagWithHugeDeclaredSizeAndNoBody() {
        val header = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0x80.toByte()) + synchsafe(0x0FFFFFFF)
        assertEquals(AudioFormat.MP3, read(header).format)
    }

    @Test
    fun negativeV23ExtendedHeaderSize() {
        val t = read(id3(3, listOf(int32(-20)), flags = 0x40), "歌手 - 歌名.mp3")
        assertEquals("歌名", t.title)
    }

    @Test
    fun flacNegativeVendorLength() {
        val t = read(flacBlock(4, le32(-8) + le32(1)), "a.flac")
        assertEquals(AudioFormat.FLAC, t.format)
    }

    @Test
    fun flacCommentLengthOverflow() {
        val vendor = le32(0)
        val t = read(flacBlock(4, vendor + le32(1) + le32(Int.MAX_VALUE) + ByteArray(8)), "a.flac")
        assertEquals(AudioFormat.FLAC, t.format)
    }

    @Test
    fun flacPictureNegativeMimeLength() {
        assertNull(read(flacBlock(6, int32(3) + int32(-1) + ByteArray(32)), "a.flac").cover)
    }

    @Test
    fun flacPictureDataLengthBeyondBlock() {
        val body = int32(3) + int32(0) + int32(0) + ByteArray(16) + int32(Int.MAX_VALUE) + ByteArray(8)
        assertNull(read(flacBlock(6, body), "a.flac").cover)
    }

    @Test
    fun groupedFramesSkipGroupId() {
        val body = byteArrayOf(7) + text(0, "A")
        val v23 = "TIT2".toByteArray() + int32(body.size) + byteArrayOf(0, 0x20) + body
        assertEquals("A", read(id3(3, listOf(v23))).title)
        val v24 = "TIT2".toByteArray() + synchsafe(body.size) + byteArrayOf(0, 0x40) + body
        assertEquals("A", read(id3(4, listOf(v24))).title)
    }

    @Test
    fun syltTimestampIsUnsigned() {
        val tag = id3(3, listOf(frame("SYLT", sylt(listOf(0x80000000L to "很晚的一行")), 3)))
        assertEquals(0x80000000L, requireNotNull(read(tag).syncedLyrics).lines[0].startMs)
    }

    @Test
    fun yrcHugeNumbersDoNotThrow() {
        val l = LrcParser.parse("[99999999999999999999,1](1,1,0)字")
        assertFalse(l.synced)
    }

    @Test
    fun longRampDoesNotOverflow() {
        val dsp = VocalCutDsp(48_000, rampMs = 60_000).apply { enabled = true }
        dsp.process(FloatArray(2), 1, 2)
        assertTrue("首帧不应全开：${dsp.currentMix}", dsp.currentMix < 0.01)
    }
}
