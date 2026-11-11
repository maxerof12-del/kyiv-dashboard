package ua.dash.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

/** Состояние получаса */
enum class Slot { ON, OFF, MAYBE, UNKNOWN }

data class DaySchedule(val date: LocalDate, val slots: List<Slot>) // 48 получасов

data class PowerInfo(
    val group: String,
    val groupName: String,
    val today: DaySchedule?,
    val tomorrow: DaySchedule?,
    val updated: String,
    /** Текущее аварийное/плановое отключение по адресу, если ДТЭК его показывает */
    val notice: String?,
    val fetchedAt: Long,
)

class DtekException(msg: String) : Exception(msg)

object DtekRepo {
    val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")
    private const val PAGE = "https://www.dtek-kem.com.ua/ua/shutdowns"

    /** Скрипт выполняется внутри страницы ДТЭК: ждёт DisconSchedule, при необходимости ищет дом. */
    private fun script(street: String?): String {
        val s = if (street == null) "null" else JSONObject.quote(street)
        return """
(function(){
  if (window.__dashStarted) return; window.__dashStarted = true;
  var street = $s; var tries = 0;
  function done(o){ try { DashBridge.onResult(JSON.stringify(o)); } catch(e) { DashBridge.onResult('{"error":"stringify"}'); } }
  function tick(){
    tries++;
    var D = null; try { D = DisconSchedule; } catch(e) {}
    if (!D || !D.fact || !D.fact.data || !window.jQuery) {
      if (tries < 90) { setTimeout(tick, 500); } else { done({error: 'Сторінка ДТЕК не віддала графік'}); }
      return;
    }
    var res = { fact: D.fact, names: (D.preset && D.preset.sch_names) || {} };
    try { res.streets = D.streets || []; } catch(e) {}
    if (!street) { done(res); return; }
    var data = [{name:'street', value: street}, {name:'house_num', value:''}, {name:'updateFact', value: D.fact.update || ''}];
    jQuery.post(D.ajax.url, {method: 'getHomeNum', data: data}, function(a){
      res.houses = (a && a.data) ? a.data : {};
      done(res);
    }, 'JSON').fail(function(x){ res.houseError = 'HTTP ' + x.status; done(res); });
  }
  tick();
})();
""".trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun loadPage(context: Context, street: String?): JSONObject {
        val main = Handler(Looper.getMainLooper())
        return withTimeout(75_000) {
            suspendCancellableCoroutine { cont ->
                var webView: WebView? = null
                var finished = false
                fun finish(result: JSONObject) {
                    main.post {
                        if (finished) return@post
                        finished = true
                        webView?.apply {
                            stopLoading()
                            removeJavascriptInterface("DashBridge")
                            destroy()
                        }
                        webView = null
                        if (cont.isActive) cont.resume(result)
                    }
                }
                main.post {
                    val wv = WebView(context.applicationContext)
                    webView = wv
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.blockNetworkImage = true
                    wv.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onResult(json: String) {
                            val obj = try { JSONObject(json) } catch (e: Exception) {
                                JSONObject().put("error", "Некоректна відповідь ДТЕК")
                            }
                            finish(obj)
                        }
                    }, "DashBridge")
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(script(street), null)
                        }
                    }
                    wv.loadUrl(PAGE)
                }
                cont.invokeOnCancellation {
                    main.post {
                        finished = true
                        webView?.destroy()
                        webView = null
                    }
                }
            }
        }
    }

    private fun norm(s: String): String = s.trim().uppercase()
        .replace('A', 'А').replace('B', 'В').replace('E', 'Е').replace('K', 'К')
        .replace('M', 'М').replace('H', 'Н').replace('O', 'О').replace('P', 'Р')
        .replace('C', 'С').replace('T', 'Т').replace('X', 'Х').replace('I', 'І')
        .replace(" ", "").replace("-", "")

    suspend fun fetch(context: Context, prefs: Prefs): PowerInfo {
        val address = prefs.street.trim() + "|" + prefs.house.trim()
        val needGroup = prefs.dtekGroup.isBlank() || prefs.dtekGroupForAddress != address
        val j = loadPage(context, if (needGroup) prefs.street.trim() else null)
        if (j.has("error")) throw DtekException(j.getString("error"))

        j.optJSONArray("streets")?.let { if (it.length() > 0) prefs.streetsCache = it.toString() }

        var notice: String? = null
        if (needGroup) {
            if (j.has("houseError")) throw DtekException("Пошук адреси: ${j.getString("houseError")}")
            val houses = j.optJSONObject("houses") ?: JSONObject()
            val want = norm(prefs.house)
            var entry: JSONObject? = null
            val keys = houses.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (norm(k) == want) { entry = houses.optJSONObject(k); break }
            }
            if (entry == null) {
                throw DtekException(
                    if (houses.length() == 0) "Вулицю «${prefs.street}» не знайдено"
                    else "Будинок «${prefs.house}» не знайдено на вулиці"
                )
            }
            val reasons = entry.optJSONArray("sub_type_reason") ?: JSONArray()
            val group = if (reasons.length() > 0) reasons.getString(0) else ""
            if (group.isBlank()) throw DtekException("ДТЕК не повернув групу для адреси")
            prefs.dtekGroup = group
            prefs.dtekGroupForAddress = address
            notice = noticeOf(entry)
        }
        prefs.lastDtekJson = j.optJSONObject("fact")?.toString() ?: ""
        return parse(j.getJSONObject("fact"), j.optJSONObject("names"), prefs.dtekGroup, notice)
    }

    private fun noticeOf(e: JSONObject): String? {
        val type = e.optString("sub_type").ifBlank { e.optString("type") }
        if (type.isBlank()) return null
        val start = e.optString("start_date")
        val end = e.optString("end_date")
        return buildString {
            append(type)
            if (start.isNotBlank()) append(" з $start")
            if (end.isNotBlank()) append(" до $end")
        }
    }

    /** Разбор кеша (для мгновенного показа после перезапуска) */
    fun fromCache(prefs: Prefs): PowerInfo? {
        if (prefs.lastDtekJson.isBlank() || prefs.dtekGroup.isBlank()) return null
        return try {
            parse(JSONObject(prefs.lastDtekJson), null, prefs.dtekGroup, null).copy(fetchedAt = 0)
        } catch (e: Exception) { null }
    }

    fun parse(fact: JSONObject, names: JSONObject?, group: String, notice: String?): PowerInfo {
        val data = fact.getJSONObject("data")
        val days = mutableListOf<DaySchedule>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val ts = k.toLongOrNull() ?: continue
            val g = data.getJSONObject(k).optJSONObject(group) ?: continue
            val date = Instant.ofEpochSecond(ts).atZone(KYIV).toLocalDate()
            val slots = ArrayList<Slot>(48)
            for (h in 1..24) {
                val (a, b) = when (g.optString(h.toString())) {
                    "yes" -> Slot.ON to Slot.ON
                    "no" -> Slot.OFF to Slot.OFF
                    "maybe" -> Slot.MAYBE to Slot.MAYBE
                    "first" -> Slot.OFF to Slot.ON
                    "second" -> Slot.ON to Slot.OFF
                    "mfirst" -> Slot.MAYBE to Slot.ON
                    "msecond" -> Slot.ON to Slot.MAYBE
                    else -> Slot.UNKNOWN to Slot.UNKNOWN
                }
                slots.add(a); slots.add(b)
            }
            days.add(DaySchedule(date, slots))
        }
        val today = LocalDate.now(KYIV)
        val name = names?.optString(group)?.takeIf { it.isNotBlank() }
            ?: ("Черга " + group.removePrefix("GPV"))
        return PowerInfo(
            group = group,
            groupName = name,
            today = days.firstOrNull { it.date == today },
            tomorrow = days.firstOrNull { it.date == today.plusDays(1) },
            updated = fact.optString("update"),
            notice = notice,
            fetchedAt = System.currentTimeMillis(),
        )
    }
}
