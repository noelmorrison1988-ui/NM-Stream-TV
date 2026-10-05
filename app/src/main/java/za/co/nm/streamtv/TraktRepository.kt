package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class TraktRepository(context: Context) {
    companion object {
        private const val API = "https://api.trakt.tv"
        private const val CLIENT_ID_KEY = "trakt_client_id"
        private const val AUTH_KEY = "trakt_auth"
        private const val API_VERSION = "2"
    }

    private val secureStore = SecretStore(context)
    private val gson = Gson()

    fun saveClientId(clientId: String) {
        if (clientId.isBlank()) {
            secureStore.remove(CLIENT_ID_KEY)
            secureStore.remove(AUTH_KEY)
        } else secureStore.put(CLIENT_ID_KEY, clientId.trim())
    }

    fun clientIdConfigured(): Boolean = !secureStore.get(CLIENT_ID_KEY).isNullOrBlank()
    fun maskedClientId(): String = secureStore.get(CLIENT_ID_KEY)?.let { if (it.length > 10) "••••${it.takeLast(6)}" else "Saved" } ?: "Not configured"
    fun isConnected(): Boolean = loadAuth()?.expiresAtEpochMs?.let { it > System.currentTimeMillis() } == true

    suspend fun startDeviceAuth(): TraktDeviceCode {
        val clientId = requireClientId()
        val payload = gson.toJson(mapOf("client_id" to clientId))
        val body = SimpleHttp.requireSuccess(
            SimpleHttp.postJson("$API/oauth/device/code", payload),
            "Starting Trakt sign-in"
        )
        return gson.fromJson(body, TraktDeviceCode::class.java)
    }

    suspend fun pollDeviceToken(deviceCode: String): TraktStoredAuth? {
        val clientId = requireClientId()
        val payload = gson.toJson(mapOf("code" to deviceCode, "client_id" to clientId))
        val result = SimpleHttp.postJson("$API/oauth/device/token", payload)
        if (result.code in listOf(400, 404, 409, 410, 418, 429)) return null
        val token = gson.fromJson(SimpleHttp.requireSuccess(result, "Completing Trakt sign-in"), TraktToken::class.java)
        val stored = TraktStoredAuth(
            clientId = clientId,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtEpochMs = System.currentTimeMillis() + token.expiresIn * 1000L
        )
        secureStore.put(AUTH_KEY, gson.toJson(stored))
        return stored
    }

    suspend fun getUser(): TraktUser? {
        val auth = validAuth() ?: return null
        val result = SimpleHttp.get("$API/users/settings", headers(auth))
        if (result.code !in 200..299) return null
        val root = JsonParser.parseString(result.body).asJsonObject
        val user = root.getAsJsonObject("user") ?: return null
        return TraktUser(
            username = user.get("username")?.asString.orEmpty(),
            name = user.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        )
    }

    suspend fun scrobble(action: String, media: AppMedia, videoId: String, progressPercent: Double) {
        val auth = validAuth() ?: return
        val imdb = Regex("tt\\d{5,10}").find(videoId)?.value ?: Regex("tt\\d{5,10}").find(media.meta.id)?.value ?: return
        val percent = progressPercent.coerceIn(0.0, 100.0)
        val payload = JsonObject().apply {
            addProperty("progress", percent)
            if (media.meta.type == "series" && videoId.contains(':')) {
                val parts = videoId.split(':')
                val season = parts.getOrNull(parts.size - 2)?.toIntOrNull()
                val episode = parts.lastOrNull()?.toIntOrNull()
                if (season != null && episode != null) {
                    add("show", JsonObject().apply { add("ids", JsonObject().apply { addProperty("imdb", imdb) }) })
                    add("episode", JsonObject().apply {
                        addProperty("season", season)
                        addProperty("number", episode)
                    })
                } else {
                    add("movie", JsonObject().apply { add("ids", JsonObject().apply { addProperty("imdb", imdb) }) })
                }
            } else {
                add("movie", JsonObject().apply { add("ids", JsonObject().apply { addProperty("imdb", imdb) }) })
            }
        }
        SimpleHttp.postJson("$API/scrobble/$action", gson.toJson(payload), headers(auth))
    }

    fun disconnect() = secureStore.remove(AUTH_KEY)

    private fun requireClientId(): String = secureStore.get(CLIENT_ID_KEY)?.takeIf { it.isNotBlank() }
        ?: error("Add your Trakt Client ID in Settings first")

    private fun loadAuth(): TraktStoredAuth? {
        val json = secureStore.get(AUTH_KEY) ?: return null
        return runCatching { gson.fromJson(json, TraktStoredAuth::class.java) }.getOrNull()
    }

    private fun validAuth(): TraktStoredAuth? {
        val auth = loadAuth() ?: return null
        if (auth.expiresAtEpochMs <= System.currentTimeMillis()) {
            disconnect()
            return null
        }
        return auth
    }

    private fun headers(auth: TraktStoredAuth): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${auth.accessToken}",
        "trakt-api-version" to API_VERSION,
        "trakt-api-key" to auth.clientId
    )
}
