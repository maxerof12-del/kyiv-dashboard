package ua.dash.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
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
        get() = str("lat", "50.4501").toDoubleOrNull() ?: 50.4501
        set(v) = put("lat", v.toString())
    var lon: Double
        get() = str("lon", "30.5234").toDoubleOrNull() ?: 30.5234
        set(v) = put("lon", v.toString())

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

    // Экран
    var lightTheme: Boolean
        get() = sp.getBoolean("lightTheme", false)
        set(v) = sp.edit().putBoolean("lightTheme", v).apply()
    var nightDim: Boolean
        get() = sp.getBoolean("nightDim", true)
        set(v) = sp.edit().putBoolean("nightDim", v).apply()
}
