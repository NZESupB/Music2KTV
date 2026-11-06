package com.music2ktv.engine.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 频谱分析：累积单声道样本到 [fftSize]，做汉宁窗 FFT，按对数间隔分成 [bands] 段能量（0..1），
 * 带上升快、下降慢的平滑，直接驱动频谱条/环形动画。
 */
class SpectrumAnalyzer(
    private val sampleRate: Int,
    private val fftSize: Int = 1024,
    val bands: Int = 32,
    private val minFreq: Double = 60.0,
    private val maxFreq: Double = 16000.0,
    private val floorDb: Double = -70.0,
    private val decay: Float = 0.85f,
) {
    init {
        require(fftSize >= 64 && fftSize and (fftSize - 1) == 0) { "fftSize 必须是 2 的幂：$fftSize" }
    }

    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val buffer = DoubleArray(fftSize)
    private var filled = 0
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val output = FloatArray(bands)
    private val edges = IntArray(bands + 1)

    init {
        val nyquist = sampleRate / 2.0
        val top = minOf(maxFreq, nyquist)
        for (b in 0..bands) {
            val f = minFreq * (top / minFreq).pow(b.toDouble() / bands)
            edges[b] = (f / nyquist * (fftSize / 2)).toInt().coerceIn(1, fftSize / 2)
        }
        for (b in 1..bands) if (edges[b] <= edges[b - 1]) edges[b] = minOf(fftSize / 2, edges[b - 1] + 1)
    }

    /** 喂入交错 PCM；返回 true 表示完成了一次分析，可读取 [levels]。 */
    fun feed(
        samples: FloatArray,
        frames: Int,
        channels: Int,
    ): Boolean {
        var updated = false
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) sum += samples[f * channels + c]
            buffer[filled++] = sum / channels
            if (filled == fftSize) {
                analyze()
                filled = 0
                updated = true
            }
        }
        return updated
    }

    /** 最近一次分析的分段能量副本。 */
    fun levels(): FloatArray = output.copyOf()

    private fun analyze() {
        for (i in 0 until fftSize) {
            re[i] = buffer[i] * window[i]
            im[i] = 0.0
        }
        fft(re, im)
        val norm = 2.0 / (fftSize * 0.5) // 汉宁窗相干增益 0.5
        for (b in 0 until bands) {
            var peak = 0.0
            for (k in edges[b] until maxOf(edges[b] + 1, edges[b + 1])) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * norm
                if (mag > peak) peak = mag
            }
            val db = 20 * log10(peak + 1e-12)
            val level = ((db - floorDb) / -floorDb).coerceIn(0.0, 1.0).toFloat()
            output[b] = if (level > output[b]) level else output[b] * decay + level * (1 - decay)
        }
    }

    companion object {
        /** 原地基 2 FFT。 */
        fun fft(
            re: DoubleArray,
            im: DoubleArray,
        ) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]
                    re[i] = re[j]
                    re[j] = t
                    t = im[i]
                    im[i] = im[j]
                    im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang)
                val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr
                        im[b] = im[a] - xi
                        re[a] += xr
                        im[a] += xi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
