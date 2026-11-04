package com.animestemsplitter

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animestemsplitter.data.JobRepository
import com.animestemsplitter.data.JobState
import com.animestemsplitter.data.STEMS
import com.animestemsplitter.engine.SeparationPipeline
import com.animestemsplitter.engine.WavReader
import com.animestemsplitter.engine.WavWriter
import com.animestemsplitter.service.SeparationService
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** エミュレーター上で、実際にモデルを読み込んで分離まで通すテスト */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "OnDeviceTest"

    private fun makeWav(seconds: Double, rate: Int = 48000): File {
        val f = File(ctx.cacheDir, "test_${rate}_${seconds}.wav")
        val n = (seconds * rate).toInt()
        val buf = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val v = 0.3 * sin(2 * PI * 220 * t) + 0.2 * sin(2 * PI * (180 + 40 * sin(2 * PI * 3 * t)) * t)
            buf[2 * i] = v.toFloat(); buf[2 * i + 1] = (0.8 * v).toFloat()
        }
        WavWriter(f, rate).use { it.write(buf, n) }
        return f
    }

    @Test
    fun pipelineEndToEnd() {
        val f = makeWav(3.0)
        val t0 = SystemClock.elapsedRealtime()
        val r = SeparationPipeline(ctx).run(
            Uri.fromFile(f), f.name,
            onProgress = { s, p, _ -> Log.i(tag, "progress $s ${(p * 100).toInt()}%") },
            isCancelled = { false },
        )
        Log.i(tag, "pipeline done in ${SystemClock.elapsedRealtime() - t0} ms")
        STEMS.forEach { stem ->
            WavReader(r.file(stem)).use {
                Log.i(tag, "$stem frames=${it.frames} sr=${it.sampleRate}")
                assertTrue("$stem frames=${it.frames}", kotlin.math.abs(it.frames - 3 * 44100) < 10)
            }
        }
    }

    @Test
    fun serviceEndToEnd() {
        val f = makeWav(2.0)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a -> SeparationService.start(a, Uri.fromFile(f), f.name) }
            val deadline = SystemClock.elapsedRealtime() + 10 * 60_000
            while (SystemClock.elapsedRealtime() < deadline) {
                when (val s = JobRepository.state.value) {
                    is JobState.Done -> { Log.i(tag, "service done: ${s.result.dir}"); return }
                    is JobState.Failed -> fail("service failed: ${s.message}")
                    else -> SystemClock.sleep(500)
                }
            }
            fail("service timed out: ${JobRepository.state.value}")
        }
    }
}
