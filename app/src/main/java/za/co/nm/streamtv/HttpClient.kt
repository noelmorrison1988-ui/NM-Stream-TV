package za.co.nm.streamtv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class HttpResult(val code: Int, val body: String)

object SimpleHttp {
    private const val USER_AGENT = "NMStreamTV/0.11"

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult =
        request("GET", url, headers = headers)

    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val body = fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return request(
            method = "POST",
            url = url,
            headers = headers + ("Content-Type" to "application/x-www-form-urlencoded"),
            body = body
        )
    }

    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = request(
        method = "POST",
        url = url,
        headers = headers + ("Content-Type" to "application/json"),
        body = json
    )

    private suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null
    ): HttpResult = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
        }

        val code = connection.responseCode
        val stream = if (code in 200..399) connection.inputStream else connection.errorStream
        val response = stream?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
        }.orEmpty()
        connection.disconnect()
        HttpResult(code, response)
    }

    fun requireSuccess(result: HttpResult, action: String): String {
        if (result.code !in 200..299) {
            val details = result.body.take(320).ifBlank { "HTTP ${result.code}" }
            error("$action failed: $details")
        }
        return result.body
    }

    fun encode(value: String): String = enc(value)

    fun normalizeManifestUrl(input: String): String {
        var raw = input.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .substringBefore('#')

        if (raw.startsWith("stremio://", true)) {
            raw = "https://" + raw.substringAfter("://")
        } else if (!raw.contains("://")) {
            raw = "https://$raw"
        }

        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase()
        val localhostHttp = scheme == "http" && (
            uri.host.equals("127.0.0.1", true) ||
                uri.host.equals("localhost", true)
            )
        require(scheme == "https" || localhostHttp) {
            "Add-on manifests must use HTTPS (or localhost HTTP)"
        }
        require(uri.userInfo == null) { "URLs containing username/password user-info are not supported" }
        require(!uri.host.isNullOrBlank()) { "Manifest URL is invalid" }

        val cleanPath = uri.path.orEmpty().ifBlank { "/" }
        val manifestPath = when {
            cleanPath.endsWith("/manifest.json", true) -> cleanPath
            cleanPath.endsWith("/") -> cleanPath + "manifest.json"
            else -> cleanPath + "/manifest.json"
        }

        return URI(
            uri.scheme,
            null,
            uri.host,
            uri.port,
            manifestPath,
            uri.query,
            null
        ).toString()
    }

    fun baseUrlFromManifest(manifestUrl: String): String {
        val uri = URI(manifestUrl)
        val basePath = uri.path.removeSuffix("/manifest.json").trimEnd('/')
        return URI(uri.scheme, null, uri.host, uri.port, basePath, null, null)
            .toString()
            .trimEnd('/')
    }

    private fun enc(value: String): String = URLEncoder
        .encode(value, StandardCharsets.UTF_8.toString())
        .replace("+", "%20")
}
