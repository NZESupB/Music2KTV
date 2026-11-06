package com.music2ktv.engine.tags

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 标签文本解码。国内大量 MP3 把 GBK 文本标记为 ISO-8859-1，这里按「ASCII → 严格 UTF-8 → GB18030」识别。 */
internal object TextDecoder {
    private val gb18030: Charset = Charset.forName("GB18030")

    fun decodeLegacy(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): String {
        if ((from until to).none { bytes[it].toInt() and 0x80 != 0 }) {
            return String(bytes, from, to - from, StandardCharsets.ISO_8859_1)
        }
        strict(StandardCharsets.UTF_8, bytes, from, to)?.let { return it }
        return strict(gb18030, bytes, from, to) ?: String(bytes, from, to - from, StandardCharsets.ISO_8859_1)
    }

    private fun strict(
        charset: Charset,
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): String? =
        try {
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, from, to - from))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
}
