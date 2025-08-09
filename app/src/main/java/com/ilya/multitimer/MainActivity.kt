// MainActivity.kt
package com.ilya.multitimer

import android.content.Context
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import androidx.compose.foundation.BorderStroke

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MultiTimerApp() }
    }
}

@Composable
fun MultiTimerApp() {
    MaterialTheme { TimerListScreen() }
}

data class TimerState(
    val id: Long,
    val label: String,
    val totalMs: Long,
    val startedWallClockMs: Long?,   // first start after last restart
    val startedRealtimeMs: Long?,    // monotonic start of current run; null if paused
    val accumulatedMs: Long,         // elapsed from previous runs
    val isRunning: Boolean,
    val finishedWallClockMs: Long? = null, // when crossed zero; freezes "will stop"
    val alerted: Boolean = false            // beep/vibrate done
)

@Composable
fun TimerListScreen() {
    val timers = remember { mutableStateListOf<TimerState>() }
    var idCounter by remember { mutableLongStateOf(1L) }
    var showAddDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Ticker to refresh UI ~5x/sec
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = SystemClock.elapsedRealtime()
            // Finish detection + one-shot alert
            val nowWall = System.currentTimeMillis()
            for (i in timers.indices) {
                val t = timers[i]
                val elapsed = elapsedMs(t, tick)
                if (elapsed >= t.totalMs) {
                    // Mark finished time once
                    val justFinished = t.finishedWallClockMs == null
                    val updated = t.copy(
                        // keep running so elapsed keeps counting
                        finishedWallClockMs = t.finishedWallClockMs ?: nowWall
                    )
                    timers[i] = updated
                    if (justFinished && !updated.alerted) {
                        // fire alert once
                        alertOnce(context)
                        timers[i] = updated.copy(alerted = true)
                    }
                }
            }
            kotlinx.coroutines.delay(200)
        }
    }

    // No top bar per your request
    Scaffold(
        floatingActionButton = { FloatingActionButton(onClick = { showAddDialog = true }) { Text("+") } }
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
                        onToggle = {
                            val nowR = SystemClock.elapsedRealtime()
                            val nowW = System.currentTimeMillis()
                            val idx = timers.indexOfFirst { it.id == t.id }
                            if (idx >= 0) {
                                val cur = timers[idx]
                                timers[idx] = if (cur.isRunning) {
                                    val accrued = cur.accumulatedMs + (nowR - (cur.startedRealtimeMs ?: nowR))
                                    cur.copy(
                                        isRunning = false,
                                        startedRealtimeMs = null,
                                        accumulatedMs = accrued
                                    )
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
                        onDelete = { timers.removeAll { it.id == t.id } }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddTimerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { label, totalMs, startNow ->
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
                showAddDialog = false
            }
        )
    }
}

@Composable
fun TimerCard(
    timer: TimerState,
    nowRealtime: Long,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFmt = remember { DateTimeFormatter.ofPattern("h:mm:ss a").withZone(zone) }

    val elapsed = elapsedMs(timer, nowRealtime)
    val remaining = max(0L, timer.totalMs - elapsed)

    // Split time and day-offset for label badge
    val (startedTime, startedOffset) = timer.startedWallClockMs?.let { timeAndOffset(it, zone, timeFmt) } ?: (null to null)
    val (stopTime, stopOffset) = when {
        timer.finishedWallClockMs != null -> timeAndOffset(timer.finishedWallClockMs, zone, timeFmt) // freeze
        timer.startedWallClockMs != null -> {
            val projEndMs = System.currentTimeMillis() + remaining
            timeAndOffset(projEndMs, zone, timeFmt)
        }
        else -> (null to null)
    }

    val isFinished = timer.finishedWallClockMs != null

    // Highlight finished timer
    val containerColor =
        if (isFinished) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surface
    val onContainer =
        if (isFinished) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // Header: label + original duration appended
            Text(
                "${timer.label}  ·  ${formatHMS(timer.totalMs)}",
                color = onContainer,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Big time row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatPill(label = "Elapsed", value = formatHMS(elapsed), modifier = Modifier.weight(1f))
                StatPill(label = "Left", value = formatHMS(remaining), modifier = Modifier.weight(1f))
            }

            // Secondary times row (labels get the bold (+/- day) tag)
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

            // Actions — reversed order: Delete | Restart | Start/Pause
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Delete (outlined, red)
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Text("    🗑   Delete", fontSize = 16.sp)
                }

                // Restart
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("    ↺   Reset", fontSize = 16.sp)
                }

                // Start / Pause
                Button(
                    onClick = onToggle,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(if (timer.isRunning) "    ⏸   Pause" else "    ▶   Start", fontSize = 16.sp)
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
    val bg = if (tonal) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.primaryContainer
    val fg = if (tonal) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onPrimaryContainer

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
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("(${labelSuffixBold})")
                    }
                }
            }
            Text(lbl, fontSize = 11.sp, letterSpacing = 0.8.sp, color = fg.copy(alpha = 0.75f))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AddTimerDialog(
    onDismiss: () -> Unit,
    onAdd: (label: String, totalMs: Long, startNow: Boolean) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("00:25:00") } // HH:MM:SS
    val error = remember(durationText) { parseDurationToMsOrNull(durationText) == null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Label") }, singleLine = true
                )
                OutlinedTextField(
                    value = durationText, onValueChange = { durationText = it },
                    label = { Text("Duration (HH:MM:SS or MM:SS)") },
                    isError = error,
                    supportingText = { if (error) Text("Try 00:05:00 or 5:00") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Add & Start (primary)
                Button(
                    enabled = !error,
                    onClick = {
                        val ms = parseDurationToMsOrNull(durationText)!!
                        onAdd(label.trim(), ms, true)
                    }
                ) { Text("Add & Start") }

                // Add (secondary)
                OutlinedButton(
                    enabled = !error,
                    onClick = {
                        val ms = parseDurationToMsOrNull(durationText)!!
                        onAdd(label.trim(), ms, false)
                    }
                ) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/* ---------- Helpers ---------- */

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

private suspend fun alertOnce(context: Context) {
    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    val rt: Ringtone? = try { RingtoneManager.getRingtone(context, uri) } catch (_: Exception) { null }

    try { rt?.play() } catch (_: Exception) {}
    vibrateOnce(context)
    kotlinx.coroutines.delay(3000)
    try { rt?.stop() } catch (_: Exception) {}
}

private fun vibrateOnce(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= 31) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    } catch (_: Exception) { /* ignore */ }
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
