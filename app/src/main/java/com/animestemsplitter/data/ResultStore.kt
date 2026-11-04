package com.animestemsplitter.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/** 分離結果1件分（アプリ内に保存） */
data class StemResult(
    val id: String,
    val dir: File,
    val name: String,
    val durationSec: Double,
    val elapsedSec: Int,
    val createdAt: Long,
) {
    fun file(stem: String) = File(dir, "$stem.wav")
    val baseName: String get() = name.substringBeforeLast('.').ifBlank { "audio" }
}

val STEMS = listOf("dialogue", "music", "effects")

object ResultStore {
    private fun root(ctx: Context) = File(ctx.filesDir, "results").apply { mkdirs() }

    fun newWorkDir(ctx: Context): File =
        File(root(ctx), ".work_${System.currentTimeMillis()}").apply { mkdirs() }

    fun commit(
        ctx: Context, work: File, name: String,
        durationSec: Double, elapsedSec: Int, sourceInfo: String,
    ): StemResult {
        val id = System.currentTimeMillis().toString()
        val dir = File(root(ctx), id)
        val now = System.currentTimeMillis()
        JSONObject()
            .put("name", name).put("durationSec", durationSec).put("elapsedSec", elapsedSec)
            .put("createdAt", now).put("source", sourceInfo)
            .let { File(work, "info.json").writeText(it.toString()) }
        check(work.renameTo(dir)) { "結果を保存できませんでした" }
        return StemResult(id, dir, name, durationSec, elapsedSec, now)
    }

    /** 新しい順 */
    fun list(ctx: Context): List<StemResult> {
        root(ctx).listFiles { f -> f.name.startsWith(".work_") }?.forEach { it.deleteRecursively() }
        return root(ctx).listFiles()?.mapNotNull { dir ->
            runCatching {
                val j = JSONObject(File(dir, "info.json").readText())
                StemResult(
                    dir.name, dir, j.getString("name"), j.getDouble("durationSec"),
                    j.getInt("elapsedSec"), j.getLong("createdAt"),
                )
            }.getOrNull()
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    fun delete(r: StemResult) { r.dir.deleteRecursively() }

    /** 「ミュージック/アニメ音声分離」フォルダに WAV をコピーする */
    fun exportToMusic(ctx: Context, r: StemResult, stems: List<String> = STEMS): List<Uri> {
        val resolver = ctx.contentResolver
        return stems.map { stem ->
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${r.baseName}_$stem.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/アニメ音声分離")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("保存先を作れませんでした")
            resolver.openOutputStream(uri)!!.use { o -> r.file(stem).inputStream().use { it.copyTo(o, 1 shl 20) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }
    }
}
