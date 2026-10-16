package com.bernardo.ereader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * System media controls (notification shade / quick settings player / lock screen / headset buttons)
 * via MediaSession, plus the foreground service + wake lock that keep narration alive with the screen off.
 * Narration itself lives in MainActivity, which implements [Controller].
 */
class NarrationService : Service() {

    interface Controller {
        fun remotePlay()
        fun remotePause()
        fun remoteSkip(dir: Int)
        fun remoteDismissed()
    }

    companion object {
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_COVER = "cover"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        private const val ACTION_TOGGLE = "com.bernardo.ereader.TOGGLE"
        private const val ACTION_NEXT = "com.bernardo.ereader.NEXT"
        private const val ACTION_PREV = "com.bernardo.ereader.PREV"
        private const val ACTION_DISMISS = "com.bernardo.ereader.DISMISS"
        private const val CHANNEL = "narration"
        private const val NOTIF_ID = 1

        @Volatile var controller: Controller? = null
        @Volatile var instance: NarrationService? = null
    }

    private lateinit var session: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var playing = false
    private var title = ""
    private var chapter = ""
    private var coverPath: String? = null
    private var cover: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "bagueration").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { NarrationService.controller?.remotePlay() }
                override fun onPause() { NarrationService.controller?.remotePause() }
                override fun onStop() { NarrationService.controller?.remotePause() }
                override fun onSkipToNext() { NarrationService.controller?.remoteSkip(+1) }
                override fun onSkipToPrevious() { NarrationService.controller?.remoteSkip(-1) }
            })
            setSessionActivity(PendingIntent.getActivity(this@NarrationService, 0,
                Intent(this@NarrationService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> if (playing) controller?.remotePause() else controller?.remotePlay()
            ACTION_NEXT -> controller?.remoteSkip(+1)
            ACTION_PREV -> controller?.remoteSkip(-1)
            ACTION_DISMISS -> { controller?.remoteDismissed(); stopSelf() }
            else -> if (intent != null) update(
                intent.getBooleanExtra(EXTRA_PLAYING, false),
                intent.getStringExtra(EXTRA_TITLE) ?: "",
                intent.getStringExtra(EXTRA_CHAPTER) ?: "",
                intent.getStringExtra(EXTRA_COVER),
                intent.getLongExtra(EXTRA_POSITION, -1), intent.getLongExtra(EXTRA_DURATION, -1),
            )
        }
        return START_NOT_STICKY
    }

    /**
     * The chapter's progress bar in the media controls: the reader sends where the voice is every few seconds, and
     * the system moves the bar on its own in between (playback speed 1 = real time). No notification rebuild.
     */
    fun progress(position: Long, duration: Long) {
        positionMs = position
        durationMs = duration
        publish()
    }

    private fun publish() {
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, chapter)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Bagueration")
            .apply {
                this@NarrationService.cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                if (durationMs > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            }
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                if (positionMs >= 0 && durationMs > 0) positionMs.coerceAtMost(durationMs) else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1f else 0f)
            .build())
    }

    /** Where the voice is in the chapter and how long the chapter is, as listening time (ms); -1 = unknown. */
    private var positionMs = -1L
    private var durationMs = -1L

    /** Called by the reader whenever play state / book / chapter changes. */
    fun update(isPlaying: Boolean, bookTitle: String, chapterTitle: String, cover: String?, position: Long = -1, duration: Long = -1) {
        playing = isPlaying
        title = bookTitle
        chapter = chapterTitle
        positionMs = position
        durationMs = duration
        if (cover != coverPath) {
            coverPath = cover
            this.cover = cover?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        }
        publish()

        val n = buildNotification()
        if (playing) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF_ID, n)
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ereader:narration").apply { acquire() }
            }
        } else {
            // paused: keep the controls, but let the notification be swiped away and the CPU sleep
            stopForeground(STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
            releaseWakeLock()
        }
    }

    private fun action(action: String, icon: Int, label: String, code: Int) = Notification.Action.Builder(
        Icon.createWithResource(this, icon), label,
        PendingIntent.getService(this, code, Intent(this, NarrationService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_library)
        .setContentTitle(title)
        .setContentText(chapter)
        .setLargeIcon(cover)
        .setContentIntent(session.controller.sessionActivity)
        .setDeleteIntent(PendingIntent.getService(this, 9,
            Intent(this, NarrationService::class.java).setAction(ACTION_DISMISS), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(playing)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .addAction(action(ACTION_PREV, R.drawable.ic_skip_prev, "Previous sentence", 2))
        .addAction(if (playing) action(ACTION_TOGGLE, R.drawable.ic_pause, "Pause", 3)
                   else action(ACTION_TOGGLE, R.drawable.ic_play, "Play", 3))
        .addAction(action(ACTION_NEXT, R.drawable.ic_skip_next, "Next sentence", 4))
        .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        .build()

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        session.isActive = false
        session.release()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        if (instance === this) instance = null
        super.onDestroy()
    }
}
