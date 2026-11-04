package com.animestemsplitter.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.animestemsplitter.data.Diagnostics
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** アプリに同梱した ONNX モデル（BandIt Plus）を ONNX Runtime で動かす。 */
class OnnxStemModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val sampleRate: Int,
    val chunk: Int,
    val numOverlap: Int,
    val stems: List<String>,
) : ChunkModel, Closeable {

    private val inBuf: FloatBuffer = ByteBuffer.allocateDirect(2 * chunk * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val shape = longArrayOf(1, 2, chunk.toLong())

    override fun run(input: FloatArray, output: FloatArray) {
        inBuf.clear()
        inBuf.put(input, 0, 2 * chunk)
        inBuf.rewind()
        OnnxTensor.createTensor(env, inBuf, shape).use { t ->
            session.run(mapOf("mix" to t)).use { res ->
                val y = res[0] as OnnxTensor
                val fb = y.floatBuffer
                fb.get(output, 0, minOf(output.size, fb.remaining()))
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private const val ASSET = "bandit_plus.onnx"
        private const val META = "model_meta.json"

        /** 初回だけアセットから内部ストレージへコピーして読み込む（数秒かかる） */
        fun load(context: Context): OnnxStemModel {
            val meta = JSONObject(context.assets.open(META).bufferedReader().readText())
            val dir = File(context.filesDir, "model").apply { mkdirs() }
            val file = File(dir, ASSET)
            val assetSize = context.assets.openFd(ASSET).use { it.length }
            if (!file.exists() || file.length() != assetSize) {
                Diagnostics.mark("LOADING: モデルをコピー中", "size=$assetSize")
                val tmp = File(dir, "$ASSET.tmp")
                context.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                tmp.renameTo(file)
            }
            Diagnostics.mark("LOADING: ONNX Runtime 初期化", "model=${file.length()} bytes")
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // 計算用メモリを使い回さず都度返す（常駐メモリを減らす。メモリ不足で落ちるのを防ぐ）
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
                // 高性能コア中心に使う（小さいコアまで使うと逆に遅くなることがある）
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 6))
            }
            Diagnostics.mark("LOADING: モデル読み込み (createSession)")
            val session = env.createSession(file.absolutePath, opts)
            val stemsJson = meta.getJSONArray("stems")
            return OnnxStemModel(
                env, session,
                sampleRate = meta.getInt("sample_rate"),
                chunk = meta.getInt("chunk"),
                numOverlap = meta.getInt("num_overlap"),
                stems = (0 until stemsJson.length()).map { stemsJson.getString(it) },
            )
        }
    }
}
