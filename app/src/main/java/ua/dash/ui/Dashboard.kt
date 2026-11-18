package ua.dash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.border
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
            Column(
                Modifier
                    .fillMaxSize()
                    .offset(x = shift.dp, y = (-shift).dp)
                    .padding(d(12)),
                verticalArrangement = Arrangement.spacedBy(d(10))
            ) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(d(10))
                ) {
                    ClockCard(state, now, battery, onSettings, Modifier.weight(1.1f).fillMaxHeight())
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(d(10))
                    ) {
                        if (state.feedEnabled) FeedBox(state, now)
                        PowerPanel(
                            state.power, now, compact = state.feedEnabled,
                            modifier = Modifier.weight(1f).fillMaxWidth().clickable { onRefresh() }
                        )
                    }
                    Column(
                        Modifier.weight(1.1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(d(10))
                    ) {
                        AlertCard(state, now)
                        MapCard(state.mapUrl, state.homeLat, state.homeLon, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                BottomStrip(state)
            }
        }
    }
}

// ───────────────────────── Часы ─────────────────────────

@Composable
private fun ClockCard(
    state: UiState, now: ZonedDateTime, battery: Pair<Int, Boolean>,
    onSettings: () -> Unit, modifier: Modifier,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(d(18)))
            .background(p.card)
            .padding(d(12))
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val dial = minOf(maxWidth, maxHeight - d(118))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(dial)) { AnalogClock(state.power.data, now) }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        hm(now), color = p.text, fontSize = t(68), fontWeight = FontWeight.Bold,
                        lineHeight = t(70), letterSpacing = t(-1)
                    )
                    Text(
                        ":%02d".format(now.second), color = p.muted, fontSize = t(28),
                        modifier = Modifier.padding(start = d(2), bottom = d(9))
                    )
                }
                Text(
                    "${DAYS[now.dayOfWeek.value - 1]}, ${now.dayOfMonth} ${MONTHS[now.monthValue - 1]}",
                    color = p.text, fontSize = t(18), maxLines = 1
                )
                Holidays.forDate(now.toLocalDate()).firstOrNull()?.let {
                    Text(
                        "🎉 $it", color = p.on, fontSize = t(14), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = d(3))
                    )
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BatteryView(battery.first, battery.second)
            Text(
                "⚙", color = p.muted, fontSize = t(18),
                modifier = Modifier
                    .clip(RoundedCornerShape(d(8)))
                    .clickable { onSettings() }
                    .padding(start = d(8), end = d(2))
            )
        }
    }
}

// ───────────────────────── Кормление (бот в Termux) ─────────────────────────

@Composable
private fun FeedBox(state: UiState, now: ZonedDateTime) {
    val p = LocalPalette.current
    val f = state.botFeed.data
    val nowMs = now.toInstant().toEpochMilli()
    Card(Modifier.fillMaxWidth()) {
        if (f?.lastAt == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🍼", fontSize = t(30))
                Spacer(Modifier.width(d(10)))
                Text(
                    when {
                        state.botSearching -> "шукаю бота в Wi-Fi…"
                        f != null -> "записів ще немає"
                        else -> state.botFeed.error ?: "підключаюсь до бота…"
                    },
                    color = p.muted, fontSize = t(18), maxLines = 1
                )
            }
            return@Card
        }
        val ago = nowMs - f.lastAt
        val c = when {
            ago < 150 * 60_000 -> p.good
            ago < 210 * 60_000 -> p.warn
            else -> p.bad
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🍼", fontSize = t(30))
            Spacer(Modifier.width(d(8)))
            Text(hm(zdt(f.lastAt)), color = p.text, fontSize = t(44), fontWeight = FontWeight.Bold, lineHeight = t(46))
            f.lastMl?.let {
                Text(
                    "  " + (if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()) + " мл",
                    color = p.text, fontSize = t(22), maxLines = 1
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(durationText(ago), color = c, fontSize = t(26), fontWeight = FontWeight.Bold, maxLines = 1, lineHeight = t(28))
                Text("тому", color = c, fontSize = t(14))
            }
        }
        val stale = state.botFeed.error?.let { " · ⚠ $it" } ?: ""
        Text(
            "сьогодні ${f.todayMl.roundToInt()} мл, ${f.todayCount} р." +
                (f.lastPoop?.let { " · 💩 " + hm(zdt(it)) } ?: "") + stale,
            color = if (stale.isNotEmpty()) p.warn else p.muted, fontSize = t(16), maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ───────────────────────── Свет ─────────────────────────

private data class Rng(val start: Int, val end: Int, val maybe: Boolean)

private fun ranges(slots: List<Slot>): List<Rng> {
    val res = mutableListOf<Rng>()
    var i = 0
    while (i < slots.size) {
        val s = slots[i]
        if (s == Slot.OFF || s == Slot.MAYBE) {
            var j = i
            while (j < slots.size && slots[j] == s) j++
            res.add(Rng(i, j, s == Slot.MAYBE))
            i = j
        } else i++
    }
    return res
}

private fun slotLbl(k: Int) = "%02d:%02d".format(k / 2, if (k % 2 == 0) 0 else 30)

private fun hoursText(h: Double) =
    String.format(java.util.Locale.US, "%.1f", h).removeSuffix(".0").replace('.', ',')

/** Период, который начнётся после текущего: (начало, конец, тип) */
private fun followingPeriod(info: PowerInfo, now: ZonedDateTime, a: PowerNow): Triple<Long, Long?, Slot>? {
    val startMs = a.nextChangeMs ?: return null
    val next = a.nextSlot ?: return null
    val all = (info.today?.slots ?: return null) + (info.tomorrow?.slots ?: emptyList())
    val dayStart = now.toLocalDate().atStartOfDay(DtekRepo.KYIV).toInstant().toEpochMilli()
    val i0 = ((startMs - dayStart) / 1_800_000L).toInt()
    var j = i0
    while (j < all.size && all[j] == next) j++
    val end = if (j < all.size && all[j] != Slot.UNKNOWN) dayStart + j * 1_800_000L else null
    return Triple(startMs, end, next)
}

@Composable
private fun PowerPanel(res: Res<PowerInfo>, now: ZonedDateTime, compact: Boolean = false, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier) {
        val info = res.data
        if (info == null) {
            Text("Графік світла", color = p.text, fontSize = t(24), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(d(8)))
            Text(
                res.error ?: "Завантажую графік ДТЕК…",
                color = if (res.error != null) p.bad else p.muted, fontSize = t(17)
            )
            return@Card
        }
        val a = analyze(info, now)
        // Статус
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (label, bg, fg) = when (a?.current) {
                Slot.ON -> Triple("⚡ Світло є", p.on, Color(0xFF1B1B1B))
                Slot.OFF -> Triple("✕ Світла немає", p.off, Color.White)
                Slot.MAYBE -> Triple("Можливе відкл.", p.warn, Color(0xFF1B1B1B))
                else -> Triple("Невідомо", p.track, p.text)
            }
            Text(
                label, color = fg, fontSize = t(18), fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .padding(horizontal = d(14), vertical = d(5))
            )
            Spacer(Modifier.weight(1f))
            Text(info.groupName, color = p.muted, fontSize = t(14), maxLines = 1)
        }
        // Главное: когда изменится
        if (a?.nextChangeMs != null) {
            val at = zdt(a.nextChangeMs)
            val verb = when {
                a.current == Slot.ON && a.nextSlot == Slot.MAYBE -> "можливо вимкнуть"
                a.current == Slot.ON -> "вимкнуть"
                a.nextSlot == Slot.ON -> "увімкнуть"
                else -> "зміна"
            }
            val tomorrow = at.toLocalDate() != now.toLocalDate()
            Text(
                verb + (if (tomorrow) " завтра о" else " о"),
                color = p.muted, fontSize = t(if (compact) 17 else 20),
                modifier = Modifier.padding(top = d(if (compact) 6 else 10))
            )
            val left = a.nextChangeMs - now.toInstant().toEpochMilli()
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hm(at),
                        color = if (a.nextSlot == Slot.ON) p.on else p.text,
                        fontSize = t(60), fontWeight = FontWeight.Bold, lineHeight = t(62), letterSpacing = t(-1)
                    )
                    Spacer(Modifier.width(d(12)))
                    Column {
                        Text("через", color = p.muted, fontSize = t(15))
                        Text(durationText(left), color = p.text, fontSize = t(19), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            } else {
                Text(
                    hm(at),
                    color = if (a.nextSlot == Slot.ON) p.on else p.text,
                    fontSize = t(86), fontWeight = FontWeight.Bold, lineHeight = t(88), letterSpacing = t(-2)
                )
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("через ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append(durationText(left))
                        pop()
                    },
                    color = p.text, fontSize = t(21)
                )
            }
            if (a.periodStartMs != null) {
                val total = (a.nextChangeMs - a.periodStartMs).toFloat()
                val done = ((now.toInstant().toEpochMilli() - a.periodStartMs) / total).coerceIn(0f, 1f)
                Spacer(Modifier.height(d(if (compact) 4 else 8)))
                Canvas(Modifier.fillMaxWidth().height(d(9))) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(p.track, cornerRadius = r)
                    drawRoundRect(
                        if (a.current == Slot.ON) p.on else p.muted,
                        size = Size(size.width * done, size.height), cornerRadius = r
                    )
                }
                if (!compact) Text(
                    (if (a.current == Slot.ON) "світло з " else "без світла з ") + hm(zdt(a.periodStartMs)) +
                        " · минуло " + durationText(now.toInstant().toEpochMilli() - a.periodStartMs),
                    color = p.muted, fontSize = t(13), maxLines = 1, modifier = Modifier.padding(top = d(4))
                )
            }
            Spacer(Modifier.weight(1f))
            // «Далі»: следующий период целиком
            followingPeriod(info, now, a)?.let { (st, en, kind) ->
                val isOn = kind == Slot.ON
                val tint = if (isOn) p.on else p.muted
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(d(12)))
                        .background(tint.copy(alpha = 0.12f))
                        .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(d(12)))
                        .padding(horizontal = d(12), vertical = d(7)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isOn) "⚡" else "✕", color = tint, fontSize = t(22))
                    Spacer(Modifier.width(d(10)))
                    Column {
                        Text(
                            if (isOn) "далі світло буде" else "далі світла не буде",
                            color = p.muted, fontSize = t(12)
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                hm(zdt(st)) + " – " + (en?.let { hm(zdt(it)) } ?: "…"),
                                color = p.text, fontSize = t(20), fontWeight = FontWeight.Bold, maxLines = 1
                            )
                            en?.let {
                                Text(
                                    " · " + hoursText((it - st) / 3_600_000.0) + " год",
                                    color = tint, fontSize = t(18), maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        } else if (a != null) {
            Spacer(Modifier.height(d(12)))
            Text(
                if (a.current == Slot.ON) "без відключень до кінця графіка" else "до кінця графіка",
                color = p.text, fontSize = t(26), fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.height(d(12)))
            Text("Графік на сьогодні не опубліковано", color = p.muted, fontSize = t(20))
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(d(10)))
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.track))
        Spacer(Modifier.height(d(8)))
        DayRanges("Сьогодні", info.today?.slots, now, isToday = true)
        Spacer(Modifier.height(d(8)))
        DayRanges("Завтра", info.tomorrow?.slots, now, isToday = false)
        Spacer(Modifier.height(d(6)))
        val stale = res.error?.let { " · $it" } ?: ""
        Text(
            "ДТЕК ${info.updated.takeLast(5)} · край годинника = 12 год" + (info.notice?.let { " · $it" } ?: "") + stale,
            color = if (res.error != null) p.warn else p.muted, fontSize = t(11), maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayRanges(title: String, slots: List<Slot>?, now: ZonedDateTime, isToday: Boolean) {
    val p = LocalPalette.current
    val nowIdx = now.hour * 2 + if (now.minute >= 30) 1 else 0
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, color = p.text, fontSize = t(17), fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (slots != null) {
            val off = slots.count { it == Slot.OFF } / 2.0
            Text(
                if (off == 0.0) "без відключень 🎉" else "${hoursText(off)} год без світла",
                color = if (off == 0.0) p.good else p.muted, fontSize = t(13)
            )
        }
    }
    if (slots == null) {
        Text("ще не опубліковано", color = p.muted, fontSize = t(17))
        return
    }
    val rs = ranges(slots)
    if (rs.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(d(10)),
        verticalArrangement = Arrangement.spacedBy(d(2))
    ) {
        rs.forEach { r ->
            val past = isToday && r.end <= nowIdx
            val current = isToday && nowIdx >= r.start && nowIdx < r.end
            Text(
                slotLbl(r.start) + "–" + slotLbl(r.end),
                color = when {
                    past -> p.muted.copy(alpha = 0.55f)
                    r.maybe -> p.warn
                    current -> Color.White
                    else -> p.text
                },
                fontSize = t(19), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                modifier = if (current) Modifier
                    .clip(RoundedCornerShape(d(6)))
                    .background(p.off)
                    .padding(horizontal = d(5)) else Modifier
            )
        }
    }
}

// ───────────────────────── Тревога ─────────────────────────

@Composable
private fun AlertCard(state: UiState, now: ZonedDateTime) {
    val p = LocalPalette.current
    val a = state.alert.data
    val active = a?.active == true
    val nowMs = now.toInstant().toEpochMilli()
    Card(Modifier.fillMaxWidth(), color = if (active) p.alertBg else p.card) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = when {
                a == null -> p.muted
                active -> Color.White
                else -> p.good
            }
            Box(
                Modifier
                    .size(d(26))
                    .clip(RoundedCornerShape(50))
                    .background(dot.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(d(18)).clip(RoundedCornerShape(50)).background(dot))
            }
            Spacer(Modifier.width(d(12)))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        a == null -> state.alert.error?.let { "Тривоги: $it" } ?: "Тривоги…"
                        active -> "ТРИВОГА"
                        else -> "Тривоги немає"
                    },
                    color = if (active) Color.White else p.text,
                    fontSize = t(if (active) 35 else 29), fontWeight = FontWeight.Bold,
                    lineHeight = t(36), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (a != null) {
                    Text(
                        a.region + if (active) " · триває ${durationText(nowMs - a.since)} · в укриття!"
                        else " · вже ${durationText(nowMs - a.since)}",
                        color = if (active) Color.White else p.muted, fontSize = t(14), maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (a?.oblastActive != null) {
            Spacer(Modifier.height(d(9)))
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(if (active) Color.White.copy(alpha = 0.3f) else p.track)
            )
            Spacer(Modifier.height(d(8)))
            val oa = a.oblastActive == true
            val c = when {
                active -> Color.White
                oa -> p.bad
                else -> p.good
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(d(11)).clip(RoundedCornerShape(50)).background(c))
                Spacer(Modifier.width(d(8)))
                Text(
                    "Київська обл. — " + if (oa) "тривога" else "немає тривоги",
                    color = if (active) Color.White else if (oa) p.bad else p.muted,
                    fontSize = t(15), modifier = Modifier.weight(1f), maxLines = 1
                )
                if (a.oblastSince > 0) {
                    Text(
                        durationText(nowMs - a.oblastSince),
                        color = if (active) Color.White else p.muted, fontSize = t(15)
                    )
                }
            }
        }
    }
}

// ───────────────────────── Нижняя полоса ─────────────────────────

@Composable
private fun BottomStrip(state: UiState) {
    val p = LocalPalette.current
    val w = state.weather.data
    Row(
        Modifier
            .fillMaxWidth()
            .height(d(78))
            .clip(RoundedCornerShape(d(18)))
            .background(p.card)
            .padding(horizontal = d(18)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (w != null) {
            Text(w.icon, fontSize = t(34), color = p.on)
            Spacer(Modifier.width(d(8)))
            Text(sign(w.temp) + "°", color = p.text, fontSize = t(42), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(d(12)))
            Column {
                Text(w.description, color = p.text, fontSize = t(16), maxLines = 1)
                Text(
                    "відчув. ${sign(w.feels)}° · ☀ ${w.sunrise}–${w.sunset}",
                    color = p.muted, fontSize = t(13), maxLines = 1
                )
            }
            Spacer(Modifier.width(d(14)))
            Box(Modifier.width(1.dp).height(d(48)).background(p.track))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceAround) {
                w.hours.take(6).forEach { h ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%02d".format(h.hour), color = p.muted, fontSize = t(12))
                        Text(h.icon, fontSize = t(14), color = p.on, lineHeight = t(16))
                        Text(sign(h.temp) + "°", color = p.text, fontSize = t(16), fontWeight = FontWeight.Bold)
                        Text(
                            if (h.precip >= 10) "${h.precip}%" else " ",
                            color = Color(0xFF6CB4FF), fontSize = t(11), lineHeight = t(12)
                        )
                    }
                }
            }
        } else {
            Text(state.weather.error ?: "погода…", color = p.muted, fontSize = t(15), modifier = Modifier.weight(1f))
        }
        Box(Modifier.width(1.dp).height(d(48)).background(p.track))
        Spacer(Modifier.width(d(14)))
        fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v).replace('.', ',')
        val r = state.rates.data
        if (r != null) {
            Text("$ ", color = p.muted, fontSize = t(14))
            Text(f2(r.usd), color = p.text, fontSize = t(18), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(d(10)))
            Text("€ ", color = p.muted, fontSize = t(14))
            Text(f2(r.eur), color = p.text, fontSize = t(18), fontWeight = FontWeight.Bold)
        }
        val b = state.btc.data
        if (b != null) {
            Spacer(Modifier.width(d(12)))
            Text("₿", color = Color(0xFFF7931A), fontSize = t(16), fontWeight = FontWeight.Bold)
            Text(
                "%,d".format(java.util.Locale.US, b.usd.roundToInt()).replace(',', ' '),
                color = p.text, fontSize = t(18), fontWeight = FontWeight.Bold, maxLines = 1
            )
            val up = b.change24h >= 0
            Text(
                (if (up) " ▲" else " ▼") + String.format(java.util.Locale.US, "%.1f", abs(b.change24h)).replace('.', ',') + "%",
                color = if (up) p.good else p.bad, fontSize = t(12), maxLines = 1
            )
        }
    }
}

@Composable
private fun BatteryView(level: Int, charging: Boolean) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (charging) "⚡" else "") + "$level%",
            color = if (level <= 15 && !charging) p.bad else p.muted, fontSize = t(14)
        )
        Spacer(Modifier.width(d(6)))
        val fill = if (level <= 15 && !charging) p.bad else p.muted
        Canvas(Modifier.size(d(24), d(12))) {
            val sw = 1.5.dp.toPx()
            val bodyW = size.width - 3.dp.toPx()
            drawRoundRect(
                color = p.muted, size = Size(bodyW, size.height),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(sw)
            )
            drawRect(
                p.muted,
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

private fun sign(v: Int) = if (v > 0) "+$v" else "$v"

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


/**
 * Обычные стрелочные часы. По краю циферблата — свет на ближайшие 12 часов:
 * жёлтый — свет есть, тёмный — нет. Часовая стрелка «едет» по этой полосе.
 */
@Composable
private fun AnalogClock(info: PowerInfo?, now: ZonedDateTime) {
    val p = LocalPalette.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val numStyle = androidx.compose.ui.text.TextStyle(color = p.text, fontSize = t(22), fontWeight = FontWeight.Bold)
    val all: List<Slot> = (info?.today?.slots ?: List(48) { Slot.UNKNOWN }) +
        (info?.tomorrow?.slots ?: List(48) { Slot.UNKNOWN })
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2
        val cy = size.height / 2
        val R = size.minDimension / 2
        val ringW = R * 0.13f
        val rRing = R - ringW / 2 - 1.dp.toPx()

        fun pt(angDeg: Double, r: Float) = Offset(
            cx + (r * kotlin.math.cos(Math.toRadians(angDeg))).toFloat(),
            cy + (r * kotlin.math.sin(Math.toRadians(angDeg))).toFloat()
        )
        /** угол на 12-часовом циферблате для момента (минуты от начала суток) */
        fun ang(minOfDay: Double) = -90.0 + (minOfDay % 720.0) / 720.0 * 360.0


        // Полоса света: от «сейчас» на 12 часов вперёд
        val nowMin = now.hour * 60 + now.minute + now.second / 60.0
        val endMin = nowMin + 720.0
        val tl = Offset(cx - rRing, cy - rRing)
        val sz = Size(2 * rRing, 2 * rRing)
        drawCircle(p.track, radius = rRing, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(ringW))
        for (i in all.indices) {
            val s0 = i * 30.0
            val s1 = s0 + 30.0
            if (s1 <= nowMin || s0 >= endMin) continue
            val from = maxOf(s0, nowMin)
            val to = minOf(s1, endMin)
            val c = when (all[i]) {
                Slot.ON -> p.on
                Slot.OFF -> p.off
                Slot.MAYBE -> p.maybe
                Slot.UNKNOWN -> p.track
            }
            drawArc(
                c, startAngle = ang(from).toFloat(), sweepAngle = ((to - from) / 720.0 * 360.0).toFloat() + 0.3f,
                useCenter = false, topLeft = tl, size = sz,
                style = androidx.compose.ui.graphics.drawscope.Stroke(ringW)
            )
        }
        // Разделители часов на полосе
        for (h in 0 until 12) {
            drawLine(
                p.bg,
                pt(-90.0 + h * 30, rRing - ringW / 2),
                pt(-90.0 + h * 30, rRing + ringW / 2),
                strokeWidth = 2.dp.toPx()
            )
        }
        // Начало и конец 12-часового окна (там, где сейчас часовая стрелка)
        drawLine(
            p.text, pt(ang(nowMin), rRing - ringW / 2 - 2.dp.toPx()), pt(ang(nowMin), rRing + ringW / 2 + 1.dp.toPx()),
            strokeWidth = 3.dp.toPx()
        )

        // Минутные риски
        val rFace = R - ringW - 2.dp.toPx()
        for (m in 0 until 60) {
            val big = m % 5 == 0
            drawLine(
                if (big) p.text else p.muted,
                pt(-90.0 + m * 6, rFace - R * (if (big) 0.075f else 0.035f)),
                pt(-90.0 + m * 6, rFace - R * 0.012f),
                strokeWidth = R * (if (big) 0.018f else 0.008f),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
        // Цифры 1–12
        val rNum = rFace - R * 0.17f
        for (h in 1..12) {
            val layout = measurer.measure(h.toString(), numStyle)
            val c = pt(-90.0 + h * 30, rNum)
            drawText(layout, topLeft = Offset(c.x - layout.size.width / 2, c.y - layout.size.height / 2))
        }

        // Стрелки
        val hourAng = ang(nowMin)
        val minAng = -90.0 + (now.minute + now.second / 60.0) * 6
        val secAng = -90.0 + now.second * 6.0
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(p.text, pt(hourAng + 180, rFace * 0.08f), pt(hourAng, rFace * 0.52f), strokeWidth = R * 0.05f, cap = cap)
        drawLine(p.text, pt(minAng + 180, rFace * 0.1f), pt(minAng, rFace * 0.82f), strokeWidth = R * 0.03f, cap = cap)
        drawLine(p.bad, pt(secAng + 180, rFace * 0.15f), pt(secAng, rFace * 0.88f), strokeWidth = R * 0.012f, cap = cap)
        drawCircle(p.text, radius = R * 0.045f, center = Offset(cx, cy))
        drawCircle(p.bad, radius = R * 0.02f, center = Offset(cx, cy))
    }
}

