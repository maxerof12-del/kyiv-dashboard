package ua.dash.data

import org.json.JSONObject
import kotlin.math.roundToInt

data class Weather(
    val temp: Int,
    val feels: Int,
    val code: Int,
    val isDay: Boolean,
    val wind: Int,
    val min: Int,
    val max: Int,
    val description: String,
    val icon: String,
)

object WeatherRepo {
    fun fetch(lat: Double, lon: Double): Weather {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min&timezone=Europe%2FKyiv&forecast_days=1&wind_speed_unit=ms"
        val j = JSONObject(Http.get(url))
        val c = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val code = c.getInt("weather_code")
        val isDay = c.optInt("is_day", 1) == 1
        return Weather(
            temp = c.getDouble("temperature_2m").roundToInt(),
            feels = c.getDouble("apparent_temperature").roundToInt(),
            code = code,
            isDay = isDay,
            wind = c.getDouble("wind_speed_10m").roundToInt(),
            min = d.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            max = d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            description = describe(code),
            icon = icon(code, isDay),
        )
    }

    fun describe(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "переважно ясно"
        2 -> "мінлива хмарність"
        3 -> "хмарно"
        45, 48 -> "туман"
        51, 53, 55 -> "мряка"
        56, 57 -> "крижана мряка"
        61 -> "невеликий дощ"
        63 -> "дощ"
        65 -> "сильний дощ"
        66, 67 -> "крижаний дощ"
        71 -> "невеликий сніг"
        73 -> "сніг"
        75 -> "сильний сніг"
        77 -> "снігова крупа"
        80, 81 -> "злива"
        82 -> "сильна злива"
        85, 86 -> "снігопад"
        95 -> "гроза"
        96, 99 -> "гроза з градом"
        else -> ""
    }

    fun icon(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀" else "☾"
        1, 2 -> if (isDay) "⛅" else "☁"
        3 -> "☁"
        45, 48 -> "🌫"
        in 51..67, in 80..82 -> "🌧"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "⛈"
        else -> "☁"
    }
}
