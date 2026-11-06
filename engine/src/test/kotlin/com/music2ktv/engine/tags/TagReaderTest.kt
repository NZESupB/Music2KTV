package com.music2ktv.engine.tags

import com.music2ktv.engine.io.ByteArraySource
import com.music2ktv.engine.tags.TagFixtures.apic
import com.music2ktv.engine.tags.TagFixtures.flac
import com.music2ktv.engine.tags.TagFixtures.frame
import com.music2ktv.engine.tags.TagFixtures.id3
import com.music2ktv.engine.tags.TagFixtures.legacyText
import com.music2ktv.engine.tags.TagFixtures.mp3Frames
import com.music2ktv.engine.tags.TagFixtures.sylt
import com.music2ktv.engine.tags.TagFixtures.text
import com.music2ktv.engine.tags.TagFixtures.uslt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TagReaderTest {
    private fun read(
        bytes: ByteArray,
        name: String = "x.mp3",
    ) = TagReader.read(ByteArraySource(bytes), name)

    @Test
    fun id3v24Utf8TextAndUslt() {
        val tag =
            id3(
                4,
                listOf(
                    frame("TIT2", text(3, "后来"), 4),
                    frame("TPE1", text(3, "刘若英"), 4),
                    frame("TALB", text(3, "我等你"), 4),
                    frame("USLT", uslt(3, "[00:01.00]后来"), 4),
                ),
            )
        val t = read(tag + mp3Frames(10))
        assertEquals(AudioFormat.MP3, t.format)
        assertEquals("后来", t.title)
        assertEquals("刘若英", t.artist)
        assertEquals("我等你", t.album)
        assertEquals("[00:01.00]后来", t.lyricsText)
    }

    @Test
    fun id3v23Utf16WithBomAndTlen() {
        val tag =
            id3(
                3,
                listOf(
                    frame("TIT2", text(1, "晴天"), 3),
                    frame("TPE1", text(1, "周杰伦"), 3),
                    frame("TLEN", text(0, "269000"), 3),
                    frame("USLT", uslt(1, "故事的小黄花"), 3),
                ),
            )
        val t = read(tag)
        assertEquals("晴天", t.title)
        assertEquals("周杰伦", t.artist)
        assertEquals(269_000L, t.durationMs)
        assertEquals("故事的小黄花", t.lyricsText)
    }

    @Test
    fun legacyEncodingFallsBackToGbk() {
        val gbk = Charset.forName("GBK")
        val tag = id3(3, listOf(frame("TIT2", legacyText("童年", gbk), 3), frame("TPE1", legacyText("罗大佑", gbk), 3)))
        val t = read(tag)
        assertEquals("童年", t.title)
        assertEquals("罗大佑", t.artist)
    }

    @Test
    fun legacyEncodingAcceptsLatin1() {
        val tag = id3(3, listOf(frame("TIT2", text(0, "Hello"), 3)))
        assertEquals("Hello", read(tag).title)
    }

    @Test
    fun syltProducesSyncedLyrics() {
        val tag = id3(3, listOf(frame("SYLT", sylt(listOf(1000L to "第一行", 4000L to "第二行")), 3)))
        val lyrics = requireNotNull(read(tag).syncedLyrics)
        assertTrue(lyrics.synced)
        assertEquals(listOf("第一行", "第二行"), lyrics.lines.map { it.text })
        assertEquals(1000L, lyrics.lines[0].startMs)
        assertEquals(4000L, lyrics.lines[0].endMs)
    }

    @Test
    fun apicCoverIsReferencedAndReadable() {
        val image = ByteArray(300) { (it % 251).toByte() }
        val bytes = id3(3, listOf(frame("TIT2", text(3, "封面测试"), 3), frame("APIC", apic(image), 3))) + mp3Frames(5)
        val t = read(bytes)
        val cover = requireNotNull(t.cover)
        assertEquals("image/png", cover.mime)
        assertArrayEquals(image, TagReader.readCover(ByteArraySource(bytes), cover))
    }

    @Test
    fun id3v22ThreeCharFrames() {
        fun frame22(
            id: String,
            body: ByteArray,
        ) = id.toByteArray() + byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        val t = read(id3(2, listOf(frame22("TT2", text(0, "Old Song")), frame22("TP1", text(0, "Singer")))))
        assertEquals("Old Song", t.title)
        assertEquals("Singer", t.artist)
    }

    @Test
    fun mp3DurationFromXingFrameCount() {
        // 1000 帧 × 1152 / 44100 ≈ 26122ms
        val t = read(id3(4, listOf(frame("TIT2", text(3, "VBR"), 4))) + mp3Frames(3, xingFrames = 1000))
        assertEquals(26_122L, t.durationMs)
    }

    @Test
    fun mp3DurationCbrEstimateWithoutTag() {
        val frames = mp3Frames(100)
        // 100 × 417 字节 × 8 / 128kbps ≈ 2606ms
        assertEquals(frames.size * 8L * 1000 / 128_000, read(frames, "歌手 - 歌名.mp3").durationMs)
    }

    @Test
    fun missingTagsFallBackToFileName() {
        val t = read(mp3Frames(2), "/music/03. 林夏 - 城市晚风.mp3")
        assertEquals("城市晚风", t.title)
        assertEquals("林夏", t.artist)
    }

    @Test
    fun flacVorbisCommentsAndDuration() {
        val bytes =
            flac(
                sampleRate = 44_100,
                totalSamples = 44_100L * 245,
                comments = listOf("TITLE=城市晚风", "ARTIST=林夏", "album=夜色", "LYRICS=[00:01.00]夜色把城市轻轻点亮"),
            )
        val t = read(bytes, "a.flac")
        assertEquals(AudioFormat.FLAC, t.format)
        assertEquals("城市晚风", t.title)
        assertEquals("林夏", t.artist)
        assertEquals("夜色", t.album)
        assertEquals(245_000L, t.durationMs)
        assertEquals("[00:01.00]夜色把城市轻轻点亮", t.lyricsText)
    }

    @Test
    fun flacUnsyncedLyricsAndPicture() {
        val image = ByteArray(128) { it.toByte() }
        val bytes = flac(48_000, 48_000L * 10, listOf("TITLE=T", "UNSYNCEDLYRICS=纯文本歌词"), picture = image)
        val t = read(bytes, "b.flac")
        assertEquals("纯文本歌词", t.lyricsText)
        assertEquals(10_000L, t.durationMs)
        assertArrayEquals(image, TagReader.readCover(ByteArraySource(bytes), t.cover!!))
    }

    @Test
    fun flacPrefixedById3IsDetected() {
        val bytes = id3(3, listOf(frame("TIT2", text(3, "ignored"), 3))) + flac(44_100, 44_100, listOf("TITLE=FLAC 标题"))
        val t = read(bytes, "c.flac")
        assertEquals(AudioFormat.FLAC, t.format)
        assertEquals("FLAC 标题", t.title)
    }

    @Test
    fun truncatedTagDoesNotThrow() {
        val full = id3(3, listOf(frame("TIT2", text(3, "完整标题"), 3)))
        val t = read(full.copyOf(16), "歌手 - 截断.mp3")
        assertEquals("截断", t.title)
        assertNull(t.lyricsText)
    }

    @Test
    fun fileNameParserVariants() {
        assertEquals(ParsedName("晴天", "周杰伦"), FileNameParser.parse("周杰伦 - 晴天.flac"))
        assertEquals(ParsedName("晴天", "周杰伦"), FileNameParser.parse("01 - 周杰伦-晴天.mp3"))
        assertEquals(ParsedName("Yesterday", null), FileNameParser.parse("Yesterday.mp3"))
    }
}
