package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class TraktRepository(context: Context) {
    companion object {
        private const val API = "https://api.trakt.tv"
        private const val TOKEN_API = "https://auth.trakt.tv"
        private const val CLIENT_ID_KEY = "trakt_client_id"
        private const val CLIENT_SECRET_KEY = "trakt_client_secret"
        private const val AUTH_KEY = "trakt_auth"
        private const val API_VERSION = "2"
        private const val REFRESH_AHEAD_MS = 10 * 60 * 1000L
    }

    private val secureStore = SecretStore(context)
    private val gson = Gson()
    private val authMutex = Mutex()

    fun saveCredentials(clientId: String, clientSecret: String) {
        require(clientId.isNotBlank()) { "Enter your Trakt Client ID" }
        require(clientSecret.isNotBlank()) { "Enter your Trakt Client Secret" }
        secureStore.put(CLIENT_ID_KEY, clientId.trim())
        secureStore.put(CLIENT_SECRET_KEY, clientSecret.trim())
        secureStore.remove(AUTH_KEY)
    }

    fun clearCredentials() {
        secureStore.remove(CLIENT_ID_KEY)
        secureStore.remove(CLIENT_SECRET_KEY)
        secureStore.remove(AUTH_KEY)
    }

    fun credentialsConfigured(): Boolean =
        !secureStore.get(CLIENT_ID_KEY).isNullOrBlank() &&
            !secureStore.get(CLIENT_SECRET_KEY).isNullOrBlank()

    fun maskedClientId(): String = secureStore.get(CLIENT_ID_KEY)?.let {
        if (it.length > 10) "••••${it.takeLast(6)}" else "Saved"
    } ?: "Not configured"

    fun isConnected(): Boolean = loadAuth() != null

    suspend fun startDeviceAuth(): TraktDeviceCode {
        val (clientId, _) = requireCredentials()
        val payload = gson.toJson(mapOf("client_id" to clientId))
        val body = SimpleHttp.requireSuccess(
            SimpleHttp.postJson("$API/oauth/device/code", payload),
            "Starting Trakt sign-in"
        )
        return gson.fromJson(body, TraktDeviceCode::class.java)
    }

    suspend fun pollDeviceToken(deviceCode: String): TraktStoredAuth? {
        val (clientId, clientSecret) = requireCredentials()
        val payload = gson.toJson(
            mapOf(
                "code" to deviceCode,
                "client_id" to clientId,
                "client_secret" to clientSecret
            )
        )
        val result = SimpleHttp.postJson("$API/oauth/device/token", payload)
        when (result.code) {
            400, 429 -> return null
            404 -> error("Trakt sign-in code is invalid. Start the connection again.")
            409 -> error("This Trakt sign-in code was already used. Start again.")
            410 -> error("The Trakt sign-in code expired. Start again.")
            418 -> error("Trakt authorization was denied.")
        }

        val token = gson.fromJson(
            SimpleHttp.requireSuccess(result, "Completing Trakt sign-in"),
            TraktToken::class.java
        )
        return saveToken(clientId, token)
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

    suspend fun personalList(name: String, limit: Int = 80): List<AppMedia> {
        val auth = validAuth() ?: return emptyList()
        val listsResult = SimpleHttp.get("$API/users/me/lists?limit=100", headers(auth))
        if (listsResult.code !in 200..299) return emptyList()

        val lists = JsonParser.parseString(listsResult.body).asJsonArray
        val selected = lists.firstOrNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstOrNull false
            obj.get("name")?.asString?.equals(name, ignoreCase = true) == true
        }?.asJsonObject ?: return emptyList()

        val ids = selected.getAsJsonObject("ids")
        val listId = ids?.get("trakt")?.takeUnless { it.isJsonNull }?.asInt?.toString()
            ?: ids?.get("slug")?.takeUnless { it.isJsonNull }?.asString
            ?: return emptyList()

        val itemsResult = SimpleHttp.get(
            "$API/users/me/lists/$listId/items?extended=full&limit=$limit",
            headers(auth)
        )
        if (itemsResult.code !in 200..299) return emptyList()

        return JsonParser.parseString(itemsResult.body).asJsonArray
            .mapNotNull { parsePersonalListItem(it.asJsonObject, name) }
            .distinctBy { "${it.meta.type}:${it.meta.id}" }
            .take(limit)
    }

    suspend fun upNext(limit: Int = 12): List<PlaybackProgress> {
        val auth = validAuth() ?: return emptyList()
        val watchedResult = SimpleHttp.get("$API/sync/watched/shows?extended=noseasons", headers(auth))
        if (watchedResult.code !in 200..299) return emptyList()

        val watched = JsonParser.parseString(watchedResult.body).asJsonArray
            .mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
            .sortedByDescending {
                parseTraktTime(
                    it.get("last_watched_at")?.takeUnless { v -> v.isJsonNull }?.asString,
                    0L
                )
            }
            .take((limit + 4).coerceAtMost(20))

        return supervisorScope {
            watched.map { entry ->
                async {
                    val show = entry.getAsJsonObject("show") ?: return@async null
                    val ids = show.getAsJsonObject("ids")
                    val traktId = ids?.get("trakt")?.takeUnless { it.isJsonNull }?.asInt ?: return@async null
                    val progressResult = SimpleHttp.get(
                        "$API/shows/$traktId/progress/watched?hidden=false&specials=false&count_specials=false&last_activity=watched",
                        headers(auth)
                    )
                    if (progressResult.code !in 200..299) return@async null
                    val progress = JsonParser.parseString(progressResult.body).asJsonObject
                    val next = progress.getAsJsonObject("next_episode") ?: return@async null

                    val showTitle = show.get("title")?.asString?.takeIf { it.isNotBlank() } ?: return@async null
                    val showImdb = ids.get("imdb")?.takeUnless { it.isJsonNull }?.asString
                    val showId = showImdb ?: "trakt:show:$traktId"
                    val season = next.get("season")?.asInt ?: return@async null
                    val number = next.get("number")?.asInt ?: return@async null
                    val episodeTitle = next.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    val videoId = if (showImdb != null) "$showImdb:$season:$number" else "$showId:$season:$number"
                    val episodeLabel = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"
                    val title = if (episodeTitle.isBlank()) "$showTitle · $episodeLabel" else "$showTitle · $episodeLabel · $episodeTitle"
                    val lastWatchedAt = parseTraktTime(
                        entry.get("last_watched_at")?.takeUnless { it.isJsonNull }?.asString,
                        System.currentTimeMillis()
                    )
                    val year = show.get("year")?.takeUnless { it.isJsonNull }?.asInt
                    val overview = show.get("overview")?.takeUnless { it.isJsonNull }?.asString

                    PlaybackProgress(
                        media = AppMedia(
                            meta = MetaItem(
                                id = showId,
                                type = "series",
                                name = showTitle,
                                description = overview,
                                releaseInfo = year?.toString()
                            ),
                            originAddonName = "Trakt Up Next"
                        ),
                        videoId = videoId,
                        title = title,
                        positionMs = 0L,
                        durationMs = 0L,
                        updatedAtMs = lastWatchedAt,
                        cloudPercent = 0.0,
                        source = "Up Next"
                    )
                }
            }.awaitAll()
                .filterNotNull()
                .sortedByDescending { it.updatedAtMs }
                .take(limit)
        }
    }

    private fun parsePersonalListItem(root: JsonObject, listName: String): AppMedia? {
        root.getAsJsonObject("movie")?.let { movie ->
            val title = movie.get("title")?.asString?.takeIf { it.isNotBlank() } ?: return null
            val ids = movie.getAsJsonObject("ids")
            val imdb = ids?.get("imdb")?.takeUnless { it.isJsonNull }?.asString
            val traktId = ids?.get("trakt")?.takeUnless { it.isJsonNull }?.asInt
            val year = movie.get("year")?.takeUnless { it.isJsonNull }?.asInt
            val overview = movie.get("overview")?.takeUnless { it.isJsonNull }?.asString
            val rating = movie.get("rating")?.takeUnless { it.isJsonNull }?.asDouble
            return AppMedia(
                meta = MetaItem(
                    id = imdb ?: "trakt:movie:${traktId ?: title.hashCode()}",
                    type = "movie",
                    name = title,
                    description = overview,
                    releaseInfo = year?.toString(),
                    imdbRating = rating?.let { String.format("%.1f", it) }
                ),
                originAddonName = "Trakt · $listName"
            )
        }

        root.getAsJsonObject("show")?.let { show ->
            val title = show.get("title")?.asString?.takeIf { it.isNotBlank() } ?: return null
            val ids = show.getAsJsonObject("ids")
            val imdb = ids?.get("imdb")?.takeUnless { it.isJsonNull }?.asString
            val traktId = ids?.get("trakt")?.takeUnless { it.isJsonNull }?.asInt
            val year = show.get("year")?.takeUnless { it.isJsonNull }?.asInt
            val overview = show.get("overview")?.takeUnless { it.isJsonNull }?.asString
            val rating = show.get("rating")?.takeUnless { it.isJsonNull }?.asDouble
            return AppMedia(
                meta = MetaItem(
                    id = imdb ?: "trakt:show:${traktId ?: title.hashCode()}",
                    type = "series",
                    name = title,
                    description = overview,
                    releaseInfo = year?.toString(),
                    imdbRating = rating?.let { String.format("%.1f", it) }
                ),
                originAddonName = "Trakt · $listName"
            )
        }

        return null
    }

    suspend fun watchlist(limit: Int = 80): List<AppMedia> {
        val auth = validAuth() ?: return emptyList()
        val movieResult = SimpleHttp.get("$API/sync/watchlist/movies?extended=full&limit=$limit", headers(auth))
        val showResult = SimpleHttp.get("$API/sync/watchlist/shows?extended=full&limit=$limit", headers(auth))
        val out = mutableListOf<AppMedia>()

        if (movieResult.code in 200..299) {
            out += parseWatchlist(JsonParser.parseString(movieResult.body).asJsonArray, "movie")
        }
        if (showResult.code in 200..299) {
            out += parseWatchlist(JsonParser.parseString(showResult.body).asJsonArray, "series")
        }
        return out.distinctBy { "${it.meta.type}:${it.meta.id}" }.take(limit)
    }

    suspend fun playbackProgress(limit: Int = 60): List<PlaybackProgress> {
        val auth = validAuth() ?: return emptyList()
        val moviesResult = SimpleHttp.get("$API/sync/playback/movies?extended=full", headers(auth))
        val episodesResult = SimpleHttp.get("$API/sync/playback/episodes?extended=full", headers(auth))
        val now = System.currentTimeMillis()
        val out = mutableListOf<PlaybackProgress>()

        if (moviesResult.code in 200..299) {
            val array = JsonParser.parseString(moviesResult.body).asJsonArray
            out += array.mapIndexedNotNull { index, element ->
                parseMoviePlayback(element.asJsonObject, now - index)
            }
        }

        if (episodesResult.code in 200..299) {
            val array = JsonParser.parseString(episodesResult.body).asJsonArray
            out += array.mapIndexedNotNull { index, element ->
                parseEpisodePlayback(element.asJsonObject, now - 10_000L - index)
            }
        }

        return out
            .filter { it.cloudPercent != null && it.cloudPercent >= 1.0 && it.cloudPercent < 95.0 }
            .sortedByDescending { it.updatedAtMs }
            .take(limit)
    }

    private fun parseMoviePlayback(root: JsonObject, fallbackTime: Long): PlaybackProgress? {
        val movie = root.getAsJsonObject("movie") ?: return null
        val title = movie.get("title")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val ids = movie.getAsJsonObject("ids")
        val imdb = ids?.get("imdb")?.takeUnless { it.isJsonNull }?.asString
        val traktId = ids?.get("trakt")?.takeUnless { it.isJsonNull }?.asInt
        val progress = root.get("progress")?.asDouble ?: return null
        val year = movie.get("year")?.takeUnless { it.isJsonNull }?.asInt
        val overview = movie.get("overview")?.takeUnless { it.isJsonNull }?.asString
        val rating = movie.get("rating")?.takeUnless { it.isJsonNull }?.asDouble
        val mediaId = imdb ?: "trakt:movie:${traktId ?: title.hashCode()}"

        val media = AppMedia(
            meta = MetaItem(
                id = mediaId,
                type = "movie",
                name = title,
                description = overview,
                releaseInfo = year?.toString(),
                imdbRating = rating?.let { String.format("%.1f", it) }
            ),
            originAddonName = "Trakt Playback"
        )

        return PlaybackProgress(
            media = media,
            videoId = mediaId,
            title = title,
            positionMs = 0L,
            durationMs = 0L,
            updatedAtMs = parseTraktTime(root.get("paused_at")?.asString, fallbackTime),
            cloudPercent = progress.coerceIn(0.0, 100.0),
            source = "Trakt"
        )
    }

    private fun parseEpisodePlayback(root: JsonObject, fallbackTime: Long): PlaybackProgress? {
        val show = root.getAsJsonObject("show") ?: return null
        val episode = root.getAsJsonObject("episode") ?: return null
        val showTitle = show.get("title")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val showIds = show.getAsJsonObject("ids")
        val showImdb = showIds?.get("imdb")?.takeUnless { it.isJsonNull }?.asString
        val showTrakt = showIds?.get("trakt")?.takeUnless { it.isJsonNull }?.asInt
        val season = episode.get("season")?.asInt ?: return null
        val number = episode.get("number")?.asInt ?: return null
        val episodeTitle = episode.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val progress = root.get("progress")?.asDouble ?: return null
        val year = show.get("year")?.takeUnless { it.isJsonNull }?.asInt
        val overview = show.get("overview")?.takeUnless { it.isJsonNull }?.asString
        val showId = showImdb ?: "trakt:show:${showTrakt ?: showTitle.hashCode()}"
        val videoId = if (showImdb != null) "$showImdb:$season:$number" else "$showId:$season:$number"
        val episodeLabel = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"
        val title = if (episodeTitle.isBlank()) "$showTitle · $episodeLabel" else "$showTitle · $episodeLabel · $episodeTitle"

        val media = AppMedia(
            meta = MetaItem(
                id = showId,
                type = "series",
                name = showTitle,
                description = overview,
                releaseInfo = year?.toString()
            ),
            originAddonName = "Trakt Playback"
        )

        return PlaybackProgress(
            media = media,
            videoId = videoId,
            title = title,
            positionMs = 0L,
            durationMs = 0L,
            updatedAtMs = parseTraktTime(root.get("paused_at")?.asString, fallbackTime),
            cloudPercent = progress.coerceIn(0.0, 100.0),
            source = "Trakt"
        )
    }

    private fun parseTraktTime(value: String?, fallback: Long): Long {
        if (value.isNullOrBlank()) return fallback
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return fallback
    }

    suspend fun scrobble(action: String, media: AppMedia, videoId: String, progressPercent: Double) {
        val auth = validAuth() ?: return
        val imdb = Regex("tt\\d{5,10}").find(videoId)?.value
            ?: Regex("tt\\d{5,10}").find(media.meta.id)?.value
            ?: return
        val percent = progressPercent.coerceIn(0.0, 100.0)
        val payload = JsonObject().apply {
            addProperty("progress", percent)
            if (media.meta.type == "series" && videoId.contains(':')) {
                val parts = videoId.split(':')
                val season = parts.getOrNull(parts.size - 2)?.toIntOrNull()
                val episode = parts.lastOrNull()?.toIntOrNull()
                if (season != null && episode != null) {
                    add("show", JsonObject().apply {
                        add("ids", JsonObject().apply { addProperty("imdb", imdb) })
                    })
                    add("episode", JsonObject().apply {
                        addProperty("season", season)
                        addProperty("number", episode)
                    })
                } else {
                    add("movie", JsonObject().apply {
                        add("ids", JsonObject().apply { addProperty("imdb", imdb) })
                    })
                }
            } else {
                add("movie", JsonObject().apply {
                    add("ids", JsonObject().apply { addProperty("imdb", imdb) })
                })
            }
        }
        SimpleHttp.postJson("$API/scrobble/$action", gson.toJson(payload), headers(auth))
    }

    fun disconnect() = secureStore.remove(AUTH_KEY)

    private fun requireCredentials(): Pair<String, String> {
        val clientId = secureStore.get(CLIENT_ID_KEY)?.takeIf { it.isNotBlank() }
            ?: error("Add your Trakt Client ID in Settings first")
        val clientSecret = secureStore.get(CLIENT_SECRET_KEY)?.takeIf { it.isNotBlank() }
            ?: error("Add your Trakt Client Secret in Settings first")
        return clientId to clientSecret
    }

    private fun loadAuth(): TraktStoredAuth? {
        val json = secureStore.get(AUTH_KEY) ?: return null
        return runCatching { gson.fromJson(json, TraktStoredAuth::class.java) }.getOrNull()
    }

    private suspend fun validAuth(): TraktStoredAuth? = authMutex.withLock {
        val auth = loadAuth() ?: return@withLock null
        if (auth.expiresAtEpochMs > System.currentTimeMillis() + REFRESH_AHEAD_MS) {
            return@withLock auth
        }
        refresh(auth)
    }

    private suspend fun refresh(auth: TraktStoredAuth): TraktStoredAuth? {
        val clientSecret = secureStore.get(CLIENT_SECRET_KEY)?.takeIf { it.isNotBlank() } ?: return null
        if (auth.refreshToken.isBlank()) return null
        val payload = gson.toJson(
            mapOf(
                "refresh_token" to auth.refreshToken,
                "client_id" to auth.clientId,
                "client_secret" to clientSecret,
                "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob",
                "grant_type" to "refresh_token"
            )
        )
        val result = SimpleHttp.postJson("$TOKEN_API/oauth/token", payload)
        if (result.code !in 200..299) {
            if (result.code == 400 || result.code == 401) disconnect()
            return null
        }
        val token = gson.fromJson(result.body, TraktToken::class.java)
        return saveToken(auth.clientId, token)
    }

    private fun saveToken(clientId: String, token: TraktToken): TraktStoredAuth {
        require(token.accessToken.isNotBlank()) { "Trakt returned an empty access token" }
        val stored = TraktStoredAuth(
            clientId = clientId,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtEpochMs = System.currentTimeMillis() + token.expiresIn * 1000L
        )
        secureStore.put(AUTH_KEY, gson.toJson(stored))
        return stored
    }

    private fun parseWatchlist(array: JsonArray, mediaType: String): List<AppMedia> =
        array.mapNotNull { element ->
            val root = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val objName = if (mediaType == "movie") "movie" else "show"
            val media = root.getAsJsonObject(objName) ?: return@mapNotNull null
            val title = media.get("title")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val year = media.get("year")?.takeUnless { it.isJsonNull }?.asInt
            val ids = media.getAsJsonObject("ids")
            val imdb = ids?.get("imdb")?.takeUnless { it.isJsonNull }?.asString
            val traktId = ids?.get("trakt")?.takeUnless { it.isJsonNull }?.asInt
            val overview = media.get("overview")?.takeUnless { it.isJsonNull }?.asString
            val rating = media.get("rating")?.takeUnless { it.isJsonNull }?.asDouble
            AppMedia(
                meta = MetaItem(
                    id = imdb ?: "trakt:${traktId ?: title.hashCode()}",
                    type = mediaType,
                    name = title,
                    description = overview,
                    releaseInfo = year?.toString(),
                    imdbRating = rating?.let { String.format("%.1f", it) }
                ),
                originAddonName = "Trakt Watchlist"
            )
        }

    private fun headers(auth: TraktStoredAuth): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${auth.accessToken}",
        "trakt-api-version" to API_VERSION,
        "trakt-api-key" to auth.clientId
    )
}
