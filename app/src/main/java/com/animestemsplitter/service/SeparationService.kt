package com.animestemsplitter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.animestemsplitter.MainActivity
import com.animestemsplitter.R
import com.animestemsplitter.data.JobRepository
import com.animestemsplitter.data.JobState
import com.animestemsplitter.engine.CancelledException
import com.animestemsplitter.engine.SeparationPipeline
import com.animestemsplitter.engine.Stage
import kotlin.concurrent.thread

/**
 * 分離処理を画面を閉じても続けられるよう、フォアグラウンドサービスで実行する。
 * Android 15 以降は「メディア処理」タイプ、それより前は「データ同期」タイプ。
 */
class SeparationService : Service() {

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            JobRepository.cancelRequested = true
            return START_NOT_STICKY
        }
        val uri = intent?.data ?: return START_NOT_STICKY.also { stopSelf() }
        val name = intent.getStringExtra(EXTRA_NAME) ?: "audio"
        if (worker?.isAlive == true) return START_NOT_STICKY

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(name, "準備中…", -1), type)

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnimeStemSplitter:separate")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        JobRepository.cancelRequested = false
        JobRepository.update(JobState.Running(name, Stage.LOADING, 0f, null))
        worker = thread(name = "separation", priority = Thread.MAX_PRIORITY) { work(uri, name) }
        return START_NOT_STICKY
    }

    private fun work(uri: Uri, name: String) {
        try {
            val result = SeparationPipeline(applicationContext).run(
                uri, name,
                onProgress = { stage, frac, eta ->
                    JobRepository.update(JobState.Running(name, stage, frac, eta))
                    notifyProgress(name, stage, frac, eta)
                },
                isCancelled = { JobRepository.cancelRequested },
            )
            JobRepository.update(JobState.Done(result))
            finishNotification(name, "分離が完了しました")
        } catch (e: CancelledException) {
            JobRepository.update(JobState.Idle)
            cancelNotification()
        } catch (e: OutOfMemoryError) {
            JobRepository.update(JobState.Failed(name, "メモリが足りませんでした。ほかのアプリを閉じてもう一度お試しください。"))
            finishNotification(name, "分離に失敗しました")
        } catch (e: Throwable) {
            JobRepository.update(JobState.Failed(name, e.message ?: e.javaClass.simpleName))
            finishNotification(name, "分離に失敗しました")
        } finally {
            wakeLock?.takeIf { it.isHeld }?.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    // Android 15 以降：メディア処理は 1 日あたり 6 時間まで
    override fun onTimeout(startId: Int, fgsType: Int) {
        JobRepository.cancelRequested = true
    }

    private fun stageText(stage: Stage, frac: Float, eta: Int?): String = when (stage) {
        Stage.LOADING -> "モデルを読み込み中…"
        Stage.DECODING -> "音声を読み込み中 ${(frac * 100).toInt()}%"
        Stage.SEPARATING -> "分離中 ${(frac * 100).toInt()}%" + (eta?.let { " ・残り約${formatTime(it)}" } ?: "")
        Stage.SAVING -> "保存中…"
    }

    private fun notifyProgress(name: String, stage: Stage, frac: Float, eta: Int?) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000 && frac < 1f) return
        lastNotify = now
        val pct = if (stage == Stage.SEPARATING) (frac * 100).toInt() else -1
        nm().notify(NOTIF_ID, buildNotification(name, stageText(stage, frac, eta), pct))
    }

    private fun buildNotification(name: String, text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, SeparationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .addAction(0, "キャンセル", cancel)
            .build()
    }

    private fun finishNotification(name: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm().notify(
            NOTIF_DONE_ID,
            NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text)
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
        nm().cancel(NOTIF_ID)
    }

    private fun cancelNotification() = nm().cancel(NOTIF_ID)

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        nm().createNotificationChannel(
            NotificationChannel(CHANNEL, "分離の進捗", NotificationManager.IMPORTANCE_LOW),
        )
        nm().createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "分離の完了", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val CHANNEL = "progress"
        private const val CHANNEL_DONE = "done"
        private const val NOTIF_ID = 1
        private const val NOTIF_DONE_ID = 2
        private const val EXTRA_NAME = "name"
        private const val ACTION_CANCEL = "cancel"

        fun start(ctx: Context, uri: Uri, name: String) {
            val i = Intent(ctx, SeparationService::class.java).setData(uri)
                .putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) {
            JobRepository.cancelRequested = true
        }
    }
}

fun formatTime(sec: Int): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
