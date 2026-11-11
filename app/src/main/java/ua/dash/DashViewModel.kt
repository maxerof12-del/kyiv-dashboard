package ua.dash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.dash.data.AlertState
import ua.dash.data.AlertsRepo
import ua.dash.data.ClaudeAuth
import ua.dash.data.CodexAuth
import ua.dash.data.DtekRepo
import ua.dash.data.HttpException
import ua.dash.data.LimitsInfo
import ua.dash.data.OAuth
import ua.dash.data.PowerInfo
import ua.dash.data.Prefs
import ua.dash.data.Weather
import ua.dash.data.WeatherRepo

data class Res<T>(val data: T? = null, val error: String? = null, val loading: Boolean = false)

data class UiState(
    val weather: Res<Weather> = Res(),
    val alert: Res<AlertState> = Res(),
    val power: Res<PowerInfo> = Res(),
    val claude: Res<LimitsInfo> = Res(),
    val codex: Res<LimitsInfo> = Res(),
    val claudeLoggedIn: Boolean = false,
    val codexLoggedIn: Boolean = false,
    val loginMessage: String? = null,
    val lightTheme: Boolean = false,
    val nightDim: Boolean = true,
)

class DashViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    private val _state = MutableStateFlow(
        UiState(
            power = Res(DtekRepo.fromCache(prefs)),
            claudeLoggedIn = prefs.claudeAccess.isNotBlank(),
            codexLoggedIn = prefs.codexAccess.isNotBlank(),
            lightTheme = prefs.lightTheme,
            nightDim = prefs.nightDim,
        )
    )
    val state: StateFlow<UiState> = _state

    private var dtekJob: Job? = null
    private var claudeJob: Job? = null
    private var codexJob: Job? = null

    init {
        loop(15 * 60_000L) { refreshWeather() }
        loop(30_000L) { refreshAlert() }
        dtekJob = loop(10 * 60_000L) { refreshPower() }
        claudeJob = loop(2 * 60_000L) { refreshClaude() }
        codexJob = loop(2 * 60_000L) { refreshCodex() }
    }

    private fun loop(period: Long, block: suspend () -> Unit): Job = viewModelScope.launch {
        while (isActive) {
            try { block() } catch (_: Exception) {}
            delay(period)
        }
    }

    private fun msg(e: Throwable): String = when (e) {
        is HttpException -> when (e.code) {
            401, 403 -> "Потрібно увійти знову (${e.code})"
            429 -> "Забагато запитів, спробую пізніше"
            else -> "Помилка сервера ${e.code}"
        }
        is java.net.UnknownHostException -> "Немає інтернету"
        is java.net.SocketTimeoutException -> "Таймаут мережі"
        is kotlinx.coroutines.TimeoutCancellationException -> "Таймаут"
        else -> e.message ?: e.javaClass.simpleName
    }

    private suspend fun <T> load(
        get: (UiState) -> Res<T>,
        set: (UiState, Res<T>) -> UiState,
        io: Boolean = true,
        block: suspend () -> T,
    ) {
        _state.update { set(it, get(it).copy(loading = true)) }
        try {
            val v = if (io) withContext(Dispatchers.IO) { block() } else block()
            _state.update { set(it, Res(v)) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            _state.update { set(it, get(it).copy(error = msg(e), loading = false)) }
        }
    }

    suspend fun refreshWeather() = load({ it.weather }, { s, r -> s.copy(weather = r) }) {
        WeatherRepo.fetch(prefs.lat, prefs.lon)
    }

    suspend fun refreshAlert() = load({ it.alert }, { s, r -> s.copy(alert = r) }) {
        AlertsRepo.fetch(prefs)
    }

    suspend fun refreshPower() = load({ it.power }, { s, r -> s.copy(power = r) }, io = false) {
        DtekRepo.fetch(getApplication(), prefs)
    }

    suspend fun refreshClaude() {
        if (prefs.claudeAccess.isBlank()) {
            _state.update { it.copy(claude = Res(), claudeLoggedIn = false) }; return
        }
        load({ it.claude }, { s, r -> s.copy(claude = r, claudeLoggedIn = true) }) { ClaudeAuth.usage(prefs) }
    }

    suspend fun refreshCodex() {
        if (prefs.codexAccess.isBlank()) {
            _state.update { it.copy(codex = Res(), codexLoggedIn = false) }; return
        }
        load({ it.codex }, { s, r -> s.copy(codex = r, codexLoggedIn = true) }) { CodexAuth.usage(prefs) }
    }

    fun refreshAll() = viewModelScope.launch {
        launch { refreshWeather() }
        launch { refreshAlert() }
        launch { refreshPower() }
        launch { refreshClaude() }
        launch { refreshCodex() }
    }

    // ───── Настройки ─────

    fun saveAddress(street: String, house: String) {
        prefs.street = street.trim()
        prefs.house = house.trim()
        prefs.dtekGroup = ""
        prefs.dtekGroupForAddress = ""
        _state.update { it.copy(power = Res(loading = true)) }
        viewModelScope.launch { refreshPower() }
    }

    fun setAlertRegion(r: String) {
        prefs.alertRegion = r
        prefs.alertSince = 0
        viewModelScope.launch { refreshAlert() }
    }

    fun setLightTheme(v: Boolean) { prefs.lightTheme = v; _state.update { it.copy(lightTheme = v) } }
    fun setNightDim(v: Boolean) { prefs.nightDim = v; _state.update { it.copy(nightDim = v) } }

    fun clearLoginMessage() = _state.update { it.copy(loginMessage = null) }
    private fun say(m: String) = _state.update { it.copy(loginMessage = m) }

    private var claudeManual: ClaudeAuth.Pending? = null

    /** Вход в Claude: браузер → редирект на localhost внутри приложения */
    fun loginClaude(openUrl: (String) -> Unit) {
        val p = ClaudeAuth.start(manual = false)
        viewModelScope.launch {
            say("Claude: увійдіть у браузері та натисніть «Authorize»…")
            try {
                val params = kotlinx.coroutines.coroutineScope {
                    val d = kotlinx.coroutines.async {
                        OAuth.awaitRedirect(ClaudeAuth.PORT, ClaudeAuth.PATH)
                    }
                    delay(300)
                    openUrl(p.url)
                    d.await()
                }
                params["error"]?.let { throw IllegalStateException(it) }
                val code = params["code"] ?: throw IllegalStateException("Немає коду")
                withContext(Dispatchers.IO) { ClaudeAuth.exchange(p, code, prefs) }
                say("Claude: вхід виконано ✓")
                refreshClaude()
            } catch (e: Exception) {
                say("Claude: помилка — ${msg(e)}. Спробуйте «Вхід з кодом».")
            }
        }
    }

    /** Запасной вход: страница Anthropic покажет код, его нужно вставить */
    fun startClaudeManual(openUrl: (String) -> Unit) {
        val p = ClaudeAuth.start(manual = true)
        claudeManual = p
        openUrl(p.url)
        say("Скопіюйте код зі сторінки Anthropic і вставте нижче")
    }

    fun finishClaudeManual(code: String) {
        val p = claudeManual ?: run { say("Спочатку натисніть «Вхід з кодом»"); return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ClaudeAuth.exchange(p, code, prefs) }
                claudeManual = null
                say("Claude: вхід виконано ✓")
                refreshClaude()
            } catch (e: Exception) {
                say("Claude: помилка — ${msg(e)}")
            }
        }
    }

    fun logoutClaude() { ClaudeAuth.logout(prefs); viewModelScope.launch { refreshClaude() } }

    fun loginCodex(openUrl: (String) -> Unit) {
        val p = CodexAuth.start()
        viewModelScope.launch {
            say("ChatGPT: увійдіть у браузері…")
            try {
                val params = kotlinx.coroutines.coroutineScope {
                    val d = kotlinx.coroutines.async {
                        OAuth.awaitRedirect(CodexAuth.PORT, CodexAuth.PATH)
                    }
                    delay(300)
                    openUrl(p.url)
                    d.await()
                }
                params["error"]?.let { throw IllegalStateException(params["error_description"] ?: it) }
                val code = params["code"] ?: throw IllegalStateException("Немає коду")
                if (params["state"] != null && params["state"] != p.state) throw IllegalStateException("state не збігається")
                withContext(Dispatchers.IO) { CodexAuth.exchange(p, code, prefs) }
                say("ChatGPT/Codex: вхід виконано ✓")
                refreshCodex()
            } catch (e: Exception) {
                say("ChatGPT: помилка — ${msg(e)}")
            }
        }
    }

    fun logoutCodex() { CodexAuth.logout(prefs); viewModelScope.launch { refreshCodex() } }
}
