package com.bernardo.ereader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Runs audio-file jobs one at a time (queueing the rest) and reports progress to the UI and a notification. */
object RenderManager {

    class Progress(val render: Render, val startedAt: Long) {
        @Volatile var fraction = 0f
        @Volatile var audioMs = 0L
        /** Time left, from the pace so far (null until there's enough to tell). */
        val etaMs: Long? get() {
            val elapsed = System.currentTimeMillis() - startedAt
            return if (fraction < 0.02f) null else (elapsed * (1 - fraction) / fraction).toLong()
        }
    }

    private class Job(val render: Render, val text: String, val spec: Narrator.RenderSpec)

    @Volatile var current: Progress? = null
        private set
    private val waiting = mutableListOf<Job>()
    val isBusy get() = current != null
    fun isWaiting(id: String) = waiting.any { it.render.id == id }

    fun enqueue(ctx: Context, render: Render, text: String, spec: Narrator.RenderSpec) {
        Renders.save(ctx, render)
        waiting.add(Job(render, text, spec))
        AppLog.i("Render", "queued ${render.id}: ${render.label} (${render.describe()})")
        if (current == null) next(ctx.applicationContext)
    }

    fun cancel(ctx: Context, id: String) {
        waiting.removeAll { it.render.id == id }
        if (current?.render?.id == id) {
            Narrator.instance?.cancelRender()
            current = null
            RenderService.stop(ctx)
            next(ctx.applicationContext)
        }
        Renders.load(ctx, id)?.let { Renders.delete(ctx, it) }
        AppLog.i("Render", "cancelled $id")
    }

    /** Files left "processing" by a run that was closed mid-way. */
    fun markInterrupted(ctx: Context, bookId: String) {
        for (r in Renders.list(ctx, bookId)) {
            if (r.status == "processing" && current?.render?.id != r.id && !isWaiting(r.id)) {
                Renders.save(ctx, r.copy(status = "interrupted"))
            }
        }
    }

    private fun next(ctx: Context) {
        val job = if (waiting.isEmpty()) return else waiting.removeAt(0)
        val narrator = Narrator.instance
        if (narrator == null) {
            Renders.save(ctx, job.render.copy(status = "failed"))
            AppLog.e("Render", "no narrator for ${job.render.id}")
            return
        }
        val r = job.render
        val p = Progress(r, System.currentTimeMillis())
        current = p
        Renders.dir(ctx, r.id).mkdirs()
        RenderService.start(ctx, r)
        narrator.render(job.text, r.fromChar, r.toChar, job.spec, Narrator.RenderOut(
            writerFor = { rate -> AacWriter(Renders.audio(ctx, r.id), rate) },
            onProgress = { f, ms -> p.fraction = f; p.audioMs = ms; RenderService.update(ctx, p) },
            onDone = { ms, sync ->
                Renders.writeSync(ctx, r.id, sync)
                Renders.save(ctx, r.copy(status = "ready", durationMs = ms))
                if (Renders.activeId(ctx, r.bookId) == null) Renders.setActive(ctx, r.bookId, r.id)
                AppLog.i("Render", "done ${r.id}: ${ms / 1000}s audio, ${sync.size} sentences")
                current = null
                RenderService.finished(ctx, r, ok = true)
                next(ctx)
            },
            onError = { msg ->
                Renders.save(ctx, r.copy(status = "failed"))
                AppLog.e("Render", "failed ${r.id}: $msg")
                current = null
                RenderService.finished(ctx, r, ok = false)
                next(ctx)
            },
        ))
    }

    fun formatDuration(ms: Long): String {
        val min = (ms / 60_000).toInt()
        return if (min >= 60) "${min / 60} h ${min % 60} min" else if (min >= 1) "$min min" else "${ms / 1000} s"
    }
}

/** Keeps the process and CPU alive while an audio file is being made, with a progress notification. */
class RenderService : Service() {

    companion object {
        private const val CHANNEL = "render"
        private const val NOTIF = 2
        private const val DONE_NOTIF = 3
        private var title = ""

        fun start(ctx: Context, r: Render) {
            title = r.label
            ctx.startForegroundService(Intent(ctx, RenderService::class.java))
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, RenderService::class.java))

        fun update(ctx: Context, p: RenderManager.Progress) {
            val pct = (p.fraction * 100).toInt()
            val eta = p.etaMs?.let { " · about ${RenderManager.formatDuration(it)} left" } ?: ""
            ctx.getSystemService(NotificationManager::class.java)
                .notify(NOTIF, build(ctx, "Making audio: $title", "$pct%$eta", pct, ongoing = true))
        }

        fun finished(ctx: Context, r: Render, ok: Boolean) {
            stop(ctx)
            ctx.getSystemService(NotificationManager::class.java).notify(DONE_NOTIF,
                build(ctx, if (ok) "Audio ready: ${r.label}" else "Audio file failed: ${r.label}",
                    if (ok) r.describe() else "Open the app to try again", -1, ongoing = false))
        }

        private fun build(ctx: Context, title: String, text: String, pct: Int, ongoing: Boolean): Notification {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Making audio files", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(open)
                .setOngoing(ongoing).setAutoCancel(!ongoing)
                .apply { if (pct >= 0) setProgress(100, pct, false) }
                .build()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, "Making audio: $title", "Starting…", 0, ongoing = true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF, n)
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ereader:render").apply { acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }
}
