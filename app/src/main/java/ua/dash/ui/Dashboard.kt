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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.text.drawText
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
                    verticalArrangement = Arrangement.spacedBy(d(10))
                ) {
                    MapCard(state.mapUrl, Modifier.weight(1.45f).fillMaxWidth())
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(d(12))
                    ) {
                        LimitsCard(
                            ">_", "Codex", p.codex, state.codex, state.codexLoggedIn, now,
                            onSettings, Modifier.weight(0.9f).fillMaxHeight()
                        )
                        InfoCard(state, Modifier.weight(1.1f).fillMaxHeight())
                    }
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

private data class PowerNow(
    val current: Slot,
    val periodStartMs: Long?,
    val nextChangeMs: Long?,
    val nextSlot: Slot?,
)

private fun lit(s: Slot) = s == Slot.ON

private fun analyze(info: PowerInfo, now: ZonedDateTime): PowerNow? {
    val today = info.today ?: return null
    val all = today.slots + (info.tomorrow?.slots ?: emptyList())
    val idx = now.hour * 2 + if (now.minute >= 30) 1 else 0
    val cur = today.slots[idx]
    val dayStart = now.toLocalDate().atStartOfDay(DtekRepo.KYIV)
    fun ms(i: Int) = dayStart.plusMinutes(i * 30L).toInstant().toEpochMilli()
    var startIdx = idx
    while (startIdx > 0 && same(today.slots[startIdx - 1], cur)) startIdx--
    val start = if (startIdx == 0) null else ms(startIdx)
    for (i in idx + 1 until all.size) {
        val s = all[i]
        if (s == Slot.UNKNOWN) break
        if (!same(s, cur)) return PowerNow(cur, start, ms(i), s)
    }
    return PowerNow(cur, start, null, null)
}

private fun same(a: Slot, b: Slot) = a == b || (a != Slot.ON && b != Slot.ON && a != Slot.UNKNOWN && b != Slot.UNKNOWN && a == b)

/** Интервалы без света: «08:00–09:00» */
private fun offRanges(slots: List<Slot>): List<Pair<String, Boolean>> {
    val res = mutableListOf<Pair<String, Boolean>>()
    var i = 0
    fun lbl(k: Int) = "%02d:%02d".format(k / 2, if (k % 2 == 0) 0 else 30)
    while (i < slots.size) {
        val s = slots[i]
        if (s == Slot.OFF || s == Slot.MAYBE) {
            var j = i
            while (j < slots.size && slots[j] == s) j++
            res.add("${lbl(i)}–${lbl(j)}" to (s == Slot.MAYBE))
            i = j
        } else i++
    }
    return res
}

private fun offHours(slots: List<Slot>) = slots.count { it == Slot.OFF } / 2.0

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
        Row(Modifier.fillMaxSize()) {
            // Циферблат 24 часа: снаружи сегодня, внутри завтра
            Box(Modifier.fillMaxHeight().aspectRatio(1f), contentAlignment = Alignment.Center) {
                PowerDial(info, now)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val on = a?.current == Slot.ON
                    Text(
                        if (on) "⚡" else "✕",
                        color = if (on) p.on else p.muted,
                        fontSize = t(if (on) 34 else 28), fontWeight = FontWeight.Bold
                    )
                    Text(hm(now), color = p.text, fontSize = t(20), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(d(16)))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (label, bg, fg) = when (a?.current) {
                        Slot.ON -> Triple("Світло є", p.on, Color(0xFF1B1B1B))
                        Slot.OFF -> Triple("Світла немає", p.off, Color.White)
                        Slot.MAYBE -> Triple("Можливе відключення", p.warn, Color(0xFF1B1B1B))
                        else -> Triple("Невідомо", p.track, p.text)
                    }
                    Text(
                        label, color = fg, fontSize = t(16), fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(bg)
                            .padding(horizontal = d(12), vertical = d(4))
                    )
                    Spacer(Modifier.weight(1f))
                    Text(info.groupName, color = p.muted, fontSize = t(15))
                }
                Spacer(Modifier.height(d(6)))
                if (a?.nextChangeMs != null) {
                    val at = zdt(a.nextChangeMs)
                    val dayPrefix = if (at.toLocalDate() != now.toLocalDate()) "завтра " else ""
                    val verb = when {
                        a.current == Slot.ON && a.nextSlot == Slot.MAYBE -> "можливо вимкнуть"
                        a.current == Slot.ON -> "вимкнуть"
                        a.nextSlot == Slot.ON -> "увімкнуть"
                        else -> "зміна"
                    }
                    Text(verb, color = p.muted, fontSize = t(16))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            dayPrefix + hm(at),
                            color = if (a.current == Slot.ON) p.text else p.on,
                            fontSize = t(46), fontWeight = FontWeight.Bold, lineHeight = t(48)
                        )
                        Spacer(Modifier.width(d(10)))
                        val left = a.nextChangeMs - now.toInstant().toEpochMilli()
                        Text(
                            "через ${durationText(left)}", color = p.text, fontSize = t(18),
                            modifier = Modifier.padding(bottom = d(7))
                        )
                    }
                    // Прогресс текущего периода
                    if (a.periodStartMs != null) {
                        val total = (a.nextChangeMs - a.periodStartMs).toFloat()
                        val done = ((now.toInstant().toEpochMilli() - a.periodStartMs) / total).coerceIn(0f, 1f)
                        Spacer(Modifier.height(d(4)))
                        Canvas(Modifier.fillMaxWidth().height(d(8))) {
                            val r = CornerRadius(size.height / 2)
                            drawRoundRect(p.track, cornerRadius = r)
                            drawRoundRect(
                                if (a.current == Slot.ON) p.on else p.muted,
                                size = Size(size.width * done, size.height), cornerRadius = r
                            )
                        }
                        Text(
                            "з ${hm(zdt(a.periodStartMs))} · минуло ${durationText(now.toInstant().toEpochMilli() - a.periodStartMs)}",
                            color = p.muted, fontSize = t(13)
                        )
                    }
                } else if (a != null) {
                    Text(
                        if (a.current == Slot.ON) "без відключень до кінця графіка" else "до кінця графіка",
                        color = p.text, fontSize = t(22), fontWeight = FontWeight.Bold
                    )
                } else {
                    Text("Графік на сьогодні не опубліковано", color = p.muted, fontSize = t(18))
                }
                Spacer(Modifier.weight(1f))
                RangesLine("Сьогодні", info.today?.slots, now, isToday = true)
                Spacer(Modifier.height(d(4)))
                RangesLine("Завтра", info.tomorrow?.slots, now, isToday = false)
                Spacer(Modifier.height(d(4)))
                val stale = res.error?.let { " · $it" } ?: ""
                val notice = info.notice?.replace(Regex("\\s*\\(.*?\\)"), "")
                Text(
                    (notice?.let { "$it · " } ?: "") + "ДТЕК ${info.updated}$stale",
                    color = if (res.error != null) p.warn else p.muted, fontSize = t(11), maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RangesLine(title: String, slots: List<Slot>?, now: ZonedDateTime, isToday: Boolean) {
    val p = LocalPalette.current
    val nowIdx = now.hour * 2 + if (now.minute >= 30) 1 else 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, color = p.muted, fontSize = t(13), fontWeight = FontWeight.Medium,
            modifier = Modifier.width(d(72))
        )
        if (slots == null) {
            Text("ще не опубліковано", color = p.muted, fontSize = t(13))
            return@Row
        }
        val ranges = offRanges(slots)
        if (ranges.isEmpty()) {
            Text("без відключень 🎉", color = p.good, fontSize = t(14))
            return@Row
        }
        Text(
            "${String.format(java.util.Locale.US, "%.1f", offHours(slots)).removeSuffix(".0").replace('.', ',')} год без світла",
            color = p.muted, fontSize = t(13), modifier = Modifier.padding(end = d(8))
        )
    }
    if (slots != null) {
        val ranges = offRanges(slots)
        if (ranges.isNotEmpty()) {
            // Прошедшие интервалы сегодня — бледнее
            val text = androidx.compose.ui.text.buildAnnotatedString {
                ranges.forEachIndexed { i, (r, maybe) ->
                    val endIdx = r.substringAfter("–").let { it.substring(0, 2).toInt() * 2 + if (it.endsWith("30")) 1 else 0 }
                    val past = isToday && endIdx <= nowIdx
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = when {
                                past -> p.muted.copy(alpha = 0.55f)
                                maybe -> p.warn
                                else -> p.text
                            }
                        )
                    )
                    append(r)
                    pop()
                    if (i < ranges.size - 1) append("   ")
                }
            }
            Text(
                text, fontSize = t(15), fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = d(72))
            )
        }
    }
}

@Composable
private fun PowerDial(info: PowerInfo, now: ZonedDateTime) {
    val p = LocalPalette.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(color = p.muted, fontSize = t(11))
    Canvas(Modifier.fillMaxSize()) {
        val outerW = size.minDimension * 0.11f
        val innerW = size.minDimension * 0.045f
        val pad = outerW / 2 + 2.dp.toPx()
        val cx = size.width / 2
        val cy = size.height / 2
        val rOuter = size.minDimension / 2 - pad
        val rInner = rOuter - outerW / 2 - innerW / 2 - 4.dp.toPx()

        fun color(s: Slot?) = when (s) {
            Slot.ON -> p.on
            Slot.OFF -> p.off
            Slot.MAYBE -> p.maybe
            else -> p.track
        }

        fun ring(slots: List<Slot>?, r: Float, w: Float, dim: Boolean) {
            val tl = Offset(cx - r, cy - r)
            val sz = Size(2 * r, 2 * r)
            for (i in 0 until 48) {
                val gapDeg = if (i % 2 == 1) 1.4f else 0f
                val start = -90f + i * 7.5f
                drawArc(
                    color = color(slots?.getOrNull(i)).let { if (dim) it.copy(alpha = 0.75f) else it },
                    startAngle = start,
                    sweepAngle = 7.5f - gapDeg,
                    useCenter = false,
                    topLeft = tl, size = sz,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w)
                )
            }
        }
        ring(info.today?.slots, rOuter, outerW, false)
        ring(info.tomorrow?.slots, rInner, innerW, true)

        // Метки часов
        for (h in listOf(0, 6, 12, 18)) {
            val ang = Math.toRadians((-90.0 + h * 15.0))
            val rl = rInner - innerW / 2 - 9.dp.toPx()
            val layout = measurer.measure("%02d".format(h), labelStyle)
            drawText(
                layout,
                topLeft = Offset(
                    cx + (rl * kotlin.math.cos(ang)).toFloat() - layout.size.width / 2,
                    cy + (rl * kotlin.math.sin(ang)).toFloat() - layout.size.height / 2
                )
            )
        }

        // Стрелка текущего времени
        val frac = (now.hour * 60 + now.minute) / 1440.0
        val ang = Math.toRadians(-90.0 + frac * 360.0)
        val r1 = rOuter + outerW / 2 + 1.dp.toPx()
        val r0 = rOuter - outerW / 2 - 1.dp.toPx()
        drawLine(
            p.text,
            Offset(cx + (r0 * kotlin.math.cos(ang)).toFloat(), cy + (r0 * kotlin.math.sin(ang)).toFloat()),
            Offset(cx + (r1 * kotlin.math.cos(ang)).toFloat(), cy + (r1 * kotlin.math.sin(ang)).toFloat()),
            strokeWidth = 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawCircle(
            p.text, radius = 4.dp.toPx(),
            center = Offset(cx + (r1 * kotlin.math.cos(ang)).toFloat(), cy + (r1 * kotlin.math.sin(ang)).toFloat())
        )
    }
}

// ───────────────────────── Инфо ─────────────────────────

@Composable
private fun InfoCard(state: UiState, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier) {
        val w = state.weather.data
        if (w != null && w.hours.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                w.hours.take(6).forEach { h ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%02d".format(h.hour), color = p.muted, fontSize = t(12))
                        Text(h.icon, fontSize = t(16), color = p.on)
                        Text(sign(h.temp) + "°", color = p.text, fontSize = t(14), fontWeight = FontWeight.Medium)
                        Text(
                            if (h.precip >= 10) "${h.precip}%" else " ",
                            color = Color(0xFF6CB4FF), fontSize = t(11)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val r = state.rates.data
        Row(verticalAlignment = Alignment.Bottom) {
            if (r != null) {
                Text("$ ", color = p.muted, fontSize = t(15))
                Text(String.format(java.util.Locale.US, "%.2f", r.usd).replace('.', ','), color = p.text, fontSize = t(20), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(d(12)))
                Text("€ ", color = p.muted, fontSize = t(15))
                Text(String.format(java.util.Locale.US, "%.2f", r.eur).replace('.', ','), color = p.text, fontSize = t(20), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(d(6)))
                Text("НБУ", color = p.muted, fontSize = t(11), modifier = Modifier.padding(bottom = d(3)))
            } else {
                Text(state.rates.error?.let { "курс: $it" } ?: "курс…", color = p.muted, fontSize = t(13))
            }
        }
        Spacer(Modifier.height(d(4)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val air = state.air.data
            if (air != null) {
                val c = when {
                    air.aqi <= 40 -> p.good
                    air.aqi <= 60 -> p.warn
                    else -> p.bad
                }
                Box(Modifier.size(d(10)).clip(RoundedCornerShape(50)).background(c))
                Spacer(Modifier.width(d(6)))
                Text("повітря ${air.label} · ${air.aqi}", color = p.text, fontSize = t(14))
            }
            Spacer(Modifier.weight(1f))
            if (w != null) Text("☀ ${w.sunrise}–${w.sunset}", color = p.muted, fontSize = t(13))
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
