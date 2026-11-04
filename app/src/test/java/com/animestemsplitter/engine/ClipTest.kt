package com.animestemsplitter.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ClipTest {
    private fun makeWav(frames: Int): File {
        val f = File.createTempFile("src", ".wav")
        val buf = FloatArray(frames * 2) { i -> ((i / 2) % 100) / 200f + 0.1f }
        WavWriter(f, 44100).use { it.write(buf, frames) }
        return f
    }

    @Test
    fun cutKeepsRangeAndFades() {
        val src = makeWav(10000)
        val dst = File.createTempFile("dst", ".wav")
        Clip.cutWav(src, dst, 2000, 5000, fadeFrames = 100)
        WavReader(dst).use { r ->
            assertEquals(3000L, r.frames)
            val all = FloatArray(3000 * 2)
            r.read(0, all, 3000)
            WavReader(src).use { s ->
                val orig = FloatArray(3000 * 2)
                s.read(2000, orig, 3000)
                // 真ん中は元と同じ
                for (i in 200 until 2800) assert(abs(all[2 * i] - orig[2 * i]) < 1e-3f)
            }
            // 端は 0 から始まり 0 に向かう
            assert(abs(all[0]) < 1e-3f) { "head=${all[0]}" }
            assert(abs(all[2 * 2999]) < 1e-3f) { "tail=${all[2 * 2999]}" }
        }
    }

    @Test
    fun cutClampsOutOfRange() {
        val src = makeWav(1000)
        val dst = File.createTempFile("dst", ".wav")
        Clip.cutWav(src, dst, 900, 5000)
        WavReader(dst).use { assertEquals(100L, it.frames) }
    }

    @Test
    fun peaksAndDownsample() {
        val src = makeWav(10000)
        val p = Clip.peaks(src, 0, 10000, 100)
        assertEquals(100, p.size)
        assert(p.all { it > 0.1f && it <= 0.6f })
        val d = Clip.downsample(p, 10000, 0, 10000, 10)
        assertEquals(10, d.size)
        assert(abs(d.max() - p.max()) < 1e-6f)
    }

    @Test
    fun formatSec() {
        assertEquals("1:05.3", Clip.formatSec(65.3))
        assertEquals("0:00.0", Clip.formatSec(-1.0))
    }
}
