package com.music2ktv.engine.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun standardLrcWithMetadataAndMixedPrecision() {
        val l =
            LrcParser.parse(
                """
                [ti:后来]
                [ar:刘若英]
                [00:01.5]第一行
                [00:03.25]第二行
                [00:05.123]第三行
                """.trimIndent(),
            )
        assertTrue(l.synced)
        assertEquals("后来", l.metadata["ti"])
        assertEquals(listOf(1500L, 3250L, 5123L), l.lines.map { it.startMs })
        assertEquals(3250L, l.lines[0].endMs)
        assertEquals(listOf("第一行", "第二行", "第三行"), l.lines.map { it.text })
    }

    @Test
    fun multipleTimestampsOnOneLineAreSorted() {
        val l = LrcParser.parse("[00:10.00][00:30.00]副歌\n[00:20.00]主歌")
        assertEquals(listOf(10_000L, 20_000L, 30_000L), l.lines.map { it.startMs })
        assertEquals(listOf("副歌", "主歌", "副歌"), l.lines.map { it.text })
    }

    @Test
    fun offsetShiftsLyricsEarlier() {
        val l = LrcParser.parse("[offset:+500]\n[00:02.00]A\n[00:04.00]B")
        assertEquals(listOf(1500L, 3500L), l.lines.map { it.startMs })
        val neg = LrcParser.parse("[offset:-300]\n[00:02.00]A")
        assertEquals(2300L, neg.lines[0].startMs)
    }

    @Test
    fun emptyLinesKeptInMiddleButTrimmedAtEdges() {
        val l = LrcParser.parse("[00:00.00]\n[00:01.00]A\n[00:02.00]\n[00:03.00]B\n[00:09.00]")
        assertEquals(listOf("A", "", "B"), l.lines.map { it.text })
    }

    @Test
    fun longGapCapsLineDuration() {
        val l = LrcParser.parse("[00:01.00]前奏后第一句\n[01:00.00]第二句")
        assertEquals(1000L + LrcParser.MAX_LINE_MS, l.lines[0].endMs)
    }

    @Test
    fun enhancedLrcWordTiming() {
        val l = LrcParser.parse("[00:01.00]<00:01.00>后<00:01.50>来 <00:02.00>我<00:03.00>\n[00:05.00]下一行")
        val line = l.lines[0]
        assertTrue(l.wordLevel)
        assertEquals("后来 我", line.text)
        assertEquals(line.text, line.words.joinToString("") { it.text })
        assertEquals(listOf(1000L, 1500L, 2000L), line.words.map { it.startMs })
        assertEquals(listOf(1500L, 2000L, 3000L), line.words.map { it.endMs })
        assertEquals(3000L, line.endMs)
    }

    @Test
    fun netEaseYrc() {
        val yrc =
            """
            {"t":0,"c":[{"tx":"作词: "}]}
            [1000,2000](1000,500,0)夜(1500,500,0)色(2000,1000,0)美
            [4000,1000](4000,1000,0)啊
            """.trimIndent()
        val l = LrcParser.parse(yrc)
        assertEquals(2, l.lines.size)
        assertEquals("夜色美", l.lines[0].text)
        assertEquals(3000L, l.lines[0].endMs)
        assertEquals(listOf(1000L, 1500L, 2000L), l.lines[0].words.map { it.startMs })
    }

    @Test
    fun plainTextIsUnsynced() {
        val l = LrcParser.parse("第一行\n\n第二行")
        assertFalse(l.synced)
        assertEquals(listOf("第一行", "第二行"), l.lines.map { it.text })
        assertEquals(-1, l.indexAt(5000))
    }

    @Test
    fun bomIsIgnored() {
        assertEquals("A", LrcParser.parse("\uFEFF[00:01.00]A").lines[0].text)
    }

    @Test
    fun indexAtUsesBinarySearch() {
        val l = LrcParser.parse("[00:01.00]A\n[00:02.00]B\n[00:03.00]C")
        assertEquals(-1, l.indexAt(500))
        assertEquals(0, l.indexAt(1000))
        assertEquals(1, l.indexAt(2999))
        assertEquals(2, l.indexAt(99_000))
    }

    @Test
    fun translationMergedByTime() {
        val main = LrcParser.parse("[00:01.00]你好\n[00:03.00]世界")
        val tr = LrcParser.parse("[00:01.05]Hello\n[00:03.00]World")
        assertEquals(listOf("Hello", "World"), main.withTranslation(tr).lines.map { it.translation })
    }

    @Test
    fun syltLineModeAndWordMode() {
        val lineMode = LrcParser.fromTimedSegments(listOf(0L to "A 行", 2000L to "B 行"))
        assertEquals(listOf("A 行", "B 行"), lineMode.lines.map { it.text })
        assertTrue(lineMode.lines.all { it.words.isEmpty() })

        val wordMode = LrcParser.fromTimedSegments(listOf(0L to "你", 300L to "好", 600L to "\n世", 900L to "界"))
        assertEquals(listOf("你好", "世界"), wordMode.lines.map { it.text })
        assertEquals(listOf(0L, 300L), wordMode.lines[0].words.map { it.startMs })
        assertEquals(600L, wordMode.lines[0].words[1].endMs)
    }

    @Test
    fun karaokeProgressWordAndLine() {
        val l = LrcParser.parse("[00:01.00]<00:01.00>AB<00:02.00>C<00:03.00>")
        val line = l.lines[0]
        assertEquals(0f, KaraokeProgress.sungChars(line, 900), 1e-4f)
        assertEquals(1f, KaraokeProgress.sungChars(line, 1500), 1e-4f)
        assertEquals(2.5f, KaraokeProgress.sungChars(line, 2500), 1e-4f)
        assertEquals(3f, KaraokeProgress.sungChars(line, 5000), 1e-4f)

        val plain = LyricLine(1000, 3000, "ABCD")
        assertEquals(0.5f, KaraokeProgress.lineFraction(plain, 2000), 1e-4f)
        assertEquals(2f, KaraokeProgress.sungChars(plain, 2000), 1e-4f)
    }
}
