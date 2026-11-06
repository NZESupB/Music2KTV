package com.music2ktv.engine.tags

import com.music2ktv.engine.io.CachedSource
import com.music2ktv.engine.io.RandomAccessSource

/** 标签读取入口：按文件魔数分派，并在标题/歌手缺失时回退到文件名解析。 */
object TagReader {
    fun read(
        source: RandomAccessSource,
        fileName: String,
    ): AudioTags {
        val src = CachedSource(source)
        val id3 = Id3Reader.tagSize(src)
        val tags =
            when {
                FlacReader.isFlac(src, 0) -> FlacReader.read(src, 0)
                // 少数 FLAC 前面带 ID3v2 标签
                id3 > 0 && FlacReader.isFlac(src, id3) -> FlacReader.read(src, id3)
                id3 > 0 || fileName.endsWith(".mp3", ignoreCase = true) -> Id3Reader.read(src)
                else -> AudioTags(AudioFormat.UNKNOWN)
            }
        val fallback = FileNameParser.parse(fileName)
        return tags.copy(
            title = tags.title?.takeIf { it.isNotBlank() } ?: fallback.title,
            artist = tags.artist?.takeIf { it.isNotBlank() } ?: fallback.artist,
        )
    }

    /** 按 [CoverRef] 读取封面字节。 */
    fun readCover(
        source: RandomAccessSource,
        ref: CoverRef,
    ): ByteArray = CachedSource(source).readFully(ref.offset, ref.length)
}

data class ParsedName(
    val title: String,
    val artist: String?,
)

/** 文件名规则：`歌手 - 歌名`，去掉序号前缀和扩展名。 */
object FileNameParser {
    private val trackNo = Regex("""^\s*\d{1,3}\s*[.\-_、]\s*""")
    private val separators = listOf(" - ", " – ", " — ", "-", "_")

    fun parse(fileName: String): ParsedName {
        val base =
            fileName
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .replace(trackNo, "")
                .trim()
        for (sep in separators) {
            val i = base.indexOf(sep)
            if (i > 0 && i + sep.length < base.length) {
                return ParsedName(title = base.substring(i + sep.length).trim(), artist = base.substring(0, i).trim())
            }
        }
        return ParsedName(title = base, artist = null)
    }
}
