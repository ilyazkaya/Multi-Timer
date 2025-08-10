package com.ilya.multitimer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.coroutines.coroutineContext
import kotlin.math.min

const val CHANNEL_ID = "timer_done_channel"

const val ACTION_PAUSE = "com.ilya.multitimer.ACTION_PAUSE"
const val ACTION_RESET = "com.ilya.multitimer.ACTION_RESET"
const val ACTION_SILENCE = "com.ilya.multitimer.ACTION_SILENCE"
const val ACTION_ALARM_FIRED = "com.ilya.multitimer.ACTION_ALARM_FIRED"
const val ACTION_START_ALERT = "com.ilya.multitimer.ACTION_START_ALERT"

const val EXTRA_ID = "id"
const val EXTRA_LABEL = "label"
const val EXTRA_TOTAL = "total"
const val EXTRA_ELAPSED = "elapsed"
const val EXTRA_ACCUM = "accum"
const val EXTRA_STARTED_WALL = "startedWall"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Remove background TimerService start to avoid FGS denial
        // TimerService.startService(this)
        
        setContent { MultiTimerApp() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop TimerService here - let it keep running in background
        // TimerService.stopService(this)
    }
}

@Composable
fun MultiTimerApp() {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme) {
        EnsureNotificationPermission()
        TimerListScreen()
    }
}

@Composable
fun EnsureNotificationPermission() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        LaunchedEffect(granted) {
            if (!granted) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

data class TimerState(
    val id: Long,
    val label: String,
    val totalMs: Long,
    val startedWallClockMs: Long?,
    val startedRealtimeMs: Long?,
    val accumulatedMs: Long,
    val isRunning: Boolean,
    val finishedWallClockMs: Long? = null,
    val alerted: Boolean = false
)

object TimerStore {
    private const val PREFS = "timers"
    private const val KEY_TIMERS = "timers_json"
    private const val KEY_COUNTER = "id_counter"

    fun load(context: Context): Pair<MutableList<TimerState>, Long> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arrStr = p.getString(KEY_TIMERS, "[]")
        val counter = p.getLong(KEY_COUNTER, 1L)
        val list = mutableListOf<TimerState>()
        val arr = JSONArray(arrStr)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                TimerState(
                    id = o.getLong("id"),
                    label = o.getString("label"),
                    totalMs = o.getLong("totalMs"),
                    startedWallClockMs = if (o.isNull("startedWallClockMs")) null else o.getLong("startedWallClockMs"),
                    startedRealtimeMs = null,
                    accumulatedMs = o.getLong("accumulatedMs"),
                    isRunning = o.getBoolean("isRunning"),
                    finishedWallClockMs = if (o.isNull("finishedWallClockMs")) null else o.getLong("finishedWallClockMs"),
                    alerted = o.optBoolean("alerted", false)
                )
            )
        }
        return list to counter
    }

    fun save(context: Context, timers: List<TimerState>, counter: Long) {
        val arr = JSONArray()
        timers.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id)
            o.put("label", t.label)
            o.put("totalMs", t.totalMs)
            o.put("startedWallClockMs", t.startedWallClockMs)
            o.put("accumulatedMs", t.accumulatedMs)
            o.put("isRunning", t.isRunning)
            o.put("finishedWallClockMs", t.finishedWallClockMs)
            o.put("alerted", t.alerted)
            arr.put(o)
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putString(KEY_TIMERS, arr.toString()).putLong(KEY_COUNTER, counter).apply()

        if (Build.VERSION.SDK_INT >= 24) {
            val dp = context.createDeviceProtectedStorageContext()
            val dpPrefs = dp.getSharedPreferences("timers", Context.MODE_PRIVATE)
            dpPrefs.edit()
                .putString("timers_json", arr.toString())
                .putLong("id_counter", counter)
                .apply()
        }

    }

    fun scheduleForTimer(context: Context, t: TimerState) {
        if (!t.isRunning || t.startedWallClockMs == null) return
        val endAt = t.startedWallClockMs + (t.totalMs - t.accumulatedMs)
        if (endAt <= System.currentTimeMillis()) {
            val intent = Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_ALARM_FIRED)
                .putExtra(EXTRA_ID, t.id)
                .putExtra(EXTRA_LABEL, t.label)
                .putExtra(EXTRA_TOTAL, t.totalMs)
                .putExtra(EXTRA_ACCUM, t.accumulatedMs)
                .putExtra(EXTRA_STARTED_WALL, t.startedWallClockMs)
            context.sendBroadcast(intent)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            t.id.toInt(),
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_ALARM_FIRED)
                .putExtra(EXTRA_ID, t.id)
                .putExtra(EXTRA_LABEL, t.label)
                .putExtra(EXTRA_TOTAL, t.totalMs)
                .putExtra(EXTRA_ACCUM, t.accumulatedMs)
                .putExtra(EXTRA_STARTED_WALL, t.startedWallClockMs),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi)
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, endAt, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi)
        }
    }

    fun cancelForTimer(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM_FIRED),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(pi)
        pi.cancel()
    }

    fun rescheduleAll(context: Context) {
        val (list, _) = load(context)
        list.forEach { scheduleForTimer(context, it) }
    }
}

@Composable
fun TimerListScreen() {
    val context = LocalContext.current
    val (loaded, loadedCounter) = remember { TimerStore.load(context) }
    val timers = remember { mutableStateListOf<TimerState>().also { it.addAll(loaded) } }
    var idCounter by remember { mutableLongStateOf(max(loadedCounter, (loaded.maxOfOrNull { it.id } ?: 0L) + 1L)) }
    var showDialog by remember { mutableStateOf(false) }
    var editingTimer by remember { mutableStateOf<TimerState?>(null) }

    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val (loadedTimers, loadedCounter) = TimerStore.load(context)
        timers.clear()
        timers.addAll(loadedTimers)
        idCounter = loadedCounter
        
        // Restore timer states after app restart
        val nowW = System.currentTimeMillis()
        val nowR = SystemClock.elapsedRealtime()
        var changed = false
        
        for (i in timers.indices) {
            val timer = timers[i]
            if (timer.isRunning && timer.startedWallClockMs != null && timer.startedRealtimeMs != null) {
                val elapsed = elapsedMs(timer, nowR, nowW)
                if (elapsed >= timer.totalMs) {
                    // Timer finished while app was closed
                    timers[i] = timer.copy(
                        isRunning = false,
                        finishedWallClockMs = timer.startedWallClockMs + timer.totalMs,
                        accumulatedMs = timer.totalMs
                    )
                    changed = true
                } else {
                    // Timer still running, update accumulated time
                    timers[i] = timer.copy(accumulatedMs = elapsed)
                    changed = true
                }
            }
        }
        
        if (changed) {
            TimerStore.save(context, timers, idCounter)
        }
        
        // Set up BroadcastReceiver for timer actions
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id == -1L) return
                
                when (intent.action) {
                    ACTION_PAUSE -> {
                        val id = intent.getLongExtra(EXTRA_ID, -1L)
                        if (id != -1L) {
                            // Reload timer from persistent store to get exact paused state
                            val (stored, _) = TimerStore.load(ctx)
                            val idxMem = timers.indexOfFirst { it.id == id }
                            val idxStore = stored.indexOfFirst { it.id == id }
                            if (idxMem >= 0 && idxStore >= 0) {
                                timers[idxMem] = stored[idxStore]
                            }
                            NotificationManagerCompat.from(ctx).cancel(id.toInt())
                        }
                    }
                    ACTION_RESET -> {
                        val idx = timers.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            val cur = timers[idx]
                            NotificationManagerCompat.from(ctx).cancel(id.toInt())
                            TimerStore.cancelForTimer(ctx, id)
                            timers[idx] = cur.copy(
                                isRunning = false,
                                startedRealtimeMs = null,
                                startedWallClockMs = null,
                                finishedWallClockMs = null,
                                alerted = false,
                                accumulatedMs = 0L
                            )
                            TimerStore.save(ctx, timers, idCounter)
                        }
                    }
                    ACTION_SILENCE -> {
                        // No state change here; service already silences sound. Keep timer running.
                        NotificationManagerCompat.from(ctx).cancel(id.toInt())
                    }
                    ACTION_ALARM_FIRED -> {
                        // No-op here: AlarmReceiver/AlarmService handle notification + sound.
                        // UI state will be updated by the ticking loop.
                    }
                }
            }
        }
        
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_PAUSE)
                addAction(ACTION_RESET)
                addAction(ACTION_SILENCE)
                addAction(ACTION_ALARM_FIRED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        
        onDispose { context.unregisterReceiver(receiver) }
    }

    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            var changed = false
            for (i in timers.indices) {
                val t = timers[i]
                val elapsed = elapsedMs(t, tick, nowWall)
                if (elapsed >= t.totalMs) {
                    val updated = t.copy(finishedWallClockMs = t.finishedWallClockMs ?: nowWall)
                    if (updated != timers[i]) {
                        timers[i] = updated
                        changed = true
                    }
                }
            }
            if (changed) TimerStore.save(context, timers, idCounter)
            delay(200)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingTimer = null
                showDialog = true
            }) { Text("+") }
        }
    ) { padding ->
        if (timers.isEmpty()) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("Tap + to add a timer", fontSize = 18.sp) }
        } else {
            LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(timers, key = { it.id }) { t ->
                    TimerCard(
                        timer = t,
                        nowRealtime = tick,
                        onEdit = {
                            editingTimer = t
                            showDialog = true
                        },
                        onToggle = {
                            val updated = if (t.isRunning) {
                                // Pause timer
                                if (t.startedRealtimeMs != null && t.startedWallClockMs != null) {
                                    val nowR = SystemClock.elapsedRealtime()
                                    val nowW = System.currentTimeMillis()
                                    val realtimeElapsed = nowR - t.startedRealtimeMs
                                    val wallElapsed = nowW - t.startedWallClockMs
                                    val accrued = min(realtimeElapsed, wallElapsed)
                                    val newAccumulated = t.accumulatedMs + accrued
                                    
                                    val finalTimer = if (newAccumulated >= t.totalMs) {
                                        // Timer finished while pausing
                                        t.copy(
                                            isRunning = false,
                                            accumulatedMs = t.totalMs,
                                            finishedWallClockMs = nowW
                                        )
                                    } else {
                                        t.copy(
                                            isRunning = false,
                                            accumulatedMs = newAccumulated
                                        )
                                    }
                                    finalTimer
                                } else {
                                    // Fallback if timestamps are null
                                    t.copy(isRunning = false)
                                }
                            } else {
                                // Start/resume timer
                                val nowR = SystemClock.elapsedRealtime()
                                val nowW = System.currentTimeMillis()
                                t.copy(
                                    isRunning = true,
                                    startedRealtimeMs = nowR,
                                    startedWallClockMs = nowW,
                                    finishedWallClockMs = null,
                                    alerted = false
                                )
                            }
                            timers[timers.indexOfFirst { it.id == t.id }] = updated
                            
                            // Cancel notification if timer is paused or finished
                            if (!updated.isRunning || updated.finishedWallClockMs != null) {
                                NotificationManagerCompat.from(context).cancel(t.id.toInt())
                                TimerStore.cancelForTimer(context, t.id)
                            } else {
                                // Schedule new alarm if timer is started/resumed
                                TimerStore.scheduleForTimer(context, updated)
                            }
                            
                            TimerStore.save(context, timers, idCounter)
                        },
                        onRestart = {
                            val idx = timers.indexOfFirst { it.id == t.id }
                            if (idx >= 0) {
                                NotificationManagerCompat.from(context).cancel(t.id.toInt())
                                TimerStore.cancelForTimer(context, t.id)
                                val next = t.copy(
                                    isRunning = false,
                                    startedRealtimeMs = null,
                                    startedWallClockMs = null,
                                    finishedWallClockMs = null,
                                    alerted = false,
                                    accumulatedMs = 0L
                                )
                                timers[idx] = next
                                TimerStore.save(context, timers, idCounter)
                            }
                        },
                        onDelete = {
                            NotificationManagerCompat.from(context).cancel(t.id.toInt())
                            TimerStore.cancelForTimer(context, t.id)
                            timers.removeAll { it.id == t.id }
                            TimerStore.save(context, timers, idCounter)
                        },
                        onSilence = {
                            // Don't stop the timer, just silence the alarm
                        }
                    )
                }
            }
        }
    }

    if (showDialog) {
        val editing = editingTimer
        TimerUpsertDialog(
            mode = if (editing == null) DialogMode.ADD else DialogMode.EDIT(editing.label),
            initialLabel = editing?.label ?: "",
            initialDurationMs = editing?.totalMs ?: parseDurationToMsOrNull("00:25:00")!!,
            onDismiss = { showDialog = false },
            onConfirm = { label, totalMs, startNow, restartOnEdit ->
                if (editing == null) {
                    val nowR = SystemClock.elapsedRealtime()
                    val nowW = System.currentTimeMillis()
                    val item = TimerState(
                        id = idCounter++,
                        label = label.ifBlank { "Timer $idCounter" },
                        totalMs = totalMs,
                        startedWallClockMs = if (startNow) nowW else null,
                        startedRealtimeMs = if (startNow) nowR else null,
                        accumulatedMs = 0L,
                        isRunning = startNow
                    )
                    timers.add(item)
                    if (startNow) TimerStore.scheduleForTimer(context, item)
                    TimerStore.save(context, timers, idCounter)
                } else {
                    val idx = timers.indexOfFirst { it.id == editing.id }
                    if (idx >= 0) {
                        val cur = timers[idx]
                        val nowR = SystemClock.elapsedRealtime()
                        val nowW = System.currentTimeMillis()
                        val base = cur.copy(
                            label = label.trim().ifBlank { cur.label },
                            totalMs = totalMs,
                            finishedWallClockMs = null,
                            alerted = false
                        )
                        val next = if (restartOnEdit || startNow) {
                            NotificationManagerCompat.from(context).cancel(cur.id.toInt())
                            TimerStore.cancelForTimer(context, cur.id)
                            base.copy(
                                isRunning = true,
                                accumulatedMs = 0L,
                                startedRealtimeMs = nowR,
                                startedWallClockMs = nowW,
                                finishedWallClockMs = null,
                                alerted = false
                            )
                        } else base
                        timers[idx] = next
                        if (next.isRunning) TimerStore.scheduleForTimer(context, next) else TimerStore.cancelForTimer(context, next.id)
                        TimerStore.save(context, timers, idCounter)
                    }
                }
                showDialog = false
            }
        )
    }
}

sealed class DialogMode {
    data object ADD : DialogMode()
    data class EDIT(val title: String) : DialogMode()
}

@Composable
fun TimerCard(
    timer: TimerState,
    nowRealtime: Long,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit,
    onSilence: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFmt = remember { DateTimeFormatter.ofPattern("h:mm:ss a").withZone(zone) }

    val elapsed = elapsedMs(timer, nowRealtime, System.currentTimeMillis())
    val remaining = if (timer.finishedWallClockMs != null) 0L else max(0L, timer.totalMs - elapsed)

    val (startedTime, startedOffset) = timer.startedWallClockMs?.let { timeAndOffset(it, zone, timeFmt) } ?: (null to null)
    val (stopTime, stopOffset) = when {
        timer.finishedWallClockMs != null -> timeAndOffset(timer.finishedWallClockMs, zone, timeFmt)
        timer.startedWallClockMs != null -> {
            val projEndMs = System.currentTimeMillis() + remaining
            timeAndOffset(projEndMs, zone, timeFmt)
        }
        else -> (null to null)
    }

    val isFinished = timer.finishedWallClockMs != null
    val containerColor = if (isFinished) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (isFinished) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${timer.label}  ·  ${formatHMS(timer.totalMs)}",
                    color = onContainer,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(
                        "✏ Edit",
                        color = onContainer,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatPill(label = "Elapsed", value = formatHMS(elapsed), modifier = Modifier.weight(1f))
                StatPill(label = "Left", value = formatHMS(remaining), modifier = Modifier.weight(1f))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatPill(
                    label = "Started",
                    labelSuffixBold = startedOffset,
                    value = startedTime ?: "—",
                    tonal = true,
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    label = "Will stop",
                    labelSuffixBold = stopOffset,
                    value = stopTime ?: "—",
                    tonal = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isFinished) {
                    OutlinedButton(
                        onClick = onSilence,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔕", fontSize = 18.sp, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Text("Silence", fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🗑", fontSize = 18.sp, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete", fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("↺", fontSize = 18.sp, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Text("Reset", fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
                Button(
                    onClick = onToggle,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val txt = if (timer.isRunning) "Pause" else "Start"
                        val sym = if (timer.isRunning) "⏸" else "▶"
                        Text(sym, fontSize = 18.sp, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Text(txt, fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
    tonal: Boolean = false,
    labelSuffixBold: String? = null,
    modifier: Modifier = Modifier
) {
    val bg = if (tonal) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = if (tonal) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.clip(RoundedCornerShape(24.dp))
    ) {
        Column(
            Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val lbl = buildAnnotatedString {
                append(label.uppercase())
                if (!labelSuffixBold.isNullOrBlank()) {
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("(${labelSuffixBold})") }
                }
            }
            Text(lbl, fontSize = 11.sp, letterSpacing = 0.8.sp, color = fg.copy(alpha = 0.75f))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun TimerUpsertDialog(
    mode: DialogMode,
    initialLabel: String,
    initialDurationMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (label: String, totalMs: Long, startNow: Boolean, restartOnEdit: Boolean) -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var durationText by remember { mutableStateOf(formatHMS(initialDurationMs)) }
    val error = remember(durationText) { parseDurationToMsOrNull(durationText) == null }
    val isEdit = mode is DialogMode.EDIT
    val titleText = when (mode) {
        is DialogMode.EDIT -> "Edit ${mode.title} Timer"
        DialogMode.ADD -> "New Timer"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") }, singleLine = true)
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it },
                    label = { Text("Duration (HH|MM|SS split by any symbol)") },
                    isError = error,
                    supportingText = { if (error) Text("Try 1:30 or 00-25-00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !error,
                    onClick = {
                        val ms = parseDurationToMsOrNull(durationText)!!
                        onConfirm(label.trim(), ms, true, isEdit)
                    }
                ) { Text(if (isEdit) "Save & Start" else "Add & Start") }
                OutlinedButton(
                    enabled = !error,
                    onClick = {
                        val ms = parseDurationToMsOrNull(durationText)!!
                        onConfirm(label.trim(), ms, false, false)
                    }
                ) { Text(if (isEdit) "Save" else "Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Timers", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(ch)
    }
}

private fun showTimerDoneNotification(context: Context, timer: TimerState, elapsedMs: Long) {
    if (!canPostNotifications(context)) return
    val n = buildDoneNotification(context, timer.id, timer.label, timer.totalMs, elapsedMs)
    NotificationManagerCompat.from(context).notify(timer.id.toInt(), n)
}

private fun elapsedMs(t: TimerState, nowRealtime: Long, nowWall: Long): Long {
    if (!t.isRunning) return t.accumulatedMs.coerceAtLeast(0L)
    
    val runPart = when {
        t.startedRealtimeMs != null -> nowRealtime - t.startedRealtimeMs
        t.startedWallClockMs != null -> nowWall - t.startedWallClockMs
        else -> 0L
    }
    
    return (t.accumulatedMs + runPart).coerceAtLeast(0L)
}

private fun formatHMS(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun timeAndOffset(
    epochMs: Long,
    zone: ZoneId,
    timeFmt: DateTimeFormatter
): Pair<String, String?> {
    val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
    val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone)
    val dayDiff = ChronoUnit.DAYS.between(now.toLocalDate(), zdt.toLocalDate()).toInt()
    val base = timeFmt.format(zdt)
    val suffix = when {
        dayDiff == 0 -> null
        abs(dayDiff) == 1 -> if (dayDiff > 0) "+1 day" else "-1 day"
        else -> if (dayDiff > 0) "+$dayDiff days" else "$dayDiff days"
    }
    return base to suffix
}

private fun parseDurationToMsOrNull(text: String): Long? {
    val parts = text.trim().split(Regex("\\D+")).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return try {
        val (h, m, s) = when (parts.size) {
            1 -> Triple(0, parts[0].toInt(), 0)
            2 -> Triple(0, parts[0].toInt(), parts[1].toInt())
            3 -> Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            else -> return null
        }
        if (m !in 0..59 || s !in 0..59 || h < 0) return null
        (h * 3600L + m * 60L + s) * 1000L
    } catch (_: NumberFormatException) { null }
}

private fun canPostNotifications(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else true
}

class AlarmActivity : ComponentActivity() {
    private val scope = MainScope()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Timer"
                val total = intent.getLongExtra(EXTRA_TOTAL, 0L)
                val elapsed = intent.getLongExtra(EXTRA_ELAPSED, 0L)
                val title = "$label finished"
                val body = "Total ${formatHMS(total)} • Elapsed ${formatHMS(elapsed)}"
                AlarmScreen(
                    title = title,
                    body = body,
                    onSilence = {
                        ContextCompat.startForegroundService(
                            this@AlarmActivity,
                            Intent(this@AlarmActivity, AlarmService::class.java)
                                .setAction(ACTION_SILENCE)
                                .putExtra(EXTRA_ID, id)
                        )
                        finish()
                    },
                    onPause = {
                        ContextCompat.startForegroundService(
                            this@AlarmActivity,
                            Intent(this@AlarmActivity, AlarmService::class.java)
                                .setAction(ACTION_PAUSE)
                                .putExtra(EXTRA_ID, id)
                        )
                        finish()
                    },
                    onReset = {
                        ContextCompat.startForegroundService(
                            this@AlarmActivity,
                            Intent(this@AlarmActivity, AlarmService::class.java)
                                .setAction(ACTION_RESET)
                                .putExtra(EXTRA_ID, id)
                        )
                        finish()
                    }
                )
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

@Composable
fun AlarmScreen(
    title: String,
    body: String,
    onSilence: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSilence, shape = RoundedCornerShape(28.dp)) { Text("Silence") }
                Button(onClick = onPause, shape = RoundedCornerShape(28.dp)) { Text("Pause") }
                OutlinedButton(onClick = onReset, shape = RoundedCornerShape(28.dp)) { Text("Reset") }
            }
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM_FIRED) return
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Timer"
        val total = intent.getLongExtra(EXTRA_TOTAL, 0L)
        val accum = intent.getLongExtra(EXTRA_ACCUM, 0L)
        val startedWall = intent.getLongExtra(EXTRA_STARTED_WALL, 0L)

        ContextCompat.startForegroundService(
            context,
            Intent(context, AlarmService::class.java)
                .setAction(ACTION_START_ALERT)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_ELAPSED, total) // elapsed == total at ring time
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            TimerStore.rescheduleAll(context)
        }
    }
}

fun buildDoneNotification(
    context: Context,
    id: Long,
    label: String,
    totalMs: Long,
    elapsedMs: Long
): android.app.Notification {
    val flags = if (Build.VERSION.SDK_INT >= 23)
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    else PendingIntent.FLAG_UPDATE_CURRENT

    fun fgServicePI(reqCode: Int, action: String): PendingIntent {
        val intent = Intent(context, AlarmService::class.java).setAction(action).putExtra(EXTRA_ID, id)
        return if (Build.VERSION.SDK_INT >= 26)
            PendingIntent.getForegroundService(context, reqCode, intent, flags)
        else
            PendingIntent.getService(context, reqCode, intent, flags)
    }

    val pausePi = fgServicePI((id*2+1).toInt(), ACTION_PAUSE)
    val resetPi = fgServicePI((id*2+2).toInt(), ACTION_RESET)
    val silencePi = fgServicePI((id*2+3).toInt(), ACTION_SILENCE)

    val fullPi = PendingIntent.getActivity(
        context, id.toInt(),
        Intent(context, AlarmActivity::class.java)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_TOTAL, totalMs)
            .putExtra(EXTRA_ELAPSED, elapsedMs)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        flags
    )

    val text = "Total ${formatHMS(totalMs)} • Elapsed ${formatHMS(elapsedMs)}"

    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("$label finished")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setAutoCancel(false)
        .addAction(android.R.drawable.ic_lock_silent_mode, "Silence", silencePi)
        .addAction(android.R.drawable.ic_media_pause, "Pause", pausePi)
        .addAction(android.R.drawable.ic_menu_revert, "Reset", resetPi)
        .setFullScreenIntent(fullPi, true)
        .build()
}
