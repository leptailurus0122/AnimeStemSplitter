package com.animestemsplitter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.animestemsplitter.R
import com.animestemsplitter.data.ResultStore
import com.animestemsplitter.data.STEMS
import com.animestemsplitter.data.StemResult
import com.animestemsplitter.engine.Clip
import com.animestemsplitter.engine.WavReader
import com.animestemsplitter.player.StemMixer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val OVERVIEW_BUCKETS = 12000

/** 全体の波形（細かめ）を計算して、結果フォルダにキャッシュする */
private fun overviewPeaks(r: StemResult, stem: String, frames: Long): FloatArray {
    val cache = File(r.dir, ".$stem.peaks")
    runCatching {
        DataInputStream(cache.inputStream().buffered()).use { d ->
            if (d.readLong() == frames) {
                val n = d.readInt()
                return FloatArray(n) { d.readFloat() }
            }
        }
    }
    val p = Clip.peaks(r.file(stem), 0, frames, OVERVIEW_BUCKETS)
    runCatching {
        DataOutputStream(cache.outputStream().buffered()).use { o ->
            o.writeLong(frames); o.writeInt(p.size); p.forEach { o.writeFloat(it) }
        }
    }
    return p
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrimScreen(r: StemResult, initialStem: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()

    val info = remember(r.id) { runCatching { WavReader(r.file(STEMS[0])).use { it.frames to it.sampleRate } }.getOrNull() }
    if (info == null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
            Text("ファイルを開けませんでした。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onClose) { Text("戻る") }
        }
        return
    }
    val (frames, sr) = info
    fun sec(f: Long) = f.toDouble() / sr
    fun fr(s: Double) = (s * sr).toLong().coerceIn(0, frames)

    var stem by remember { mutableStateOf(initialStem) }
    val color = StemColors.of(stem, dark)
    var selStart by remember { mutableLongStateOf(0L) }
    var selEnd by remember { mutableLongStateOf(min(frames, 15L * sr)) }
    var viewStart by remember { mutableLongStateOf(0L) }
    var viewEnd by remember { mutableLongStateOf(frames) }
    var loop by remember { mutableStateOf(true) }
    var fade by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }

    // 波形データ
    val overview = remember { mutableStateMapOf<String, FloatArray>() }
    var viewPeaks by remember { mutableStateOf(FloatArray(0)) }
    var widthPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(stem) {
        if (overview[stem] == null) {
            overview[stem] = withContext(Dispatchers.IO) { overviewPeaks(r, stem, frames) }
        }
    }
    LaunchedEffect(stem, viewStart, viewEnd, widthPx, overview[stem]) {
        val fine = overview[stem] ?: return@LaunchedEffect
        val buckets = (widthPx / 3).coerceAtLeast(1)
        val perFine = frames.toDouble() / fine.size
        val perBucket = (viewEnd - viewStart).toDouble() / buckets
        viewPeaks = if (perBucket >= perFine * 2) {
            Clip.downsample(fine, frames, viewStart, viewEnd, buckets)
        } else {
            delay(80)  // 連続操作中は計算を間引く
            withContext(Dispatchers.IO) { Clip.peaks(r.file(stem), viewStart, viewEnd, buckets) }
        }
    }

    // 再生（選んだトラックだけ）
    val mixer = remember(stem) { runCatching { StemMixer(listOf(r.file(stem))) }.getOrNull() }
    DisposableEffect(stem) { onDispose { mixer?.release() } }
    LaunchedEffect(mixer, selStart, selEnd, loop) { mixer?.setRange(selStart, selEnd, loop) }
    val playing by (mixer?.playing ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val pos by (mixer?.position ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }).collectAsState()

    fun setSel(a: Long, b: Long) {
        val minLen = sr / 20L   // 0.05 秒
        var s = a.coerceIn(0, frames)
        var e = b.coerceIn(0, frames)
        if (e < s) { val t = s; s = e; e = t }
        if (e - s < minLen) e = min(frames, s + minLen)
        selStart = s; selEnd = e
    }
    fun fitView(a: Long, b: Long) {
        val len = max(b - a, sr / 2L)
        val margin = len / 8
        viewStart = (a - margin).coerceAtLeast(0)
        viewEnd = (b + margin).coerceAtMost(frames)
    }
    fun zoom(factor: Double) {
        val center = (selStart + selEnd) / 2
        val half = ((viewEnd - viewStart) * factor / 2).toLong().coerceIn(sr / 4L, frames / 2 + 1)
        var a = center - half
        var b = center + half
        if (a < 0) { b -= a; a = 0 }
        if (b > frames) { a -= (b - frames); b = frames }
        viewStart = a.coerceAtLeast(0); viewEnd = b
    }

    fun clipName(s: String) = "${r.baseName}_${s}_${"%.1f".format(sec(selStart))}s-${"%.1f".format(sec(selEnd))}s.wav"
    suspend fun makeClips(stems: List<String>): List<File> = withContext(Dispatchers.IO) {
        stems.map { s ->
            val out = File(ResultStore.clipsDir(r), clipName(s))
            Clip.cutWav(r.file(s), out, selStart, selEnd, if (fade) sr / 200 else 0)
            out
        }
    }
    fun save(stems: List<String>) = scope.launch {
        busy = "保存中…"
        val ok = runCatching {
            val files = makeClips(stems)
            withContext(Dispatchers.IO) { files.forEach { ResultStore.saveToMusic(ctx, it, it.name) } }
        }
        busy = null
        Toast.makeText(
            ctx, if (ok.isSuccess) "「ミュージック/アニメ音声分離」に${stems.size}件保存しました" else "保存できませんでした: ${ok.exceptionOrNull()?.message}",
            Toast.LENGTH_LONG,
        ).show()
    }
    fun share(stems: List<String>) = scope.launch {
        busy = "書き出し中…"
        val files = runCatching { makeClips(stems) }.getOrNull()
        busy = null
        if (files != null) shareFiles(ctx, files)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "閉じる") }
            Column(Modifier.weight(1f)) {
                Text("切り取り", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(r.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))

        // トラック選択
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STEMS.forEach { s ->
                val (label, _) = STEM_LABELS.getValue(s)
                FilterChip(selected = s == stem, onClick = { mixer?.pause(); stem = s }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // 波形（拡大表示）
        val density = LocalDensity.current
        val handleTouch = with(density) { 28.dp.toPx() }
        val surfaceVar = MaterialTheme.colorScheme.surfaceVariant
        val onSurface = MaterialTheme.colorScheme.onSurface
        val vs by rememberUpdatedState(viewStart)
        val ve by rememberUpdatedState(viewEnd)
        val ss by rememberUpdatedState(selStart)
        val se by rememberUpdatedState(selEnd)
        Box(
            Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)).background(surfaceVar)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(frames) {
                    detectTapGestures { o ->
                        val f = vs + ((ve - vs) * (o.x / size.width)).toLong()
                        mixer?.seek(f.coerceIn(ss, se))
                    }
                }
                .pointerInput(frames) {
                    var mode = 0      // 1:開始 2:終了 3:新しく選ぶ
                    var anchor = 0L
                    detectDragGestures(
                        onDragStart = { o ->
                            val toX = { f: Long -> ((f - vs).toFloat() / (ve - vs)) * size.width }
                            val dS = abs(o.x - toX(ss))
                            val dE = abs(o.x - toX(se))
                            mode = when {
                                dS <= handleTouch && dS <= dE -> 1
                                dE <= handleTouch -> 2
                                else -> 3
                            }
                            anchor = vs + ((ve - vs) * (o.x / size.width)).toLong()
                            if (mode == 3) setSel(anchor, anchor)
                        },
                        onDrag = { change, _ ->
                            val f = vs + ((ve - vs) * (change.position.x / size.width).coerceIn(0f, 1f)).toLong()
                            when (mode) {
                                1 -> setSel(f, se)
                                2 -> setSel(ss, f)
                                3 -> setSel(anchor, f)
                            }
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val span = (viewEnd - viewStart).toFloat().coerceAtLeast(1f)
                fun x(f: Long) = (f - viewStart) / span * w
                val sx = x(selStart).coerceIn(0f, w)
                val ex = x(selEnd).coerceIn(0f, w)
                drawRect(color.copy(alpha = 0.16f), topLeft = Offset(sx, 0f), size = Size(ex - sx, h))
                val n = viewPeaks.size
                if (n > 0) {
                    val bw = w / n
                    for (i in 0 until n) {
                        val cx = i * bw + bw / 2
                        val amp = (viewPeaks[i].coerceAtMost(1f)) * (h / 2 - 4)
                        val inSel = cx in sx..ex
                        drawLine(
                            if (inSel) color else onSurface.copy(alpha = 0.35f),
                            Offset(cx, h / 2 - amp), Offset(cx, h / 2 + amp), strokeWidth = max(1f, bw * 0.6f),
                        )
                    }
                }
                // 開始・終了ハンドル
                listOf(selStart, selEnd).forEach { f ->
                    val hx = x(f)
                    if (hx in -2f..(w + 2f)) {
                        drawLine(color, Offset(hx, 0f), Offset(hx, h), strokeWidth = 4f)
                        drawCircle(color, radius = 12f, center = Offset(hx, h - 14f))
                        drawCircle(color, radius = 12f, center = Offset(hx, 14f))
                    }
                }
                // 再生位置
                val px = x(pos)
                if (playing && px in 0f..w) drawLine(onSurface, Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
            }
            if (overview[stem] == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 3.dp)
            }
        }
        Text(
            "表示中 ${Clip.formatSec(sec(viewStart))} 〜 ${Clip.formatSec(sec(viewEnd))}　・ハンドルをドラッグで範囲を調整、何もないところをなぞると新しく選択",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        // 全体（ミニ波形）：タップした場所を中心に表示
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(surfaceVar)
                .pointerInput(frames) {
                    detectTapGestures { o ->
                        val c = (frames * (o.x / size.width)).toLong()
                        val half = (ve - vs) / 2
                        var a = c - half; var b = c + half
                        if (a < 0) { b -= a; a = 0 }
                        if (b > frames) { a -= (b - frames); b = frames }
                        viewStart = a.coerceAtLeast(0); viewEnd = b
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val fine = overview[stem] ?: return@Canvas
                val buckets = (size.width / 3).toInt().coerceAtLeast(1)
                val p = Clip.downsample(fine, frames, 0, frames, buckets)
                val bw = size.width / buckets
                val h = size.height
                for (i in p.indices) {
                    val cx = i * bw + bw / 2
                    val amp = p[i].coerceAtMost(1f) * (h / 2 - 2)
                    drawLine(onSurface.copy(alpha = 0.3f), Offset(cx, h / 2 - amp), Offset(cx, h / 2 + amp), strokeWidth = max(1f, bw * 0.6f))
                }
                fun x(f: Long) = f.toFloat() / frames * size.width
                drawRect(color.copy(alpha = 0.35f), topLeft = Offset(x(selStart), 0f), size = Size(max(2f, x(selEnd) - x(selStart)), h))
                drawRect(onSurface.copy(alpha = 0.12f), topLeft = Offset(x(viewStart), 0f), size = Size(x(viewEnd) - x(viewStart), h))
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { fitView(selStart, selEnd) }) { Text("選択範囲を拡大") }
            OutlinedButton(onClick = { zoom(0.5) }) { Text("拡大") }
            OutlinedButton(onClick = { zoom(2.0) }) { Text("縮小") }
            OutlinedButton(onClick = { viewStart = 0; viewEnd = frames }) { Text("全体") }
        }
        Spacer(Modifier.height(12.dp))

        // 開始・終了の微調整
        TimeAdjustRow("開始", sec(selStart), color,
            onMinus = { setSel(selStart - sr / 10, selEnd) }, onPlus = { setSel(selStart + sr / 10, selEnd) },
            onSetHere = { setSel(pos, selEnd) })
        TimeAdjustRow("終了", sec(selEnd), color,
            onMinus = { setSel(selStart, selEnd - sr / 10) }, onPlus = { setSel(selStart, selEnd + sr / 10) },
            onSetHere = { setSel(selStart, pos) })
        Text(
            "長さ ${Clip.formatSec(sec(selEnd - selStart))}",
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        // 再生
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)
                    .clickable { mixer?.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(2) { Box(Modifier.size(5.dp, 18.dp).background(MaterialTheme.colorScheme.surface)) }
                    }
                } else {
                    Icon(Icons.Filled.PlayArrow, "選択範囲を再生", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("選択範囲を再生", fontWeight = FontWeight.Bold)
                Text(Clip.formatSec(sec(pos)), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilterChip(selected = loop, onClick = { loop = !loop }, label = { Text("ループ") })
        }
        Spacer(Modifier.height(8.dp))
        FilterChip(
            selected = fade, onClick = { fade = !fade },
            label = { Text("切り口をなめらかに（5ミリ秒のフェード）") },
        )
        Spacer(Modifier.height(12.dp))

        // 書き出し
        val (label, _) = STEM_LABELS.getValue(stem)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { save(listOf(stem)) }, enabled = busy == null, shape = RoundedCornerShape(12.dp)) {
                Icon(painterResource(R.drawable.ic_cut), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("${label}を保存", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { share(listOf(stem)) }, enabled = busy == null, shape = RoundedCornerShape(12.dp)) { Text("共有") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { save(STEMS) }, enabled = busy == null, shape = RoundedCornerShape(12.dp)) {
                Text("3トラックとも同じ範囲で保存")
            }
            TextButton(onClick = { share(STEMS) }, enabled = busy == null) { Text("3トラックを共有") }
        }
        busy?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text(it, fontSize = 13.sp)
            }
        }
        Text(
            "保存先：ミュージック/アニメ音声分離（WAV）",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun TimeAdjustRow(label: String, sec: Double, color: Color, onMinus: () -> Unit, onPlus: () -> Unit, onSetHere: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 36.dp))
        Text(Clip.formatSec(sec), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−0.1秒") }
        TextButton(onClick = onPlus) { Text("+0.1秒") }
        TextButton(onClick = onSetHere) { Text("再生位置") }
    }
}

private fun shareFiles(ctx: Context, files: List<File>) {
    val auth = ctx.packageName + ".files"
    val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, auth, it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/wav").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "共有"))
}
