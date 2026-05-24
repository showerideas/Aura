package com.showerideas.aura.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.showerideas.aura.R
import com.showerideas.aura.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that monitors volume-button presses to detect the
 * AURA activation gesture: 3x volume-DOWN within [TRIPLE_PRESS_WINDOW_MS].
 *
 * On Android 12+ this uses the new [AudioManager] media session callback approach.
 * The service stays alive as long as the screen is on and the user hasn't
 * explicitly disabled background activation in Settings.
 *
 * When the triple-press is detected, it fires [ACTION_AURA_ACTIVATE] broadcast
 * which MainActivity and NearbyExchangeService both listen to.
 */
@AndroidEntryPoint
class VolumeButtonListenerService : Service() {

    companion object {
        const val ACTION_AURA_ACTIVATE = "com.showerideas.aura.ACTIVATE"
        private const val CHANNEL_ID = "aura_bg_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TRIPLE_PRESS_WINDOW_MS = 1500L   // 1.5 seconds
        private const val REQUIRED_PRESSES = 3

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VolumeButtonListenerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VolumeButtonListenerService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Synchronised deque — MediaSession callbacks may arrive off the main thread
    private val pressTimestamps = ArrayDeque<Long>(REQUIRED_PRESSES + 1)
    private val timestampLock = Any()

    // MediaSession approach — intercepts volume key events at the audio focus level
    private val mediaSessionCallback = object : android.media.session.MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ?: return false

            if (keyEvent.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
                keyEvent.action == KeyEvent.ACTION_DOWN
            ) {
                handleVolumeDown()
                return false  // Don't consume — still allow volume to change
            }
            return false
        }
    }

    private lateinit var mediaSession: android.media.session.MediaSession

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupMediaSession()
        Timber.d("VolumeButtonListenerService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY   // Restart if killed

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        mediaSession.release()
        Timber.d("VolumeButtonListenerService destroyed")
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun setupMediaSession() {
        mediaSession = android.media.session.MediaSession(this, "AuraVolumeListener")
        mediaSession.setCallback(mediaSessionCallback)

        // Both flags deprecated since API 31. Suppressed rather than removed
        // because removing them entirely would break API < 31 where the flags
        // are still the routing mechanism.
        @Suppress("DEPRECATION")
        mediaSession.setFlags(
            android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        // API 31+ routes media button events to sessions with an active
        // PlaybackState rather than respecting FLAG_HANDLES_MEDIA_BUTTONS alone.
        // STATE_PAUSED makes us eligible without implying audio is playing.
        // Without this, triple-press silently stops working on Android 12+
        // (targetSdk = 35).
        val playbackState = android.media.session.PlaybackState.Builder()
            .setState(
                android.media.session.PlaybackState.STATE_PAUSED,
                android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                0f
            )
            .setActions(android.media.session.PlaybackState.ACTION_PLAY_PAUSE)
            .build()
        mediaSession.setPlaybackState(playbackState)

        mediaSession.isActive = true
    }

    private fun handleVolumeDown() {
        val triggered = synchronized(timestampLock) {
            val now = System.currentTimeMillis()
            pressTimestamps.addLast(now)

            // Drop presses outside the time window
            while (pressTimestamps.isNotEmpty() &&
                now - pressTimestamps.first() > TRIPLE_PRESS_WINDOW_MS
            ) {
                pressTimestamps.removeFirst()
            }

            Timber.d("Volume down — ${pressTimestamps.size}/$REQUIRED_PRESSES in window")

            if (pressTimestamps.size >= REQUIRED_PRESSES) {
                pressTimestamps.clear()
                true
            } else false
        }
        if (triggered) onTriplePressDetected()
    }

    private fun onTriplePressDetected() {
        Timber.i("AURA triple-press activation detected!")
        sendBroadcast(Intent(ACTION_AURA_ACTIVATE).apply {
            `package` = packageName
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AURA Background",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps AURA ready for quick activation"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AURA is ready")
            .setContentText("Triple-press volume down to share your contact")
            .setSmallIcon(R.drawable.ic_aura_small)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
