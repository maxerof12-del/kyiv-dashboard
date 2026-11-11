package ua.dash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ua.dash.Res
import ua.dash.UiState
import ua.dash.data.DtekRepo
import ua.dash.data.Holidays
import ua.dash.data.LimitsInfo
import ua.dash.data.PowerInfo
import ua.dash.data.Slot
import ua.dash.data.Window
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

private val DAYS = listOf("понеділок", "вівторок", "середа", "четвер", "п'ятниця", "субота", "неділя")
private val DAYS_SHORT = listOf("пн", "вт", "ср", "чт", "пт", "сб", "нд")
private val MONTHS = listOf(
    "січня", "лютого", "березня", "квітня", "травня", "червня",
    "липня", "серпня", "вересня", "жовтня", "листопада", "грудня"
)

private fun hm(z: ZonedDateTime) = "%02d:%02d".format(z.hour, z.minute)
private fun zdt(ms: Long) = Instant.ofEpochMilli(ms).atZone(DtekRepo.KYIV)

private fun durationText(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    val h = m / 60
    val mm = m % 60
    return when {
        h >= 24 -> "${h / 24} д ${h % 24} год"
        h > 0 -> "$h год $mm хв"
        else -> "$mm хв"
    }
}

@Composable
fun rememberNow(): ZonedDateTime {
    var now by remember { mutableStateOf(ZonedDateTime.now(DtekRepo.KYIV)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now(DtekRepo.KYIV)
            delay(1000L - System.currentTimeMillis() % 1000L + 5)
        }
    }
    return now
}

@Composable
fun Card(modifier: Modifier = Modifier, color: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(d(18)))
            .background(color ?: p.card)
            .padding(horizontal = d(18), vertical = d(14)),
        content = content
    )
}

@Composable
fun Dashboard(state: UiState, battery: Pair<Int, Boolean>, onSettings: () -> Unit, onRefresh: () -> Unit) {
    val p = if (state.lightTheme) LightPalette else DarkPalette
    val now = rememberNow()
    // Лёгкий сдвиг всего экрана раз в несколько минут — защита от выгорания
    val shift = ((now.toEpochSecond() / 180) % 5).toInt() - 2

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(p.bg)
    ) {
        val scale = minOf(maxWidth.value / 960f, maxHeight.value / 600f)
        CompositionLocalProvider(LocalPalette provides p, LocalScale provides scale) {
            Row(
                Modifier
                    .fillMaxSize()
                    .offset(x = shift.dp, y = (-shift).dp)
                    .padding(d(14)),
                horizontalArrangement = Arrangement.spacedBy(d(12))
            ) {
                Column(
                    Modifier
                        .weight(1.3f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(d(10))
                ) {
                    Header(now, state, battery, onRefresh)
                    val hol = Holidays.forDate(now.toLocalDate())
                    if (hol.isNotEmpty()) {
                        Text(
                            hol.joinToString(" · "),
                            color = p.muted, fontSize = t(15), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = d(6))
                        )
                    }
                    AlertCard(state, now)
                    PowerCard(state.power, now, Modifier.weight(1f))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(d(12))
                ) {
                    LimitsCard(
                        "✳", "Claude", p.claude, state.claude, state.claudeLoggedIn, now,
                        onSettings, Modifier.weight(1f)
                    )
                    LimitsCard(
                        ">_", "Codex", p.codex, state.codex, state.codexLoggedIn, now,
                        onSettings, Modifier.weight(1f)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            "⚙ налаштування",
                            color = p.muted, fontSize = t(13),
                            modifier = Modifier
                                .clip(RoundedCornerShape(d(8)))
                                .clickable { onSettings() }
                                .padding(horizontal = d(8), vertical = d(2))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(now: ZonedDateTime, state: UiState, battery: Pair<Int, Boolean>, onRefresh: () -> Unit) {
    val p = LocalPalette.current
    Card(Modifier.fillMaxWidth().clickable { onRefresh() }, color = p.headerBg) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        hm(now), color = p.headerText, fontSize = t(104), fontWeight = FontWeight.Bold,
                        lineHeight = t(108), fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        ":%02d".format(now.second), color = p.headerMuted, fontSize = t(30),
                        modifier = Modifier.padding(start = d(4), bottom = d(18))
                    )
                }
                Text(
                    "${DAYS[now.dayOfWeek.value - 1]}, ${now.dayOfMonth} ${MONTHS[now.monthValue - 1]}",
                    color = p.headerText, fontSize = t(24)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val w = state.weather.data
                if (w != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(w.icon, fontSize = t(40), color = p.on)
                        Spacer(Modifier.width(d(8)))
                        Text(
                            (if (w.temp > 0) "+" else "") + "${w.temp}°",
                            color = p.headerText, fontSize = t(56), fontWeight = FontWeight.Bold
                        )
                    }
                    Text(w.description, color = p.headerMuted, fontSize = t(18))
                    Text(
                        "відчув. ${sign(w.feels)}° · ${w.wind} м/с",
                        color = p.headerMuted, fontSize = t(15)
                    )
                    Text(
                        "${sign(w.min)}° … ${sign(w.max)}°",
                        color = p.headerMuted, fontSize = t(15)
                    )
                } else {
                    Text(state.weather.error ?: "погода…", color = p.headerMuted, fontSize = t(16))
                }
                Spacer(Modifier.height(d(10)))
                BatteryView(battery.first, battery.second)
            }
        }
    }
}

private fun sign(v: Int) = if (v > 0) "+$v" else "$v"

@Composable
private fun BatteryView(level: Int, charging: Boolean) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (charging) "⚡" else "") + "$level%",
            color = if (level <= 15 && !charging) p.bad else p.headerText, fontSize = t(18)
        )
        Spacer(Modifier.width(d(6)))
        val fill = if (level <= 15 && !charging) p.bad else p.headerText
        Canvas(Modifier.size(d(30), d(15))) {
            val sw = 1.5.dp.toPx()
            val bodyW = size.width - 3.dp.toPx()
            drawRoundRect(
                color = p.headerMuted, size = Size(bodyW, size.height),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(sw)
            )
            drawRect(
                p.headerMuted,
                topLeft = Offset(bodyW + 0.5.dp.toPx(), size.height * 0.3f),
                size = Size(2.dp.toPx(), size.height * 0.4f)
            )
            val inner = (bodyW - 4 * sw) * (level.coerceIn(0, 100) / 100f)
            drawRoundRect(
                fill, topLeft = Offset(2 * sw, 2 * sw),
                size = Size(inner, size.height - 4 * sw),
                cornerRadius = CornerRadius(1.5.dp.toPx())
            )
        }
    }
}

@Composable
private fun AlertCard(state: UiState, now: ZonedDateTime) {
    val p = LocalPalette.current
    val a = state.alert.data
    val active = a?.active == true
    Card(Modifier.fillMaxWidth(), color = if (active) p.alertBg else p.card) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(d(14))
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            a == null -> p.muted
                            active -> Color.White
                            else -> p.good
                        }
                    )
            )
            Spacer(Modifier.width(d(12)))
            Text(
                when {
                    a == null -> state.alert.error?.let { "Тривоги: $it" } ?: "Тривоги…"
                    active -> "ПОВІТРЯНА ТРИВОГА"
                    else -> "Тривоги немає"
                },
                color = if (active) Color.White else p.text,
                fontSize = t(24), fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (a != null) {
                val since = now.toInstant().toEpochMilli() - a.since
                val txt = if (active) "триває ${durationText(since)}" else
                    (if (a.oblastActive == true) "в області тривога · " else "") + "${durationText(since)}"
                Text(
                    txt,
                    color = if (active) Color.White else (if (a.oblastActive == true) p.warn else p.muted),
                    fontSize = t(16)
                )
            }
        }
    }
}

// ───────────────────────── Свет ─────────────────────────

private data class PowerNow(val current: Slot, val nextChangeMs: Long?, val nextSlot: Slot?)

private fun analyze(info: PowerInfo, now: ZonedDateTime): PowerNow? {
    val today = info.today ?: return null
    val all = today.slots + (info.tomorrow?.slots ?: emptyList())
    val idx = now.hour * 2 + if (now.minute >= 30) 1 else 0
    val cur = today.slots[idx]
    fun lit(s: Slot) = s == Slot.ON
    for (i in idx + 1 until all.size) {
        val s = all[i]
        if (s == Slot.UNKNOWN) break
        if (lit(s) != lit(cur) || (cur == Slot.OFF && s == Slot.MAYBE) || (cur == Slot.MAYBE && s == Slot.OFF)) {
            val start = now.toLocalDate().atStartOfDay(DtekRepo.KYIV).plusMinutes(i * 30L)
            return PowerNow(cur, start.toInstant().toEpochMilli(), s)
        }
    }
    return PowerNow(cur, null, null)
}

@Composable
private fun PowerCard(res: Res<PowerInfo>, now: ZonedDateTime, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier.fillMaxWidth()) {
        val info = res.data
        if (info == null) {
            Text("Графік світла", color = p.text, fontSize = t(22), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(d(8)))
            Text(
                res.error ?: "Завантажую графік ДТЕК…",
                color = if (res.error != null) p.bad else p.muted, fontSize = t(16)
            )
            return@Card
        }
        val a = analyze(info, now)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(info.groupName, color = p.text, fontSize = t(22), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (a != null) {
                val (label, color) = when (a.current) {
                    Slot.ON -> "Світло є" to p.on
                    Slot.OFF -> "Світла немає" to p.muted
                    Slot.MAYBE -> "Можливе відключення" to p.warn
                    Slot.UNKNOWN -> "Невідомо" to p.muted
                }
                Box(
                    Modifier
                        .size(d(12))
                        .clip(RoundedCornerShape(50))
                        .background(if (a.current == Slot.OFF) p.off else color)
                )
                Spacer(Modifier.width(d(8)))
                Text(label, color = p.text, fontSize = t(20), fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(d(4)))
        if (a?.nextChangeMs != null) {
            val at = zdt(a.nextChangeMs)
            val dayPrefix = if (at.toLocalDate() != now.toLocalDate()) "завтра о " else "о "
            val verb = when {
                a.current == Slot.ON && a.nextSlot == Slot.MAYBE -> "можливе вимкнення "
                a.current == Slot.ON -> "вимкнуть "
                a.nextSlot == Slot.ON -> "увімкнуть "
                a.nextSlot == Slot.MAYBE -> "можливо увімкнуть "
                else -> "зміна "
            }
            val left = a.nextChangeMs - now.toInstant().toEpochMilli()
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    verb + dayPrefix + hm(at),
                    color = p.text, fontSize = t(34), fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(d(12)))
                Text("через ${durationText(left)}", color = p.muted, fontSize = t(18),
                    modifier = Modifier.padding(bottom = d(5)))
            }
        } else if (a != null) {
            Text(
                if (a.current == Slot.ON) "без відключень до кінця графіка" else "до кінця графіка",
                color = p.text, fontSize = t(26), fontWeight = FontWeight.Bold
            )
        } else {
            Text("Графік на сьогодні не опубліковано", color = p.muted, fontSize = t(20))
        }
        Spacer(Modifier.height(d(10)))
        info.today?.let { DayBar(it.slots, now, height = 44, showMarker = true) }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("завтра", color = p.muted, fontSize = t(14), modifier = Modifier.width(d(58)))
            if (info.tomorrow != null) {
                Box(Modifier.weight(1f)) { DayBar(info.tomorrow.slots, now, height = 18, showMarker = false, labels = false) }
            } else {
                Text("ще не опубліковано", color = p.muted, fontSize = t(14))
            }
        }
        Spacer(Modifier.height(d(6)))
        val stale = res.error?.let { " · $it" } ?: ""
        Text(
            (info.notice?.let { "$it · " } ?: "") + "ДТЕК: ${info.updated}$stale",
            color = if (res.error != null) p.warn else p.muted, fontSize = t(12), maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DayBar(slots: List<Slot>, now: ZonedDateTime, height: Int, showMarker: Boolean, labels: Boolean = true) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        val markerH = if (showMarker) 12 else 0
        val markerDp = d(markerH)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(d(height + markerH))
        ) {
            val top = markerDp.toPx()
            val h = size.height - top
            val gap = 2.dp.toPx()
            val slotW = size.width / 48f
            val r = CornerRadius(3.dp.toPx())
            for (i in 0 until 48) {
                val c = when (slots.getOrNull(i)) {
                    Slot.ON -> p.on
                    Slot.OFF -> p.off
                    Slot.MAYBE -> p.maybe
                    else -> p.track
                }
                // зазор только между часами, полчаса внутри часа — слитно
                val leftGap = if (i % 2 == 0) gap / 2 else 0f
                val rightGap = if (i % 2 == 1) gap / 2 else 0f
                drawRoundRect(
                    c,
                    topLeft = Offset(i * slotW + leftGap, top),
                    size = Size(slotW - leftGap - rightGap, h),
                    cornerRadius = if (leftGap > 0 || rightGap > 0) r else CornerRadius.Zero
                )
            }
            if (showMarker) {
                val frac = (now.hour * 60 + now.minute) / 1440f
                val x = size.width * frac
                val tri = top * 0.9f
                val path = Path().apply {
                    moveTo(x - tri * 0.7f, 0f)
                    lineTo(x + tri * 0.7f, 0f)
                    lineTo(x, tri)
                    close()
                }
                drawPath(path, p.text)
                drawRect(p.text, topLeft = Offset(x - 1.dp.toPx(), top), size = Size(2.dp.toPx(), h))
            }
        }
        if (labels) {
            Box(Modifier.fillMaxWidth().padding(top = d(3))) {
                for (hh in listOf(0, 3, 6, 9, 12, 15, 18, 21, 24)) {
                    Text(
                        "%02d".format(hh),
                        color = p.muted, fontSize = t(13),
                        modifier = Modifier.align(BiasAlignment(-1f + 2f * hh / 24f, 0f))
                    )
                }
            }
        }
    }
}


// ───────────────────────── Лимиты ─────────────────────────

@Composable
private fun LimitsCard(
    icon: String,
    title: String,
    accent: Color,
    res: Res<LimitsInfo>,
    loggedIn: Boolean,
    now: ZonedDateTime,
    onSettings: () -> Unit,
    modifier: Modifier,
) {
    val p = LocalPalette.current
    Card(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(d(7)))
                    .background(accent)
                    .padding(horizontal = d(7), vertical = d(1))
            ) {
                Text(icon, color = Color.White, fontSize = t(18), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(d(10)))
            Text(title, color = p.text, fontSize = t(26), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            res.data?.plan?.let { Text(it, color = p.muted, fontSize = t(14)) }
        }
        Spacer(Modifier.height(d(8)))
        if (!loggedIn) {
            Spacer(Modifier.weight(1f))
            Text(
                "Увійдіть у налаштуваннях →",
                color = accent, fontSize = t(18),
                modifier = Modifier.clickable { onSettings() }
            )
            Spacer(Modifier.weight(1f))
            return@Card
        }
        val data = res.data
        if (data == null) {
            Text(res.error ?: "Завантаження…", color = if (res.error != null) p.bad else p.muted, fontSize = t(16))
            return@Card
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            data.windows.take(3).forEach { w -> WindowRow(w, accent, now) }
            if (data.windows.isEmpty()) Text("Немає даних про ліміти", color = p.muted, fontSize = t(16))
        }
        if (res.error != null) {
            Text("⚠ ${res.error}", color = p.warn, fontSize = t(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WindowRow(w: Window, accent: Color, now: ZonedDateTime) {
    val p = LocalPalette.current
    val nowMs = now.toInstant().toEpochMilli()
    val used = w.usedPercent.coerceIn(0.0, 100.0)
    val elapsed: Double? = if (w.resetsAt != null && w.windowMs > 0) {
        (1.0 - (w.resetsAt - nowMs).toDouble() / w.windowMs).coerceIn(0.0, 1.0) * 100
    } else null
    val reserve = elapsed?.let { it - used }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(w.label, color = p.text, fontSize = t(19))
            Spacer(Modifier.weight(1f))
            Text(
                "${used.roundToInt()}%",
                color = if (used >= 90) p.bad else p.text,
                fontSize = t(32), fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(d(4)))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(d(18))
        ) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(p.track, cornerRadius = r)
            val fw = (size.width * (used / 100.0)).toFloat().coerceAtLeast(if (used > 0) size.height else 0f)
            val barColor = when {
                used >= 90 -> p.bad
                reserve != null && reserve < -10 -> p.warn
                else -> accent
            }
            drawRoundRect(barColor, size = Size(fw.coerceAtMost(size.width), size.height), cornerRadius = r)
            if (elapsed != null) {
                val x = (size.width * (elapsed / 100.0)).toFloat()
                drawRect(
                    p.text,
                    topLeft = Offset(x - 1.25.dp.toPx(), -3.dp.toPx()),
                    size = Size(2.5.dp.toPx(), size.height + 6.dp.toPx())
                )
            }
        }
        Spacer(Modifier.height(d(4)))
        val resetTxt = w.resetsAt?.let {
            val z = zdt(it)
            val sameDay = z.toLocalDate() == now.toLocalDate()
            "до " + (if (sameDay) "" else DAYS_SHORT[z.dayOfWeek.value - 1] + " ") + hm(z)
        } ?: ""
        val resTxt = reserve?.let {
            val v = it.roundToInt()
            if (v >= 0) "запас $v%" else "перевищення ${abs(v)}%"
        } ?: ""
        Text(
            listOf(resetTxt, resTxt).filter { it.isNotBlank() }.joinToString(" · "),
            color = if (reserve != null && reserve < 0) p.warn else p.muted,
            fontSize = t(14)
        )
    }
}
