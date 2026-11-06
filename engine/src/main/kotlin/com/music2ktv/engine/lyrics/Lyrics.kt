package com.music2ktv.engine.lyrics

/** 单个逐字片段；时间单位毫秒。 */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** 一行歌词。[words] 为空表示只有行级时间。 */
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
)

/** 解析后的歌词。[synced] 为 false 时是无时间轴的纯文本，只能静态滚动显示。 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val metadata: Map<String, String> = emptyMap(),
) {
    val wordLevel: Boolean get() = synced && lines.any { it.words.isNotEmpty() }

    /** 播放位置 [positionMs] 所在行的下标；首行开始前返回 -1。 */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 把 [translations] 中时间最接近（容差 [toleranceMs]）的行作为翻译合并进来。 */
    fun withTranslation(
        translations: Lyrics,
        toleranceMs: Long = 1000,
    ): Lyrics {
        if (!synced || !translations.synced) return this
        val source = translations.lines.filter { it.text.isNotBlank() && it.text.trim() != "//" }
        if (source.isEmpty()) return this
        val merged =
            lines.map { line ->
                val best = source.minByOrNull { kotlin.math.abs(it.startMs - line.startMs) }
                if (best != null && kotlin.math.abs(best.startMs - line.startMs) <= toleranceMs) {
                    line.copy(translation = best.text)
                } else {
                    line
                }
            }
        return copy(lines = merged)
    }

    companion object {
        val EMPTY = Lyrics(emptyList(), synced = false)
    }
}

/** 卡拉OK 染色进度计算。 */
object KaraokeProgress {
    /** 行级进度 0..1，按行时长线性推进（UI 再乘以行宽即可，等价于按字符宽度估算）。 */
    fun lineFraction(
        line: LyricLine,
        positionMs: Long,
    ): Float {
        val span = line.endMs - line.startMs
        if (span <= 0) return if (positionMs >= line.startMs) 1f else 0f
        return ((positionMs - line.startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /**
     * 逐字进度：返回已唱的字符数（可带小数，表示当前字唱到一半）。
     * 字符下标基于 [LyricLine.text]，与 words 拼接结果一致。
     */
    fun sungChars(
        line: LyricLine,
        positionMs: Long,
    ): Float {
        if (line.words.isEmpty()) return lineFraction(line, positionMs) * line.text.length
        var chars = 0f
        for (w in line.words) {
            if (positionMs >= w.endMs) {
                chars += w.text.length
                continue
            }
            if (positionMs > w.startMs && w.endMs > w.startMs) {
                chars += w.text.length * (positionMs - w.startMs).toFloat() / (w.endMs - w.startMs)
            }
            break
        }
        return chars
    }
}
