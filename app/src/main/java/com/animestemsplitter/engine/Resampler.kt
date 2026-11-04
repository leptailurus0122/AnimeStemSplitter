package com.animestemsplitter.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ステレオ用のストリーミング・リサンプラー（Kaiser 窓付き sinc 補間）。
 * 動画の 48kHz などをモデルが扱う 44.1kHz に変換する。
 *
 * push() で入力（インターリーブのステレオ）を少しずつ渡し、最後に finish() を呼ぶ。
 * 変換結果は [output] にインターリーブのステレオで渡される。
 */
class Resampler(
    private val inRate: Int,
    private val outRate: Int,
    private val output: (data: FloatArray, frames: Int) -> Unit,
    zeroCrossings: Int = 16,
) {
    private val passthrough = inRate == outRate
    private val step = inRate.toDouble() / outRate          // 出力1サンプルあたり進む入力サンプル数
    private val fc = 0.5 * min(1.0, outRate.toDouble() / inRate) * 0.97   // 入力サンプル基準のカットオフ
    private val halfWidth = ceil(zeroCrossings / (2 * fc)).toInt()
    private val tableRes = 256
    private val table: FloatArray

    // 入力バッファ（チャンネル別）。bufStart = buf[0] の絶対インデックス
    private var bufL = FloatArray(1 shl 16)
    private var bufR = FloatArray(1 shl 16)
    private var bufLen = 0
    private var bufStart = 0L
    private var outIndex = 0L
    private var inTotal = 0L
    private val outBlock = FloatArray(4096 * 2)
    private var outFill = 0

    init {
        val n = halfWidth * tableRes + 2
        table = FloatArray(n)
        val beta = 8.6
        val i0b = besselI0(beta)
        for (k in 0 until n) {
            val t = k.toDouble() / tableRes
            val x = 2 * fc * t
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val r = t / halfWidth
            val win = if (r >= 1.0) 0.0 else besselI0(beta * sqrt(1 - r * r)) / i0b
            table[k] = (2 * fc * sinc * win).toFloat()
        }
    }

    private fun kernel(t: Double): Float {
        val a = kotlin.math.abs(t) * tableRes
        val i = a.toInt()
        if (i >= table.size - 1) return 0f
        val f = (a - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * f
    }

    fun push(data: FloatArray, frames: Int) {
        if (frames <= 0) return
        if (passthrough) { output(data, frames); return }
        ensureCapacity(bufLen + frames)
        for (i in 0 until frames) {
            bufL[bufLen + i] = data[2 * i]
            bufR[bufLen + i] = data[2 * i + 1]
        }
        bufLen += frames
        inTotal += frames
        produce(final = false)
    }

    fun finish() {
        if (passthrough) return
        produce(final = true)
        flushOut()
    }

    private fun sample(buf: FloatArray, abs: Long): Float {
        val i = abs - bufStart
        return if (i < 0 || i >= bufLen) 0f else buf[i.toInt()]
    }

    private fun produce(final: Boolean) {
        val totalOut = floor(inTotal / step).toLong()
        while (true) {
            if (final && outIndex >= totalOut) break
            val t = outIndex * step
            val hi = floor(t + halfWidth).toLong()
            if (!final && hi >= bufStart + bufLen) break
            val lo = ceil(t - halfWidth).toLong()
            var l = 0f
            var r = 0f
            var i = lo
            while (i <= hi) {
                val k = kernel(t - i)
                l += sample(bufL, i) * k
                r += sample(bufR, i) * k
                i++
            }
            outBlock[outFill++] = l
            outBlock[outFill++] = r
            if (outFill == outBlock.size) flushOut()
            outIndex++
        }
        // もう使わない古い入力を捨てる
        val keepFrom = floor(outIndex * step - halfWidth).toLong() - 1
        val drop = (keepFrom - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(bufL, drop, bufL, 0, bufLen - drop)
            System.arraycopy(bufR, drop, bufR, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }

    private fun flushOut() {
        if (outFill > 0) {
            output(outBlock, outFill / 2)
            outFill = 0
        }
    }

    private fun ensureCapacity(n: Int) {
        if (n <= bufL.size) return
        var cap = bufL.size
        while (cap < n) cap *= 2
        bufL = bufL.copyOf(cap)
        bufR = bufR.copyOf(cap)
    }

    companion object {
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (k < 200) {
                term *= q / (k.toDouble() * k)
                sum += term
                if (term < sum * 1e-12) break
                k++
            }
            return sum
        }
    }
}
