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

    class Progress(@Volatile var render: Render, val startedAt: Long) {
        /** Where this run started (a file can be continued after a pause), for the time-left estimate. */
        private val startFraction = render.fraction
        @Volatile var fraction = startFraction
        @Volatile var audioMs = render.durationMs
        /** Time left, from the pace so far (null until there's enough to tell). */
        val etaMs: Long? get() {
            val elapsed = System.currentTimeMillis() - startedAt
            val done = fraction - startFraction
            return if (done < 0.02f) null else (elapsed * (1 - fraction) / done).toLong()
        }
    }

    private class Job(val render: Render, val text: String, val spec: Narrator.RenderSpec)

    @Volatile var current: Progress? = null
        private set
    private val waiting = mutableListOf<Job>()
    val isBusy get() = current != null
    fun isWaiting(id: String) = waiting.any { it.render.id == id }
    /** "Continue later" was pressed: the file stops at the next sentence end. */
    @Volatile var pausing: String? = null
        private set

    /** Make [render], or continue it from where it stopped ([Render.doneChar], keeping its finished parts). */
    fun enqueue(ctx: Context, render: Render, text: String, spec: Narrator.RenderSpec) {
        val r = render.copy(status = "processing")
        Renders.save(ctx, r)
        waiting.add(Job(r, text, spec))
        AppLog.i("Render", "queued ${r.id}: ${r.label} (${r.describe()})" + if (r.parts.isNotEmpty()) ", continuing at ${(r.fraction * 100).toInt()}%" else "")
        if (current == null) next(ctx.applicationContext)
    }

    fun cancel(ctx: Context, id: String) {
        waiting.removeAll { it.render.id == id }
        if (current?.render?.id == id) {
            Narrator.instance?.cancelRender()
            current = null
            pausing = null
            RenderService.stop(ctx)
            next(ctx.applicationContext)
        }
        Renders.load(ctx, id)?.let { Renders.delete(ctx, it) }
        AppLog.i("Render", "cancelled $id")
    }

    /** "Continue later": keep everything made so far as a playable file and stop; [enqueue] picks it up again. */
    fun pause(ctx: Context, id: String) {
        if (waiting.removeAll { it.render.id == id }) {
            Renders.load(ctx, id)?.let { Renders.save(ctx, it.copy(status = "paused")) }
            AppLog.i("Render", "paused $id before it started")
            return
        }
        if (current?.render?.id != id) return
        pausing = id
        AppLog.i("Render", "pausing $id at the next sentence end")
        Narrator.instance?.pauseRender() ?: run {
            Renders.load(ctx, id)?.let { Renders.save(ctx, it.copy(status = "paused")) }
            current = null; pausing = null
            RenderService.stop(ctx)
        }
    }

    /** Files left "processing" by a run that was closed mid-way (what was finished is kept and can be continued). */
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
        // the latest state on disk: parts finished before a pause or an interruption are kept
        var r = Renders.load(ctx, job.render.id) ?: job.render
        if (narrator == null) {
            Renders.save(ctx, r.copy(status = "failed"))
            AppLog.e("Render", "no narrator for ${r.id}")
            return
        }
        // sentences of the kept parts only (a part being written when the app was closed is made again)
        val sync = Renders.readSync(ctx, r.id).filter { it.ms < r.durationMs && it.start < r.doneChar }.toMutableList()
        val firstPart = r.parts.size
        val p = Progress(r, System.currentTimeMillis())
        current = p
        pausing = null
        Renders.dir(ctx, r.id).mkdirs()
        RenderService.start(ctx, r)
        val span = (r.toChar - r.fromChar).coerceAtLeast(1).toFloat()
        val baseMs = r.durationMs
        narrator.render(job.text, r.doneChar, r.toChar, job.spec, Narrator.RenderOut(
            writerFor = { rate, i -> AacWriter(Renders.partFile(ctx, r.id, firstPart + i), rate) },
            onProgress = { upTo, ms ->
                p.fraction = ((upTo - r.fromChar) / span).coerceIn(0f, 1f); p.audioMs = baseMs + ms
                RenderService.update(ctx, p)
            },
            onPart = { ms, upTo, points ->
                val shift = r.durationMs
                sync += points.map { SyncPoint(it.start, it.end, it.ms + shift) }
                Renders.writeSync(ctx, r.id, sync)
                r = r.copy(parts = r.parts + ms, doneChar = upTo, durationMs = shift + ms)
                Renders.save(ctx, r)
                p.render = r
                // playable now: if the book has no file in use, this one is it
                if (Renders.activeId(ctx, r.bookId) == null) Renders.setActive(ctx, r.bookId, r.id)
            },
            onDone = {
                r = r.copy(status = "ready", doneChar = r.toChar)
                Renders.save(ctx, r)
                if (Renders.activeId(ctx, r.bookId) == null) Renders.setActive(ctx, r.bookId, r.id)
                AppLog.i("Render", "done ${r.id}: ${r.durationMs / 1000}s audio, ${r.parts.size} parts, ${sync.size} sentences")
                current = null
                RenderService.finished(ctx, r, ok = true)
                next(ctx)
            },
            onPaused = {
                r = r.copy(status = "paused")
                Renders.save(ctx, r)
                AppLog.i("Render", "paused ${r.id} at ${(r.fraction * 100).toInt()}%: ${r.durationMs / 1000}s audio kept")
                current = null
                pausing = null
                RenderService.stop(ctx)
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

    /** Rough spoken length of [chars] of text: ~14.5 chars a second at 1×, plus a pause per sentence (~90 chars). */
    fun estimateMs(chars: Int, speed: Float, pauseMs: Int): Long =
        (chars / (14.5 * speed) * 1000).toLong() + chars / 90 * pauseMs

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
            val ready = p.render.durationMs.takeIf { it > 0 }?.let { " · ${RenderManager.formatDuration(it)} ready to play" } ?: ""
            val eta = ready + (p.etaMs?.let { " · about ${RenderManager.formatDuration(it)} left" } ?: "")
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
