package com.animestemsplitter.engine

import java.io.File
import kotlin.math.abs

/** 切り取り・波形表示のための処理（Android に依存しない） */
object Clip {

    /**
     * src の [start, end) フレームを dst に書き出す。
     * 切り口の「プツッ」という音を防ぐため、両端に fadeFrames の短いフェードをかける。
     */
    fun cutWav(src: File, dst: File, start: Long, end: Long, fadeFrames: Int = 220) {
        WavReader(src).use { r ->
            val s = start.coerceIn(0, r.frames)
            val e = end.coerceIn(s, r.frames)
            val total = e - s
            val fade = minOf(fadeFrames.toLong(), total / 2).toInt()
            val block = 65536
            val buf = FloatArray(block * 2)
            WavWriter(dst, r.sampleRate).use { w ->
                var pos = s
                while (pos < e) {
                    val n = r.read(pos, buf, minOf(block.toLong(), e - pos).toInt())
                    if (n <= 0) break
                    if (fade > 0) {
                        for (i in 0 until n) {
                            val k = pos - s + i
                            val g = when {
                                k < fade -> k.toFloat() / fade
                                k >= total - fade -> (total - 1 - k).toFloat() / fade
                                else -> continue
                            }
                            buf[2 * i] *= g
                            buf[2 * i + 1] *= g
                        }
                    }
                    w.write(buf, n)
                    pos += n
                }
            }
        }
    }

    /**
     * [from, to) を buckets 個に分け、それぞれの区間の最大振幅（0〜1）を返す。波形表示用。
     */
    fun peaks(src: File, from: Long, to: Long, buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        WavReader(src).use { r ->
            val a = from.coerceIn(0, r.frames)
            val b = to.coerceIn(a, r.frames)
            if (b <= a || buckets <= 0) return out
            val span = (b - a).toDouble() / buckets
            val block = 65536
            val buf = FloatArray(block * 2)
            var pos = a
            while (pos < b) {
                val n = r.read(pos, buf, minOf(block.toLong(), b - pos).toInt())
                if (n <= 0) break
                for (i in 0 until n) {
                    val idx = (((pos + i) - a) / span).toInt().coerceAtMost(buckets - 1)
                    val v = maxOf(abs(buf[2 * i]), abs(buf[2 * i + 1]))
                    if (v > out[idx]) out[idx] = v
                }
                pos += n
            }
        }
        return out
    }

    /** 細かく計算済みのピーク列 fine（全体を均等割り）から、[from, to) の buckets 個分を作る */
    fun downsample(fine: FloatArray, totalFrames: Long, from: Long, to: Long, buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        if (fine.isEmpty() || totalFrames <= 0 || to <= from) return out
        val per = totalFrames.toDouble() / fine.size
        for (k in 0 until buckets) {
            val f0 = from + (to - from) * k / buckets
            val f1 = from + (to - from) * (k + 1) / buckets
            val i0 = (f0 / per).toInt().coerceIn(0, fine.size - 1)
            val i1 = (f1 / per).toInt().coerceIn(i0, fine.size - 1)
            var m = 0f
            for (i in i0..i1) if (fine[i] > m) m = fine[i]
            out[k] = m
        }
        return out
    }

    /** 秒を「1:23.4」の形に */
    fun formatSec(sec: Double): String {
        val t = maxOf(0.0, sec)
        val m = (t / 60).toInt()
        val s = t - m * 60
        return "%d:%04.1f".format(m, s)
    }
}
