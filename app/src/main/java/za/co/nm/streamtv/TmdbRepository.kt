package za.co.nm.streamtv

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

data class TmdbDiscoveryRows(
    val newMovies: List<AppMedia> = emptyList(),
    val trendingMovies: List<AppMedia> = emptyList(),
    val newSeries: List<AppMedia> = emptyList(),
    val trendingSeries: List<AppMedia> = emptyList()
)

data class SarahEditionRows(
    val belowDeck: List<AppMedia> = emptyList(),
    val realHousewives: List<AppMedia> = emptyList(),
    val bravo: List<AppMedia> = emptyList()
)

class TmdbRepository(context: Context) {
    companion object {
        private const val TOKEN_KEY = "tmdb_read_access_token"
        private const val API = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"
    }

    private val secureStore = SecretStore(context)

    private fun activeToken(): String =
        TmdbBuildSecret.TOKEN.trim()
            .ifBlank { secureStore.get(TOKEN_KEY)?.trim().orEmpty() }

    fun configured(): Boolean = activeToken().isNotBlank()

    fun saveToken(token: String) {
        if (TmdbBuildSecret.TOKEN.isNotBlank()) return
        if (token.isBlank()) secureStore.remove(TOKEN_KEY) else secureStore.put(TOKEN_KEY, token.trim())
    }

    fun maskedToken(): String = when {
        TmdbBuildSecret.TOKEN.isNotBlank() -> "Built in · Connected"
        activeToken().isNotBlank() -> "Local token fallback"
        else -> "Not configured"
    }

    suspend fun enrich(item: AppMedia): AppMedia {
        val token = activeToken()
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
        val tmdbId = candidate.get("id")?.takeUnless { it.isJsonNull }?.asInt
        val originalLanguage = candidate.get("original_language")?.takeUnless { it.isJsonNull }?.asString
        val genreIds = candidate.getAsJsonArray("genre_ids")
            ?.mapNotNull { runCatching { it.asInt }.getOrNull() }
            .orEmpty()
        val animeDetected = item.meta.isAnime ||
            (originalLanguage.equals("ja", true) && 16 in genreIds)
        val trailers = if (item.meta.trailers.isNotEmpty()) {
            item.meta.trailers
        } else {
            tmdbId?.let { fetchTrailers(it, item.meta.type, headers) }.orEmpty()
        }
        val contentRating = item.meta.contentRating
            ?: tmdbId?.let { fetchContentRating(it, item.meta.type, headers) }

        val merged = item.meta.copy(
            poster = posterPath?.let { "$IMG/w500$it" } ?: item.meta.poster,
            background = backdropPath?.let { "$IMG/w1280$it" } ?: item.meta.background,
            description = item.meta.description?.takeIf { it.isNotBlank() } ?: overview,
            releaseInfo = item.meta.releaseInfo?.takeIf { it.isNotBlank() } ?: release?.take(4),
            imdbRating = item.meta.imdbRating?.takeIf { it.isNotBlank() }
                ?: rating?.takeIf { it > 0.0 }?.let { String.format("%.1f", it) },
            tmdbId = tmdbId ?: item.meta.tmdbId,
            contentRating = contentRating,
            isAnime = animeDetected,
            trailers = trailers
        )
        return item.copy(meta = merged)
    }

    suspend fun enrichBatch(items: List<AppMedia>, limit: Int = 24): List<AppMedia> = supervisorScope {
        val selected = items.take(limit)
        val enriched = selected.map { item -> async { runCatching { enrich(item) }.getOrDefault(item) } }.awaitAll()
        enriched + items.drop(limit)
    }

    suspend fun searchPersonCredits(query: String, limit: Int = 40): List<AppMedia> {
        val token = activeToken()
        if (token.isBlank() || query.isBlank()) return emptyList()
        val headers = mapOf("Authorization" to "Bearer $token")

        val search = SimpleHttp.get(
            "$API/search/person?query=${SimpleHttp.encode(query.trim())}&include_adult=false&language=en-US&page=1",
            headers
        )
        if (search.code !in 200..299) return emptyList()
        val person = JsonParser.parseString(search.body).asJsonObject
            .getAsJsonArray("results")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return emptyList()
        val personId = person.get("id")?.takeUnless { it.isJsonNull }?.asInt ?: return emptyList()

        val credits = SimpleHttp.get("$API/person/$personId/combined_credits?language=en-US", headers)
        if (credits.code !in 200..299) return emptyList()
        return JsonParser.parseString(credits.body).asJsonObject
            .getAsJsonArray("cast")
            ?.mapNotNull { element ->
                val candidate = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val mediaType = candidate.get("media_type")?.takeUnless { it.isJsonNull }?.asString
                val type = when (mediaType) {
                    "movie" -> "movie"
                    "tv" -> "series"
                    else -> return@mapNotNull null
                }
                tmdbCandidateToAppMedia(candidate, type)?.copy(originAddonName = "TMDB Person Search")
            }
            ?.filter(MediaPolicy::allows)
            ?.distinctBy { "${it.meta.type}:${it.meta.tmdbId ?: it.meta.id}" }
            ?.sortedWith(
                compareByDescending<AppMedia> {
                    it.meta.releaseInfo?.toIntOrNull() ?: 0
                }.thenByDescending { it.meta.imdbRating?.toDoubleOrNull() ?: 0.0 }
            )
            ?.take(limit)
            .orEmpty()
    }
    suspend fun recommendationsFor(history: List<AppMedia>, limit: Int = 24): List<AppMedia> = supervisorScope {
        val token = activeToken()
        if (token.isBlank()) return@supervisorScope emptyList()
        val headers = mapOf("Authorization" to "Bearer $token")

        val seeds = history
            .filter { it.meta.type == "movie" || it.meta.type == "series" }
            .filter { it.meta.tmdbId != null }
            .distinctBy { "${it.meta.type}:${it.meta.tmdbId}" }
            .take(4)

        val recommended = seeds.map { seed ->
            async {
                runCatching { recommendationsForSeed(seed, headers) }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        val seedIds = seeds.mapNotNull { it.meta.tmdbId }.toSet()
        recommended
            .filter { it.meta.tmdbId !in seedIds }
            .filter(MediaPolicy::allows)
            .distinctBy { "${it.meta.type}:${it.meta.tmdbId ?: it.meta.id}" }
            .take(limit)
    }

    suspend fun sarahEditionRows(limit: Int = 24): SarahEditionRows = supervisorScope {
        val token = activeToken()
        if (token.isBlank()) return@supervisorScope SarahEditionRows()
        val headers = mapOf("Authorization" to "Bearer $token")

        val housewivesTitles = listOf(
            "The Real Housewives of Beverly Hills",
            "The Real Housewives of Durban",
            "The Real Housewives of New Jersey",
            "The Real Housewives of Orange County",
            "The Real Housewives of Salt Lake City",
            "The Real Housewives Ultimate Girls Trip"
        )

        val belowDeckDeferred = async { searchTvFamily("Below Deck", "below deck", headers) }
        val housewivesDeferred = async { curatedTvTitles(housewivesTitles, headers) }
        val bravoDeferred = async {
            val discovered = fetchCollection(
                "discover/tv?with_networks=74&sort_by=popularity.desc&include_adult=false",
                "series",
                headers,
                limit
            )
            if (discovered.isNotEmpty()) discovered else curatedTvTitles(
                listOf(
                    "Vanderpump Rules",
                    "Southern Charm",
                    "Summer House",
                    "Top Chef",
                    "Married to Medicine",
                    "Watch What Happens Live with Andy Cohen"
                ),
                headers
            )
        }

        SarahEditionRows(
            belowDeck = belowDeckDeferred.await().take(limit),
            realHousewives = housewivesDeferred.await().take(limit),
            bravo = bravoDeferred.await().take(limit)
        )
    }

    private suspend fun searchTvFamily(
        query: String,
        prefix: String,
        headers: Map<String, String>
    ): List<AppMedia> {
        val url = "$API/search/tv?query=${SimpleHttp.encode(query)}&include_adult=false&language=en-US&page=1"
        val result = SimpleHttp.get(url, headers)
        if (result.code !in 200..299) return emptyList()
        val preferredOrder = listOf(
            "below deck",
            "below deck mediterranean",
            "below deck sailing yacht",
            "below deck down under",
            "below deck adventure"
        )
        return JsonParser.parseString(result.body).asJsonObject
            .getAsJsonArray("results")
            ?.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val name = obj.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                if (!name.lowercase().startsWith(prefix.lowercase())) return@mapNotNull null
                tmdbCandidateToAppMedia(obj, "series")
            }
            ?.filter(MediaPolicy::allows)
            ?.distinctBy { it.meta.tmdbId ?: it.meta.id }
            ?.sortedWith(
                compareBy<AppMedia> { item ->
                    val key = item.meta.name.lowercase()
                    preferredOrder.indexOf(key).takeIf { it >= 0 } ?: preferredOrder.size
                }.thenBy { it.meta.name }
            )
            .orEmpty()
    }

    private suspend fun curatedTvTitles(
        titles: List<String>,
        headers: Map<String, String>
    ): List<AppMedia> = supervisorScope {
        titles.map { title ->
            async {
                runCatching { searchTvTitle(title, headers) }.getOrNull()
            }
        }.awaitAll()
            .filterNotNull()
            .filter(MediaPolicy::allows)
            .distinctBy { it.meta.tmdbId ?: it.meta.id }
    }

    private suspend fun searchTvTitle(
        title: String,
        headers: Map<String, String>
    ): AppMedia? {
        val url = "$API/search/tv?query=${SimpleHttp.encode(title)}&include_adult=false&language=en-US&page=1"
        val result = SimpleHttp.get(url, headers)
        if (result.code !in 200..299) return null
        val candidates = JsonParser.parseString(result.body).asJsonObject
            .getAsJsonArray("results")
            ?.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject
            }
            .orEmpty()
        val wanted = title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val candidate = candidates.firstOrNull { candidate ->
            val name = candidate.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            name == wanted
        } ?: candidates.firstOrNull()
        return candidate?.let { tmdbCandidateToAppMedia(it, "series") }
    }

    suspend fun discoveryRows(limit: Int = 18): TmdbDiscoveryRows = supervisorScope {
        val token = activeToken()
        if (token.isBlank()) return@supervisorScope TmdbDiscoveryRows()
        val headers = mapOf("Authorization" to "Bearer $token")

        val newMovies = async { fetchCollection("movie/now_playing", "movie", headers, limit) }
        val trendingMovies = async { fetchCollection("trending/movie/week", "movie", headers, limit) }
        val newSeries = async { fetchCollection("tv/on_the_air", "series", headers, limit) }
        val trendingSeries = async { fetchCollection("trending/tv/week", "series", headers, limit) }

        TmdbDiscoveryRows(
            newMovies = newMovies.await(),
            trendingMovies = trendingMovies.await(),
            newSeries = newSeries.await(),
            trendingSeries = trendingSeries.await()
        )
    }

    private suspend fun fetchCollection(
        path: String,
        type: String,
        headers: Map<String, String>,
        limit: Int
    ): List<AppMedia> {
        val separator = if (path.contains("?")) "&" else "?"
        val result = SimpleHttp.get("$API/$path${separator}language=en-US&page=1", headers)
        if (result.code !in 200..299) return emptyList()
        val root = JsonParser.parseString(result.body).asJsonObject
        return root.getAsJsonArray("results")
            ?.mapNotNull { element ->
                val candidate = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                tmdbCandidateToAppMedia(candidate, type)
            }
            ?.filter(MediaPolicy::allows)
            ?.distinctBy { "${it.meta.type}:${it.meta.tmdbId ?: it.meta.id}" }
            ?.take(limit)
            .orEmpty()
    }
    private suspend fun recommendationsForSeed(
        item: AppMedia,
        headers: Map<String, String>
    ): List<AppMedia> {
        val tmdbId = item.meta.tmdbId ?: return emptyList()
        val endpoint = if (item.meta.type == "series") "tv" else "movie"
        val result = SimpleHttp.get(
            "$API/$endpoint/$tmdbId/recommendations?language=en-US&page=1",
            headers
        )
        if (result.code !in 200..299) return emptyList()

        val root = JsonParser.parseString(result.body).asJsonObject
        return root.getAsJsonArray("results")
            ?.mapNotNull { element ->
                val candidate = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                tmdbCandidateToAppMedia(candidate, item.meta.type)
            }
            .orEmpty()
    }

    private fun tmdbCandidateToAppMedia(candidate: JsonObject, type: String): AppMedia? {
        val id = candidate.get("id")?.takeUnless { it.isJsonNull }?.asInt ?: return null
        val name = if (type == "series") {
            candidate.get("name")?.takeUnless { it.isJsonNull }?.asString
        } else {
            candidate.get("title")?.takeUnless { it.isJsonNull }?.asString
        }?.takeIf { it.isNotBlank() } ?: return null

        val posterPath = candidate.get("poster_path")?.takeUnless { it.isJsonNull }?.asString
        val backdropPath = candidate.get("backdrop_path")?.takeUnless { it.isJsonNull }?.asString
        val overview = candidate.get("overview")?.takeUnless { it.isJsonNull }?.asString
        val release = candidate.get("release_date")?.takeUnless { it.isJsonNull }?.asString
            ?: candidate.get("first_air_date")?.takeUnless { it.isJsonNull }?.asString
        val rating = candidate.get("vote_average")?.takeUnless { it.isJsonNull }?.asDouble
        val originalLanguage = candidate.get("original_language")?.takeUnless { it.isJsonNull }?.asString
        val genreIds = candidate.getAsJsonArray("genre_ids")
            ?.mapNotNull { runCatching { it.asInt }.getOrNull() }
            .orEmpty()
        val animeDetected = originalLanguage.equals("ja", true) && 16 in genreIds

        return AppMedia(
            meta = MetaItem(
                id = "tmdb:$type:$id",
                type = type,
                name = name,
                poster = posterPath?.let { "$IMG/w500$it" },
                background = backdropPath?.let { "$IMG/w1280$it" },
                description = overview,
                releaseInfo = release?.take(4),
                imdbRating = rating?.takeIf { it > 0.0 }?.let { String.format("%.1f", it) },
                tmdbId = id,
                isAnime = animeDetected
            ),
            originAddonName = "TMDB"
        )
    }

    private suspend fun fetchContentRating(
        tmdbId: Int,
        type: String,
        headers: Map<String, String>
    ): String? {
        return if (type == "series") {
            val result = SimpleHttp.get("$API/tv/$tmdbId/content_ratings", headers)
            if (result.code !in 200..299) return null
            val values = JsonParser.parseString(result.body).asJsonObject
                .getAsJsonArray("results")
                ?.mapNotNull { element ->
                    val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val region = obj.get("iso_3166_1")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
                    val rating = obj.get("rating")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                    if (rating.isBlank()) null else region to rating
                }
                .orEmpty()
            preferredCertification(values)
        } else {
            val result = SimpleHttp.get("$API/movie/$tmdbId/release_dates", headers)
            if (result.code !in 200..299) return null
            val values = JsonParser.parseString(result.body).asJsonObject
                .getAsJsonArray("results")
                ?.flatMap { element ->
                    val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@flatMap emptyList()
                    val region = obj.get("iso_3166_1")?.takeUnless { it.isJsonNull }?.asString ?: return@flatMap emptyList()
                    obj.getAsJsonArray("release_dates")
                        ?.mapNotNull { dateElement ->
                            val dateObj = dateElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                            val certification = dateObj.get("certification")
                                ?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                            if (certification.isBlank()) null else region to certification
                        }
                        .orEmpty()
                }
                .orEmpty()
            preferredCertification(values)
        }
    }

    private fun preferredCertification(values: List<Pair<String, String>>): String? {
        val priority = listOf("ZA", "GB", "US", "AU")
        priority.forEach { region ->
            values.firstOrNull { it.first.equals(region, true) }?.second?.let { return it }
        }
        return values.firstOrNull()?.second
    }

    private suspend fun fetchTrailers(
        tmdbId: Int,
        type: String,
        headers: Map<String, String>
    ): List<TrailerRef> {
        val endpoint = if (type == "series") "tv" else "movie"
        val result = SimpleHttp.get("$API/$endpoint/$tmdbId/videos?language=en-US", headers)
        if (result.code !in 200..299) return emptyList()

        val root = JsonParser.parseString(result.body).asJsonObject
        return root.getAsJsonArray("results")
            ?.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val site = obj.get("site")?.asString ?: return@mapNotNull null
                if (!site.equals("YouTube", true)) return@mapNotNull null
                val key = obj.get("key")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val typeName = obj.get("type")?.asString ?: "Trailer"
                if (typeName !in listOf("Trailer", "Teaser", "Clip")) return@mapNotNull null

                TrailerRef(
                    source = key,
                    type = typeName,
                    title = obj.get("name")?.takeUnless { it.isJsonNull }?.asString,
                    quality = obj.get("size")?.takeUnless { it.isJsonNull }?.asInt,
                    official = obj.get("official")?.takeUnless { it.isJsonNull }?.asBoolean
                )
            }
            ?.sortedWith(
                compareBy<TrailerRef>(
                    { if (it.type.equals("Trailer", true)) 0 else 1 },
                    { if (it.official == true) 0 else 1 },
                    {
                        when (it.quality) {
                            1080 -> 0
                            2160 -> 1
                            720 -> 2
                            480 -> 3
                            360 -> 4
                            null -> 6
                            else -> 5
                        }
                    }
                )
            )
            ?.take(6)
            .orEmpty()
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
