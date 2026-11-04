package com.animestemsplitter

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.animestemsplitter.data.ResultStore
import com.animestemsplitter.engine.SeparationPipeline
import com.animestemsplitter.engine.WavReader
import com.animestemsplitter.engine.WavWriter
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** 画面を実際に操作して、切り取りまで通す（スクリーンショットも残す） */
@RunWith(AndroidJUnit4::class)
class UiScreenshotTest {
    // 通知の許可ダイアログが前に出ると画面を操作できないので、先に許可しておく
    @get:Rule(order = 0) val perm: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    companion object {
        @BeforeClass @JvmStatic
        fun prepareResult() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            // 他のテストが残した記録で「前回終了しました」が出ないように消しておく
            File(ctx.filesDir, "diag").deleteRecursively()
            if (ResultStore.list(ctx).isNotEmpty()) return
            // 音量が変わる 4 秒の音（波形が見やすいように）
            val rate = 44100
            val n = 4 * rate
            val buf = FloatArray(n * 2)
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val env = 0.2 + 0.6 * kotlin.math.abs(sin(PI * t * 1.5))
                val v = env * (0.5 * sin(2 * PI * 220 * t) + 0.3 * sin(2 * PI * 3.0 * 440 * t))
                buf[2 * i] = v.toFloat(); buf[2 * i + 1] = (0.9 * v).toFloat()
            }
            val f = File(ctx.cacheDir, "ui_test.wav")
            WavWriter(f, rate).use { it.write(buf, n) }
            SeparationPipeline(ctx).run(Uri.fromFile(f), "第1話_テスト.wav", { _, _, _ -> }, { false })
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        SystemClock.sleep(600)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun trimFlow() {
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("これまでの分離")).fetchSemanticsNodes().isNotEmpty() }
        shot("1_main")

        // セリフの切り取りボタン
        rule.onAllNodesWithContentDescription("セリフ を切り取る")[0].performScrollTo().performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("選択範囲を拡大")).fetchSemanticsNodes().isNotEmpty() }
        SystemClock.sleep(1500)   // 波形の計算待ち
        shot("2_trim")

        // 選択範囲を拡大して再生
        rule.onNodeWithText("選択範囲を拡大").performClick()
        rule.onAllNodesWithContentDescription("選択範囲を再生")[0].performClick()
        SystemClock.sleep(1200)
        shot("3_trim_zoom_playing")

        // 切り取って保存
        rule.onNodeWithText("セリフを保存").performScrollTo().performClick()
        val r = ResultStore.list(ctx).first()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var clip: File? = null
        while (SystemClock.elapsedRealtime() < deadline && clip == null) {
            clip = ResultStore.clipsDir(r).listFiles()?.firstOrNull { it.name.contains("dialogue") && it.length() > 44 }
            SystemClock.sleep(200)
        }
        assertTrue("clip not written", clip != null)
        SystemClock.sleep(500)
        val whole = WavReader(r.file("dialogue")).use { it.frames }
        val expect = minOf(whole, 15L * 44100)
        WavReader(clip!!).use { assertTrue("clip frames=${it.frames} expect=$expect", kotlin.math.abs(it.frames - expect) <= 10) }
        shot("4_saved")
    }
}
