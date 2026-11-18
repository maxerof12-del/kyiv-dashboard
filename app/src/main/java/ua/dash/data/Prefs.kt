package ua.dash.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    companion object {
        const val DEFAULT_MAP = "https://map.ukrainealarm.com/"
    }

    private val sp: SharedPreferences =
        context.getSharedPreferences("dash", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = sp.getString(key, def) ?: def
    private fun put(key: String, value: String?) = sp.edit().putString(key, value).apply()

    // Адрес / ДТЭК
    var street: String
        get() = str("street", "просп. Берестейський")
        set(v) = put("street", v)
    var house: String
        get() = str("house", "67А")
        set(v) = put("house", v)
    /** Группа ДТЭК (GPV22.1). Пусто = определить по адресу. */
    var dtekGroup: String
        get() = str("dtekGroup")
        set(v) = put("dtekGroup", v)
    var dtekGroupForAddress: String
        get() = str("dtekGroupForAddress")
        set(v) = put("dtekGroupForAddress", v)
    var streetsCache: String
        get() = str("streetsCache")
        set(v) = put("streetsCache", v)
    var lastDtekJson: String
        get() = str("lastDtekJson")
        set(v) = put("lastDtekJson", v)

    // Погода
    var lat: Double
        get() = str("lat2", "50.4545").toDoubleOrNull() ?: 50.4545
        set(v) = put("lat2", v.toString())
    var lon: Double
        get() = str("lon2", "30.3985").toDoubleOrNull() ?: 30.3985
        set(v) = put("lon2", v.toString())

    // Тревоги
    var alertRegion: String
        get() = str("alertRegion", "м. Київ")
        set(v) = put("alertRegion", v)
    var alertSince: Long
        get() = sp.getLong("alertSince", 0L)
        set(v) = sp.edit().putLong("alertSince", v).apply()
    var alertLastState: Boolean
        get() = sp.getBoolean("alertLastState", false)
        set(v) = sp.edit().putBoolean("alertLastState", v).apply()

    var oblastSince: Long
        get() = sp.getLong("oblastSince", 0L)
        set(v) = sp.edit().putLong("oblastSince", v).apply()
    var oblastLastState: Boolean
        get() = sp.getBoolean("oblastLastState", false)
        set(v) = sp.edit().putBoolean("oblastLastState", v).apply()

    // Claude
    var claudeAccess: String
        get() = str("claudeAccess")
        set(v) = put("claudeAccess", v)
    var claudeRefresh: String
        get() = str("claudeRefresh")
        set(v) = put("claudeRefresh", v)
    var claudeExpires: Long
        get() = sp.getLong("claudeExpires", 0L)
        set(v) = sp.edit().putLong("claudeExpires", v).apply()

    // Codex (ChatGPT)
    var codexAccess: String
        get() = str("codexAccess")
        set(v) = put("codexAccess", v)
    var codexRefresh: String
        get() = str("codexRefresh")
        set(v) = put("codexRefresh", v)
    var codexAccount: String
        get() = str("codexAccount")
        set(v) = put("codexAccount", v)
    var codexExpires: Long
        get() = sp.getLong("codexExpires", 0L)
        set(v) = sp.edit().putLong("codexExpires", v).apply()

    // Карта
    var mapUrl: String
        get() = str("mapUrl", DEFAULT_MAP)
        set(v) = put("mapUrl", v)

    // Telegram
    var tgToken: String
        get() = str("tgToken")
        set(v) = put("tgToken", v)
    var tgOffset: Long
        get() = sp.getLong("tgOffset", 0L)
        set(v) = sp.edit().putLong("tgOffset", v).apply()
    var tgFeedChat: Long
        get() = sp.getLong("tgFeedChat", 0L)
        set(v) = sp.edit().putLong("tgFeedChat", v).apply()
    var tgNewsChat: Long
        get() = sp.getLong("tgNewsChat", 0L)
        set(v) = sp.edit().putLong("tgNewsChat", v).apply()
    var tgNews: String
        get() = str("tgNews")
        set(v) = put("tgNews", v)
    var tgFeeds: String
        get() = str("tgFeeds")
        set(v) = put("tgFeeds", v)
    var tgChats: String
        get() = str("tgChats")
        set(v) = put("tgChats", v)
    var tgLastPoop: Long
        get() = sp.getLong("tgLastPoop", 0L)
        set(v) = sp.edit().putLong("tgLastPoop", v).apply()

    // Экран
    var lightTheme: Boolean
        get() = sp.getBoolean("lightTheme", false)
        set(v) = sp.edit().putBoolean("lightTheme", v).apply()
    var nightDim: Boolean
        get() = sp.getBoolean("nightDim", true)
        set(v) = sp.edit().putBoolean("nightDim", v).apply()
}
