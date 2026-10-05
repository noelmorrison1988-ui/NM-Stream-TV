package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class RealDebridRepository(context: Context) {
    companion object {
        private const val PUBLIC_OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM"
        private const val OAUTH_BASE = "https://api.real-debrid.com/oauth/v2"
        private const val REST_BASE = "https://api.real-debrid.com/rest/1.0"
        private const val AUTH_KEY = "real_debrid_auth"
        private const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
    }

    private val gson = Gson()
    private val secureStore = SecretStore(context)

    suspend fun startDeviceAuth(): RdDeviceCode {
        val url = "$OAUTH_BASE/device/code?client_id=$PUBLIC_OPEN_SOURCE_CLIENT_ID&new_credentials=yes"
        val body = SimpleHttp.requireSuccess(SimpleHttp.get(url), "Starting Real-Debrid sign-in")
        return gson.fromJson(body, RdDeviceCode::class.java)
    }

    suspend fun pollBoundCredentials(deviceCode: String): RdBoundCredentials? {
        val url = "$OAUTH_BASE/device/credentials?client_id=$PUBLIC_OPEN_SOURCE_CLIENT_ID&code=${SimpleHttp.encode(deviceCode)}"
        val result = SimpleHttp.get(url)
        if (result.code in listOf(400, 401, 403, 404)) return null
        val body = SimpleHttp.requireSuccess(result, "Checking Real-Debrid authorization")
        return gson.fromJson(body, RdBoundCredentials::class.java)
    }

    suspend fun exchangeDeviceCode(deviceCode: String, credentials: RdBoundCredentials): RdStoredAuth {
        val result = SimpleHttp.postForm(
            "$OAUTH_BASE/token",
            mapOf(
                "client_id" to credentials.clientId,
                "client_secret" to credentials.clientSecret,
                "code" to deviceCode,
                "grant_type" to DEVICE_GRANT
            )
        )
        val token = gson.fromJson(
            SimpleHttp.requireSuccess(result, "Completing Real-Debrid sign-in"),
            RdTokenResponse::class.java
        )
        val stored = RdStoredAuth(
            clientId = credentials.clientId,
            clientSecret = credentials.clientSecret,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtEpochMs = System.currentTimeMillis() + token.expiresIn * 1000L
        )
        saveAuth(stored)
        return stored
    }

    suspend fun getUser(): RdUser? {
        val auth = validAuth() ?: return null
        val result = SimpleHttp.get("$REST_BASE/user", mapOf("Authorization" to "Bearer ${auth.accessToken}"))
        if (result.code == 401) return null
        return gson.fromJson(SimpleHttp.requireSuccess(result, "Loading Real-Debrid account"), RdUser::class.java)
    }

    suspend fun recentDownloads(limit: Int = 20): List<RdDownload> {
        val auth = validAuth() ?: return emptyList()
        val result = SimpleHttp.get(
            "$REST_BASE/downloads?limit=${limit.coerceIn(1, 100)}",
            mapOf("Authorization" to "Bearer ${auth.accessToken}")
        )
        if (result.code == 401) return emptyList()
        val type = object : TypeToken<List<RdDownload>>() {}.type
        return gson.fromJson(SimpleHttp.requireSuccess(result, "Loading Real-Debrid downloads"), type)
    }

    fun disconnect() = secureStore.remove(AUTH_KEY)

    private suspend fun validAuth(): RdStoredAuth? {
        val current = loadAuth() ?: return null
        if (current.expiresAtEpochMs > System.currentTimeMillis() + 60_000L) return current

        val result = SimpleHttp.postForm(
            "$OAUTH_BASE/token",
            mapOf(
                "client_id" to current.clientId,
                "client_secret" to current.clientSecret,
                "code" to current.refreshToken,
                "grant_type" to DEVICE_GRANT
            )
        )
        if (result.code !in 200..299) {
            disconnect()
            return null
        }
        val token = gson.fromJson(result.body, RdTokenResponse::class.java)
        val refreshed = current.copy(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken.ifBlank { current.refreshToken },
            expiresAtEpochMs = System.currentTimeMillis() + token.expiresIn * 1000L
        )
        saveAuth(refreshed)
        return refreshed
    }

    private fun loadAuth(): RdStoredAuth? {
        val json = secureStore.get(AUTH_KEY) ?: return null
        return runCatching { gson.fromJson(json, RdStoredAuth::class.java) }.getOrNull()
    }

    private fun saveAuth(auth: RdStoredAuth) = secureStore.put(AUTH_KEY, gson.toJson(auth))
}
