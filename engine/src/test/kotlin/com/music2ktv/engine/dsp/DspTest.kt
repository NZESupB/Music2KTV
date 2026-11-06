package com.music2ktv.engine.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {
    private val sr = 44_100

    /** 生成交错立体声：中置分量 [center]（L=R），侧向分量 [side]（L=-R）。 */
    private fun stereo(
        seconds: Double,
        center: (Double) -> Double,
        side: (Double) -> Double = { 0.0 },
    ): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n * 2) { i ->
            val t = (i / 2).toDouble() / sr
            val v = if (i % 2 == 0) center(t) + side(t) else center(t) - side(t)
            v.toFloat()
        }
    }

    private fun rms(
        x: FloatArray,
        fromFrame: Int,
        channel: Int,
    ): Double {
        var s = 0.0
        var n = 0
        for (f in fromFrame until x.size / 2) {
            s += x[f * 2 + channel] * x[f * 2 + channel]
            n++
        }
        return sqrt(s / n)
    }

    private fun sine(freq: Double) = { t: Double -> 0.5 * sin(2 * PI * freq * t) }

    private fun cut(
        x: FloatArray,
        enabled: Boolean = true,
    ): FloatArray {
        val dsp = VocalCutDsp(sr).apply { this.enabled = enabled }
        dsp.process(x, x.size / 2, 2)
        return x
    }

    @Test
    fun centeredVocalBandIsStronglyAttenuated() {
        val x = stereo(1.0, sine(1000.0))
        val before = rms(x.copyOf(), sr / 2, 0)
        val after = rms(cut(x), sr / 2, 0)
        assertTrue("1kHz 中置应衰减 > 20dB，实际 ${after / before}", after / before < 0.1)
    }

    @Test
    fun centeredBassIsPreserved() {
        val x = stereo(1.0, sine(50.0))
        val before = rms(x.copyOf(), sr / 2, 0)
        val after = rms(cut(x), sr / 2, 0)
        assertTrue("50Hz 低频应基本保留，实际 ${after / before}", after / before > 0.8)
    }

    @Test
    fun sideContentIsUntouched() {
        val x = stereo(1.0, { 0.0 }, sine(1000.0))
        val before = x.copyOf()
        cut(x)
        for (i in sr until x.size) assertEquals(before[i], x[i], 1e-6f)
    }

    @Test
    fun disabledIsBitExactPassThrough() {
        val x = stereo(0.2, sine(1000.0), sine(300.0))
        val before = x.copyOf()
        cut(x, enabled = false)
        assertTrue(before.contentEquals(x))
    }

    @Test
    fun monoPassThrough() {
        val x = FloatArray(1000) { sin(it * 0.1).toFloat() }
        val before = x.copyOf()
        VocalCutDsp(sr).apply { enabled = true }.process(x, x.size, 1)
        assertTrue(before.contentEquals(x))
    }

    @Test
    fun toggleRampsWithoutJump() {
        val x = stereo(0.5, sine(1000.0))
        val dsp = VocalCutDsp(sr, rampMs = 30)
        val half = x.size / 4 // 帧数的一半
        dsp.process(x, half, 2)
        dsp.enabled = true
        val rest = x.copyOfRange(half * 2, x.size)
        dsp.process(rest, rest.size / 2, 2)
        var maxStep = 0f
        for (f in 1 until rest.size / 2) maxStep = maxOf(maxStep, abs(rest[f * 2] - rest[(f - 1) * 2]))
        // 0.5 幅度 1kHz 正弦相邻采样最大差 ≈ 0.071；淡入过程不应出现明显跳变
        assertTrue("切换时出现跳变 $maxStep", maxStep < 0.08f)
        assertEquals(1.0, dsp.currentMix, 1e-9)
    }

    @Test
    fun pcm16RoundTrip() {
        val s = ShortArray(2000) { if (it % 2 == 0) 1000 else 1000 }
        VocalCutDsp(sr).apply { enabled = false }.processPcm16(s, 1000, 2)
        assertTrue(s.all { it.toInt() == 1000 })
    }

    @Test
    fun spectrumPeaksAtToneBand() {
        val an = SpectrumAnalyzer(sr, fftSize = 2048, bands = 32)
        val x = stereo(0.2, sine(1000.0))
        assertTrue(an.feed(x, x.size / 2, 2))
        val levels = an.levels()
        val peak = levels.indices.maxBy { levels[it] }
        // 1kHz 在 60..16000Hz 对数 32 段中的位置
        val expected = (kotlin.math.ln(1000.0 / 60) / kotlin.math.ln(16000.0 / 60) * 32).toInt()
        assertTrue("峰值段 $peak 应接近 $expected", abs(peak - expected) <= 1)
        assertTrue(levels[peak] > 0.7f)
        assertTrue(levels[0] < 0.3f)
    }

    @Test
    fun spectrumSilenceIsZero() {
        val an = SpectrumAnalyzer(sr, fftSize = 1024)
        an.feed(FloatArray(4096), 2048, 2)
        assertTrue(an.levels().all { it == 0f })
    }

    @Test
    fun fftOfImpulseIsFlat() {
        val re = DoubleArray(8).also { it[0] = 1.0 }
        val im = DoubleArray(8)
        SpectrumAnalyzer.fft(re, im)
        re.forEach { assertEquals(1.0, it, 1e-9) }
        im.forEach { assertEquals(0.0, it, 1e-9) }
    }
}
