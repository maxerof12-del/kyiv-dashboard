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
        return AlertState(active, prefs.alertSince, region, oblast, prefs.oblastSince)
    }
}
