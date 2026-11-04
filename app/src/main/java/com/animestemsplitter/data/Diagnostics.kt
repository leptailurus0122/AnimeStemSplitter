package com.animestemsplitter.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 不具合調査用の記録。
 * - 落ちたときのエラー内容（crash.txt）
 * - 処理中にどこまで進んだか（running.txt）。アプリが強制終了すると残るので、次回起動時に分かる
 */
object Diagnostics {
    private lateinit var dir: File
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        dir = File(ctx.filesDir, "diag").apply { mkdirs() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash("スレッド ${t.name} で例外", e) }
            prev?.uncaughtException(t, e)
        }
    }

    private fun header(): String =
        "日時: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())}\n" +
            "端末: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n"

    fun writeCrash(where: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        File(dir, "crash.txt").writeText(header() + "場所: $where\n\n" + sw.toString())
    }

    /** 処理の段階を記録（段階が変わったときだけ呼ぶ） */
    fun mark(stage: String, detail: String = "") {
        if (!::dir.isInitialized) return
        runCatching { File(dir, "running.txt").writeText(header() + "段階: $stage\n$detail\n") }
    }

    fun clearMark() { if (::dir.isInitialized) File(dir, "running.txt").delete() }

    /** 前回の異常終了の記録があれば返して消す（処理中なら running.txt は見ない） */
    fun takeReport(jobRunningNow: Boolean): String? {
        if (!::dir.isInitialized) return null
        val crash = File(dir, "crash.txt")
        val running = File(dir, "running.txt")
        val parts = mutableListOf<String>()
        if (crash.exists()) { parts += crash.readText(); crash.delete() }
        if (!jobRunningNow && running.exists()) {
            parts += "【前回の処理が途中で強制終了しました】\n" + running.readText()
            running.delete()
        }
        lastAbnormalExit()?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n----\n")
    }

    /** Android が記録している「前回アプリが終わった理由」（強制終了・ネイティブクラッシュ・メモリ不足など） */
    fun lastAbnormalExit(): String? = runCatching {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = app.getSharedPreferences("diag", Context.MODE_PRIVATE)
        val seen = prefs.getLong("lastExitTs", 0L)
        val infos = am.getHistoricalProcessExitReasons(null, 0, 5).filter { it.timestamp > seen }
        infos.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("lastExitTs", it).apply() }
        val bad = infos.filter {
            it.reason in setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_UNKNOWN,
            )
        }
        if (bad.isEmpty()) return null
        bad.joinToString("\n\n") { describeExit(it) }
    }.getOrElse { "（終了理由を取得できませんでした: ${it.message}）" }

    fun describeExit(e: ApplicationExitInfo): String {
        val reason = when (e.reason) {
            ApplicationExitInfo.REASON_CRASH -> "Java の例外で終了"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "ネイティブコードのクラッシュ"
            ApplicationExitInfo.REASON_ANR -> "応答なし (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "メモリ不足でシステムが終了"
            ApplicationExitInfo.REASON_SIGNALED -> "シグナル ${e.status} で終了"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "リソースの使いすぎでシステムが終了"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初期化の失敗"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依存プロセスの終了"
            else -> "その他 (reason=${e.reason})"
        }
        val sb = StringBuilder()
        sb.append("【Android の記録】$reason\n")
        sb.append("時刻: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date(e.timestamp))}\n")
        sb.append("status=${e.status} importance=${e.importance} pss=${e.pss / 1024}MB rss=${e.rss / 1024}MB\n")
        e.description?.let { sb.append("説明: $it\n") }
        if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching {
                e.traceInputStream?.use { ins ->
                    val bytes = ins.readBytes()
                    // ネイティブクラッシュは tombstone（protobuf）なので、読める文字列だけ抜き出す
                    val text = if (e.reason == ApplicationExitInfo.REASON_ANR) String(bytes)
                    else Regex("[\\x20-\\x7E]{6,}").findAll(String(bytes, Charsets.ISO_8859_1))
                        .map { it.value }.filter { it.contains(".so") || it.contains("Abort") || it.contains("signal") || it.contains("SIG") || it.contains("cause") || it.contains("abort") }
                        .distinct().take(40).joinToString("\n")
                    sb.append("\n").append(text.take(6000))
                }
            }
        }
        return sb.toString()
    }
}
