package com.animestemsplitter.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.animestemsplitter.data.Diagnostics
import com.animestemsplitter.data.ResultStore
import com.animestemsplitter.data.StemResult
import java.io.File

enum class Stage { LOADING, DECODING, SEPARATING, SAVING }

/** ファイル1本を「読み込み → 44.1kHz に変換 → 分離 → WAV 保存」まで行う。 */
class SeparationPipeline(private val context: Context) {

    fun run(
        uri: Uri,
        displayName: String,
        onProgress: (stage: Stage, fraction: Float, etaSec: Int?) -> Unit,
        isCancelled: () -> Boolean,
    ): StemResult {
        val started = SystemClock.elapsedRealtime()
        val workDir = ResultStore.newWorkDir(context)
        val tmpPcm = File(context.cacheDir, "decoded_${workDir.name}.f32")
        try {
            onProgress(Stage.LOADING, 0f, null)
            OnnxStemModel.load(context).use { model ->
                // 1) デコード + リサンプル
                onProgress(Stage.DECODING, 0f, null)
                val store = PcmStore(tmpPcm)
                var resampler: Resampler? = null
                var durationUs = 0L
                val info = AudioDecoder(context, uri).decode(
                    onFormat = { sr, dur ->
                        durationUs = dur
                        resampler = Resampler(sr, model.sampleRate, { d, f -> store.append(d, f) })
                    },
                    onPcm = { data, frames, posUs ->
                        resampler!!.push(data, frames)
                        if (durationUs > 0) onProgress(Stage.DECODING, (posUs.toFloat() / durationUs).coerceIn(0f, 1f), null)
                    },
                    isCancelled = isCancelled,
                )
                resampler?.finish() ?: throw IllegalArgumentException("音声を読み込めませんでした。")
                val src = store.openSource()
                if (src.frames < model.sampleRate / 2) throw IllegalArgumentException("音声が短すぎます。")

                // 2) 分離
                val sep = StreamingSeparator(model.chunk, model.numOverlap, model.stems.size)
                val writers = model.stems.map { WavWriter(File(workDir, "$it.wav"), model.sampleRate) }
                val t0 = SystemClock.elapsedRealtime()
                onProgress(Stage.SEPARATING, 0f, null)
                Diagnostics.mark("SEPARATING: 1チャンク目を計算中", "frames=${src.frames}")
                try {
                    sep.run(
                        src, model,
                        sink = { blocks, frames -> blocks.forEachIndexed { i, b -> writers[i].write(b, frames) } },
                        onChunk = { done, total ->
                            if (done == 1) Diagnostics.mark("SEPARATING: 1チャンク目完了", "chunks=$total")
                            val per = (SystemClock.elapsedRealtime() - t0) / done.toDouble()
                            val eta = ((total - done) * per / 1000).toInt()
                            onProgress(Stage.SEPARATING, done.toFloat() / total, if (done >= 2) eta else null)
                        },
                        isCancelled = isCancelled,
                    )
                } finally {
                    writers.forEach { runCatching { it.close() } }
                    src.close()
                }

                // 3) 保存
                onProgress(Stage.SAVING, 1f, null)
                val elapsed = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
                return ResultStore.commit(
                    context, workDir, displayName,
                    durationSec = src.frames.toDouble() / model.sampleRate,
                    elapsedSec = elapsed,
                    sourceInfo = info.mime,
                )
            }
        } catch (t: Throwable) {
            workDir.deleteRecursively()
            throw t
        } finally {
            tmpPcm.delete()
        }
    }
}
