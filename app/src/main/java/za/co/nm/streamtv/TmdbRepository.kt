package za.co.nm.streamtv

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

class TmdbRepository(context: Context) {
    companion object {
        private const val TOKEN_KEY = "tmdb_read_access_token"
        private const val API = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"
    }

    private val secureStore = SecretStore(context)

    fun configured(): Boolean = !secureStore.get(TOKEN_KEY).isNullOrBlank()
    fun saveToken(token: String) {
        if (token.isBlank()) secureStore.remove(TOKEN_KEY) else secureStore.put(TOKEN_KEY, token.trim())
    }
    fun maskedToken(): String = secureStore.get(TOKEN_KEY)?.let { if (it.length > 12) "••••${it.takeLast(6)}" else "Saved" } ?: "Not configured"

    suspend fun enrich(item: AppMedia): AppMedia {
        val token = secureStore.get(TOKEN_KEY)?.trim().orEmpty()
        if (token.isBlank() || item.meta.type == "rd") return item
        val headers = mapOf("Authorization" to "Bearer $token")
        val candidate = when {
            imdbId(item.meta.id) != null -> findByImdb(imdbId(item.meta.id)!!, item.meta.type, headers)
            else -> searchByTitle(item.meta.name, item.meta.type, headers)
        } ?: return item

        val posterPath = candidate.get("poster_path")?.takeUnless { it.isJsonNull }?.asString
        val backdropPath = candidate.get("backdrop_path")?.takeUnless { it.isJsonNull }?.asString
        val overview = candidate.get("overview")?.takeUnless { it.isJsonNull }?.asString
        val release = candidate.get("release_date")?.takeUnless { it.isJsonNull }?.asString
            ?: candidate.get("first_air_date")?.takeUnless { it.isJsonNull }?.asString
        val rating = candidate.get("vote_average")?.takeUnless { it.isJsonNull }?.asDouble

        val merged = item.meta.copy(
            poster = posterPath?.let { "$IMG/w500$it" } ?: item.meta.poster,
            background = backdropPath?.let { "$IMG/w1280$it" } ?: item.meta.background,
            description = item.meta.description?.takeIf { it.isNotBlank() } ?: overview,
            releaseInfo = item.meta.releaseInfo?.takeIf { it.isNotBlank() } ?: release?.take(4),
            imdbRating = item.meta.imdbRating?.takeIf { it.isNotBlank() }
                ?: rating?.takeIf { it > 0.0 }?.let { String.format("%.1f", it) }
        )
        return item.copy(meta = merged)
    }

    suspend fun enrichBatch(items: List<AppMedia>, limit: Int = 24): List<AppMedia> = supervisorScope {
        val selected = items.take(limit)
        val enriched = selected.map { item -> async { runCatching { enrich(item) }.getOrDefault(item) } }.awaitAll()
        enriched + items.drop(limit)
    }

    private suspend fun findByImdb(id: String, type: String, headers: Map<String, String>): JsonObject? {
        val url = "$API/find/${SimpleHttp.encode(id)}?external_source=imdb_id&language=en-US"
        val result = SimpleHttp.get(url, headers)
        if (result.code !in 200..299) return null
        val root = JsonParser.parseString(result.body).asJsonObject
        val key = if (type == "series") "tv_results" else "movie_results"
        return root.getAsJsonArray(key)?.firstOrNull()?.asJsonObject
            ?: root.getAsJsonArray(if (key == "tv_results") "movie_results" else "tv_results")?.firstOrNull()?.asJsonObject
    }

    private suspend fun searchByTitle(title: String, type: String, headers: Map<String, String>): JsonObject? {
        val endpoint = if (type == "series") "search/tv" else "search/movie"
        val url = "$API/$endpoint?query=${SimpleHttp.encode(title)}&include_adult=false&language=en-US&page=1"
        val result = SimpleHttp.get(url, headers)
        if (result.code !in 200..299) return null
        val root = JsonParser.parseString(result.body).asJsonObject
        return root.getAsJsonArray("results")?.firstOrNull()?.asJsonObject
    }

    private fun imdbId(value: String): String? = Regex("tt\\d{5,10}").find(value)?.value
}
