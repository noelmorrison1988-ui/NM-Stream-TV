package za.co.nm.streamtv

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/** Paged on-demand TMDB discovery, separate from stream-provider polling. */
class HomeCategoryBrowser(
    private val token: String,
    private val toMedia: (JsonObject, String) -> AppMedia?
) {
    private val api = "https://api.themoviedb.org/3"
    private val headers get() = mapOf("Authorization" to "Bearer $token")

    suspend fun themed(key: String, page: Int, limit: Int = 20): TmdbBrowsePage =
        supervisorScope {
            val spec = HomeCollections.byKey[key] ?: return@supervisorScope TmdbBrowsePage()
            if (token.isBlank()) return@supervisorScope TmdbBrowsePage()
            val safePage = page.coerceAtLeast(1)
            val responses = spec.queries.map { query ->
                async {
                    if (safePage > 1 && query.searchName != null)
                        return@async emptyList<AppMedia>() to false
                    val suffix = query.searchName?.let {
                        "?query=${SimpleHttp.encode(it)}&include_adult=false"
                    } ?: if (query.route.contains("?")) "&include_adult=false" else "?include_adult=false"
                    val url = "$api/${query.route.replace("|", "%7C")}$suffix&language=en-US&page=$safePage"
                    runCatching {
                        val response = SimpleHttp.get(url, headers)
                        if (response.code !in 200..299)
                            return@runCatching emptyList<AppMedia>() to false
                        val root = JsonParser.parseString(response.body).asJsonObject
                        val maxPage = root.get("total_pages")?.takeUnless { it.isJsonNull }?.asInt ?: safePage
                        val results = root.getAsJsonArray("results")?.mapNotNull { el ->
                            el.takeIf { it.isJsonObject }?.asJsonObject?.let { toMedia(it, query.kind) }
                        }.orEmpty().filter(MediaPolicy::allows)
                        val count = if (query.searchName != null) 2 else 12
                        results.take(count) to (query.searchName == null && safePage < maxPage)
                    }.getOrDefault(emptyList<AppMedia>() to false)
                }
            }.awaitAll()
            TmdbBrowsePage(
                items = responses.zip(spec.queries)
                    .sortedByDescending { (_, query) -> query.searchName != null }
                    .flatMap { (response, _) -> response.first }
                    .distinctBy { "${it.meta.type}:${it.meta.tmdbId ?: it.meta.id}" }
                    .take(limit),
                hasNext = responses.any { it.second }
            )
        }

    suspend fun similar(item: AppMedia, page: Int, limit: Int = 20): TmdbBrowsePage =
        supervisorScope {
            val tmdbId = item.meta.tmdbId ?: return@supervisorScope TmdbBrowsePage()
            if (token.isBlank()) return@supervisorScope TmdbBrowsePage()
            val path = if (item.meta.type == "series") "tv" else "movie"
            val safePage = page.coerceAtLeast(1)
            val responses = listOf("recommendations", "similar").map { collection ->
                async {
                    runCatching {
                        val response = SimpleHttp.get(
                            "$api/$path/$tmdbId/$collection?language=en-US&page=$safePage",
                            headers
                        )
                        if (response.code !in 200..299)
                            return@runCatching emptyList<AppMedia>() to false
                        val root = JsonParser.parseString(response.body).asJsonObject
                        val maxPage = root.get("total_pages")?.takeUnless { it.isJsonNull }?.asInt ?: safePage
                        val results = root.getAsJsonArray("results")?.mapNotNull { el ->
                            el.takeIf { it.isJsonObject }?.asJsonObject?.let {
                                toMedia(it, item.meta.type)
                            }
                        }.orEmpty().filter(MediaPolicy::allows)
                        results to (safePage < maxPage)
                    }.getOrDefault(emptyList<AppMedia>() to false)
                }
            }.awaitAll()
            TmdbBrowsePage(
                items = responses.flatMap { it.first }
                    .filter { it.meta.tmdbId != tmdbId }
                    .distinctBy { it.meta.tmdbId ?: it.meta.id }
                    .take(limit),
                hasNext = responses.any { it.second }
            )
        }
}
