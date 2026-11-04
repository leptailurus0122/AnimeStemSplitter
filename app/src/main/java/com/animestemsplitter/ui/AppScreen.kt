package com.animestemsplitter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.ui.res.painterResource
import com.animestemsplitter.R
import com.animestemsplitter.data.JobRepository
import com.animestemsplitter.data.JobState
import com.animestemsplitter.data.ResultStore
import com.animestemsplitter.data.STEMS
import com.animestemsplitter.data.StemResult
import com.animestemsplitter.engine.Stage
import com.animestemsplitter.player.StemMixer
import com.animestemsplitter.service.SeparationService
import com.animestemsplitter.service.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppScreen(onPickFile: () -> Unit) {
    val ctx = LocalContext.current
    val job by JobRepository.state.collectAsState()
    val history = remember { mutableStateListOf<StemResult>() }
    var selected by remember { mutableStateOf<StemResult?>(null) }
    var trim by remember { mutableStateOf<Pair<StemResult, String>?>(null) }

    fun reload() {
        history.clear(); history.addAll(ResultStore.list(ctx))
        if (selected != null && history.none { it.id == selected!!.id }) selected = null
    }
    LaunchedEffect(Unit) { reload(); if (selected == null) selected = history.firstOrNull() }
    LaunchedEffect(job) {
        if (job is JobState.Done) { reload(); selected = (job as JobState.Done).result }
    }

    trim?.let { (r, stem) ->
        TrimScreen(r, stem, onClose = { trim = null })
        return
    }
    val onTrim: (StemResult, String) -> Unit = { r, stem -> trim = r to stem }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 640.dp   // 開いた Fold・タブレット
        val left: @Composable () -> Unit = {
            Header()
            Spacer(Modifier.height(20.dp))
            JobCard(job, onPickFile)
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                HistoryList(history, selected?.id, onSelect = { selected = it }, onDelete = {
                    ResultStore.delete(it); reload()
                })
            }
        }
        val right: @Composable () -> Unit = {
            val r = selected
            if (r != null) MixerCard(r, onTrim) else if (wide) EmptyMixer()
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 32.dp)) { left() }
                Column(Modifier.weight(1.15f).verticalScroll(rememberScrollState()).padding(vertical = 32.dp)) { right() }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 28.dp),
            ) {
                Header()
                Spacer(Modifier.height(20.dp))
                JobCard(job, onPickFile)
                selected?.let { Spacer(Modifier.height(16.dp)); MixerCard(it, onTrim) }
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    HistoryList(history, selected?.id, onSelect = { selected = it }, onDelete = {
                        ResultStore.delete(it); reload()
                    })
                }
            }
        }
    }
}

@Composable
private fun Header() {
    val dark = isSystemInDarkTheme()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("アニメ音声分離", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(8.dp))
        STEMS.forEach {
            Box(Modifier.padding(horizontal = 2.dp).size(10.dp).clip(CircleShape).background(StemColors.of(it, dark)))
        }
    }
    Text(
        "動画や音声を、セリフ・BGM・効果音の3トラックに分けます。処理はすべてこの端末の中で行います。",
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
    ) { Column(Modifier.padding(20.dp)) { content() } }
}

@Composable
private fun JobCard(job: JobState, onPickFile: () -> Unit) {
    val ctx = LocalContext.current
    SectionCard {
        when (job) {
            is JobState.Running -> {
                Text(job.fileName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                val text = when (job.stage) {
                    Stage.LOADING -> "モデルを読み込み中…"
                    Stage.DECODING -> "音声を読み込み中 ${(job.fraction * 100).toInt()}%"
                    Stage.SEPARATING -> "分離中 ${(job.fraction * 100).toInt()}%" +
                        (job.etaSec?.let { " ・残り約 ${formatTime(it)}" } ?: "")
                    Stage.SAVING -> "保存中…"
                }
                if (job.stage == Stage.SEPARATING || job.stage == Stage.DECODING) {
                    LinearProgressIndicator(
                        progress = { job.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
                }
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "アプリを閉じても処理は続きます。長い動画は時間がかかり、本体が温かくなります。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { SeparationService.cancel(ctx) }) { Text("キャンセル") }
            }
            else -> {
                if (job is JobState.Failed) {
                    Text("「${job.fileName}」を分離できませんでした", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text(job.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                }
                Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("動画・音声ファイルを選ぶ", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text(
                    "mp4 / mkv / webm / m4a / mp3 / wav など。ファイルアプリやフォトの「共有」からも送れます。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyMixer() {
    SectionCard {
        Text("分離が終わると、ここで3トラックを聴き比べられます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MixerCard(r: StemResult, onTrim: (StemResult, String) -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val mixer = remember(r.id) { runCatching { StemMixer(STEMS.map { r.file(it) }) }.getOrNull() }
    DisposableEffect(r.id) { onDispose { mixer?.release() } }
    if (mixer == null) {
        SectionCard { Text("ファイルを開けませんでした。", color = MaterialTheme.colorScheme.error) }
        return
    }
    val playing by mixer.playing.collectAsState()
    val pos by mixer.position.collectAsState()
    var dragging by remember(r.id) { mutableStateOf<Float?>(null) }

    SectionCard {
        Text(r.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "長さ ${formatTime(r.durationSec.toInt())} ・処理時間 ${formatTime(r.elapsedSec)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)
                    .clickable { mixer.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(2) { Box(Modifier.size(5.dp, 18.dp).background(MaterialTheme.colorScheme.surface)) }
                    }
                } else {
                    Icon(Icons.Filled.PlayArrow, "再生", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val frac = dragging ?: if (mixer.frames > 0) pos.toFloat() / mixer.frames else 0f
                Slider(
                    value = frac,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { mixer.seek((it * mixer.frames).toLong()) }
                        dragging = null
                    },
                )
                val cur = ((dragging?.times(mixer.frames) ?: pos.toFloat()) / mixer.sampleRate).toInt()
                Text(
                    "${formatTime(cur)} / ${formatTime((mixer.frames / mixer.sampleRate).toInt())}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        STEMS.forEachIndexed { i, stem ->
            StemRow(stem, StemColors.of(stem, dark), mixer, i, r) { onTrim(r, stem) }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { ResultStore.exportToMusic(ctx, r) }.isSuccess }
                        Toast.makeText(
                            ctx, if (ok) "「ミュージック/アニメ音声分離」に保存しました" else "保存できませんでした",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                shape = RoundedCornerShape(12.dp),
            ) { Text("3トラックを保存", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { onTrim(r, STEMS[0]) }, shape = RoundedCornerShape(12.dp)) {
                Icon(painterResource(R.drawable.ic_cut), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("切り取り")
            }
            OutlinedButton(onClick = { share(ctx, r, STEMS) }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("共有")
            }
        }
    }
}

@Composable
private fun StemRow(stem: String, color: Color, mixer: StemMixer, index: Int, r: StemResult, onTrim: () -> Unit) {
    val key = r.id
    val ctx = LocalContext.current
    var vol by remember(key) { mutableFloatStateOf(1f) }
    var mute by remember(key) { mutableStateOf(false) }
    var solo by remember(key) { mutableStateOf(false) }
    val (label, sub) = STEM_LABELS.getValue(stem)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(104.dp).background(color))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            // 1段目：名前と、切り取り・共有
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.Bold)
                Text("  $sub", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onTrim) {
                    Icon(painterResource(R.drawable.ic_cut), "$label を切り取る", Modifier.size(20.dp))
                }
                IconButton(onClick = { share(ctx, r, listOf(stem)) }) {
                    Icon(Icons.Filled.Share, "$label を共有", Modifier.size(20.dp))
                }
            }
            // 2段目：音量と、ソロ・ミュート
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = vol, onValueChange = { vol = it; mixer.setVolume(index, it) },
                    colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
                    modifier = Modifier.weight(1f).height(32.dp),
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = solo, onClick = { solo = !solo; mixer.setSolo(index, solo) }, label = { Text("ソロ", maxLines = 1) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = mute, onClick = { mute = !mute; mixer.setMute(index, mute) }, label = { Text("ミュート", maxLines = 1) })
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

@Composable
private fun HistoryList(
    items: List<StemResult>, selectedId: String?,
    onSelect: (StemResult) -> Unit, onDelete: (StemResult) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.JAPAN) }
    Text("これまでの分離", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    items.forEach { r ->
        val sel = r.id == selectedId
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                .background(if (sel) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .clickable { onSelect(r) }.padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                Text(
                    "${fmt.format(Date(r.createdAt))} ・ ${formatTime(r.durationSec.toInt())}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onDelete(r) }) { Icon(Icons.Filled.Delete, "削除") }
        }
    }
}

private fun share(ctx: Context, r: StemResult, stems: List<String>) {
    val auth = ctx.packageName + ".files"
    val uris = ArrayList<Uri>()
    stems.forEach { uris += FileProvider.getUriForFile(ctx, auth, r.file(it), "${r.baseName}_$it.wav") }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/wav").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "共有"))
}
