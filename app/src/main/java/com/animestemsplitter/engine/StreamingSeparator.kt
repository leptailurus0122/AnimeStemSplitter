package com.animestemsplitter.engine

/**
 * 長い音声を 6 秒ずつ（1/4 ずつ重ねて）モデルに通し、フェード付きで重ね合わせて元の長さに戻す。
 * PC 版（separator.py）と同じ計算を、メモリをほとんど使わないストリーミング形式で行う。
 *
 * - 入力: [PcmSource]（44.1kHz ステレオ）
 * - モデル: [ChunkModel]  入力 [2 * chunk]（L 全部 → R 全部）→ 出力 [stems * 2 * chunk]
 * - 出力: 確定したサンプルから順に [sink] へ（トラックごとにインターリーブのステレオ）
 */
interface PcmSource {
    val frames: Long
    /** frame 番目のサンプル（ch = 0:L, 1:R） */
    fun get(frame: Long, ch: Int): Float
}

fun interface ChunkModel {
    fun run(input: FloatArray, output: FloatArray)
}

fun interface StemSink {
    /** blocks[stem] はインターリーブのステレオ。frames フレーム分が有効 */
    fun write(blocks: Array<FloatArray>, frames: Int)
}

class StreamingSeparator(
    private val chunk: Int,
    numOverlap: Int,
    private val stems: Int,
) {
    private val step = chunk / numOverlap
    private val fade = chunk / 10
    private val border = chunk - step

    /** 処理するチャンク数（進捗表示用） */
    fun chunkCount(frames: Long): Int {
        val pad = if (frames > 2L * border) border.toLong() else 0L
        val total = frames + 2 * pad
        return ((total + step - 1) / step).toInt()
    }

    fun run(
        src: PcmSource,
        model: ChunkModel,
        sink: StemSink,
        onChunk: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val n = src.frames
        require(n > 0) { "音声が空です" }
        val pad = if (n > 2L * border) border.toLong() else 0L
        val total = n + 2 * pad
        val count = chunkCount(n)

        val baseWin = FloatArray(chunk) { 1f }
        for (i in 0 until fade) {
            val v = if (fade > 1) i.toFloat() / (fade - 1) else 1f
            baseWin[i] = v
            baseWin[chunk - fade + i] = 1f - v
        }

        val input = FloatArray(2 * chunk)
        val out = FloatArray(stems * 2 * chunk)
        // 重ね合わせ用リングバッファ（位置 p は p % chunk に置く）
        val acc = Array(stems) { FloatArray(2 * chunk) }
        val cnt = FloatArray(chunk)
        val flushBlock = 4096
        val blocks = Array(stems) { FloatArray(flushBlock * 2) }
        var flushed = 0L   // ここより前の位置は出力済み

        fun original(p: Long): Long {         // パディング込みの位置 → 元の位置（反射）
            var o = p - pad
            if (o < 0) o = -o
            if (o >= n) o = 2 * (n - 1) - o
            return o.coerceIn(0, n - 1)
        }

        fun flushUntil(end: Long) {
            var p = flushed
            while (p < end) {
                val len = minOf(flushBlock.toLong(), end - p).toInt()
                var outFrames = 0
                for (j in 0 until len) {
                    val pos = p + j
                    val r = (pos % chunk).toInt()
                    val o = pos - pad
                    val c = cnt[r].coerceAtLeast(1e-8f)
                    if (o in 0 until n) {
                        for (s in 0 until stems) {
                            blocks[s][2 * outFrames] = acc[s][2 * r] / c
                            blocks[s][2 * outFrames + 1] = acc[s][2 * r + 1] / c
                        }
                        outFrames++
                    }
                    for (s in 0 until stems) { acc[s][2 * r] = 0f; acc[s][2 * r + 1] = 0f }
                    cnt[r] = 0f
                }
                if (outFrames > 0) sink.write(blocks, outFrames)
                p += len
            }
            flushed = end
        }

        var start = 0L
        var done = 0
        while (start < total) {
            if (isCancelled()) throw CancelledException()
            val len = minOf(chunk.toLong(), total - start).toInt()
            for (i in 0 until len) {
                val o = original(start + i)
                input[i] = src.get(o, 0)
                input[chunk + i] = src.get(o, 1)
            }
            if (len < chunk) {
                val reflect = len > chunk / 2
                for (i in len until chunk) {
                    val j = 2 * (len - 1) - i
                    input[i] = if (reflect) input[j] else 0f
                    input[chunk + i] = if (reflect) input[chunk + j] else 0f
                }
            }

            model.run(input, out)

            val first = start == 0L
            val last = start + step >= total
            for (i in 0 until len) {
                var w = baseWin[i]
                if (first && i < fade) w = 1f
                if (last && i >= chunk - fade) w = 1f
                val r = ((start + i) % chunk).toInt()
                for (s in 0 until stems) {
                    val base = s * 2 * chunk
                    acc[s][2 * r] += out[base + i] * w
                    acc[s][2 * r + 1] += out[base + chunk + i] * w
                }
                cnt[r] += w
            }

            done++
            flushUntil(if (last) total else start + step)
            onChunk(done, count)
            start += step
        }
        if (flushed < total) flushUntil(total)
    }
}

class CancelledException : RuntimeException("キャンセルされました")
