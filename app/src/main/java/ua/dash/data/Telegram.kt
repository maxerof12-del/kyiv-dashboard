package ua.dash.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Feed(val msgId: Long, val at: Long, val ml: Int)

data class FeedInfo(
    val last: Feed?,
    val todayMl: Int,
    val todayCount: Int,
    val lastPoop: Long?,
)

data class NewsInfo(val chatTitle: String, val text: String, val at: Long)

data class TgChat(val id: Long, val title: String, val type: String)

/**
 * Свой бот читает группы через Bot API (getUpdates).
 * Сообщения других ботов Telegram ботам не показывает — поэтому читаем записи людей
 * («14:40-70», «25») и сами считаем время, объём и сумму за день.
 */
object TelegramRepo {
    private val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

    private fun api(token: String, method: String, params: String = ""): JSONObject {
        val j = JSONObject(Http.get("https://api.telegram.org/bot$token/$method$params"))
        if (!j.optBoolean("ok")) throw IllegalStateException(j.optString("description", "Telegram error"))
        return j
    }

    fun botName(token: String): String =
        api(token, "getMe").getJSONObject("result").let { "@" + it.optString("username") }

    /** Забирает новые сообщения, обновляет сохранённое состояние. */
    fun poll(prefs: Prefs) {
        val token = prefs.tgToken.trim()
        if (token.isBlank()) return
        val allowed = URLEncoder.encode("[\"message\",\"edited_message\",\"channel_post\",\"edited_channel_post\"]", "UTF-8")
        var offset = prefs.tgOffset
        repeat(5) { // до 500 обновлений за раз
            val res = api(token, "getUpdates", "?timeout=0&limit=100&allowed_updates=$allowed" +
                if (offset > 0) "&offset=$offset" else "").getJSONArray("result")
            if (res.length() == 0) return
            val chats = loadChats(prefs).associateBy { it.id }.toMutableMap()
            for (i in 0 until res.length()) {
                val u = res.getJSONObject(i)
                offset = u.getLong("update_id") + 1
                val m = u.optJSONObject("message") ?: u.optJSONObject("edited_message")
                    ?: u.optJSONObject("channel_post") ?: u.optJSONObject("edited_channel_post") ?: continue
                val chat = m.getJSONObject("chat")
                val chatId = chat.getLong("id")
                val title = chat.optString("title").ifBlank {
                    listOf(chat.optString("first_name"), chat.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")
                }
                chats[chatId] = TgChat(chatId, title, chat.optString("type"))
                if (m.optJSONObject("from")?.optBoolean("is_bot") == true) continue
                val text = m.optString("text").ifBlank { m.optString("caption") }
                val at = m.getLong("date") * 1000
                if (chatId == prefs.tgFeedChat) handleFeed(prefs, m.getLong("message_id"), text, at)
                if (chatId == prefs.tgNewsChat) {
                    val shown = text.ifBlank {
                        when {
                            m.has("photo") -> "📷 фото"
                            m.has("video") -> "🎬 відео"
                            m.has("voice") -> "🎤 голосове"
                            m.has("sticker") -> m.getJSONObject("sticker").optString("emoji", "стікер")
                            else -> ""
                        }
                    }
                    if (shown.isNotBlank()) {
                        prefs.tgNews = JSONObject().put("t", title).put("x", shown).put("at", at).toString()
                    }
                }
            }
            saveChats(prefs, chats.values.toList())
            prefs.tgOffset = offset
            if (res.length() < 100) return
        }
    }

    // ───── Кормления ─────

    private val reTimeMl = Regex("""^\s*(\d{1,2})[:.,](\d{2})\s*[-–—=/ ]\s*(\d{1,4})\s*(мл|ml)?\s*$""", RegexOption.IGNORE_CASE)
    private val reMlTime = Regex("""^\s*(\d{1,4})\s*(мл|ml)?\s*[-–—/ ]\s*(?:в\s*)?(\d{1,2})[:.](\d{2})\s*$""", RegexOption.IGNORE_CASE)
    private val reMl = Regex("""^\s*(\d{1,4})\s*(мл|ml)?\s*$""", RegexOption.IGNORE_CASE)
    private val reAnyTime = Regex("""(\d{1,2})[:.](\d{2})""")
    private val reAnyMl = Regex("""(\d{2,4})\s*(мл|ml)""", RegexOption.IGNORE_CASE)

    /** Возвращает (время мс, мл) или null */
    fun parseFeed(text: String, sentAt: Long): Pair<Long, Int>? {
        var h: Int? = null
        var mi: Int? = null
        var ml: Int? = null
        reTimeMl.find(text)?.let { h = it.groupValues[1].toInt(); mi = it.groupValues[2].toInt(); ml = it.groupValues[3].toInt() }
        if (ml == null) reMlTime.find(text)?.let { ml = it.groupValues[1].toInt(); h = it.groupValues[3].toInt(); mi = it.groupValues[4].toInt() }
        if (ml == null) reMl.find(text)?.let { ml = it.groupValues[1].toInt() }
        if (ml == null) {
            val t = reAnyTime.find(text)
            val m = reAnyMl.find(text)
            if (m != null) {
                ml = m.groupValues[1].toInt()
                if (t != null) { h = t.groupValues[1].toInt(); mi = t.groupValues[2].toInt() }
            }
        }
        val amount = ml ?: return null
        if (amount <= 0 || amount > 1000) return null
        if (h == null) return sentAt to amount
        if (h!! > 23 || mi!! > 59) return sentAt to amount
        val sent = Instant.ofEpochMilli(sentAt).atZone(KYIV)
        var at = sent.toLocalDate().atTime(h!!, mi!!).atZone(KYIV)
        // «23:50-60», отправленное после полуночи, — это вчера
        if (at.toInstant().toEpochMilli() > sentAt + 30 * 60_000) at = at.minusDays(1)
        return at.toInstant().toEpochMilli() to amount
    }

    private fun handleFeed(prefs: Prefs, msgId: Long, text: String, at: Long) {
        if (text.contains("💩")) {
            prefs.tgLastPoop = maxOf(prefs.tgLastPoop, at)
            return
        }
        val parsed = parseFeed(text, at) ?: return
        val list = loadFeeds(prefs).filter { it.msgId != msgId }.toMutableList()
        list.add(Feed(msgId, parsed.first, parsed.second))
        list.sortBy { it.at }
        saveFeeds(prefs, list.takeLast(80))
    }

    fun feedInfo(prefs: Prefs): FeedInfo {
        val list = loadFeeds(prefs)
        val today = LocalDate.now(KYIV)
        val todays = list.filter { Instant.ofEpochMilli(it.at).atZone(KYIV).toLocalDate() == today }
        return FeedInfo(
            last = list.maxByOrNull { it.at },
            todayMl = todays.sumOf { it.ml },
            todayCount = todays.size,
            lastPoop = prefs.tgLastPoop.takeIf { it > 0 },
        )
    }

    fun newsInfo(prefs: Prefs): NewsInfo? = try {
        val j = JSONObject(prefs.tgNews)
        NewsInfo(j.optString("t"), j.optString("x"), j.optLong("at"))
    } catch (e: Exception) { null }

    private fun loadFeeds(prefs: Prefs): List<Feed> = try {
        val a = JSONArray(prefs.tgFeeds)
        List(a.length()) { a.getJSONObject(it).let { o -> Feed(o.getLong("id"), o.getLong("at"), o.getInt("ml")) } }
    } catch (e: Exception) { emptyList() }

    private fun saveFeeds(prefs: Prefs, list: List<Feed>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.msgId).put("at", it.at).put("ml", it.ml)) }
        prefs.tgFeeds = a.toString()
    }

    fun loadChats(prefs: Prefs): List<TgChat> = try {
        val a = JSONArray(prefs.tgChats)
        List(a.length()) { a.getJSONObject(it).let { o -> TgChat(o.getLong("id"), o.getString("t"), o.optString("ty")) } }
    } catch (e: Exception) { emptyList() }

    private fun saveChats(prefs: Prefs, list: List<TgChat>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("ty", it.type)) }
        prefs.tgChats = a.toString()
    }

    /** Ручная запись кормления (если бот пропустил) */
    fun clearFeeds(prefs: Prefs) { prefs.tgFeeds = ""; prefs.tgLastPoop = 0 }
}
