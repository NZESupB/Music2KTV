package com.music2ktv.engine.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCodecTest {
    @Test
    fun builtinsRoundTrip() {
        BuiltinTemplates.ALL.forEach { t ->
            assertEquals(t, TemplateCodec.decode(TemplateCodec.encode(t)))
        }
    }

    @Test
    fun unknownKeysIgnoredAndDefaultsApplied() {
        val t = TemplateCodec.decode("""{"id":"u1","name":"我的模板","future":123,"lyric":{"fontSizeSp":80}}""")
        assertEquals(80, t.lyric.fontSizeSp)
        assertEquals(BackgroundType.BLURRED_COVER, t.background.type)
        assertTrue(t.showNextSong)
    }

    @Test
    fun serialNamesAreStable() {
        val json = TemplateCodec.encode(BuiltinTemplates.NEON)
        assertTrue(json.contains("\"center_scroll\""))
        assertTrue(json.contains("\"ring\""))
    }

    @Test
    fun invalidInputRejectedWithReadableMessage() {
        val badColor =
            assertThrows(
                IllegalArgumentException::class.java,
            ) { TemplateCodec.decode("""{"id":"a","name":"b","lyric":{"unsungColor":"red"}}""") }
        assertTrue(badColor.message!!.contains("颜色格式"))
        assertThrows(IllegalArgumentException::class.java) { TemplateCodec.decode("""{"id":"a","name":"b","lyric":{"fontSizeSp":9}}""") }
        assertThrows(IllegalArgumentException::class.java) { TemplateCodec.decode("""{"id":"","name":"b"}""") }
        assertThrows(IllegalArgumentException::class.java) { TemplateCodec.decode("not json") }
        assertThrows(IllegalArgumentException::class.java) { TemplateCodec.decode("""{"id":"a","name":"b","version":99}""") }
    }

    @Test
    fun parseColor() {
        assertEquals(0xFFFF4FD8, TemplateCodec.parseColor("#FF4FD8"))
        assertEquals(0x80112233, TemplateCodec.parseColor("#80112233"))
    }
}
