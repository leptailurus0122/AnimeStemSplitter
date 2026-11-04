package com.animestemsplitter.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class StreamingSeparatorTest {
    private fun signal(n: Int) = FloatArray(n * 2) { kotlin.math.sin(it * 0.0137).toFloat() + (it % 7) * 0.01f }

    private fun source(x: FloatArray) = object : PcmSource {
        override val frames = (x.size / 2).toLong()
        override fun get(frame: Long, ch: Int) = x[(frame * 2 + ch).toInt()]
    }

    /** モデルが「入力を 0.5 / 0.3 / 0.2 に分けるだけ」なら、3トラックの合計は元の音声と一致するはず */
    @Test
    fun stemsSumBackToInput() {
        val chunk = 1000
        for (n in intArrayOf(10, 400, 1499, 1501, 9876)) {
            val x = signal(n)
            val model = ChunkModel { inp, out ->
                val w = floatArrayOf(0.5f, 0.3f, 0.2f)
                for (s in 0 until 3) for (i in 0 until 2 * chunk) out[s * 2 * chunk + i] = inp[i] * w[s]
            }
            val sum = FloatArray(n * 2)
            var pos = 0
            StreamingSeparator(chunk, 4, 3).run(source(x), model, { blocks, frames ->
                for (i in 0 until frames * 2) sum[pos + i] = blocks[0][i] + blocks[1][i] + blocks[2][i]
                pos += frames * 2
            })
            assertEquals("n=$n", n * 2, pos)
            val err = x.indices.maxOf { abs(x[it] - sum[it]) }
            assert(err < 1e-5f) { "n=$n err=$err" }
        }
    }

    @Test
    fun chunkCountMatchesRun() {
        val sep = StreamingSeparator(1000, 4, 3)
        var calls = 0
        var reported = 0
        sep.run(source(signal(5000)), { _, _ -> calls++ }, { _, _ -> }, onChunk = { _, total -> reported = total })
        assertEquals(sep.chunkCount(5000), calls)
        assertEquals(calls, reported)
    }
}
