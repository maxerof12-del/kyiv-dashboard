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
            Column(
                Modifier
                    .fillMaxSize()
                    .offset(x = shift.dp, y = (-shift).dp)
                    .padding(d(14)),
                verticalArrangement = Arrangement.spacedBy(d(10))
            ) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(d(12))
            ) {
                Column(
                    Modifier
                        .weight(1.55f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(d(12))
                ) {
                    PowerCard(state.power, now, Modifier.weight(1f).clickable { onRefresh() })
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(d(10))
                ) {
                    WeatherCard(state, battery)
                    AlertCard(state, now)
                    MapCard(state.mapUrl, state.homeLat, state.homeLon, Modifier.weight(1f).fillMaxWidth())
                    RatesCard(state, battery, onSettings)
                }
            }
            }
        }
    }
}

@Composable
private fun FeedCard(state: UiState, now: ZonedDateTime) {
    val p = LocalPalette.current
    val f = state.feed
    val last = f?.last
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🍼", fontSize = t(30))
            Spacer(Modifier.width(d(8)))
            if (last == null) {
                Text(
                    state.tgError ?: "Годування: чекаю перший запис у групі…",
                    color = if (state.tgError != null) p.warn else p.muted, fontSize = t(15),
                    modifier = Modifier.weight(1f)
                )
                return@Row
            }
            Text(hm(zdt(last.at)), color = p.text, fontSize = t(36), fontWeight = FontWeight.Bold, lineHeight = t(38))
            Spacer(Modifier.width(d(6)))
            Text("${last.ml} мл", color = p.text, fontSize = t(22))
            Spacer(Modifier.weight(1f))
            val ago = now.toInstant().toEpochMilli() - last.at
            val c = when {
                ago < 150 * 60_000 -> p.good
                ago < 210 * 60_000 -> p.warn
                else -> p.bad
            }
            Text("${durationText(ago)} тому", color = c, fontSize = t(28), fontWeight = FontWeight.Bold)
        }
        if (last != null) {
            Text(
                "останнє годування · сьогодні ${f.todayMl} мл, ${f.todayCount} р." +
                    (f.lastPoop?.let { " · 💩 " + hm(zdt(it)) } ?: ""),
                color = p.muted, fontSize = t(15), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NewsBar(state: UiState, now: ZonedDateTime) {
    val p = LocalPalette.current
    val n = state.news
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📰", fontSize = t(20))
            Spacer(Modifier.width(d(10)))
            if (n == null) {
                Text("Новини: чекаю наступне повідомлення в групі…", color = p.muted, fontSize = t(15))
                return@Row
            }
            Text(
                n.text.replace('\n', ' '),
                color = p.text, fontSize = t(17), maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = t(21), modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(d(12)))
            Column(horizontalAlignment = Alignment.End) {
                Text(n.chatTitle, color = p.muted, fontSize = t(12), maxLines = 1)
                Text(hm(zdt(n.at)), color = p.muted, fontSize = t(13), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WeatherCard(state: UiState, battery: Pair<Int, Boolean>) {
    val p = LocalPalette.current
    Card(Modifier.fillMaxWidth(), color = p.headerBg) {
        val w = state.weather.data
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (w != null) {
                Text(w.icon, fontSize = t(40), color = p.on)
                Spacer(Modifier.width(d(8)))
                Text(
                    sign(w.temp) + "°",
                    color = p.headerText, fontSize = t(52), fontWeight = FontWeight.Bold, lineHeight = t(54)
                )
                Spacer(Modifier.width(d(12)))
                Column(Modifier.weight(1f)) {
                    Text(w.description, color = p.headerText, fontSize = t(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("відчув. ${sign(w.feels)}° · ${w.wind} м/с", color = p.headerMuted, fontSize = t(13), maxLines = 1)
                    Text("${sign(w.min)}° … ${sign(w.max)}° · ☀ ${w.sunrise}–${w.sunset}", color = p.headerMuted, fontSize = t(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(state.weather.error ?: "погода…", color = p.headerMuted, fontSize = t(16), modifier = Modifier.weight(1f))
            }
        }
        if (w != null && w.hours.isNotEmpty()) {
            Spacer(Modifier.height(d(4)))
            Row(Modifier.fillMaxWidth()) {
                w.hours.take(6).forEach { h ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%02d".format(h.hour), color = p.headerMuted, fontSize = t(12))
                        Text(h.icon, fontSize = t(15), color = p.on)
                        Text(sign(h.temp) + "°", color = p.headerText, fontSize = t(14), fontWeight = FontWeight.Medium)
                        Text(
                            if (h.precip >= 10) "${h.precip}%" else " ",
                            color = Color(0xFF6CB4FF), fontSize = t(11)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatesCard(state: UiState, battery: Pair<Int, Boolean>, onSettings: () -> Unit) {
    val p = LocalPalette.current
    fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v).replace('.', ',')
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            val r = state.rates.data
            if (r != null) {
                Text("$", color = p.muted, fontSize = t(14))
                Text(f2(r.usd), color = p.text, fontSize = t(17), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(d(10)))
                Text("€", color = p.muted, fontSize = t(14))
                Text(f2(r.eur), color = p.text, fontSize = t(17), fontWeight = FontWeight.Bold)
            } else {
                Text(state.rates.error ?: "курс…", color = p.muted, fontSize = t(13))
            }
            Spacer(Modifier.weight(1f))
            val b = state.btc.data
            if (b != null) {
                Text("₿", color = Color(0xFFF7931A), fontSize = t(15), fontWeight = FontWeight.Bold)
                Text(
                    "%,d".format(java.util.Locale.US, b.usd.roundToInt()).replace(',', ' '),
                    color = p.text, fontSize = t(17), fontWeight = FontWeight.Bold, maxLines = 1
                )
                Spacer(Modifier.width(d(4)))
                val up = b.change24h >= 0
                Text(
                    (if (up) "▲" else "▼") + String.format(java.util.Locale.US, "%.1f", abs(b.change24h)).replace('.', ',') + "%",
                    color = if (up) p.good else p.bad, fontSize = t(12), maxLines = 1,
                    modifier = Modifier.padding(bottom = d(2))
                )
            } else {
                Text(state.btc.error?.let { "₿ $it" } ?: "₿ …", color = p.muted, fontSize = t(13))
            }
            Spacer(Modifier.width(d(10)))
            Box(Modifier.padding(bottom = d(3))) { BatteryView(battery.first, battery.second) }
            Text(
                "⚙",
                color = p.muted, fontSize = t(20),
                modifier = Modifier
                    .clip(RoundedCornerShape(d(8)))
                    .clickable { onSettings() }
                    .padding(start = d(8))
            )
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

@Composable
private fun AlertCard(state: UiState, now: ZonedDateTime) {
    val p = LocalPalette.current
    val a = state.alert.data
    val active = a?.active == true
    Card(Modifier.fillMaxWidth(), color = if (active) p.alertBg else p.card) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(d(16))
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
                    active -> "ТРИВОГА"
                    else -> "Тривоги немає"
                },
                color = if (active) Color.White else p.text,
                fontSize = t(26), fontWeight = FontWeight.Bold, lineHeight = t(28),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (a != null) {
                val since = now.toInstant().toEpochMilli() - a.since
                Text(
                    (if (active) "триває " else "") + durationText(since),
                    color = if (active) Color.White else p.muted,
                    fontSize = t(17), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
        if (a != null) {
            Spacer(Modifier.height(d(4)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.region, color = if (active) Color.White.copy(alpha = 0.85f) else p.muted, fontSize = t(14))
                if (a.oblastActive != null) {
                    Spacer(Modifier.width(d(14)))
                    Box(
                        Modifier
                            .size(d(10))
                            .clip(RoundedCornerShape(50))
                            .background(if (a.oblastActive) (if (active) Color.White else p.bad) else p.good)
                    )
                    Spacer(Modifier.width(d(6)))
                    Text(
                        "Київська обл.: " + if (a.oblastActive) "тривога" else "немає",
                        color = if (active) Color.White.copy(alpha = 0.85f) else (if (a.oblastActive) p.bad else p.muted),
                        fontSize = t(14)
                    )
                }
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
        val a = info?.let { analyze(it, now) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val digitalH = d(96)
        val dialSize = minOf(maxHeight - digitalH, maxWidth * 0.55f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Стрелочные часы, по краю — свет на 12 часов; под ними — то же время цифрами
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(dialSize), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "${DAYS_SHORT[now.dayOfWeek.value - 1]}, ${now.dayOfMonth} ${MONTHS[now.monthValue - 1]}",
                            color = p.muted, fontSize = t(14)
                        )
                        Spacer(Modifier.height(dialSize * 0.4f))
                    }
                    AnalogClock(info, now)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        hm(now), color = p.text, fontSize = t(60), fontWeight = FontWeight.Bold,
                        lineHeight = t(62)
                    )
                    Text(
                        ":%02d".format(now.second), color = p.muted, fontSize = t(26),
                        modifier = Modifier.padding(start = d(2), bottom = d(8))
                    )
                }
            }
            if (info == null) {
                Spacer(Modifier.width(d(16)))
                Column(Modifier.weight(1f)) {
                    Text("Графік світла", color = p.text, fontSize = t(22), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(d(8)))
                    Text(
                        res.error ?: "Завантажую графік ДТЕК…",
                        color = if (res.error != null) p.bad else p.muted, fontSize = t(16)
                    )
                }
                return@Row
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
                        label, color = fg, fontSize = t(18), fontWeight = FontWeight.Bold,
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
                    Text(verb, color = p.muted, fontSize = t(20))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            dayPrefix + hm(at),
                            color = if (a.current == Slot.ON) p.text else p.on,
                            fontSize = t(52), fontWeight = FontWeight.Bold, lineHeight = t(54)
                        )
                        Spacer(Modifier.width(d(10)))
                        val left = a.nextChangeMs - now.toInstant().toEpochMilli()
                        Text(
                            "через ${durationText(left)}", color = p.text, fontSize = t(20),
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
                    (Holidays.forDate(now.toLocalDate()).firstOrNull()?.let { "🎉 $it · " } ?: "") + "край годинника — світло на 12 год · " + (notice?.let { "$it · " } ?: "") + "ДТЕК ${info.updated}$stale",
                    color = if (res.error != null) p.warn else p.muted, fontSize = t(11), maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
            title, color = p.text, fontSize = t(15), fontWeight = FontWeight.Bold,
            modifier = Modifier.width(d(84))
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
            color = p.muted, fontSize = t(14), modifier = Modifier.padding(end = d(8))
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
                text, fontSize = t(16), fontWeight = FontWeight.Medium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = d(84))
            )
        }
    }
}

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
