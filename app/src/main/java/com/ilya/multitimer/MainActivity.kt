package com.ilya.multitimer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

private const val CHANNEL_ID = "timer_done_channel"
private const val ACTION_PAUSE = "com.ilya.multitimer.ACTION_PAUSE"
private const val ACTION_RESET = "com.ilya.multitimer.ACTION_RESET"
private const val EXTRA_ID = "id"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)
        setContent { MultiTimerApp() }
    }
}

@Composable
fun MultiTimerApp() {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme) { TimerListScreen() }
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

@Composable
fun TimerListScreen() {
    val timers = remember { mutableStateListOf<TimerState>() }
    var idCounter by remember { mutableLongStateOf(1L) }
    var showDialog by remember { mutableStateOf(false) }
    var editingTimer by remember { mutableStateOf<TimerState?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val alertJobs = remember { mutableStateMapOf<Long, Job>() }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id == -1L) return
                when (intent.action) {
                    ACTION_PAUSE -> {
                        val idx = timers.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            val cur = timers[idx]
                            val nowR = SystemClock.elapsedRealtime()
                            val accrued = if (cur.startedRealtimeMs != null) cur.accumulatedMs + (nowR - cur.startedRealtimeMs) else cur.accumulatedMs
                            timers[idx] = cur.copy(isRunning = false, startedRealtimeMs = null, accumulatedMs = accrued)
                            alertJobs.remove(id)?.cancel()
                            NotificationManagerCompat.from(ctx).cancel(id.toInt())
                        }
                    }
                    ACTION_RESET -> {
                        val idx = timers.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            val cur = timers[idx]
                            alertJobs.remove(id)?.cancel()
                            NotificationManagerCompat.from(ctx).cancel(id.toInt())
                            timers[idx] = cur.copy(
                                isRunning = false,
                                startedRealtimeMs = null,
                                startedWallClockMs = null,
                                finishedWallClockMs = null,
                                alerted = false,
                                accumulatedMs = 0L
                            )
                        }
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
            for (i in timers.indices) {
                val t = timers[i]
                val elapsed = elapsedMs(t, tick)
                if (elapsed >= t.totalMs) {
                    val justFinished = t.finishedWallClockMs == null
                    val updated = t.copy(finishedWallClockMs = t.finishedWallClockMs ?: nowWall)
                    timers[i] = updated
                    if (justFinished && !updated.alerted && alertJobs[t.id] == null) {
                        alertJobs[t.id] = scope.launch { continuousAlertForUpToFiveMinutes(context) }
                        showTimerDoneNotification(context, updated, elapsed)
                        timers[i] = updated.copy(alerted = true)
                    }
                }
            }
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
                            val nowR = SystemClock.elapsedRealtime()
                            val nowW = System.currentTimeMillis()
                            val idx = timers.indexOfFirst { it.id == t.id }
                            if (idx >= 0) {
                                val cur = timers[idx]
                                timers[idx] = if (cur.isRunning) {
                                    val accrued = cur.accumulatedMs + (nowR - (cur.startedRealtimeMs ?: nowR))
                                    alertJobs.remove(cur.id)?.cancel()
                                    NotificationManagerCompat.from(context).cancel(cur.id.toInt())
                                    cur.copy(isRunning = false, startedRealtimeMs = null, accumulatedMs = accrued)
                                } else {
                                    cur.copy(
                                        isRunning = true,
                                        startedRealtimeMs = nowR,
                                        startedWallClockMs = cur.startedWallClockMs ?: nowW
                                    )
                                }
                            }
                        },
                        onRestart = {
                            val idx = timers.indexOfFirst { it.id == t.id }
                            if (idx >= 0) {
                                alertJobs.remove(t.id)?.cancel()
                                NotificationManagerCompat.from(context).cancel(t.id.toInt())
                                timers[idx] = t.copy(
                                    isRunning = false,
                                    startedRealtimeMs = null,
                                    startedWallClockMs = null,
                                    finishedWallClockMs = null,
                                    alerted = false,
                                    accumulatedMs = 0L
                                )
                            }
                        },
                        onDelete = {
                            alertJobs.remove(t.id)?.cancel()
                            NotificationManagerCompat.from(context).cancel(t.id.toInt())
                            timers.removeAll { it.id == t.id }
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
                    timers.add(
                        TimerState(
                            id = idCounter++,
                            label = label.ifBlank { "Timer $idCounter" },
                            totalMs = totalMs,
                            startedWallClockMs = if (startNow) nowW else null,
                            startedRealtimeMs = if (startNow) nowR else null,
                            accumulatedMs = 0L,
                            isRunning = startNow
                        )
                    )
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
                        timers[idx] = if (restartOnEdit || startNow) {
                            alertJobs.remove(cur.id)?.cancel()
                            NotificationManagerCompat.from(context).cancel(cur.id.toInt())
                            base.copy(
                                isRunning = true,
                                accumulatedMs = 0L,
                                startedRealtimeMs = nowR,
                                startedWallClockMs = nowW
                            )
                        } else {
                            base
                        }
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
    onDelete: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFmt = remember { DateTimeFormatter.ofPattern("h:mm:ss a").withZone(zone) }
    val elapsed = elapsedMs(timer, nowRealtime)
    val remaining = max(0L, timer.totalMs - elapsed)
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
                    Text("✏ Edit")
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
                    label = { Text("Duration (HH:MM:SS or MM:SS)") },
                    isError = error,
                    supportingText = { if (error) Text("Try 00:05:00 or 5:00") },
                    singleLine = true
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

private fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Timers", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(ch)
    }
}

private fun showTimerDoneNotification(context: Context, timer: TimerState, elapsedMs: Long) {
    val pauseIntent = Intent(ACTION_PAUSE).putExtra(EXTRA_ID, timer.id).setPackage(context.packageName)
    val resetIntent = Intent(ACTION_RESET).putExtra(EXTRA_ID, timer.id).setPackage(context.packageName)
    val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
    val pausePi = PendingIntent.getBroadcast(context, (timer.id * 2 + 1).toInt(), pauseIntent, flags)
    val resetPi = PendingIntent.getBroadcast(context, (timer.id * 2 + 2).toInt(), resetIntent, flags)

    val text = "Total ${formatHMS(timer.totalMs)} • Elapsed ${formatHMS(elapsedMs)}"

    if (!canPostNotifications(context)) return

    val n = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("${timer.label} finished")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .addAction(android.R.drawable.ic_media_pause, "Pause", pausePi)
        .addAction(android.R.drawable.ic_menu_revert, "Reset", resetPi)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(context).notify(timer.id.toInt(), n)
}

private suspend fun continuousAlertForUpToFiveMinutes(context: Context) {
    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    val rt: Ringtone? = try { RingtoneManager.getRingtone(context, uri) } catch (_: Exception) { null }
    try { rt?.isLooping = true } catch (_: Exception) {}
    try { rt?.play() } catch (_: Exception) {}
    val vibrator = getVibrator(context)
    try {
        if (Build.VERSION.SDK_INT >= 26) {
            val pattern = longArrayOf(0, 700, 300)
            val effect = VibrationEffect.createWaveform(pattern, 0)
            vibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(700)
        }
    } catch (_: Exception) {}
    val start = SystemClock.elapsedRealtime()
    try {
        while (coroutineContext.isActive && SystemClock.elapsedRealtime() - start < 300_000L) {
            delay(1000)
        }
    } finally {
        try { rt?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
    }
}

private fun getVibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

private fun elapsedMs(t: TimerState, nowRealtime: Long): Long {
    val runPart = if (t.startedRealtimeMs != null) nowRealtime - t.startedRealtimeMs else 0L
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
    val parts = text.trim().split(":").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return try {
        val (h, m, s) = when (parts.size) {
            1 -> Triple(0, 0, parts[0].toInt())
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
