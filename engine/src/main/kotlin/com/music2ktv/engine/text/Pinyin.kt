package com.music2ktv.engine.text

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/** 拼音工具：点歌首字母搜索与歌词匹配（繁简同音）使用。多音字取第一读音。 */
object Pinyin {
    private val format =
        HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.UPPERCASE
            toneType = HanyuPinyinToneType.WITHOUT_TONE
            vCharType = HanyuPinyinVCharType.WITH_V
        }

    /** 首字母：汉字取拼音首字母，英文字母与数字保留（大写），其余丢弃。如「后来 Live2」→「HLLIVE2」。 */
    fun initials(text: String): String =
        buildString {
            for (ch in text) {
                when {
                    ch.isAsciiLetterOrDigit() -> append(ch.uppercaseChar())
                    else -> syllable(ch)?.let { append(it[0]) }
                }
            }
        }

    /** 全拼（无空格），如「城市」→「CHENGSHI」。 */
    fun full(text: String): String =
        buildString {
            for (ch in text) {
                when {
                    ch.isAsciiLetterOrDigit() -> append(ch.uppercaseChar())
                    else -> syllable(ch)?.let { append(it) }
                }
            }
        }

    private fun syllable(ch: Char): String? {
        if (ch.code < 0x3400) return null
        return try {
            PinyinHelper.toHanyuPinyinStringArray(ch, format)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
