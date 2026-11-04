package com.animestemsplitter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.animestemsplitter.data.Diagnostics
import com.animestemsplitter.data.JobRepository
import com.animestemsplitter.data.JobState
import com.animestemsplitter.service.SeparationService
import com.animestemsplitter.ui.AppScreen
import com.animestemsplitter.ui.AppTheme
import com.animestemsplitter.ui.ReportDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startSeparation(uri)
    }

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val report = Diagnostics.takeReport(JobRepository.state.value is JobState.Running)
        setContent {
            AppTheme {
                var shown by remember { mutableStateOf(report) }
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    AppScreen(onPickFile = { pickFile.launch(arrayOf("video/*", "audio/*")) })
                }
                shown?.let { text -> ReportDialog(text) { shown = null } }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** ファイルアプリやフォトの「共有」から送られてきた場合 */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        startSeparation(uri)
    }

    private fun startSeparation(uri: Uri) {
        if (JobRepository.state.value is JobState.Running) {
            Toast.makeText(this, "いまの分離が終わってから選んでください", Toast.LENGTH_SHORT).show()
            return
        }
        val name = displayName(uri)
        try {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            SeparationService.start(this, uri, name)
        } catch (e: Throwable) {
            Diagnostics.writeCrash("ファイル選択後", e)
            JobRepository.update(JobState.Failed(name, "${e.javaClass.simpleName}: ${e.message ?: ""}"))
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "audio"
}
