package com.music2ktv.engine.lyrics

import com.music2ktv.engine.text.Pinyin
import java.text.Normalizer
import kotlin.math.abs

data class SongQuery(
    val title: String,
    val artist: String?,
    val durationMs: Long?,
)

/** 在线歌词搜索候选。[id] 为各 Provider 内部 id。 */
data class LyricCandidate(
    val provider: String,
    val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
)

/**
 * 歌词候选评分：标题、歌手规范化后计算相似度（同时比较拼音，繁简同音也能匹配），
 * 叠加时长差；总分 0..1，低于 [THRESHOLD] 视为未命中。
 */
object LyricMatcher {
    const val THRESHOLD = 0.6

    private val brackets = Regex("""[(\[（【「『<《].*?[)\]）】」』>》]""")
    private val featTail = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val versionTail = Regex("""\s*[-–—]\s*(live|伴奏|纯音乐|instrumental|remix|cover|demo|现场版?).*$""", RegexOption.IGNORE_CASE)
    private val artistSplit = Regex("""\s*(/|&|,|，|、|;|；|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE)
    private val noise = Regex("""[\s\p{Punct}·・'’"“”！？。，、：；~～]+""")
    private val versionWord = Regex("""live|伴奏|纯音乐|instrumental|remix|cover|demo|现场""", RegexOption.IGNORE_CASE)

    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        return nfkc
            .replace(brackets, " ")
            .replace(featTail, "")
            .replace(versionTail, "")
            .replace(noise, "")
    }

    fun score(
        query: SongQuery,
        candidate: LyricCandidate,
    ): Double {
        val titleSim = textSimilarity(normalize(query.title), normalize(candidate.title))
        val artistSim =
            if (query.artist.isNullOrBlank() || candidate.artist.isNullOrBlank()) null else artistSimilarity(query.artist, candidate.artist)
        val durationSim = durationSimilarity(query.durationMs, candidate.durationMs)

        // 缺失的维度不计分，权重按比例分给其余维度
        var total = titleSim * 0.5
        var weight = 0.5
        artistSim?.let {
            total += it * 0.3
            weight += 0.3
        }
        durationSim?.let {
            total += it * 0.2
            weight += 0.2
        }
        var s = total / weight
        // 时长差过大基本可以肯定不是同一版本
        val diff = if (query.durationMs != null && candidate.durationMs != null) abs(query.durationMs - candidate.durationMs) else 0
        if (diff > 20_000) s *= 0.5
        // 规范化会抹掉 Live/伴奏 等标记；版本标记不一致时轻微降权，保证原版优先
        if (versionTag(query.title) != versionTag(candidate.title)) s *= 0.9
        return s
    }

    private fun versionTag(title: String): String? = versionWord.find(title)?.value?.lowercase()

    fun best(
        query: SongQuery,
        candidates: List<LyricCandidate>,
    ): LyricCandidate? = rank(query, candidates).firstOrNull()

    /** 按得分降序返回达到阈值的候选，供「换一个歌词」轮换。 */
    fun rank(
        query: SongQuery,
        candidates: List<LyricCandidate>,
    ): List<LyricCandidate> =
        candidates
            .map { it to score(query, it) }
            .filter { it.second >= THRESHOLD }
            .sortedByDescending { it.second }
            .map { it.first }

    internal fun textSimilarity(
        a: String,
        b: String,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val direct = ratio(a, b)
        val pinyin = ratio(Pinyin.full(a), Pinyin.full(b))
        val contain = if (a.contains(b) || b.contains(a)) 0.85 else 0.0
        return maxOf(direct, pinyin * 0.95, contain)
    }

    private fun artistSimilarity(
        a: String,
        b: String,
    ): Double {
        val xs = a.split(artistSplit).map(::normalize).filter { it.isNotEmpty() }
        val ys = b.split(artistSplit).map(::normalize).filter { it.isNotEmpty() }
        if (xs.isEmpty() || ys.isEmpty()) return 0.0
        return xs.maxOf { x -> ys.maxOf { y -> textSimilarity(x, y) } }
    }

    private fun durationSimilarity(
        a: Long?,
        b: Long?,
    ): Double? {
        if (a == null || b == null || a <= 0 || b <= 0) return null
        val diff = abs(a - b)
        return when {
            diff <= 3_000 -> 1.0
            diff >= 15_000 -> 0.0
            else -> 1.0 - (diff - 3_000) / 12_000.0
        }
    }

    /** 1 - 编辑距离 / 较长串长度。 */
    private fun ratio(
        a: String,
        b: String,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
