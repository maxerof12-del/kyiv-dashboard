package ua.dash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class BotFeed(
    val lastAt: Long?,
    val lastMl: Double?,
    val gapMin: Int?,
    val todayMl: Double,
    val todayCount: Int,
    val poopsToday: Int,
    val lastPoop: Long?,
    val goalMl: Int,
    val host: String,
    val fetchedAt: Long,
)

/** Данные бота «Кормление», который работает в Termux на телефоне в том же Wi-Fi. */
object BotFeedRepo {
    const val PORT = 8765

    fun fetch(host: String): BotFeed {
        val h = host.trim().removePrefix("http://").removeSuffix("/")
        val url = if (h.contains(":")) "http://$h/dash.json" else "http://$h:$PORT/dash.json"
        val j = JSONObject(Http.get(url))
        if (j.optString("app") != "feeding_bot") throw IllegalStateException("це не бот годування")
        val lf = j.optJSONObject("last_feed")
        return BotFeed(
            lastAt = lf?.optString("ts")?.let(::parseIso),
            lastMl = lf?.optDouble("ml"),
            gapMin = lf?.let { if (it.isNull("gap_min")) null else it.optInt("gap_min") },
            todayMl = j.optDouble("today_ml", 0.0),
            todayCount = j.optInt("today_count", 0),
            poopsToday = j.optInt("poops_today", 0),
            lastPoop = if (j.isNull("last_poop")) null else j.optString("last_poop").let(::parseIso),
            goalMl = j.optInt("goal_ml", 0),
            host = h,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** IP планшета в Wi-Fi (для подсказки в настройках) */
    fun myIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress!! }
    } catch (e: Exception) { emptyList() }

    private fun localPrefixes(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress!!.substringBeforeLast(".") }
            .distinct()
    } catch (e: Exception) { emptyList() }

    private fun portOpen(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, PORT), 350); true }
    } catch (e: Exception) { false }

    /** Ищет телефон с ботом в локальной сети (x.x.x.1–254). Возвращает адрес или null. */
    suspend fun discover(): String? = withContext(Dispatchers.IO) {
        for (prefix in localPrefixes()) {
            val found = coroutineScope {
                (1..254).map { i ->
                    async { val ip = "$prefix.$i"; if (portOpen(ip)) ip else null }
                }.awaitAll().filterNotNull()
            }
            for (ip in found) {
                try { fetch(ip); return@withContext ip } catch (_: Exception) {}
            }
        }
        null
    }
}
