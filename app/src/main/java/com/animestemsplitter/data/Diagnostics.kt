package com.animestemsplitter.data

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

    fun init(ctx: Context) {
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
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n----\n")
    }
}
