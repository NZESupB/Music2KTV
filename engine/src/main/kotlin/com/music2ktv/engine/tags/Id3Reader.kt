package com.music2ktv.engine.tags

import com.music2ktv.engine.io.CachedSource
import com.music2ktv.engine.lyrics.LrcParser
import com.music2ktv.engine.lyrics.Lyrics
import java.nio.charset.StandardCharsets

/**
 * ID3v2.2/2.3/2.4 解析 + MP3 时长估算。
 * 逐帧读取帧头，只取需要的帧体；APIC 只记录偏移（无反同步时），不读入内存。
 */
internal object Id3Reader {
    private const val HEADER = 10

    /** 文本/歌词帧上限，超过视为损坏，避免按声明长度读入大量数据。 */
    private const val MAX_TEXT_FRAME = 4 * 1024 * 1024

    /** 整标签反同步需要整体读入，只处理前 32MiB。 */
    private const val MAX_UNSYNC_TAG = 32 * 1024 * 1024

    /** 文件开头 ID3v2 标签总长度（含头和可选尾），无标签返回 0。 */
    fun tagSize(src: CachedSource): Long {
        val h = src.readUpTo(0, HEADER)
        if (h.size < HEADER || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return 0
        val footer = if (h[5].toInt() and 0x10 != 0) HEADER else 0
        return HEADER + synchsafe(h, 6).toLong() + footer
    }

    fun read(src: CachedSource): AudioTags {
        val h = src.readUpTo(0, HEADER)
        val tagEnd = tagSize(src)
        var tags = AudioTags(AudioFormat.MP3)
        if (tagEnd > 0) {
            val major = h[3].toInt()
            val flags = h[5].toInt()
            val bodyEnd = HEADER + synchsafe(h, 6).toLong()
            tags =
                if (flags and 0x80 != 0 && major < 4) {
                    // v2.3 及以下的整标签反同步：整体读入后去除 0xFF00 填充
                    val bodySize = minOf(bodyEnd - HEADER, MAX_UNSYNC_TAG.toLong()).toInt()
                    val body = unsync(src.readUpTo(HEADER.toLong(), bodySize))
                    parseFrames(Frames.InMemory(body), major, extended = flags and 0x40 != 0, base = -1)
                } else {
                    parseFrames(Frames.Streamed(src, bodyEnd), major, extended = flags and 0x40 != 0, base = HEADER.toLong())
                }
        }
        val duration = tags.durationMs ?: Mp3Duration.estimate(src, tagEnd)
        return tags.copy(durationMs = duration)
    }

    /** 帧数据访问：内存（反同步后）或按偏移流式读取。 */
    private sealed interface Frames {
        val end: Long

        fun bytes(
            pos: Long,
            size: Int,
        ): ByteArray

        class InMemory(
            val data: ByteArray,
        ) : Frames {
            override val end: Long get() = data.size.toLong()

            override fun bytes(
                pos: Long,
                size: Int,
            ): ByteArray = data.copyOfRange(pos.toInt(), minOf(data.size, (pos + size).toInt()))
        }

        class Streamed(
            val src: CachedSource,
            override val end: Long,
        ) : Frames {
            override fun bytes(
                pos: Long,
                size: Int,
            ): ByteArray = src.readUpTo(pos, size)
        }
    }

    private fun parseFrames(
        frames: Frames,
        major: Int,
        extended: Boolean,
        base: Long,
    ): AudioTags {
        val start = if (base < 0) 0L else base
        var pos = start
        if (extended) {
            val ext = frames.bytes(pos, 4)
            if (ext.size < 4) return AudioTags(AudioFormat.MP3)
            // v2.4 扩展头大小含自身且为 synchsafe；v2.3 不含自身 4 字节
            val extSize = if (major >= 4) synchsafe(ext, 0).toLong() else 4L + int32(ext, 0)
            if (extSize < 4 || pos + extSize > frames.end) return AudioTags(AudioFormat.MP3)
            pos += extSize
        }
        val idLen = if (major == 2) 3 else 4
        val headLen = if (major == 2) 6 else 10
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var tlen: Long? = null
        var uslt: String? = null
        var sylt: Lyrics? = null
        var cover: CoverRef? = null

        while (pos + headLen <= frames.end) {
            val head = frames.bytes(pos, headLen)
            if (head.size < headLen || head[0].toInt() == 0) break
            val id = String(head, 0, idLen, StandardCharsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size =
                when (major) {
                    2 -> (head[3].u() shl 16) or (head[4].u() shl 8) or head[5].u()
                    3 -> int32(head, 4)
                    else -> synchsafe(head, 4)
                }
            if (size <= 0 || pos + headLen + size > frames.end) break
            val formatFlags = if (major >= 4) head[9].u() else 0
            val rawBodyPos = pos + headLen
            pos = rawBodyPos + size

            val key = if (major == 2) V22[id] ?: id else id
            if (key !in WANTED) continue
            if (major == 3 && head[9].u() and 0xC0 != 0) continue // 压缩/加密帧不处理
            if (major >= 4 && formatFlags and 0x0C != 0) continue

            // 分组帧的正文前有 1 字节组 ID，需先于数据长度指示符跳过
            val grouped = (major == 3 && head[9].u() and 0x20 != 0) || (major >= 4 && formatFlags and 0x40 != 0)
            val groupBytes = if (grouped) 1 else 0
            val bodyPos = rawBodyPos + groupBytes
            val bodySize = size - groupBytes
            if (bodySize <= 0) continue

            if (key == "APIC") {
                if (cover == null && base >= 0 && formatFlags and 0x03 == 0) cover = parseApicRef(frames, bodyPos, bodySize, major)
                continue
            }
            if (bodySize > MAX_TEXT_FRAME) continue
            var body = frames.bytes(bodyPos, bodySize)
            if (major >= 4) {
                if (formatFlags and 0x01 != 0) body = body.copyOfRange(minOf(4, body.size), body.size)
                if (formatFlags and 0x02 != 0) body = unsync(body)
            }
            when (key) {
                "TIT2" -> title = textFrame(body)
                "TPE1" -> artist = textFrame(body)
                "TALB" -> album = textFrame(body)
                "TLEN" -> tlen = textFrame(body)?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                "USLT" -> if (uslt == null) uslt = usltFrame(body)
                "SYLT" -> if (sylt == null) sylt = syltFrame(body)
            }
        }
        return AudioTags(
            format = AudioFormat.MP3,
            title = title,
            artist = artist,
            album = album,
            durationMs = tlen,
            lyricsText = uslt,
            syncedLyrics = sylt,
            cover = cover,
        )
    }

    private fun textFrame(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val parts = decodeTerminated(body, 1, body[0].toInt(), all = true)
        return parts
            .filter { it.isNotBlank() }
            .joinToString("/")
            .trim()
            .ifEmpty { null }
    }

    private fun usltFrame(body: ByteArray): String? {
        if (body.size < 5) return null
        val enc = body[0].toInt()
        val descEnd = terminatorEnd(body, 4, enc)
        return decode(body, descEnd, body.size, enc).trim().ifEmpty { null }
    }

    private fun syltFrame(body: ByteArray): Lyrics? {
        if (body.size < 7) return null
        val enc = body[0].toInt()
        if (body[4].toInt() != 2) return null // 只支持毫秒时间戳，MPEG 帧时间戳极少见
        var p = terminatorEnd(body, 6, enc)
        val segments = ArrayList<Pair<Long, String>>()
        while (p < body.size) {
            val textEnd = terminatorStart(body, p, enc)
            val text = decode(body, p, textEnd, enc)
            p = textEnd + terminatorLen(enc)
            if (p + 4 > body.size) break
            segments += (int32(body, p).toLong() and 0xFFFFFFFFL) to text
            p += 4
        }
        return LrcParser.fromTimedSegments(segments).takeIf { it.lines.isNotEmpty() }
    }

    private fun parseApicRef(
        frames: Frames,
        bodyPos: Long,
        size: Int,
        major: Int,
    ): CoverRef? {
        val head = frames.bytes(bodyPos, minOf(size, 512))
        if (head.size < 4) return null
        val enc = head[0].toInt()
        val mime: String
        var p: Int
        if (major == 2) {
            mime = "image/" + String(head, 1, 3, StandardCharsets.ISO_8859_1).trim().lowercase().replace("jpg", "jpeg")
            p = 4
        } else {
            val mimeEnd = terminatorStart(head, 1, 0)
            mime = String(head, 1, mimeEnd - 1, StandardCharsets.ISO_8859_1).ifEmpty { "image/jpeg" }
            p = mimeEnd + 1
        }
        p += 1 // 图片类型
        if (p >= head.size) return null
        p = terminatorEnd(head, p, enc)
        if (p >= head.size) return null
        return CoverRef(bodyPos + p, size - p, mime)
    }

    private fun decodeTerminated(
        b: ByteArray,
        from: Int,
        enc: Int,
        all: Boolean,
    ): List<String> {
        val out = ArrayList<String>()
        var p = from
        while (p < b.size) {
            val e = terminatorStart(b, p, enc)
            out += decode(b, p, e, enc)
            if (!all) break
            p = e + terminatorLen(enc)
        }
        return out
    }

    private fun decode(
        b: ByteArray,
        from: Int,
        to: Int,
        enc: Int,
    ): String {
        if (to <= from) return ""
        return when (enc) {
            1 -> utf16(b, from, to)
            2 -> String(b, from, to - from, StandardCharsets.UTF_16BE)
            3 -> String(b, from, to - from, StandardCharsets.UTF_8)
            else -> TextDecoder.decodeLegacy(b, from, to)
        }.trimEnd('\u0000')
    }

    private fun utf16(
        b: ByteArray,
        from: Int,
        to: Int,
    ): String {
        if (to - from >= 2) {
            if (b[from] == 0xFF.toByte() && b[from + 1] == 0xFE.toByte()) {
                return String(b, from + 2, to - from - 2, StandardCharsets.UTF_16LE)
            }
            if (b[from] == 0xFE.toByte() && b[from + 1] == 0xFF.toByte()) {
                return String(b, from + 2, to - from - 2, StandardCharsets.UTF_16BE)
            }
        }
        return String(b, from, to - from, StandardCharsets.UTF_16LE)
    }

    private fun terminatorLen(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    /** 从 [from] 开始查找字符串结束符的位置；没有结束符返回数组末尾。 */
    private fun terminatorStart(
        b: ByteArray,
        from: Int,
        enc: Int,
    ): Int {
        if (terminatorLen(enc) == 1) {
            for (i in from until b.size) if (b[i].toInt() == 0) return i
            return b.size
        }
        var i = from
        while (i + 1 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i
            i += 2
        }
        return b.size
    }

    private fun terminatorEnd(
        b: ByteArray,
        from: Int,
        enc: Int,
    ): Int = minOf(b.size, terminatorStart(b, from, enc) + terminatorLen(enc))

    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun synchsafe(
        b: ByteArray,
        o: Int,
    ): Int = (b[o].u() and 0x7F shl 21) or (b[o + 1].u() and 0x7F shl 14) or (b[o + 2].u() and 0x7F shl 7) or (b[o + 3].u() and 0x7F)

    private fun int32(
        b: ByteArray,
        o: Int,
    ): Int = (b[o].u() shl 24) or (b[o + 1].u() shl 16) or (b[o + 2].u() shl 8) or b[o + 3].u()

    private fun Byte.u() = toInt() and 0xFF

    private val WANTED = setOf("TIT2", "TPE1", "TALB", "TLEN", "USLT", "SYLT", "APIC")
    private val V22 =
        mapOf("TT2" to "TIT2", "TP1" to "TPE1", "TAL" to "TALB", "TLE" to "TLEN", "ULT" to "USLT", "SLT" to "SYLT", "PIC" to "APIC")
}

/** MP3 时长：优先 Xing/Info/VBRI 帧数，否则按首帧码率按 CBR 估算。 */
internal object Mp3Duration {
    private val BITRATE_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATE_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val SAMPLE_RATE_V1 = intArrayOf(44100, 48000, 32000)

    fun estimate(
        src: CachedSource,
        audioStart: Long,
    ): Long? {
        val window = src.readUpTo(audioStart, 64 * 1024)
        var i = 0
        while (i + 4 <= window.size) {
            if (window[i] == 0xFF.toByte() && window[i + 1].toInt() and 0xE0 == 0xE0) {
                durationFromFrame(window, i, src.length - audioStart - i)?.let { return it }
            }
            i++
        }
        return null
    }

    private fun durationFromFrame(
        w: ByteArray,
        o: Int,
        audioBytes: Long,
    ): Long? {
        val b1 = w[o + 1].toInt() and 0xFF
        val b2 = w[o + 2].toInt() and 0xFF
        val b3 = w[o + 3].toInt() and 0xFF
        val version = (b1 shr 3) and 0x3 // 3=MPEG1 2=MPEG2 0=MPEG2.5
        val layer = (b1 shr 1) and 0x3 // 1=Layer III
        if (version == 1 || layer != 1) return null
        val brIndex = b2 shr 4
        val srIndex = (b2 shr 2) and 0x3
        if (brIndex == 0 || brIndex == 15 || srIndex == 3) return null
        val mpeg1 = version == 3
        val bitrate = (if (mpeg1) BITRATE_V1_L3 else BITRATE_V2_L3)[brIndex] * 1000
        val sampleRate =
            SAMPLE_RATE_V1[srIndex] /
                if (mpeg1) {
                    1
                } else if (version == 2) {
                    2
                } else {
                    4
                }
        val samplesPerFrame = if (mpeg1) 1152 else 576
        val mono = (b3 shr 6) == 3
        val side = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)

        val xing = o + 4 + side
        if (xing + 12 <= w.size) {
            val tag = String(w, xing, 4, StandardCharsets.ISO_8859_1)
            if (tag == "Xing" || tag == "Info") {
                val flags = int32(w, xing + 4)
                if (flags and 1 != 0) {
                    val frames = int32(w, xing + 8).toLong() and 0xFFFFFFFFL
                    if (frames > 0) return frames * samplesPerFrame * 1000 / sampleRate
                }
            }
        }
        val vbri = o + 4 + 32
        if (vbri + 18 <= w.size && String(w, vbri, 4, StandardCharsets.ISO_8859_1) == "VBRI") {
            val frames = int32(w, vbri + 14).toLong() and 0xFFFFFFFFL
            if (frames > 0) return frames * samplesPerFrame * 1000 / sampleRate
        }
        if (audioBytes <= 0) return null
        return audioBytes * 8 * 1000 / bitrate
    }

    private fun int32(
        b: ByteArray,
        o: Int,
    ): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
