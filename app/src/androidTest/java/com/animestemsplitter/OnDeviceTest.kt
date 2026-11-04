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

    /** テスト用の音声（CI で ffmpeg が作る）を取り出す */
    private fun testAsset(name: String): File {
        val f = File(ctx.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    @Test fun wav48k() = separate(makeWav(3.0), 3.0)
    @Test fun aacM4a() = separate(testAsset("stereo_48k.m4a"), 3.0)
    @Test fun mp4WithVideo51() = separate(testAsset("video_51.mp4"), 3.0)
    @Test fun mp3Mono() = separate(testAsset("mono_44k.mp3"), 3.0)

    private fun separate(f: File, seconds: Double) {
        val t0 = SystemClock.elapsedRealtime()
        val r = SeparationPipeline(ctx).run(
            Uri.fromFile(f), f.name,
            onProgress = { s, p, _ ->
                val pct = (p * 100).toInt()
                if (pct % 25 == 0) Log.i(tag, "${f.name} progress $s $pct%")
            },
            isCancelled = { false },
        )
        Log.i(tag, "pipeline done in ${SystemClock.elapsedRealtime() - t0} ms")
        STEMS.forEach { stem ->
            WavReader(r.file(stem)).use {
                Log.i(tag, "$stem frames=${it.frames} sr=${it.sampleRate}")
                // AAC/MP3 は先頭に数十ミリ秒の無音が付くことがあるので少し幅を持たせる
                val expect = seconds * 44100
                assertTrue("${f.name} $stem frames=${it.frames}", kotlin.math.abs(it.frames - expect) < 44100 * 0.1)
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
