package ua.dash.data

import org.json.JSONObject

data class AlertState(
    val active: Boolean,
    /** Момент, когда состояние сменилось (по наблюдению приложения), мс */
    val since: Long,
    val region: String,
    /** Тревога в области (для справки, если смотрим город) */
    val oblastActive: Boolean?,
    val oblastSince: Long = 0,
    /** Уровень тревоги: "red" (ракетна / масована) или "yellow" (дронова), null — неизвестно */
    val level: String? = null,
    val oblastLevel: String? = null,
)

object AlertsRepo {
    private const val URL = "https://ubilling.net.ua/aerialalerts/"

    fun fetch(prefs: Prefs): AlertState {
        val j = JSONObject(Http.get(URL))
        val states = j.getJSONObject("states")
        val region = prefs.alertRegion
        val st = states.optJSONObject(region)
            ?: throw IllegalStateException("Регіон «$region» не знайдено")
        val active = st.optBoolean("alertnow", false)
        val oblast = if (region == "м. Київ")
            states.optJSONObject("Київська область")?.optBoolean("alertnow", false)
        else null

        val now = System.currentTimeMillis()
        if (prefs.alertSince == 0L || prefs.alertLastState != active) {
            prefs.alertLastState = active
            prefs.alertSince = now
        }
        if (oblast != null && (prefs.oblastSince == 0L || prefs.oblastLastState != oblast)) {
            prefs.oblastLastState = oblast
            prefs.oblastSince = now
        }
        var lvl: String? = null
        var oLvl: String? = null
        if (active || oblast == true) {
            val md = levelsReport(now)
            if (md != null) {
                if (active) lvl = levelFor(md, region)
                if (oblast == true) oLvl = levelFor(md, "Київська область")
            }
        }
        return AlertState(active, prefs.alertSince, region, oblast, prefs.oblastSince, lvl, oLvl)
    }

    // ───── Уровни (жовтий / червоний) из публичного отчёта alerts.in.ua ─────

    private const val LEVELS_URL = "https://api.alerts.in.ua/v3/alerts/active.md"
    private var cachedMd: String? = null
    private var cachedAt = 0L

    private fun levelsReport(now: Long): String? {
        if (cachedMd != null && now - cachedAt < 25_000) return cachedMd
        return try {
            Http.get(LEVELS_URL, mapOf("Accept" to "text/markdown, text/plain, */*")).also {
                cachedMd = it
                cachedAt = now
            }
        } catch (e: Exception) {
            cachedMd
        }
    }

    private val reTag = Regex("\\[(red|yellow)\\b|level:\\s*\\**(red|yellow)", RegexOption.IGNORE_CASE)
    private val endedWords = listOf("ended", "terminat", "cleared", "завершен", "відбій")

    /**
     * Ищет в отчёте упоминания «(м. Київ)» / «(Київська область)» и ближайшую метку уровня
     * в том же блоке. Для области берётся самая серьёзная метка среди её районов.
     * null — метку не нашли (тогда карточка просто красная, как раньше).
     */
    fun levelFor(md: String, ukName: String): String? {
        var best: String? = null
        val key = "($ukName)"
        var idx = md.indexOf(key)
        while (idx >= 0) {
            // пропускаем раздел с уже завершёнными тревогами
            val before = md.substring(maxOf(0, idx - 600), idx).lowercase()
            val lastHeader = maxOf(before.lastIndexOf("\n#"), 0)
            val section = before.substring(lastHeader)
            val ended = endedWords.any { section.contains(it) }
            if (!ended) {
                var end = md.length
                for (stop in listOf("\n\n", "\n**", "\n#", "\n- **")) {
                    val k = md.indexOf(stop, idx + 1)
                    if (k in 0 until end) end = k
                }
                val block = md.substring(idx, minOf(end, idx + 1500))
                for (m in reTag.findAll(block)) {
                    val v = m.groupValues[1].ifEmpty { m.groupValues[2] }.lowercase()
                    if (v == "red") best = "red" else if (best == null) best = "yellow"
                }
            }
            idx = md.indexOf(key, idx + 1)
        }
        return best
    }
}
