package com.animestemsplitter.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.animestemsplitter.engine.WavReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.concurrent.thread

/**
 * 3トラックを1つの AudioTrack に混ぜて再生する（サンプル単位で完全に同期）。
 * トラックごとの音量・ミュート・ソロに対応。
 */
class StemMixer(files: List<File>) {
    private val readers = files.map { WavReader(it) }
    val sampleRate = readers.first().sampleRate
    val frames = readers.minOf { it.frames }

    private val volume = FloatArray(readers.size) { 1f }
    private val mute = BooleanArray(readers.size)
    private val solo = BooleanArray(readers.size)
    private val current = FloatArray(readers.size) { 1f }   // 実際にかけている音量（クリック防止に滑らかに変える）

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position

    @Volatile private var readPos = 0L
    @Volatile private var seekTo = -1L
    @Volatile private var running = false
    @Volatile private var released = false
    private var track: AudioTrack? = null
    private var loop: Thread? = null
    private val lock = Object()

    fun setVolume(i: Int, v: Float) { volume[i] = v }
    fun setMute(i: Int, m: Boolean) { mute[i] = m }
    fun setSolo(i: Int, s: Boolean) { solo[i] = s }

    private fun target(i: Int): Float {
        val anySolo = solo.any { it }
        val audible = if (anySolo) solo[i] else !mute[i]
        return if (audible) volume[i] else 0f
    }

    fun toggle() = if (_playing.value) pause() else play()

    fun play() {
        if (released) return
        if (readPos >= frames) seek(0)
        synchronized(lock) {
            if (track == null) track = buildTrack()
            track!!.play()
            running = true
            _playing.value = true
            if (loop?.isAlive != true) loop = thread(name = "stem-mixer") { mixLoop() }
        }
    }

    fun pause() {
        synchronized(lock) {
            running = false
            _playing.value = false
            track?.pause()
        }
    }

    fun seek(frame: Long) {
        val f = frame.coerceIn(0, frames)
        seekTo = f
        _position.value = f
        if (!running) { readPos = f; seekTo = -1; synchronized(lock) { track?.flush() } }
    }

    fun release() {
        released = true
        pause()
        loop?.join(500)
        synchronized(lock) { track?.release(); track = null }
        readers.forEach { runCatching { it.close() } }
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build(),
            )
            .setBufferSizeInBytes(maxOf(min, 4096 * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun mixLoop() {
        val block = 2048
        val tmp = FloatArray(block * 2)
        val mix = FloatArray(block * 2)
        // track の再生ヘッドが playedBase のとき、聞こえている位置が headBase
        var playedBase = track?.playbackHeadPosition?.toLong() ?: 0L
        var headBase = _position.value
        while (running && !released) {
            val t = track ?: break
            val s = seekTo
            if (s >= 0) {
                seekTo = -1
                synchronized(lock) { t.pause(); t.flush(); if (running) t.play() }
                readPos = s
                headBase = s
                playedBase = t.playbackHeadPosition.toLong()
            }
            val n = minOf(block.toLong(), frames - readPos).toInt()
            if (n <= 0) {
                // 最後まで再生したら止める
                Thread.sleep(200)
                if (running) { pause(); readPos = frames; _position.value = frames }
                break
            }
            java.util.Arrays.fill(mix, 0, n * 2, 0f)
            for (i in readers.indices) {
                val got = readers[i].read(readPos, tmp, n)
                val goal = target(i)
                var g = current[i]
                val stepG = (goal - g) / n
                for (k in 0 until got) {
                    g += stepG
                    mix[2 * k] += tmp[2 * k] * g
                    mix[2 * k + 1] += tmp[2 * k + 1] * g
                }
                current[i] = goal
            }
            for (k in 0 until n * 2) mix[k] = mix[k].coerceIn(-1f, 1f)
            val w = t.write(mix, 0, n * 2, AudioTrack.WRITE_BLOCKING)
            if (w < 0) break
            readPos += n
            val head = (t.playbackHeadPosition.toLong() - playedBase).coerceAtLeast(0)
            if (seekTo < 0) _position.value = (headBase + head).coerceAtMost(frames)
        }
    }
}
