package com.music2ktv.engine.lyrics

/**
 * 歌词解析：
 * - 标准 LRC：`[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss]`，一行可有多个时间戳
 * - 元信息：`[ti:]` `[ar:]` `[offset:+/-ms]`（正值表示歌词提前）
 * - 增强 LRC（A2）：`[00:01.00]<00:01.00>字<00:01.50>字<00:02.00>`
 * - 网易云 YRC：`[起始ms,时长ms](字起始ms,字时长ms,0)字...`
 */
object LrcParser {
    /** 行级时间无法确定结束时，最长按此时长染色，避免长间奏导致染色过慢。 */
    const val MAX_LINE_MS = 10_000L

    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val metaTag = Regex("""^\[([a-zA-Z#]+):(.*)]\s*$""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val yrcLine = Regex("""^\[(\d{1,10}),(\d{1,10})](.*)$""")
    private val yrcWord = Regex("""\((\d{1,10}),(\d{1,10}),-?\d+\)""")

    fun parse(text: String): Lyrics {
        val raw = text.removePrefix("\uFEFF").lines()
        if (raw.any { yrcLine.matches(it.trim()) }) return parseYrc(raw)

        val meta = LinkedHashMap<String, String>()
        val entries = ArrayList<Pair<Long, String>>()
        for (line in raw) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val stamps = ArrayList<Long>()
            var rest = trimmed
            while (true) {
                val m = timeTag.matchAt(rest, 0) ?: break
                stamps += toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) {
                metaTag.matchEntire(trimmed)?.let { meta[it.groupValues[1].lowercase()] = it.groupValues[2].trim() }
                continue
            }
            stamps.forEach { entries += it to rest }
        }

        if (entries.isEmpty()) {
            val plain = raw.map { it.trim() }.filter { it.isNotEmpty() && metaTag.matchEntire(it) == null }
            return Lyrics(plain.map { LyricLine(0, 0, it) }, synced = false, metadata = meta)
        }

        val offset = meta["offset"]?.trim()?.removePrefix("+")?.toLongOrNull() ?: 0L
        val sorted = entries.sortedBy { it.first }
        val lines =
            sorted.mapIndexed { i, (start, body) ->
                val nextStart = sorted.getOrNull(i + 1)?.first
                buildLine(start - offset, nextStart?.minus(offset), body, offset)
            }
        return Lyrics(dropEmptyEdges(lines), synced = true, metadata = meta)
    }

    /** 由 SYLT 这类「时间 + 文本片段」序列构建歌词；片段以换行开头表示新的一行。 */
    fun fromTimedSegments(segments: List<Pair<Long, String>>): Lyrics {
        if (segments.isEmpty()) return Lyrics.EMPTY
        // 片段带换行前缀表示逐字 SYLT；否则多数软件是「一个片段一行」
        val wordMode = segments.drop(1).any { it.second.startsWith("\n") || it.second.startsWith("\r") }
        val groups = ArrayList<MutableList<Pair<Long, String>>>()
        for ((t, s) in segments) {
            val newLine = !wordMode || groups.isEmpty() || s.startsWith("\n") || s.startsWith("\r")
            val clean = s.trimStart('\r', '\n')
            if (newLine) groups += mutableListOf(t to clean) else groups.last() += t to clean
        }
        val lines =
            groups.mapIndexed { gi, g ->
                val nextStart = groups.getOrNull(gi + 1)?.first()?.first
                val start = g.first().first
                if (g.size == 1) return@mapIndexed LyricLine(start, lineEnd(start, nextStart), g[0].second.trim())
                val words =
                    g
                        .mapIndexed { wi, (t, s) ->
                            LyricWord(t, g.getOrNull(wi + 1)?.first ?: lineEnd(t, nextStart), s)
                        }.trimWords()
                LyricLine(start, words.lastOrNull()?.endMs ?: start, words.joinToString("") { it.text }, words)
            }
        return Lyrics(dropEmptyEdges(lines), synced = true)
    }

    private fun buildLine(
        start: Long,
        nextStart: Long?,
        body: String,
        offset: Long,
    ): LyricLine {
        val tags = wordTag.findAll(body).toList()
        if (tags.isEmpty()) {
            return LyricLine(start, lineEnd(start, nextStart), body.trim())
        }
        val words = ArrayList<LyricWord>()
        val prefix = body.substring(0, tags.first().range.first)
        if (prefix.isNotBlank()) words += LyricWord(start, toWordMs(tags.first()) - offset, prefix)
        for ((i, tag) in tags.withIndex()) {
            val wStart = toWordMs(tag) - offset
            val textEnd = tags.getOrNull(i + 1)?.range?.first ?: body.length
            val wText = body.substring(tag.range.last + 1, textEnd)
            if (wText.isEmpty()) continue
            val wEnd = tags.getOrNull(i + 1)?.let { toWordMs(it) - offset } ?: lineEnd(wStart, nextStart)
            words += LyricWord(wStart, wEnd, wText)
        }
        val trimmed = words.trimWords()
        val text = trimmed.joinToString("") { it.text }
        val end = tags.last().let { toWordMs(it) - offset }.coerceAtLeast(trimmed.lastOrNull()?.endMs ?: start)
        return LyricLine(start, end, text, trimmed)
    }

    private fun parseYrc(raw: List<String>): Lyrics {
        val lines =
            raw.mapNotNull { line ->
                val m = yrcLine.matchEntire(line.trim()) ?: return@mapNotNull null
                val start = m.groupValues[1].toLong()
                val dur = m.groupValues[2].toLong()
                val body = m.groupValues[3]
                val tags = yrcWord.findAll(body).toList()
                val words =
                    tags.mapIndexed { i, tag ->
                        val textEnd = tags.getOrNull(i + 1)?.range?.first ?: body.length
                        val ws = tag.groupValues[1].toLong()
                        LyricWord(ws, ws + tag.groupValues[2].toLong(), body.substring(tag.range.last + 1, textEnd))
                    }
                val trimmed = words.trimWords()
                LyricLine(start, start + dur, trimmed.joinToString("") { it.text }, trimmed)
            }
        return Lyrics(dropEmptyEdges(lines.sortedBy { it.startMs }), synced = true)
    }

    /** 去掉首尾空白字符片段，保证 words 拼接结果与 text 完全一致。 */
    private fun List<LyricWord>.trimWords(): List<LyricWord> {
        if (isEmpty()) return this
        val out = toMutableList()
        out[0] = out[0].copy(text = out[0].text.trimStart())
        out[out.lastIndex] = out[out.lastIndex].copy(text = out[out.lastIndex].text.trimEnd())
        return out.filter { it.text.isNotEmpty() }
    }

    private fun dropEmptyEdges(lines: List<LyricLine>): List<LyricLine> =
        lines.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }

    private fun lineEnd(
        start: Long,
        nextStart: Long?,
    ): Long = if (nextStart != null && nextStart > start) minOf(nextStart, start + MAX_LINE_MS) else start + MAX_LINE_MS / 2

    private fun toWordMs(m: MatchResult): Long = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])

    private fun toMs(
        min: String,
        sec: String,
        frac: String,
    ): Long {
        val fracMs =
            when (frac.length) {
                0 -> 0L
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.take(3).toLong()
            }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs
    }
}
