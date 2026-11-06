package com.music2ktv.engine.dsp

/**
 * 实时简易消人声（中置声道抵消）。
 *
 * 人声通常混在中置（M=(L+R)/2），把 M 中 [lowCut]..[highCut] 频段从左右声道同时减去：
 * `L' = L - BP(M)`，`R' = R - BP(M)`。频段外的低频（贝斯、底鼓）和高频（镲片、空气感）保留，
 * 避免纯 L−R 抵消把低频一起吃掉。开关时在 [rampMs] 内线性淡入淡出，防止爆音。
 *
 * 单声道输入无法区分中置，直接透传。线程不安全，由音频线程独占使用；[enabled] 可跨线程写。
 */
class VocalCutDsp(
    private val sampleRate: Int,
    private val lowCut: Double = 150.0,
    private val highCut: Double = 6000.0,
    rampMs: Int = 30,
) {
    @Volatile
    var enabled: Boolean = false

    private val highPass = Biquad.highPass(sampleRate, lowCut)
    private val lowPass = Biquad.lowPass(sampleRate, minOf(highCut, sampleRate * 0.45))
    private val step = 1.0 / maxOf(1L, sampleRate.toLong() * rampMs / 1000)
    private var mix = 0.0

    /** 当前抵消强度 0..1（淡入淡出过程中为中间值）。 */
    val currentMix: Double get() = mix

    /** 原地处理交错 PCM（float，-1..1）。 */
    fun process(
        samples: FloatArray,
        frames: Int,
        channels: Int,
    ) {
        if (channels < 2) return
        val target = if (enabled) 1.0 else 0.0
        if (mix == 0.0 && target == 0.0) {
            // 完全关闭时直通；重新打开时滤波器从零状态起步，瞬态由淡入掩盖
            return
        }
        for (f in 0 until frames) {
            val i = f * channels
            val l = samples[i].toDouble()
            val r = samples[i + 1].toDouble()
            val band = lowPass.process(highPass.process((l + r) * 0.5))
            if (mix != target) mix = if (mix < target) minOf(target, mix + step) else maxOf(target, mix - step)
            val cut = band * mix
            samples[i] = (l - cut).toFloat()
            samples[i + 1] = (r - cut).toFloat()
        }
        if (mix == 0.0) {
            highPass.reset()
            lowPass.reset()
        }
    }

    /** 处理交错 PCM16，内部转 float。 */
    fun processPcm16(
        samples: ShortArray,
        frames: Int,
        channels: Int,
        scratch: FloatArray = FloatArray(frames * channels),
    ) {
        val n = frames * channels
        for (i in 0 until n) scratch[i] = samples[i] / 32768f
        process(scratch, frames, channels)
        for (i in 0 until n) samples[i] = (scratch[i] * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()
    }
}
