package com.music2ktv.engine.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** RBJ Audio EQ Cookbook 二阶滤波器，Direct Form I。 */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    companion object {
        private const val Q_BUTTERWORTH = 0.7071067811865476

        fun lowPass(
            sampleRate: Int,
            cutoff: Double,
            q: Double = Q_BUTTERWORTH,
        ): Biquad {
            val w = 2 * PI * cutoff / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalized((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(
            sampleRate: Int,
            cutoff: Double,
            q: Double = Q_BUTTERWORTH,
        ): Biquad {
            val w = 2 * PI * cutoff / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        private fun normalized(
            b0: Double,
            b1: Double,
            b2: Double,
            a0: Double,
            a1: Double,
            a2: Double,
        ) = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}
