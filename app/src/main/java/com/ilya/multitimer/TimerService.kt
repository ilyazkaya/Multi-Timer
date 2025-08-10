package com.ilya.multitimer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.min

class TimerService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timerJob: Job? = null
    private var isRunning = false

    companion object {
        private const val TAG = "TimerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "timer_service_channel"
        
        fun startService(context: Context) {
            val intent = Intent(context, TimerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, TimerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "TimerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "TimerService onStartCommand")
        
        if (!isRunning) {
            startForeground(NOTIFICATION_ID, createNotification())
            startTimerLoop()
            isRunning = true
        }
        
        return START_STICKY
    }

    private fun startTimerLoop() {
        timerJob = scope.launch {
            Log.d(TAG, "Timer loop started")
            while (isActive) {
                try {
                    // Update timer states every 200ms
                    updateTimerStates()
                    delay(200)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in timer loop", e)
                    delay(1000) // Wait longer on error
                }
            }
        }
    }

    private fun updateTimerStates() {
        try {
            val (timers, idCounter) = TimerStore.load(this)
            val nowWall = System.currentTimeMillis()
            val tick = SystemClock.elapsedRealtime()
            var changed = false
            
            for (i in timers.indices) {
                val timer = timers[i]
                if (timer.isRunning && timer.finishedWallClockMs == null) {
                    val elapsed = elapsedMs(timer, tick, nowWall)
                    if (elapsed >= timer.totalMs) {
                        // Timer finished
                        val updated = timer.copy(
                            finishedWallClockMs = nowWall,
                            isRunning = false
                        )
                        timers[i] = updated
                        changed = true
                        Log.d(TAG, "Timer ${timer.id} finished in background")
                        
                        // Schedule alarm if not already alerted
                        if (!timer.alerted) {
                            scheduleAlarm(timer.id, timer.label, timer.totalMs, elapsed)
                        }
                    }
                }
            }
            
            if (changed) {
                TimerStore.save(this, timers, idCounter)
                Log.d(TAG, "Timer states updated and saved")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating timer states", e)
        }
    }

    private fun elapsedMs(timer: TimerState, tick: Long, nowWall: Long): Long {
        if (!timer.isRunning) return timer.accumulatedMs
        
        if (timer.startedRealtimeMs != null && timer.startedWallClockMs != null) {
            val realtimeElapsed = tick - timer.startedRealtimeMs
            val wallElapsed = nowWall - timer.startedWallClockMs
            return timer.accumulatedMs + min(realtimeElapsed, wallElapsed)
        }
        return timer.accumulatedMs
    }

    private fun scheduleAlarm(id: Long, label: String, total: Long, elapsed: Long) {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, AlarmReceiver::class.java).apply {
                action = "com.ilya.multitimer.ACTION_ALARM_FIRED"
                putExtra("id", id)
                putExtra("label", label)
                putExtra("total", total)
                putExtra("elapsed", elapsed)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this, 
                id.toInt(), 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Schedule alarm to fire immediately
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis(),
                pendingIntent
            )
            
            Log.d(TAG, "Alarm scheduled for timer $id")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Timer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps timers running in background"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MultiTimer")
            .setContentText("Timers running in background")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "TimerService destroyed")
        isRunning = false
        timerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
