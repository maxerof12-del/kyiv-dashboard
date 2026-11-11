package ua.dash.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** PKCE + приём редиректа на localhost прямо в телефоне */
object OAuth {
    private val rnd = SecureRandom()

    fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun randomToken(n: Int = 32): String = ByteArray(n).also { rnd.nextBytes(it) }.let(::b64)

    fun challenge(verifier: String): String =
        b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun query(params: List<Pair<String, String>>): String =
        params.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    /** Декодирует payload JWT */
    fun jwtPayload(jwt: String): JSONObject? = try {
        val part = jwt.split(".")[1]
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
    } catch (e: Exception) { null }

    /**
     * Ждёт один запрос браузера на http://localhost:port{path}?code=..&state=..
     * Возвращает параметры запроса.
     */
    suspend fun awaitRedirect(port: Int, path: String, timeoutMs: Int = 300_000): Map<String, String> =
        withContext(Dispatchers.IO) {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
                server.soTimeout = timeoutMs
                while (true) {
                    val socket = try { server.accept() } catch (e: SocketTimeoutException) {
                        throw IllegalStateException("Час очікування входу вичерпано")
                    }
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val line = reader.readLine() ?: ""
                        // GET /path?query HTTP/1.1
                        val target = line.split(" ").getOrNull(1) ?: ""
                        val p = target.substringBefore("?")
                        val q = target.substringAfter("?", "")
                        val params = q.split("&").filter { it.contains("=") }.associate {
                            URLDecoder.decode(it.substringBefore("="), "UTF-8") to
                                URLDecoder.decode(it.substringAfter("="), "UTF-8")
                        }
                        val ok = p == path && (params.containsKey("code") || params.containsKey("error"))
                        val html = if (ok)
                            "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'></head>" +
                                "<body style='font-family:sans-serif;background:#0e1116;color:#fff;text-align:center;padding-top:20vh'>" +
                                "<h2>Готово ✓</h2><p>Поверніться в Kyiv Dashboard</p></body></html>"
                        else "not found"
                        val bytes = html.toByteArray(Charsets.UTF_8)
                        val out = s.getOutputStream()
                        out.write(
                            ("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                        )
                        out.write(bytes)
                        out.flush()
                        if (ok) return@withContext params
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                emptyMap()
            }
        }
}

// ───────────────────────────── Claude ─────────────────────────────

data class Window(
    val label: String,
    val usedPercent: Double,
    val resetsAt: Long?,      // мс
    val windowMs: Long,
)

data class LimitsInfo(val windows: List<Window>, val plan: String?, val fetchedAt: Long)

object ClaudeAuth {
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val PORT = 54545
    const val PATH = "/callback"
    const val LOCAL_REDIRECT = "http://localhost:$PORT$PATH"
    const val MANUAL_REDIRECT = "https://console.anthropic.com/oauth/code/callback"
    private const val SCOPES = "org:create_api_key user:profile user:inference"
    private val TOKEN_URLS = listOf(
        "https://console.anthropic.com/v1/oauth/token",
        "https://platform.claude.com/v1/oauth/token",
    )

    data class Pending(val verifier: String, val state: String, val redirect: String, val url: String)

    fun start(manual: Boolean): Pending {
        val verifier = OAuth.randomToken()
        val state = OAuth.randomToken()
        val redirect = if (manual) MANUAL_REDIRECT else LOCAL_REDIRECT
        val params = buildList {
            if (manual) add("code" to "true")
            add("client_id" to CLIENT_ID)
            add("response_type" to "code")
            add("redirect_uri" to redirect)
            add("scope" to SCOPES)
            add("code_challenge" to OAuth.challenge(verifier))
            add("code_challenge_method" to "S256")
            add("state" to state)
        }
        return Pending(verifier, state, redirect, "https://claude.ai/oauth/authorize?" + OAuth.query(params))
    }

    /** code может прийти как "code#state" (ручной режим) */
    fun exchange(p: Pending, rawCode: String, prefs: Prefs) {
        val code = rawCode.trim().substringBefore("#")
        val state = rawCode.trim().substringAfter("#", p.state)
        val body = JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("state", state)
            .put("client_id", CLIENT_ID)
            .put("redirect_uri", p.redirect)
            .put("code_verifier", p.verifier)
        val form = mapOf(
            "grant_type" to "authorization_code", "code" to code, "state" to state,
            "client_id" to CLIENT_ID, "redirect_uri" to p.redirect, "code_verifier" to p.verifier,
        )
        save(tokenRequest(body, form), prefs)
    }

    private fun tokenRequest(json: JSONObject, form: Map<String, String>): JSONObject {
        var last: Exception? = null
        for (url in TOKEN_URLS) {
            try { return JSONObject(Http.postJson(url, json.toString())) } catch (e: Exception) { last = e }
            try { return JSONObject(Http.postForm(url, form)) } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("token error")
    }

    private fun save(j: JSONObject, prefs: Prefs) {
        prefs.claudeAccess = j.getString("access_token")
        j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { prefs.claudeRefresh = it }
        prefs.claudeExpires = System.currentTimeMillis() + j.optLong("expires_in", 28800) * 1000
    }

    fun refreshIfNeeded(prefs: Prefs, force: Boolean = false) {
        if (prefs.claudeRefresh.isBlank()) return
        if (!force && System.currentTimeMillis() < prefs.claudeExpires - 10 * 60_000) return
        val body = JSONObject()
            .put("grant_type", "refresh_token")
            .put("refresh_token", prefs.claudeRefresh)
            .put("client_id", CLIENT_ID)
        val form = mapOf(
            "grant_type" to "refresh_token", "refresh_token" to prefs.claudeRefresh, "client_id" to CLIENT_ID,
        )
        save(tokenRequest(body, form), prefs)
    }

    fun logout(prefs: Prefs) {
        prefs.claudeAccess = ""; prefs.claudeRefresh = ""; prefs.claudeExpires = 0
    }

    fun usage(prefs: Prefs): LimitsInfo {
        if (prefs.claudeAccess.isBlank()) throw IllegalStateException("Не виконано вхід")
        refreshIfNeeded(prefs)
        val text = try {
            get(prefs)
        } catch (e: HttpException) {
            if (e.code == 401 && prefs.claudeRefresh.isNotBlank()) {
                refreshIfNeeded(prefs, force = true); get(prefs)
            } else throw e
        }
        val j = JSONObject(text)
        val list = mutableListOf<Window>()
        fun add(key: String, label: String, hours: Long) {
            val w = j.optJSONObject(key) ?: return
            if (w.isNull("utilization")) return
            list.add(
                Window(
                    label = label,
                    usedPercent = w.optDouble("utilization", 0.0),
                    resetsAt = w.optString("resets_at").takeIf { it.isNotBlank() && it != "null" }?.let(::parseIso),
                    windowMs = hours * 3600_000,
                )
            )
        }
        add("five_hour", "5 год", 5)
        add("seven_day", "тиждень", 168)
        add("seven_day_opus", "тиждень · Opus", 168)
        return LimitsInfo(list, null, System.currentTimeMillis())
    }

    private fun get(prefs: Prefs) = Http.get(
        "https://api.anthropic.com/api/oauth/usage",
        mapOf(
            "Authorization" to "Bearer ${prefs.claudeAccess}",
            "anthropic-beta" to "oauth-2025-04-20",
            "User-Agent" to "claude-code/2.0.0",
        )
    )
}

fun parseIso(s: String): Long? = try {
    java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
} catch (e: Exception) {
    try { java.time.Instant.parse(s).toEpochMilli() } catch (e2: Exception) { null }
}

// ───────────────────────────── Codex (ChatGPT) ─────────────────────────────

object CodexAuth {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val PORT = 1455
    const val PATH = "/auth/callback"
    const val REDIRECT = "http://localhost:$PORT$PATH"
    private const val TOKEN_URL = "https://auth.openai.com/oauth/token"

    data class Pending(val verifier: String, val state: String, val url: String)

    fun start(): Pending {
        val verifier = OAuth.randomToken(48)
        val state = OAuth.randomToken()
        val params = listOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT,
            "scope" to "openid profile email offline_access",
            "code_challenge" to OAuth.challenge(verifier),
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
            "originator" to "codex_cli_rs",
        )
        return Pending(verifier, state, "https://auth.openai.com/oauth/authorize?" + OAuth.query(params))
    }

    fun exchange(p: Pending, code: String, prefs: Prefs) {
        val j = JSONObject(
            Http.postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT,
                    "client_id" to CLIENT_ID,
                    "code_verifier" to p.verifier,
                )
            )
        )
        save(j, prefs)
    }

    private fun save(j: JSONObject, prefs: Prefs) {
        val access = j.getString("access_token")
        prefs.codexAccess = access
        j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { prefs.codexRefresh = it }
        val idToken = j.optString("id_token")
        val acc = (OAuth.jwtPayload(idToken) ?: OAuth.jwtPayload(access))
            ?.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")
        if (!acc.isNullOrBlank()) prefs.codexAccount = acc
        val exp = OAuth.jwtPayload(access)?.optLong("exp", 0L) ?: 0L
        prefs.codexExpires = if (exp > 0) exp * 1000 else System.currentTimeMillis() + 3600_000
    }

    fun refreshIfNeeded(prefs: Prefs, force: Boolean = false) {
        if (prefs.codexRefresh.isBlank()) return
        if (!force && System.currentTimeMillis() < prefs.codexExpires - 10 * 60_000) return
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "refresh_token")
            .put("refresh_token", prefs.codexRefresh)
            .put("scope", "openid profile email")
        save(JSONObject(Http.postJson(TOKEN_URL, body.toString())), prefs)
    }

    fun logout(prefs: Prefs) {
        prefs.codexAccess = ""; prefs.codexRefresh = ""; prefs.codexAccount = ""; prefs.codexExpires = 0
    }

    fun usage(prefs: Prefs): LimitsInfo {
        if (prefs.codexAccess.isBlank()) throw IllegalStateException("Не виконано вхід")
        refreshIfNeeded(prefs)
        val text = try {
            get(prefs)
        } catch (e: HttpException) {
            if (e.code == 401 && prefs.codexRefresh.isNotBlank()) {
                refreshIfNeeded(prefs, force = true); get(prefs)
            } else throw e
        }
        val j = JSONObject(text)
        val rl = j.optJSONObject("rate_limit")
        val now = System.currentTimeMillis()
        val list = mutableListOf<Window>()
        for (key in listOf("primary_window", "secondary_window")) {
            val w = rl?.optJSONObject(key) ?: continue
            val secs = w.optLong("limit_window_seconds", 0)
            val resetAt = when {
                w.has("reset_at") && !w.isNull("reset_at") -> w.optLong("reset_at") * 1000
                w.has("reset_after_seconds") -> now + w.optLong("reset_after_seconds") * 1000
                else -> null
            }
            val label = when {
                secs in 1..(6 * 3600) -> "${secs / 3600} год"
                secs >= 6 * 86400 -> "тиждень"
                secs > 0 -> "${secs / 3600} год"
                else -> "ліміт"
            }
            list.add(Window(label, w.optDouble("used_percent", 0.0), resetAt, secs * 1000))
        }
        return LimitsInfo(list, j.optString("plan_type").takeIf { it.isNotBlank() }, now)
    }

    private fun get(prefs: Prefs) = Http.get(
        "https://chatgpt.com/backend-api/wham/usage",
        buildMap {
            put("Authorization", "Bearer ${prefs.codexAccess}")
            if (prefs.codexAccount.isNotBlank()) put("ChatGPT-Account-Id", prefs.codexAccount)
            put("User-Agent", "codex_cli_rs/0.50.0")
            put("originator", "codex_cli_rs")
        }
    )
}
