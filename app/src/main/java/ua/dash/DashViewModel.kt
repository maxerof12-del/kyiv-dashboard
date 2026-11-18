package ua.dash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.dash.data.Air
import ua.dash.data.AlertState
import ua.dash.data.ExtrasRepo
import ua.dash.data.Rates
import ua.dash.data.AlertsRepo
import ua.dash.data.BotFeed
import ua.dash.data.BotFeedRepo
import ua.dash.data.FeedInfo
import ua.dash.data.NewsInfo
import ua.dash.data.TelegramRepo
import ua.dash.data.TgChat
import ua.dash.data.Btc
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
    val rates: Res<Rates> = Res(),
    val air: Res<Air> = Res(),
    val btc: Res<Btc> = Res(),
    val botFeed: Res<BotFeed> = Res(),
    val feedEnabled: Boolean = true,
    val botSearching: Boolean = false,
    val feed: FeedInfo? = null,
    val news: NewsInfo? = null,
    val tgEnabled: Boolean = false,
    val tgError: String? = null,
    val tgChats: List<TgChat> = emptyList(),
    val tgFeedChat: Long = 0,
    val tgNewsChat: Long = 0,
    val tgBot: String? = null,
    val mapUrl: String = "",
    val homeLat: Double = 50.4545,
    val homeLon: Double = 30.3985,
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
            mapUrl = prefs.mapUrl,
            homeLat = prefs.lat,
            homeLon = prefs.lon,
            feedEnabled = prefs.feedEnabled,
            feed = TelegramRepo.feedInfo(prefs),
            news = TelegramRepo.newsInfo(prefs),
            tgEnabled = prefs.tgToken.isNotBlank(),
            tgChats = TelegramRepo.loadChats(prefs),
            tgFeedChat = prefs.tgFeedChat,
            tgNewsChat = prefs.tgNewsChat,
        )
    )
    val state: StateFlow<UiState> = _state

    private var dtekJob: Job? = null
    private var claudeJob: Job? = null
    private var codexJob: Job? = null

    init {
        loop(15 * 60_000L) { refreshWeather() }
        loop(10_000L) { refreshAlert() }
        dtekJob = loop(10 * 60_000L) { refreshPower() }
        claudeJob = loop(2 * 60_000L) { refreshClaude() }
        codexJob = loop(2 * 60_000L) { refreshCodex() }
        loop(60 * 60_000L) { refreshRates() }
        loop(2 * 60_000L) { refreshBtc() }
        loop(10_000L) { refreshBotFeed() }
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

    suspend fun refreshRates() = load({ it.rates }, { s, r -> s.copy(rates = r) }) { ExtrasRepo.rates() }

    suspend fun refreshTelegram() {
        if (prefs.tgToken.isBlank()) return
        try {
            withContext(Dispatchers.IO) { TelegramRepo.poll(prefs) }
            _state.update { it.copy(tgError = null) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(tgError = msg(e)) }
        }
        _state.update {
            it.copy(
                feed = TelegramRepo.feedInfo(prefs),
                news = TelegramRepo.newsInfo(prefs),
                tgChats = TelegramRepo.loadChats(prefs),
                tgEnabled = true,
            )
        }
    }

    fun setTgToken(token: String) {
        prefs.tgToken = token.trim()
        prefs.tgOffset = 0
        _state.update { it.copy(tgEnabled = token.isNotBlank(), tgBot = null, tgError = null) }
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { TelegramRepo.botName(prefs.tgToken) }
                _state.update { it.copy(tgBot = name) }
                refreshTelegram()
            } catch (e: Exception) {
                _state.update { it.copy(tgError = msg(e)) }
            }
        }
    }

    fun setFeedChat(id: Long) { prefs.tgFeedChat = id; _state.update { it.copy(tgFeedChat = id) } }
    fun setNewsChat(id: Long) { prefs.tgNewsChat = id; prefs.tgNews = ""; _state.update { it.copy(tgNewsChat = id, news = null) } }
    fun clearFeeds() { TelegramRepo.clearFeeds(prefs); _state.update { it.copy(feed = TelegramRepo.feedInfo(prefs)) } }

    private var botFails = 0
    private var lastDiscover = 0L

    suspend fun refreshBotFeed(forceDiscover: Boolean = false) {
        if (!prefs.feedEnabled) return
        // 1) Через интернет по коду — работает с любым роутером
        if (prefs.botCode.isNotBlank() && !forceDiscover) {
            try {
                val f = withContext(Dispatchers.IO) { BotFeedRepo.fetchCloud(prefs.botCode) }
                _state.update { it.copy(botFeed = Res(f)) }
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (prefs.botHost.isBlank()) {
                    _state.update { it.copy(botFeed = it.botFeed.copy(error = msg(e), loading = false)) }
                    return
                }
            }
        }
        // 2) Напрямую по Wi-Fi
        val now = System.currentTimeMillis()
        if (forceDiscover || prefs.botHost.isBlank() || botFails >= 3) {
            if (!forceDiscover && now - lastDiscover < 60_000) {
                if (prefs.botHost.isBlank()) return
            } else {
                lastDiscover = now
                _state.update { it.copy(botSearching = true) }
                val found = try { BotFeedRepo.discover() } catch (e: Exception) { null }
                _state.update { it.copy(botSearching = false) }
                if (found != null) { prefs.botHost = found; botFails = 0 }
                else if (prefs.botHost.isBlank()) {
                    _state.update { it.copy(botFeed = it.botFeed.copy(error = "бота не знайдено в Wi-Fi", loading = false)) }
                    return
                }
            }
        }
        try {
            val f = withContext(Dispatchers.IO) { BotFeedRepo.fetch(prefs.botHost) }
            botFails = 0
            _state.update { it.copy(botFeed = Res(f)) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            botFails++
            _state.update { it.copy(botFeed = it.botFeed.copy(error = "бот недоступний", loading = false)) }
        }
    }

    fun setFeedEnabled(v: Boolean) {
        prefs.feedEnabled = v
        _state.update { it.copy(feedEnabled = v) }
        if (v) viewModelScope.launch { refreshBotFeed() }
    }

    fun setBotHost(h: String) {
        prefs.botHost = h.trim()
        botFails = 0
        viewModelScope.launch { refreshBotFeed() }
    }

    fun setBotCode(c: String) {
        prefs.botCode = c.trim().lowercase()
        viewModelScope.launch { refreshBotFeed() }
    }

    fun findBot() { viewModelScope.launch { refreshBotFeed(forceDiscover = true) } }

    suspend fun refreshBtc() = load({ it.btc }, { s, r -> s.copy(btc = r) }) { ExtrasRepo.btc() }

    suspend fun refreshAir() = load({ it.air }, { s, r -> s.copy(air = r) }) { ExtrasRepo.air(prefs.lat, prefs.lon) }

    fun setMapUrl(u: String) {
        val v = u.trim().ifBlank { Prefs.DEFAULT_MAP }.let { if (it.startsWith("http")) it else "https://$it" }
        prefs.mapUrl = v
        _state.update { it.copy(mapUrl = v) }
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
        launch { refreshRates() }
        launch { refreshBtc() }
        launch { refreshBotFeed() }
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
                val params: Map<String, String> = coroutineScope {
                    val d = async {
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
                val params: Map<String, String> = coroutineScope {
                    val d = async {
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
