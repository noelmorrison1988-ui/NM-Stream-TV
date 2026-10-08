package za.co.nm.streamtv

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val loading: Boolean = true,
    val addons: List<InstalledAddon> = emptyList(),
    val pendingAddonHosts: List<String> = emptyList(),
    val addonInstalling: Boolean = false,
    val addonInstallStatus: String? = null,
    val movies: List<AppMedia> = emptyList(),
    val series: List<AppMedia> = emptyList(),
    val noelList: List<AppMedia> = emptyList(),
    val sarahList: List<AppMedia> = emptyList(),
    val debridItems: List<AppMedia> = emptyList(),
    val continueWatching: List<PlaybackProgress> = emptyList(),
    val watchHistory: List<PlaybackProgress> = emptyList(),
    val myList: List<AppMedia> = emptyList(),
    val newMovies: List<AppMedia> = emptyList(),
    val trendingMovies: List<AppMedia> = emptyList(),
    val newSeries: List<AppMedia> = emptyList(),
    val trendingSeries: List<AppMedia> = emptyList(),
    val nowAiringSeries: List<AppMedia> = emptyList(),
    val expandedRowKey: String? = null,
    val expandedRowPage: Int = 1,
    val expandedRowItems: List<AppMedia> = emptyList(),
    val expandedContinueItems: List<PlaybackProgress> = emptyList(),
    val expandedRowLoading: Boolean = false,
    val expandedRowHasNext: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val searchResults: List<AppMedia> = emptyList(),
    val searchLoading: Boolean = false,
    val selectedMedia: AppMedia? = null,
    val detailsLoading: Boolean = false,
    val streamOptions: List<StreamOption> = emptyList(),
    val subtitleOptions: List<SubtitleOption> = emptyList(),
    val streamsLoading: Boolean = false,
    val sourceRequestKey: String? = null,
    val rdUser: RdUser? = null,
    val rdDeviceCode: RdDeviceCode? = null,
    val rdConnecting: Boolean = false,
    val tmdbConfigured: Boolean = false,
    val tmdbStatus: String = "Not configured",
    val traktConfigured: Boolean = false,
    val traktConnected: Boolean = false,
    val traktUser: TraktUser? = null,
    val traktWatchlist: List<AppMedia> = emptyList(),
    val traktDeviceCode: TraktDeviceCode? = null,
    val traktConnecting: Boolean = false,
    val iptvChannels: List<AppMedia> = emptyList(),
    val iptvSports: List<AppMedia> = emptyList(),
    val sportsCatalog: List<AppMedia> = emptyList(),
    val xtreamMovies: List<AppMedia> = emptyList(),
    val xtreamSeries: List<AppMedia> = emptyList(),
    val iptvCategories: List<LiveTvCategory> = emptyList(),
    val iptvGuide: Map<String, List<EpgProgramme>> = emptyMap(),
    val iptvConfigured: Boolean = false,
    val iptvStatus: String = "Not configured",
    val nmAccountLinked: Boolean = false,
    val nmAccountName: String? = null,
    val nmPairCode: String? = null,
    val nmPairing: Boolean = false,
    val nmSyncStatus: String = "Not linked",
    val nmDeviceBlocked: Boolean = false,
    val preferredQuality: Int = 720,
    val preferHttpDebrid: Boolean = true,
    val preferredAudioLanguage: String = "en",
    val preferredSubtitleLanguage: String = "en",
    val message: String? = null,
    val connectionLog: List<ConnectionLogEntry> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val addons = AddonRepository(application)
    private val realDebrid = RealDebridRepository(application)
    private val tmdb = TmdbRepository(application)
    private val playback = PlaybackStore(application)
    private val myListStore = MyListStore(application)
    private val searchHistoryStore = SearchHistoryStore(application)
    private val nmAccount = NmAccountRepository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val connectionMonitor = ConnectionMonitor(application) { event ->
        val current = _uiState.value
        _uiState.value = current.copy(connectionLog = (current.connectionLog + event).takeLast(200))
    }

    fun setPlaybackMonitoringActive(active: Boolean) = connectionMonitor.setPlaybackActive(active)
    fun reportPlaybackBuffer(bufferedMs: Long, buffering: Boolean) =
        connectionMonitor.updatePlayerBuffer(bufferedMs, buffering)

    private var rdAuthJob: Job? = null
    private var nmPairJob: Job? = null
    private var lastRdAuthFingerprint: Int? = null
    private var lastAddonRetryAtMs: Long = 0L

    init {
        refreshEverything()
        viewModelScope.launch(Dispatchers.Default) { connectionMonitor.run() }
    }

    fun refreshEverything() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, message = null)
            val installed = runCatching { addons.loadInstalled() }.getOrElse {
                _uiState.value = _uiState.value.copy(message = it.message)
                emptyList()
            }

            val pendingAddonHosts = addons.storedManifestUrls()
                .filterNot { url -> installed.any { it.manifestUrl == url } }
                .mapNotNull { url -> runCatching { java.net.URI(url).host }.getOrNull() }
                .distinct()

            // Lite build: only load add-on catalogues and Real-Debrid at startup.
            // IPTV/Xtream, EPG and Trakt are deliberately excluded from the runtime path.
            val homeDeferred = async {
                runCatching { addons.loadHome(installed) }
                    .getOrDefault(emptyList<AppMedia>() to emptyList())
            }
            val rdUserDeferred = async { runCatching { realDebrid.getUser() }.getOrNull() }
            val discoveryDeferred = async {
                if (tmdb.configured()) {
                    runCatching { tmdb.discoveryRows(18) }.getOrDefault(TmdbDiscoveryRows())
                } else TmdbDiscoveryRows()
            }

            val (rawMovies, rawSeries) = homeDeferred.await()
            val movies = MediaPolicy.filter(
                if (tmdb.configured()) runCatching { tmdb.enrichBatch(rawMovies, 24) }.getOrDefault(rawMovies)
                else rawMovies
            )
            val series = MediaPolicy.filter(
                if (tmdb.configured()) runCatching { tmdb.enrichBatch(rawSeries, 24) }.getOrDefault(rawSeries)
                else rawSeries
            )

            val rdUser = rdUserDeferred.await()
            val rdItems = if (rdUser != null) {
                MediaPolicy.filter(
                    runCatching { realDebrid.recentDownloads() }
                        .getOrDefault(emptyList())
                        .mapNotNull { it.toAppMedia() }
                )
            } else emptyList()

            val rawHistory = playback.history().filter { MediaPolicy.allows(it.media) }
            val watchHistory = if (tmdb.configured()) {
                enrichProgress(rawHistory, 15)
            } else rawHistory

            val discovery = discoveryDeferred.await()
            val nmPrefs = nmAccount.playbackPreferences()

            _uiState.value = _uiState.value.copy(
                loading = false,
                addons = installed,
                pendingAddonHosts = pendingAddonHosts,
                movies = movies,
                series = series,
                noelList = emptyList(),
                sarahList = emptyList(),
                rdUser = rdUser,
                debridItems = rdItems,
                continueWatching = playback.load().filter { MediaPolicy.allows(it.media) }.take(30),
                watchHistory = watchHistory,
                myList = myListStore.load(),
                newMovies = discovery.newMovies,
                trendingMovies = discovery.trendingMovies,
                newSeries = discovery.newSeries,
                trendingSeries = discovery.trendingSeries,
                nowAiringSeries = discovery.nowAiringSeries,
                recentSearches = searchHistoryStore.load(),
                rdDeviceCode = null,
                rdConnecting = false,
                tmdbConfigured = tmdb.configured(),
                tmdbStatus = tmdb.maskedToken(),
                traktConfigured = false,
                traktConnected = false,
                traktUser = null,
                traktWatchlist = emptyList(),
                traktDeviceCode = null,
                traktConnecting = false,
                iptvChannels = emptyList(),
                iptvSports = emptyList(),
                sportsCatalog = emptyList(),
                xtreamMovies = emptyList(),
                xtreamSeries = emptyList(),
                iptvCategories = emptyList(),
                iptvGuide = emptyMap(),
                iptvConfigured = false,
                iptvStatus = "Not available in Lite",
                nmAccountLinked = nmAccount.isLinked(),
                nmAccountName = nmAccount.accountName(),
                nmSyncStatus = if (nmAccount.isLinked()) _uiState.value.nmSyncStatus else "Not linked",
                preferredQuality = nmPrefs.preferredQuality,
                preferHttpDebrid = nmPrefs.preferHttpDebrid,
                preferredAudioLanguage = nmPrefs.preferredAudioLanguage,
                preferredSubtitleLanguage = nmPrefs.subtitleLanguage
            )
        }
    }

    fun loadExpandedRow(key: String, page: Int) {
        viewModelScope.launch {
            val safePage = page.coerceAtLeast(1)
            val pageSize = 20
            _uiState.value = _uiState.value.copy(
                expandedRowKey = key,
                expandedRowPage = safePage,
                expandedRowItems = emptyList(),
                expandedContinueItems = emptyList(),
                expandedRowLoading = true,
                expandedRowHasNext = false
            )

            if (key == "continue_watching") {
                val source = _uiState.value.continueWatching
                val start = (safePage - 1) * pageSize
                val pageItems = source.drop(start).take(pageSize)
                _uiState.value = _uiState.value.copy(
                    expandedContinueItems = pageItems,
                    expandedRowLoading = false,
                    expandedRowHasNext = start + pageItems.size < source.size
                )
                return@launch
            }

            val tmdbKeys = setOf(
                "trending_movies",
                "new_movies",
                "trending_series",
                "new_series",
                "now_airing_series"
            )

            if (key in tmdbKeys) {
                val pageResult = runCatching {
                    tmdb.browseCollection(key, safePage, pageSize)
                }.getOrDefault(TmdbBrowsePage())
                _uiState.value = _uiState.value.copy(
                    expandedRowItems = pageResult.items,
                    expandedRowLoading = false,
                    expandedRowHasNext = pageResult.hasNext
                )
                return@launch
            }

            val source = when (key) {
                "watch_history" -> _uiState.value.watchHistory.map { it.media }.distinctBy { it.meta.id }
                "movies" -> _uiState.value.movies
                "series" -> _uiState.value.series
                "real_debrid" -> _uiState.value.debridItems
                "my_list" -> _uiState.value.myList
                else -> emptyList()
            }
            val start = (safePage - 1) * pageSize
            val pageItems = source.drop(start).take(pageSize)
            _uiState.value = _uiState.value.copy(
                expandedRowItems = pageItems,
                expandedRowLoading = false,
                expandedRowHasNext = start + pageItems.size < source.size
            )
        }
    }
    fun installAddon(url: String) {
        viewModelScope.launch {
            if (url.isBlank()) {
                _uiState.value = _uiState.value.copy(addonInstallStatus = "Enter a manifest URL first")
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                addonInstalling = true,
                addonInstallStatus = "Checking manifest…"
            )
            runCatching { addons.install(url) }
                .onSuccess { installed ->
                    _uiState.value = _uiState.value.copy(
                        addonInstalling = false,
                        addonInstallStatus = "Installed ${installed.manifest.name}",
                        addons = (_uiState.value.addons + installed).distinctBy { it.manifestUrl }
                    )
                    if (nmAccount.isLinked()) nmAccount.markAddonsPending()
                    refreshEverything()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        addonInstalling = false,
                        addonInstallStatus = error.message ?: "Could not install add-on"
                    )
                }
        }
    }

    fun removeAddon(manifestUrl: String) {
        addons.remove(manifestUrl)
        _uiState.value = _uiState.value.copy(addonInstallStatus = "Add-on removed")
        viewModelScope.launch {
            if (nmAccount.isLinked()) nmAccount.markAddonsPending()
            refreshEverything()
        }
    }

    fun search(query: String, category: SearchCategory = SearchCategory.MOVIE) {
        viewModelScope.launch {
            val submittedQuery = query.trim()
            if (submittedQuery.isBlank()) {
                _uiState.value = _uiState.value.copy(searchResults = emptyList())
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                searchLoading = true,
                recentSearches = searchHistoryStore.record(submittedQuery),
                message = null
            )

            val results = when (category) {
                SearchCategory.PERSON -> {
                    if (tmdb.configured()) {
                        runCatching { tmdb.searchPersonCredits(submittedQuery, 60) }
                            .getOrDefault(emptyList())
                            .filter(MediaPolicy::allows)
                            .distinctBy { mediaSearchKey(it) }
                            .take(80)
                    } else emptyList()
                }

                SearchCategory.MOVIE,
                SearchCategory.SERIES -> {
                    val wantedType = if (category == SearchCategory.MOVIE) "movie" else "series"
                    val raw = runCatching {
                        addons.search(_uiState.value.addons, submittedQuery)
                    }.getOrDefault(emptyList())
                        .filter { it.meta.type == wantedType }

                    MediaPolicy.filter(
                        if (tmdb.configured()) {
                            runCatching { tmdb.enrichBatch(raw, 50) }.getOrDefault(raw)
                        } else raw
                    )
                        .distinctBy { mediaSearchKey(it) }
                        .take(80)
                }
            }

            _uiState.value = _uiState.value.copy(
                searchLoading = false,
                searchResults = results
            )
        }
    }

    fun toggleMyList(item: AppMedia) {
        if (item.meta.type != "movie" && item.meta.type != "series") return
        val added = myListStore.toggle(item)
        _uiState.value = _uiState.value.copy(
            myList = myListStore.load(),
            message = if (added) "Added ${item.meta.name} to My List" else "Removed ${item.meta.name} from My List"
        )
    }

    fun isInMyList(item: AppMedia): Boolean = myListStore.contains(item)
    fun loadDetails(item: AppMedia) {
        viewModelScope.launch {
            if (!MediaPolicy.allows(item)) {
                _uiState.value = _uiState.value.copy(
                    selectedMedia = null,
                    detailsLoading = false,
                    message = "Anime content is blocked by NM Stream TV"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(selectedMedia = item, detailsLoading = true, message = null)
            val resolvedItem = if (item.meta.id.startsWith("tmdb:")) {
                val matches = runCatching {
                    addons.search(_uiState.value.addons, item.meta.name)
                }.getOrDefault(emptyList())
                    .filter { it.meta.type == item.meta.type }

                val wanted = mediaTitleKey(item.meta.name)
                matches.firstOrNull { mediaTitleKey(it.meta.name) == wanted }
                    ?: matches.firstOrNull()
                    ?: item
            } else item

            val sourceMeta = runCatching {
                addons.loadMeta(resolvedItem, _uiState.value.addons)
            }.getOrDefault(resolvedItem)
            val loaded = if (tmdb.configured()) {
                runCatching { tmdb.enrich(sourceMeta) }.getOrDefault(sourceMeta)
            } else sourceMeta
            if (!MediaPolicy.allows(loaded)) {
                _uiState.value = _uiState.value.copy(
                    selectedMedia = null,
                    detailsLoading = false,
                    message = "Anime content is blocked by NM Stream TV"
                )
            } else {
                _uiState.value = _uiState.value.copy(selectedMedia = loaded, detailsLoading = false)
            }
        }
    }

    fun loadSources(item: AppMedia, videoId: String = item.meta.id) {
        val requestKey = sourceRequestKey(item, videoId)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                streamOptions = emptyList(),
                subtitleOptions = emptyList(),
                streamsLoading = true,
                sourceRequestKey = requestKey,
                message = null
            )
            val streamsDeferred = async {
                if (!item.directUrl.isNullOrBlank()) {
                    listOf(
                        StreamOption(
                            addonName = item.originAddonName ?: "Direct source",
                            stream = AddonStream(
                                name = item.meta.name,
                                title = item.meta.name,
                                url = item.directUrl
                            )
                        )
                    )
                } else {
                    runCatching {
                        addons.loadStreams(_uiState.value.addons, item.meta.type, videoId)
                    }.getOrDefault(emptyList())
                }
            }
            val subtitlesDeferred = async {
                if (item.meta.type == "rd") {
                    emptyList()
                } else {
                    runCatching {
                        addons.loadSubtitles(_uiState.value.addons, item.meta.type, videoId)
                    }.getOrDefault(emptyList())
                }
            }
            val prefs = nmAccount.playbackPreferences()
            val loadedStreams = streamsDeferred.await()

            val rdHashesToVerify = loadedStreams
                .filter { option ->
                    option.isRealDebrid && !option.stream.infoHash.isNullOrBlank()
                }
                .mapNotNull { it.stream.infoHash?.lowercase() }
                .distinct()

            val instantlyAvailableRdHashes = if (rdHashesToVerify.isNotEmpty()) {
                runCatching {
                    realDebrid.instantlyAvailableHashes(rdHashesToVerify)
                }.getOrNull()
            } else {
                null
            }

            val sortedStreams = loadedStreams
                .filterNot { it.isKnownUncached }
                .filter { option ->
                    val hash = option.stream.infoHash?.lowercase()
                    hash == null ||
                        !option.isRealDebrid ||
                        instantlyAvailableRdHashes == null ||
                        hash in instantlyAvailableRdHashes
                }
                .filter { option ->
                    val quality = option.detectedQuality
                    quality == null || quality <= 1080
                }
                .sortedWith(
                    compareBy<StreamOption> { option ->
                        when (option.detectedQuality) {
                            720 -> 0
                            1080 -> 1
                            576 -> 2
                            480 -> 3
                            360 -> 4
                            null -> 5
                            else -> 6
                        }
                    }.thenBy { option ->
                        val hash = option.stream.infoHash?.lowercase()
                        when {
                            hash != null && instantlyAvailableRdHashes != null &&
                                hash in instantlyAvailableRdHashes -> 0
                            option.isExplicitlyCached -> 1
                            option.isDebrid -> 2
                            else -> 3
                        }
                    }.thenBy { option ->
                        when {
                            option.playableUrl != null -> 0
                            option.isP2p -> 1
                            else -> 2
                        }
                    }.thenByDescending { it.stream.behaviorHints?.videoSize ?: 0L }
                )

            val sortedSubtitles = subtitlesDeferred.await()
                .sortedBy { option ->
                    if (option.subtitle.lang.startsWith(prefs.subtitleLanguage, true)) 0 else 1
                }

            _uiState.value = _uiState.value.copy(
                streamOptions = sortedStreams,
                subtitleOptions = sortedSubtitles,
                streamsLoading = false
            )
        }
    }

    fun sourceRequestKey(item: AppMedia, videoId: String): String =
        "${item.meta.id}|${videoId}"

    fun bestTrailer(item: AppMedia): StreamOption? =
        item.meta.trailers
            .mapNotNull { trailer ->
                val youtubeId = extractYoutubeId(trailer.ytId)
                    ?: extractYoutubeId(trailer.source)
                    ?: extractYoutubeId(trailer.url)
                val directUrl = trailer.url
                    ?.takeUnless { extractYoutubeId(it) != null }

                if (directUrl.isNullOrBlank() && youtubeId.isNullOrBlank()) return@mapNotNull null

                val qualityText = trailer.quality?.let { " · ${it}p" }.orEmpty()
                StreamOption(
                    addonName = "Trailer",
                    stream = AddonStream(
                        name = trailer.type ?: "Trailer",
                        title = (trailer.title ?: trailer.name ?: item.meta.name + " Trailer") + qualityText,
                        url = directUrl,
                        ytId = youtubeId
                    )
                )
            }
            .sortedWith(
                compareBy<StreamOption> {
                    when (it.detectedQuality) {
                        1080 -> 0
                        2160 -> 1
                        720 -> 2
                        480 -> 3
                        360 -> 4
                        null -> 6
                        else -> 5
                    }
                }.thenBy { it.preferenceScore(preferredQuality = 1080) }
            )
            .firstOrNull()

    private fun extractYoutubeId(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null

        Regex("""(?:youtube\.com/(?:watch\?v=|embed/|shorts/)|youtu\.be/)([A-Za-z0-9_-]{6,})""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        return raw
            .removePrefix("youtube:")
            .removePrefix("yt:")
            .takeIf { candidate ->
                candidate.matches(Regex("""[A-Za-z0-9_-]{6,}"""))
            }
    }
    fun saveTmdbToken(token: String) {
        tmdb.saveToken(token)
        _uiState.value = _uiState.value.copy(
            tmdbConfigured = tmdb.configured(),
            tmdbStatus = tmdb.maskedToken(),
            message = if (token.isBlank()) "TMDB token removed" else "TMDB metadata enabled"
        )
        refreshEverything()
    }

    fun beginRealDebridSignIn() {
        rdAuthJob?.cancel()
        rdAuthJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(rdConnecting = true, rdDeviceCode = null, message = null)
            val device = runCatching { realDebrid.startDeviceAuth() }.getOrElse {
                _uiState.value = _uiState.value.copy(rdConnecting = false, message = it.message)
                return@launch
            }
            _uiState.value = _uiState.value.copy(rdDeviceCode = device, rdConnecting = true)

            val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
            while (System.currentTimeMillis() < deadline) {
                val credentials = runCatching { realDebrid.pollBoundCredentials(device.deviceCode) }.getOrNull()
                if (credentials != null) {
                    val success = runCatching { realDebrid.exchangeDeviceCode(device.deviceCode, credentials); true }.getOrDefault(false)
                    if (success) {
                        if (nmAccount.isLinked()) nmAccount.markRealDebridPending()
                        lastRdAuthFingerprint = realDebrid.exportAuth()?.hashCode() ?: 0
                        _uiState.value = _uiState.value.copy(rdConnecting = false, rdDeviceCode = null, message = "Real-Debrid connected")
                        refreshEverything()
                        return@launch
                    }
                }
                delay(device.interval.coerceAtLeast(5) * 1000L)
            }
            _uiState.value = _uiState.value.copy(rdConnecting = false, rdDeviceCode = null, message = "Real-Debrid sign-in code expired")
        }
    }

    fun disconnectRealDebrid() {
        rdAuthJob?.cancel()
        realDebrid.disconnect()
        lastRdAuthFingerprint = 0
        if (nmAccount.isLinked()) nmAccount.markRealDebridPending()
        _uiState.value = _uiState.value.copy(
            rdUser = null,
            rdDeviceCode = null,
            rdConnecting = false,
            debridItems = emptyList(),
            message = "Real-Debrid disconnected locally. Press Sync now to update linked devices."
        )
    }

    fun savePlaybackPreferences(
        preferredQuality: Int,
        preferHttpDebrid: Boolean,
        audioLanguage: String,
        subtitleLanguage: String
    ) {
        val prefs = NmPlaybackPreferences(
            preferredQuality = preferredQuality,
            preferHttpDebrid = preferHttpDebrid,
            preferredAudioLanguage = audioLanguage.trim().ifBlank { "en" },
            subtitleLanguage = subtitleLanguage.trim().ifBlank { "en" }
        )
        nmAccount.savePlaybackPreferences(prefs)
        _uiState.value = _uiState.value.copy(
            preferredQuality = prefs.preferredQuality,
            preferHttpDebrid = prefs.preferHttpDebrid,
            preferredAudioLanguage = prefs.preferredAudioLanguage,
            preferredSubtitleLanguage = prefs.subtitleLanguage,
            message = "Playback language and source preferences saved"
        )
        if (nmAccount.isLinked()) nmAccount.markPreferencesPending()
    }

    fun saveMobileLiteLanguagePreferences(
        audioLanguage: String,
        subtitleLanguage: String
    ) {
        val existing = nmAccount.playbackPreferences()
        val prefs = existing.copy(
            preferredAudioLanguage = audioLanguage.trim().ifBlank { "en" },
            subtitleLanguage = subtitleLanguage.trim().ifBlank { "en" }
        )
        nmAccount.savePlaybackPreferences(prefs)
        _uiState.value = _uiState.value.copy(
            preferredAudioLanguage = prefs.preferredAudioLanguage,
            preferredSubtitleLanguage = prefs.subtitleLanguage,
            message = "Language preferences saved"
        )
        if (nmAccount.isLinked()) nmAccount.markPreferencesPending()
    }
    fun beginNmAccountPairing() {
        nmPairJob?.cancel()
        nmPairJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                nmPairing = true,
                nmPairCode = null,
                nmSyncStatus = "Creating pairing code…",
                message = null
            )

            val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "NM Stream TV" }

            val pair = runCatching {
                nmAccount.startPairing(deviceName, BuildConfig.VERSION_NAME)
            }.getOrElse { error ->
                _uiState.value = _uiState.value.copy(
                    nmPairing = false,
                    nmPairCode = null,
                    nmSyncStatus = "Pairing failed",
                    message = error.message ?: "Could not create NM Account pairing code"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                nmPairCode = pair.code,
                nmPairing = true,
                nmSyncStatus = "Waiting for phone"
            )

            val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(3_000)
                val result = runCatching { nmAccount.pollPairing(pair) }
                val status = result.getOrNull()
                if (status?.linked == true) {
                    runCatching {
                        nmAccount.saveLinked(status)
                        nmAccount.bootstrap(
                            addons.storedManifestUrls(),
                            nmAccount.playbackPreferences()
                        )
                        nmAccount.pushRealDebridAuth(realDebrid.exportAuth())
                        lastRdAuthFingerprint = realDebrid.exportAuth()?.hashCode() ?: 0
                        applyNmAccountSync(force = true)
                        nmAccount.clearAllPending()
                    }.onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            message = error.message ?: "NM Account linked, but initial sync failed"
                        )
                    }

                    _uiState.value = _uiState.value.copy(
                        nmAccountLinked = true,
                        nmAccountName = nmAccount.accountName(),
                        nmPairCode = null,
                        nmPairing = false,
                        nmSyncStatus = "Synced",
                        nmDeviceBlocked = false,
                        message = "NM Account linked to this TV"
                    )
                    refreshEverything()
                    return@launch
                }

                val error = result.exceptionOrNull()
                if (error != null && error.message?.contains("410") == true) break
            }

            _uiState.value = _uiState.value.copy(
                nmPairCode = null,
                nmPairing = false,
                nmSyncStatus = "Pairing code expired",
                message = "NM Account pairing code expired. Try again."
            )
        }
    }

    fun syncNmAccountNow() {
        viewModelScope.launch {
            if (!nmAccount.isLinked()) {
                _uiState.value = _uiState.value.copy(message = "Link this TV to NM Account first")
                return@launch
            }
            _uiState.value = _uiState.value.copy(nmSyncStatus = "Syncing…")
            // Push only locally edited categories, then pull all cloud settings.
            runCatching {
                pushPendingManualChanges()
                applyNmAccountSync(force = true)
            }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        nmSyncStatus = "Synced",
                        nmDeviceBlocked = false,
                        message = "Manual sync complete: local changes pushed and cloud settings refreshed"
                    )
                }
                .onFailure { error ->
                    _uiState.value = when (error) {
                        is NmDeviceBlockedException -> _uiState.value.copy(
                            nmSyncStatus = "Blocked",
                            nmDeviceBlocked = true,
                            message = "Device Blocked by NM"
                        )
                        is NmDeviceUnpairedException -> _uiState.value.copy(
                            nmAccountLinked = false,
                            nmAccountName = null,
                            nmSyncStatus = "Not linked",
                            nmDeviceBlocked = false,
                            message = "This device was unpaired from NM Account"
                        )
                        else -> _uiState.value.copy(
                            nmSyncStatus = "Sync failed",
                            message = error.message ?: "NM Account sync failed"
                        )
                    }
                }
        }
    }

    fun unlinkNmAccount() {
        nmPairJob?.cancel()
        nmAccount.unlink()
        _uiState.value = _uiState.value.copy(
            nmAccountLinked = false,
            nmAccountName = null,
            nmPairCode = null,
            nmPairing = false,
            nmSyncStatus = "Not linked",
            nmDeviceBlocked = false,
            message = "This TV was unlinked from NM Account"
        )
    }

    private fun startNmSyncLoop() {
        nmSyncJob?.cancel()
        nmSyncJob = viewModelScope.launch {
            while (true) {
                if (nmAccount.isLinked()) {
                    runCatching {
                        applyNmAccountSync(force = false)
                        pushChangedServiceAuthIfNeeded()
                    }.onFailure { error ->
                        _uiState.value = when (error) {
                            is NmDeviceBlockedException -> _uiState.value.copy(
                                nmSyncStatus = "Blocked",
                                nmDeviceBlocked = true,
                                message = "Device Blocked by NM"
                            )
                            is NmDeviceUnpairedException -> _uiState.value.copy(
                                nmAccountLinked = false,
                                nmAccountName = null,
                                nmSyncStatus = "Not linked",
                                nmDeviceBlocked = false,
                                message = "This device was unpaired from NM Account"
                            )
                            else -> _uiState.value.copy(nmSyncStatus = "Waiting to sync")
                        }
                    }
                }
                delay(60_000)
            }
        }
    }

    private suspend fun applyNmAccountSync(force: Boolean): Boolean {
        if (!nmAccount.isLinked()) return false
        val remote = nmAccount.fetchState()
        val currentVersion = nmAccount.lastAppliedVersion()
        val changed = force || remote.settings.settingsVersion > currentVersion

        if (changed) {
            nmAccount.applyLocalPreferences(remote)
            addons.syncManifestUrls(remote.settings.addonManifests)

            if (remote.settings.realDebridAuthInitialized) {
                realDebrid.importAuth(remote.settings.realDebridAuth)
                lastRdAuthFingerprint = realDebrid.exportAuth()?.hashCode() ?: 0
            } else if (realDebrid.exportAuth() != null) {
                nmAccount.pushRealDebridAuth(realDebrid.exportAuth())
                lastRdAuthFingerprint = realDebrid.exportAuth()?.hashCode() ?: 0
            }

            val prefs = nmAccount.playbackPreferences()
            _uiState.value = _uiState.value.copy(
                nmAccountLinked = true,
                nmAccountName = remote.accountName,
                nmSyncStatus = "Synced",
                nmDeviceBlocked = false,
                preferredQuality = prefs.preferredQuality,
                preferHttpDebrid = prefs.preferHttpDebrid,
                preferredAudioLanguage = prefs.preferredAudioLanguage,
                preferredSubtitleLanguage = prefs.subtitleLanguage
            )
            refreshEverything()
        } else {
            _uiState.value = _uiState.value.copy(
                nmAccountLinked = true,
                nmAccountName = remote.accountName,
                nmSyncStatus = "Synced",
                nmDeviceBlocked = false
            )
            // Retry unresolved manifests without requiring a new cloud settings version.
            if (addons.storedManifestUrls().size > _uiState.value.addons.size) {
                val now = System.currentTimeMillis()
                if (now - lastAddonRetryAtMs >= 60_000L) {
                    lastAddonRetryAtMs = now
                    refreshEverything()
                }
            }
        }
        return changed
    }

    private suspend fun pushChangedServiceAuthIfNeeded() {
        if (!nmAccount.isLinked()) return

        val rdAuth = realDebrid.exportAuth()
        val rdFingerprint = rdAuth?.hashCode() ?: 0
        if (lastRdAuthFingerprint == null) {
            lastRdAuthFingerprint = rdFingerprint
        } else if (rdFingerprint != lastRdAuthFingerprint) {
            nmAccount.pushRealDebridAuth(rdAuth)
            lastRdAuthFingerprint = rdFingerprint
        }
    }

    fun resumePosition(item: AppMedia, videoId: String): Long = playback.resumePosition(item, videoId)

    fun resumeCloudPercent(item: AppMedia, videoId: String): Double? = null

    fun lastPlaybackSession(item: AppMedia, videoId: String): LastPlaybackSession? =
        playback.lastSession(item, videoId)

    fun forgetLastPlaybackSession(item: AppMedia, videoId: String) {
        playback.clearLastSession(item, videoId)
    }

    fun onPlaybackStarted(
        item: AppMedia,
        videoId: String,
        title: String,
        source: StreamOption,
        subtitles: List<SubtitleOption>,
        positionMs: Long,
        durationMs: Long
    ) {
        playback.saveLastSession(
            LastPlaybackSession(
                media = item,
                videoId = videoId,
                title = title,
                source = source,
                subtitles = subtitles,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    fun onPlaybackProgress(
        item: AppMedia,
        videoId: String,
        title: String,
        source: StreamOption,
        subtitles: List<SubtitleOption>,
        positionMs: Long,
        durationMs: Long
    ) {
        val progress = PlaybackProgress(
            item,
            videoId,
            title,
            positionMs,
            durationMs,
            System.currentTimeMillis(),
            source = source.addonName
        )
        if (durationMs > 0 && progress.percent >= 95) {
            playback.complete(item, videoId)
            playback.clearLastSession(item, videoId)
        } else {
            playback.save(progress)
            playback.saveLastSession(
                LastPlaybackSession(
                    media = item,
                    videoId = videoId,
                    title = title,
                    source = source,
                    subtitles = subtitles,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
        _uiState.value = _uiState.value.copy(
            continueWatching = playback.load().filter { MediaPolicy.allows(it.media) }.take(30),
            watchHistory = playback.history().filter { MediaPolicy.allows(it.media) }.take(60)
        )
    }

    fun onPlaybackStopped(
        item: AppMedia,
        videoId: String,
        title: String,
        source: StreamOption,
        subtitles: List<SubtitleOption>,
        positionMs: Long,
        durationMs: Long
    ) {
        onPlaybackProgress(item, videoId, title, source, subtitles, positionMs, durationMs)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private suspend fun enrichProgress(
        items: List<PlaybackProgress>,
        limit: Int
    ): List<PlaybackProgress> {
        if (!tmdb.configured() || items.isEmpty()) return items
        val rawMedia = items.map { it.media }
        val enrichedMedia = runCatching { tmdb.enrichBatch(rawMedia, limit) }.getOrDefault(rawMedia)
        return items.mapIndexed { index, progress ->
            progress.copy(media = enrichedMedia.getOrElse(index) { progress.media })
        }
    }

    private fun mergeContinueWatching(
        local: List<PlaybackProgress>,
        cloud: List<PlaybackProgress>,
        upNext: List<PlaybackProgress>
    ): List<PlaybackProgress> =
        (local + cloud + upNext)
            .filter { MediaPolicy.allows(it.media) }
            .sortedByDescending { it.updatedAtMs }
            .distinctBy {
                if (it.media.meta.type == "series") "series|${it.media.meta.id}"
                else "movie|${it.media.meta.id}|${it.videoId}"
            }
            .take(30)

    private fun sportsFromExistingAddons(items: List<AppMedia>): List<AppMedia> {
        val terms = listOf(
            "sport", "rugby", "football", "soccer", "cricket", "formula 1", "f1", "motorsport",
            "ufc", "mma", "boxing", "wwe", "tennis", "golf", "nfl", "nba", "nhl"
        )
        return items.filter(MediaPolicy::allows).filter { item ->
            val text = buildString {
                append(item.meta.name)
                append(' ')
                append(item.meta.description.orEmpty())
                append(' ')
                append(item.meta.genres.joinToString(" "))
            }.lowercase()
            terms.any(text::contains)
        }
    }

    private fun mergeSportsCatalog(
        iptvItems: List<AppMedia>,
        addonItems: List<AppMedia>
    ): List<AppMedia> {
        fun key(item: AppMedia): String = item.meta.name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim() + "|" + item.meta.genres.firstOrNull().orEmpty().lowercase()

        return (iptvItems + addonItems)
            .filter(MediaPolicy::allows)
            .distinctBy(::key)
            .sortedWith(
                compareBy<AppMedia> {
                    when {
                        it.meta.genres.any { genre -> genre.equals("Live", true) } -> 0
                        it.meta.genres.any { genre -> genre.equals("Replay", true) } -> 2
                        else -> 1
                    }
                }.thenBy { it.meta.name.lowercase() }
            )
            .take(300)
    }

    private fun mediaSearchKey(item: AppMedia): String =
        item.meta.tmdbId?.let { "tmdb|${item.meta.type}|$it" }
            ?: "${item.meta.type}|${mediaTitleKey(item.meta.name)}"
    private fun mediaTitleKey(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun RdDownload.toAppMedia(): AppMedia? {
        val playable = download?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) } ?: return null
        val prettySize = when {
            filesize >= 1_000_000_000L -> String.format("%.1f GB", filesize / 1_000_000_000.0)
            filesize >= 1_000_000L -> String.format("%.0f MB", filesize / 1_000_000.0)
            else -> "$filesize bytes"
        }
        return AppMedia(
            meta = MetaItem(
                id = "rd:$id",
                type = "rd",
                name = filename.ifBlank { "Real-Debrid download" },
                description = "Real-Debrid library item · $prettySize",
                releaseInfo = generated
            ),
            originAddonName = "Real-Debrid",
            directUrl = playable
        )
    }
}
