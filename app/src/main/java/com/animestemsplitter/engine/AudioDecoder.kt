package com.animestemsplitter.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * 動画・音声ファイルの音声トラックを取り出し、ステレオ float（インターリーブ）で返す。
 * 5.1ch はステレオにダウンミックスする。サンプリングレートは元のまま。
 */
class AudioDecoder(private val context: Context, private val uri: Uri) {

    class Info(val durationUs: Long, val mime: String)

    /**
     * @param onFormat 最初の出力の直前に、元のサンプリングレートと長さ（マイクロ秒）で呼ばれる
     * @param onPcm   ステレオ float と、その位置（マイクロ秒）
     */
    fun decode(
        onFormat: (sampleRate: Int, durationUs: Long) -> Unit,
        onPcm: (data: FloatArray, frames: Int, positionUs: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Info {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("このファイルには音声トラックがありません。")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IllegalArgumentException("この音声形式（$mime）はこの端末で読み込めません。")
            }
            try {
                fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                codec.configure(fmt, null, null, 0)
                codec.start()
                run(ex, codec, { sr -> onFormat(sr, durationUs) }, onPcm, isCancelled)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return Info(durationUs, mime)
        } finally {
            ex.release()
        }
    }

    private fun run(
        ex: MediaExtractor,
        codec: MediaCodec,
        onFormat: (Int) -> Unit,
        onPcm: (FloatArray, Int, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var channels = 2
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var formatKnown = false
        var stereo = FloatArray(0)

        fun readFormat(f: MediaFormat) {
            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            if (!formatKnown) {
                formatKnown = true
                onFormat(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            }
        }

        while (true) {
            if (isCancelled()) throw CancelledException()
            if (!inputDone) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(idx, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 10_000)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readFormat(codec.outputFormat)
                out >= 0 -> {
                    if (!formatKnown) readFormat(codec.getOutputFormat(out))
                    val buf = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val frames = info.size / (bytesPerSample * channels)
                    if (frames > 0) {
                        if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                        val raw = FloatArray(channels)
                        for (f in 0 until frames) {
                            for (c in 0 until channels) {
                                raw[c] = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buf.getFloat()
                                else buf.getShort() / 32768f
                            }
                            downmix(raw, channels, stereo, f)
                        }
                        onPcm(stereo, frames, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun downmix(raw: FloatArray, ch: Int, dst: FloatArray, f: Int) {
        when {
            ch == 1 -> { dst[2 * f] = raw[0]; dst[2 * f + 1] = raw[0] }
            ch >= 6 -> {  // FL FR FC LFE BL BR …（ffmpeg と同じく正規化）
                val c = 0.70710677f
                val g = 1f / (1f + c + c)
                dst[2 * f] = (raw[0] + c * raw[2] + c * raw[4]) * g
                dst[2 * f + 1] = (raw[1] + c * raw[2] + c * raw[5]) * g
            }
            else -> { dst[2 * f] = raw[0]; dst[2 * f + 1] = raw[1] }
        }
    }
}
