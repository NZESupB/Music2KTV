package com.music2ktv.engine.tags

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** 测试用：按规范拼装 ID3v2 / FLAC / MP3 帧字节。 */
object TagFixtures {
    fun synchsafe(v: Int) =
        byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())

    fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun frame(
        id: String,
        body: ByteArray,
        major: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (major >= 4) synchsafe(body.size) else int32(body.size))
        out.write(byteArrayOf(0, 0))
        out.write(body)
        return out.toByteArray()
    }

    fun id3(
        major: Int,
        frames: List<ByteArray>,
        padding: Int = 32,
        flags: Int = 0,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { body.write(it) }
        body.write(ByteArray(padding))
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(major.toByte(), 0, flags.toByte()))
        out.write(synchsafe(bytes.size))
        out.write(bytes)
        return out.toByteArray()
    }

    /** 文本帧：编码字节 + 文本。 */
    fun text(
        enc: Int,
        value: String,
    ): ByteArray =
        byteArrayOf(enc.toByte()) +
            when (enc) {
                1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)
                2 -> value.toByteArray(Charsets.UTF_16BE)
                3 -> value.toByteArray(Charsets.UTF_8)
                else -> value.toByteArray(Charsets.ISO_8859_1)
            }

    fun legacyText(
        value: String,
        charset: Charset,
    ): ByteArray = byteArrayOf(0) + value.toByteArray(charset)

    fun uslt(
        enc: Int,
        lyrics: String,
    ): ByteArray {
        val term = if (enc == 1 || enc == 2) byteArrayOf(0, 0) else byteArrayOf(0)
        val encoded = text(enc, lyrics)
        return byteArrayOf(enc.toByte()) + "chi".toByteArray(Charsets.ISO_8859_1) + term + encoded.copyOfRange(1, encoded.size)
    }

    fun sylt(segments: List<Pair<Long, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(3)) // UTF-8
        out.write("chi".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(2, 1)) // 毫秒，歌词
        out.write(byteArrayOf(0)) // 空描述
        for ((t, s) in segments) {
            out.write(s.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.write(int32(t.toInt()))
        }
        return out.toByteArray()
    }

    /** APIC：编码 0、mime、结束符、图片类型 3（封面）、空描述结束符、图片数据。 */
    fun apic(image: ByteArray): ByteArray = byteArrayOf(0) + "image/png".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + image

    /** MPEG1 Layer III 128kbps 44.1kHz 立体声帧，可选 Xing 帧数。 */
    fun mp3Frames(
        count: Int,
        xingFrames: Int? = null,
    ): ByteArray {
        val frameLen = 144 * 128_000 / 44_100 // 417
        val out = ByteArrayOutputStream()
        repeat(count) { idx ->
            val f = ByteArray(frameLen)
            f[0] = 0xFF.toByte()
            f[1] = 0xFB.toByte()
            f[2] = 0x90.toByte()
            f[3] = 0x00
            if (idx == 0 && xingFrames != null) {
                val x = 4 + 32
                "Xing".toByteArray(Charsets.ISO_8859_1).copyInto(f, x)
                int32(1).copyInto(f, x + 4)
                int32(xingFrames).copyInto(f, x + 8)
            }
            out.write(f)
        }
        return out.toByteArray()
    }

    fun flac(
        sampleRate: Int,
        totalSamples: Long,
        comments: List<String>,
        picture: ByteArray? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        val si = ByteArray(34)
        si[10] = (sampleRate shr 12).toByte()
        si[11] = (sampleRate shr 4).toByte()
        si[12] = ((sampleRate and 0x0F) shl 4 or (1 shl 1)).toByte() // 2 声道
        si[13] = ((15 shl 4) or ((totalSamples shr 32).toInt() and 0x0F)).toByte()
        si[14] = (totalSamples shr 24).toByte()
        si[15] = (totalSamples shr 16).toByte()
        si[16] = (totalSamples shr 8).toByte()
        si[17] = totalSamples.toByte()
        block(out, 0, si, last = false)

        val vc = ByteArrayOutputStream()
        val vendor = "test".toByteArray()
        vc.write(le32(vendor.size))
        vc.write(vendor)
        vc.write(le32(comments.size))
        comments.forEach {
            val b = it.toByteArray(Charsets.UTF_8)
            vc.write(le32(b.size))
            vc.write(b)
        }
        block(out, 4, vc.toByteArray(), last = picture == null)

        if (picture != null) {
            val pic = ByteArrayOutputStream()
            pic.write(int32(3))
            val mime = "image/jpeg".toByteArray()
            pic.write(int32(mime.size))
            pic.write(mime)
            pic.write(int32(0))
            pic.write(ByteArray(16))
            pic.write(int32(picture.size))
            pic.write(picture)
            block(out, 6, pic.toByteArray(), last = true)
        }
        out.write(ByteArray(64)) // 伪音频帧
        return out.toByteArray()
    }

    private fun block(
        out: ByteArrayOutputStream,
        type: Int,
        body: ByteArray,
        last: Boolean,
    ) {
        out.write((if (last) 0x80 else 0) or type)
        out.write(body.size shr 16)
        out.write(body.size shr 8)
        out.write(body.size)
        out.write(body)
    }
}
