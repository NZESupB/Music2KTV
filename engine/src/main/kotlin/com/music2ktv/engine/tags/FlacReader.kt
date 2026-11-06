package com.music2ktv.engine.tags

import com.music2ktv.engine.io.CachedSource
import java.nio.charset.StandardCharsets

/** FLAC 元数据块解析：STREAMINFO 时长、VORBIS_COMMENT 文本与歌词、PICTURE 封面位置。 */
internal object FlacReader {
    fun isFlac(
        src: CachedSource,
        at: Long,
    ): Boolean = src.readUpTo(at, 4).contentEquals(MAGIC)

    fun read(
        src: CachedSource,
        start: Long,
    ): AudioTags {
        var pos = start + 4
        var tags = AudioTags(AudioFormat.FLAC)
        var comments = emptyMap<String, String>()
        while (true) {
            val head = src.readUpTo(pos, 4)
            if (head.size < 4) break
            val last = head[0].toInt() and 0x80 != 0
            val type = head[0].toInt() and 0x7F
            val len = ((head[1].toInt() and 0xFF) shl 16) or ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            val body = pos + 4
            when (type) {
                0 -> tags = tags.copy(durationMs = streamInfoDuration(src.readUpTo(body, len)))
                4 -> comments = vorbisComments(src.readUpTo(body, len))
                6 -> if (tags.cover == null) tags = tags.copy(cover = pictureRef(src, body, len))
            }
            pos = body + len
            if (last || type == 127) break
        }
        val lyrics = listOf("LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS").firstNotNullOfOrNull { comments[it] }
        return tags.copy(
            title = comments["TITLE"],
            artist = comments["ARTIST"] ?: comments["ALBUMARTIST"],
            album = comments["ALBUM"],
            lyricsText = lyrics?.trim()?.ifEmpty { null },
        )
    }

    private fun streamInfoDuration(b: ByteArray): Long? {
        if (b.size < 18) return null
        val sampleRate = ((b[10].u() shl 12) or (b[11].u() shl 4) or (b[12].u() shr 4))
        val total =
            ((b[13].u() and 0x0F).toLong() shl 32) or (b[14].u().toLong() shl 24) or (b[15].u().toLong() shl 16) or
                (b[16].u().toLong() shl 8) or b[17].u().toLong()
        if (sampleRate <= 0 || total <= 0) return null
        return total * 1000 / sampleRate
    }

    private fun vorbisComments(b: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var p = 0

        fun le32(): Int? {
            if (p + 4 > b.size) return null
            val v = b[p].u() or (b[p + 1].u() shl 8) or (b[p + 2].u() shl 16) or (b[p + 3].u() shl 24)
            p += 4
            return v
        }
        val vendor = le32() ?: return out
        if (vendor < 0 || vendor > b.size - p) return out
        p += vendor
        val count = le32() ?: return out
        repeat(count) {
            val len = le32() ?: return out
            if (len < 0 || len > b.size - p) return out
            val entry = String(b, p, len, StandardCharsets.UTF_8)
            p += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                val value = entry.substring(eq + 1)
                // 多值字段（如多个 ARTIST）合并
                out[key] = out[key]?.let { "$it/$value" } ?: value
            }
        }
        return out
    }

    private fun pictureRef(
        src: CachedSource,
        body: Long,
        len: Int,
    ): CoverRef? {
        val head = src.readUpTo(body, minOf(len, 1024))
        var p = 4
        if (p + 4 > head.size) return null
        val mimeLen = be32(head, p)
        p += 4
        if (mimeLen < 0 || mimeLen > head.size - p - 4) return null
        val mime = String(head, p, mimeLen, StandardCharsets.ISO_8859_1)
        p += mimeLen
        val descLen = be32(head, p)
        p += 4
        // 描述之后还有 16 字节图片参数和 4 字节数据长度
        if (descLen < 0 || descLen > head.size - p - 20) return null
        p += descLen + 16
        val dataLen = be32(head, p)
        p += 4
        if (dataLen <= 0 || dataLen > len - p) return null
        return CoverRef(body + p, dataLen, mime.ifEmpty { "image/jpeg" })
    }

    private fun be32(
        b: ByteArray,
        o: Int,
    ): Int = (b[o].u() shl 24) or (b[o + 1].u() shl 16) or (b[o + 2].u() shl 8) or b[o + 3].u()

    private fun Byte.u() = toInt() and 0xFF

    private val MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
}
