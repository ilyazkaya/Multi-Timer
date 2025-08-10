package com.ilya.multitimer

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import android.media.RingtoneManager
import android.media.Ringtone
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.util.Log

class AlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rt: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var playingJob: Job? = null
    private var currentId: Long = -1L
    private var isSilenced: Boolean = false
    private var player: MediaPlayer? = null
    private val TAG = "AlarmService"

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var alertActive: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_START_ALERT -> {
                val id = intent!!.getLongExtra(EXTRA_ID, -1L)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Timer"
                val total = intent.getLongExtra(EXTRA_TOTAL, 0L)
                val elapsed = intent.getLongExtra(EXTRA_ELAPSED, 0L)
                if (currentId == id && alertActive) {
                    Log.d(TAG, "START_ALERT ignored: alert already active for id=$id")
                    return START_NOT_STICKY
                }
                if (currentId == id && (player != null || rt != null)) {
                    Log.d(TAG, "START_ALERT ignored: already playing for id=$id")
                    return START_NOT_STICKY
                }
                currentId = id
                isSilenced = false
                Log.d(TAG, "START_ALERT id=$id label=$label total=$total elapsed=$elapsed")

                createNotificationChannel(this)

                // Persist that the timer has finished and is now alerted
                run {
                    val (list, counter) = TimerStore.load(this)
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx >= 0) {
                        val cur = list[idx]
                        val next = cur.copy(
                            finishedWallClockMs = cur.finishedWallClockMs ?: System.currentTimeMillis(),
                            alerted = true
                        )
                        list[idx] = next
                        TimerStore.save(this, list, counter)
                    }
                }

                val n = buildDoneNotification(this, id, label, total, elapsed)
                startForeground(id.toInt(), n)

                alertActive = true
                startBeepingForUpTo5m()

                NotificationManagerCompat.from(this).notify(id.toInt(), n)
            }

            ACTION_SILENCE -> {
                Log.d(TAG, "SILENCE received")
                isSilenced = true
                alertActive = false
                stopBeeping()
                val id = intent!!.getLongExtra(EXTRA_ID, -1L)
                if (id != -1L) {
                    try { NotificationManagerCompat.from(this).cancel(id.toInt()) } catch (_: Exception) {}
                    sendBroadcast(Intent(ACTION_SILENCE).putExtra(EXTRA_ID, id).setPackage(packageName))
                }
                Log.d(TAG, "SILENCE done -> stopForeground & stopSelf")
                try { stopForeground(true) } catch (_: Exception) {}
                stopSelf()
            }

            ACTION_PAUSE -> {
                Log.d(TAG, "PAUSE received")
                isSilenced = true
                alertActive = false
                val id = intent!!.getLongExtra(EXTRA_ID, -1L)
                pauseTimerPersistently(id)
                stopBeeping()
                NotificationManagerCompat.from(this).cancel(id.toInt())
                sendBroadcast(Intent(ACTION_PAUSE).putExtra(EXTRA_ID, id).setPackage(packageName))
                Log.d(TAG, "PAUSE done -> stopForeground & stopSelf")
                try { stopForeground(true) } catch (_: Exception) {}
                stopSelf()
            }

            ACTION_RESET -> {
                Log.d(TAG, "RESET received")
                isSilenced = true
                alertActive = false
                val id = intent!!.getLongExtra(EXTRA_ID, -1L)
                resetTimerPersistently(id)
                stopBeeping()
                NotificationManagerCompat.from(this).cancel(id.toInt())
                sendBroadcast(Intent(ACTION_RESET).putExtra(EXTRA_ID, id).setPackage(packageName))
                Log.d(TAG, "RESET done -> stopForeground & stopSelf")
                try { stopForeground(true) } catch (_: Exception) {}
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun requestAudioFocus() {
        try {
            audioManager = audioManager ?: getSystemService(AudioManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = req
                audioManager?.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (e: Exception) { Log.w(TAG, "requestAudioFocus failed: ${e.message}") }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) { Log.w(TAG, "abandonAudioFocus failed: ${e.message}") }
    }

    private fun startBeepingForUpTo5m() {
        Log.d(TAG, "startBeepingForUpTo5m")
        // Ensure any previous playback is stopped first
        stopBeeping()
        requestAudioFocus()
        playingJob?.cancel()
        playingJob = scope.launch {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            // Try MediaPlayer first for reliable stop/release
            var started = false
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                player = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    setOnErrorListener { mp, what, extra ->
                        Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                        try { mp.reset() } catch (_: Exception) {}
                        false
                    }
                    prepare()
                    start()
                }
                started = true
                Log.d(TAG, "MediaPlayer started")
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer failed, falling back to Ringtone: ${e.message}")
                try { player?.release() } catch (_: Exception) {}
                player = null
            }

            // Fallback to Ringtone if MediaPlayer fails
            if (!started) {
                try {
                    rt = RingtoneManager.getRingtone(this@AlarmService, uri)
                    if (Build.VERSION.SDK_INT >= 21) {
                        val ringAttrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        rt?.audioAttributes = ringAttrs
                    }
                    if (Build.VERSION.SDK_INT >= 28) rt?.isLooping = true
                    rt?.play()
                    Log.d(TAG, "Ringtone started")
                } catch (e: Exception) {
                    Log.e(TAG, "Ringtone failed: ${e.message}")
                }
            }

            vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VibratorManager::class.java)?.defaultVibrator)
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    val pattern = longArrayOf(0, 700, 300)
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(700)
                }
            } catch (_: Exception) {}

            val start = SystemClock.elapsedRealtime()
            while (isActive && SystemClock.elapsedRealtime() - start < 300_000L && !isSilenced) {
                delay(500)
            }
            Log.d(TAG, "beep loop exit: isActive=$isActive silenced=$isSilenced")
            stopBeeping()
            if (currentId != -1L && !isSilenced) {
                Log.d(TAG, "auto-timeout -> cancel notification & stop service")
                NotificationManagerCompat.from(this@AlarmService).cancel(currentId.toInt())
                try { stopForeground(true) } catch (_: Exception) {}
                stopSelf()
            }
        }
    }

    private fun stopBeeping() {
        Log.d(TAG, "stopBeeping")
        alertActive = false
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { rt?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        playingJob?.cancel()
        rt = null
        vibrator = null
        abandonAudioFocus()
    }

    private fun pauseTimerPersistently(id: Long) {
        val (list, counter) = TimerStore.load(this)
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val cur = list[idx]
            val nowR = SystemClock.elapsedRealtime()
            val nowW = System.currentTimeMillis()
            val runPart = when {
                cur.startedRealtimeMs != null -> nowR - cur.startedRealtimeMs
                cur.startedWallClockMs != null -> nowW - cur.startedWallClockMs
                else -> 0L
            }
            val accrued = cur.accumulatedMs + runPart
            // Preserve original finish time if already set; otherwise, set only if we detect finish now
            val finishedAt = cur.finishedWallClockMs ?: run {
                if (cur.startedWallClockMs != null && accrued >= cur.totalMs) cur.startedWallClockMs + cur.totalMs else null
            }
            val next = cur.copy(
                isRunning = false,
                startedRealtimeMs = null,
                accumulatedMs = accrued,
                finishedWallClockMs = finishedAt
            )
            list[idx] = next
            TimerStore.cancelForTimer(this, id)
            TimerStore.save(this, list, counter)
        }
    }

    private fun resetTimerPersistently(id: Long) {
        val (list, counter) = TimerStore.load(this)
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val cur = list[idx]
            val next = cur.copy(
                isRunning = false,
                startedRealtimeMs = null,
                startedWallClockMs = null,
                finishedWallClockMs = null,
                alerted = false,
                accumulatedMs = 0L
            )
            list[idx] = next
            TimerStore.cancelForTimer(this, id)
            TimerStore.save(this, list, counter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { Log.d(TAG, "onDestroy"); stopBeeping(); scope.cancel(); super.onDestroy() }
}
