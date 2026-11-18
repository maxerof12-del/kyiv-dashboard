package ua.dash.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class Rates(val usd: Double, val eur: Double, val date: String)

data class Btc(val usd: Double, val change24h: Double)

data class Air(val aqi: Int, val pm25: Int, val label: String)

object ExtrasRepo {
    /** Официальный курс НБУ */
    fun rates(): Rates {
        val a = JSONArray(Http.get("https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?json"))
        var usd = 0.0
        var eur = 0.0
        var date = ""
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            when (o.optString("cc")) {
                "USD" -> { usd = o.getDouble("rate"); date = o.optString("exchangedate") }
                "EUR" -> eur = o.getDouble("rate")
            }
        }
        if (usd == 0.0) throw IllegalStateException("НБУ: немає курсу")
        return Rates(usd, eur, date)
    }

    /** Биткоин: CoinGecko, запасной — Binance */
    fun btc(): Btc {
        return try {
            val j = JSONObject(
                Http.get("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd&include_24hr_change=true")
            ).getJSONObject("bitcoin")
            Btc(j.getDouble("usd"), j.optDouble("usd_24h_change", 0.0))
        } catch (e: Exception) {
            val j = JSONObject(Http.get("https://api.binance.com/api/v3/ticker/24hr?symbol=BTCUSDT"))
            Btc(j.getString("lastPrice").toDouble(), j.getString("priceChangePercent").toDouble())
        }
    }

    /** Качество воздуха (европейский индекс AQI) */
    fun air(lat: Double, lon: Double): Air {
        val j = JSONObject(
            Http.get(
                "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon" +
                    "&current=european_aqi,pm2_5&timezone=Europe%2FKyiv"
            )
        ).getJSONObject("current")
        val aqi = j.optDouble("european_aqi", 0.0).roundToInt()
        val label = when {
            aqi <= 20 -> "добре"
            aqi <= 40 -> "нормально"
            aqi <= 60 -> "помірно"
            aqi <= 80 -> "погано"
            aqi <= 100 -> "дуже погано"
            else -> "небезпечно"
        }
        return Air(aqi, j.optDouble("pm2_5", 0.0).roundToInt(), label)
    }
}
