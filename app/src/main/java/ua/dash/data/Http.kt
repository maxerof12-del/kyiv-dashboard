package ua.dash.data

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpException(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(200)}")

object Http {
    private const val UA = "Mozilla/5.0 (Linux; Android 13) KyivDashboard/1.0"

    fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request("GET", url, headers, null, null)

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String =
        request("POST", url, headers, json, "application/json")

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        val body = form.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        return request("POST", url, headers, body, "application/x-www-form-urlencoded")
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText) ?: ""
            if (code !in 200..299) throw HttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }
}
