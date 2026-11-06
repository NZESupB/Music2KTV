package com.music2ktv.engine.lyrics

import com.music2ktv.engine.text.Pinyin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricMatcherTest {
    private val query = SongQuery("后来", "刘若英", 341_000)

    private fun cand(
        title: String,
        artist: String?,
        duration: Long?,
        id: String = title,
    ) = LyricCandidate("test", id, title, artist, duration)

    @Test
    fun normalizeStripsDecorations() {
        assertEquals("后来", LyricMatcher.normalize("后来 (Live)"))
        assertEquals("后来", LyricMatcher.normalize("后来【伴奏版】"))
        assertEquals("晴天", LyricMatcher.normalize("晴天 - Live"))
        assertEquals("loveyou", LyricMatcher.normalize("Ｌｏｖｅ　You feat. Someone"))
    }

    @Test
    fun exactMatchScoresHigh() {
        assertTrue(LyricMatcher.score(query, cand("后来", "刘若英", 341_500)) > 0.95)
    }

    @Test
    fun durationWithin3sStillPerfect() {
        assertEquals(LyricMatcher.score(query, cand("后来", "刘若英", 341_000)), LyricMatcher.score(query, cand("后来", "刘若英", 343_900)), 1e-9)
    }

    @Test
    fun wrongSongRejected() {
        assertNull(LyricMatcher.best(query, listOf(cand("晴天", "周杰伦", 269_000))))
    }

    @Test
    fun sameTitleDifferentVersionRankedLower() {
        val ranked =
            LyricMatcher.rank(
                query,
                listOf(cand("后来", "翻唱歌手", 300_000, "cover"), cand("后来 (Live)", "刘若英", 342_000, "live"), cand("后来", "刘若英", 341_000, "orig")),
            )
        assertEquals("orig", ranked.first().id)
        assertTrue(ranked.none { it.id == "cover" })
    }

    @Test
    fun traditionalChineseMatchesViaPinyin() {
        val q = SongQuery("后来", "刘若英", null)
        assertTrue(LyricMatcher.score(q, cand("後來", "劉若英", null)) >= LyricMatcher.THRESHOLD)
    }

    @Test
    fun multiArtistSplit() {
        val q = SongQuery("屋顶", "周杰伦/温岚", null)
        assertTrue(LyricMatcher.score(q, cand("屋顶", "周杰伦 & 温岚", null)) > 0.95)
    }

    @Test
    fun missingArtistUsesTitleAndDuration() {
        val q = SongQuery("城市晚风", null, 245_000)
        assertTrue(LyricMatcher.score(q, cand("城市晚风", "林夏", 245_000)) > 0.95)
    }

    @Test
    fun pinyinInitialsAndFull() {
        assertEquals("HL", Pinyin.initials("后来"))
        assertEquals("CSWF", Pinyin.initials("城市晚风"))
        assertEquals("HLLIVE2", Pinyin.initials("后来 Live2!"))
        assertEquals("CHENGSHI", Pinyin.full("城市"))
        assertEquals("", Pinyin.initials("！？"))
    }
}
