package com.animestemsplitter.engine

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplerTest {
    @Test
    fun sine48kTo44k() {
        val out = ArrayList<Float>()
        val r = Resampler(48000, 44100, { d, f -> for (i in 0 until f * 2) out.add(d[i]) })
        val n = 48000
        val blk = FloatArray(1000 * 2)
        var p = 0
        while (p < n) {
            val f = minOf(613, n - p)
            for (i in 0 until f) { val v = (0.5 * sin(2 * PI * 1000 * (p + i) / 48000)).toFloat(); blk[2 * i] = v; blk[2 * i + 1] = v }
            r.push(blk, f); p += f
        }
        r.finish()
        val m = out.size / 2
        assert(abs(m - 44100) <= 1) { "frames=$m" }
        var err = 0.0
        for (j in 200 until m - 200) err = maxOf(err, abs(out[2 * j] - 0.5 * sin(2 * PI * 1000 * j / 44100.0)))
        assert(err < 1e-3) { "err=$err" }
    }

    @Test
    fun passthroughWhenSameRate() {
        var frames = 0
        val r = Resampler(44100, 44100, { _, f -> frames += f })
        r.push(FloatArray(200), 100); r.finish()
        assert(frames == 100)
    }
}
