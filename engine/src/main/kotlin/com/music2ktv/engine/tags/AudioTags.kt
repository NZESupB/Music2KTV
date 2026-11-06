package com.music2ktv.engine.tags

import com.music2ktv.engine.lyrics.Lyrics

enum class AudioFormat { MP3, FLAC, UNKNOWN }

/** 封面在文件中的位置，UI 需要时再按需读取，避免扫描时下载大图。 */
data class CoverRef(
    val offset: Long,
    val length: Int,
    val mime: String,
)

data class AudioTags(
    val format: AudioFormat,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    /** USLT / Vorbis LYRICS 原文，可能是 LRC 也可能是纯文本，交给 LrcParser 判断。 */
    val lyricsText: String? = null,
    /** SYLT 解析结果（自带时间轴）。 */
    val syncedLyrics: Lyrics? = null,
    val cover: CoverRef? = null,
)
