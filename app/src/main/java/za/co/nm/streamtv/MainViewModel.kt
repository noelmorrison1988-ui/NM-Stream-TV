package za.co.nm.streamtv

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val addonInstalling: Boolean = false,
    val addonInstallStatus: String? = null,
    val movies: List<AppMedia> = emptyList(),
    val series: List<AppMedia> = emptyList(),
    val noelList: List<AppMedia> = emptyList(),
    val sarahList: List<AppMedia> = emptyList(),
    val debridItems: List<AppMedia> = emptyList(),
    val continueWatching: List<PlaybackProgress> = emptyList(),
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
    val message: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val addons = AddonRepository(application)
    private val realDebrid = RealDebridRepository(application)
    private val tmdb = TmdbRepository(application)
    private val trakt = TraktRepository(application)
    private val iptv = IptvRepository(application)
    private val playback = PlaybackStore(application)
    private val nmAccount = NmAccountRepository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var rdAuthJob: Job? = null
    private var traktAuthJob: Job? = null
    private var nmPairJob: Job? = null
    private var nmSyncJob: Job? = null
    private var traktCloudPlayback: List<PlaybackProgress> = emptyList()
    private var traktUpNext: List<PlaybackProgress> = emptyList()
    private var lastTraktAuthFingerprint: Int? = null
    private var lastRdAuthFingerprint: Int? = null

    init {
        refreshEverything()
        startNmSyncLoop()
    }

    fun refreshEverything() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, message = null)
            val installed = runCatching { addons.loadInstalled() }.getOrElse {
                _uiState.value = _uiState.value.copy(message = it.message)
                emptyList()
            }

            val homeDeferred = async { runCatching { addons.loadHome(installed) }.getOrDefault(emptyList<AppMedia>() to emptyList()) }
            val rdUserDeferred = async { runCatching { realDebrid.getUser() }.getOrNull() }
            val traktUserDeferred = async { runCatching { trakt.getUser() }.getOrNull() }
            val traktWatchlistDeferred = async {
                if (trakt.isConnected()) runCatching { trakt.watchlist() }.getOrDefault(emptyList()) else emptyList()
            }
            val traktPlaybackDeferred = async {
                if (trakt.isConnected()) runCatching { trakt.playbackProgress() }.getOrDefault(emptyList()) else emptyList()
            }
            val traktUpNextDeferred = async {
                if (trakt.isConnected()) runCatching { trakt.upNext() }.getOrDefault(emptyList()) else emptyList()
            }
            val noelListDeferred = async {
                if (trakt.isConnected()) runCatching { trakt.personalList("Noel") }.getOrDefault(emptyList()) else emptyList()
            }
            val sarahListDeferred = async {
                if (trakt.isConnected()) runCatching { trakt.personalList("Sarah") }.getOrDefault(emptyList()) else emptyList()
            }
            val iptvDeferred = async {
                if (iptv.configured()) runCatching { iptv.loadChannels() }.getOrDefault(emptyList()) else emptyList()
            }
            val xtreamMoviesDeferred = async {
                if (iptv.config().hasXtream) runCatching { iptv.loadXtreamMovies() }.getOrDefault(emptyList()) else emptyList()
            }
            val xtreamSeriesDeferred = async {
                if (iptv.config().hasXtream) runCatching { iptv.loadXtreamSeries() }.getOrDefault(emptyList()) else emptyList()
            }

            val (rawMovies, rawSeries) = homeDeferred.await()
            val movies = MediaPolicy.filter(
                if (tmdb.configured()) runCatching { tmdb.enrichBatch(rawMovies, 48) }.getOrDefault(rawMovies) else rawMovies
            )
            val series = MediaPolicy.filter(
                if (tmdb.configured()) runCatching { tmdb.enrichBatch(rawSeries, 48) }.getOrDefault(rawSeries) else rawSeries
            )

            val rdUser = rdUserDeferred.await()
            val rdItems = if (rdUser != null) {
                MediaPolicy.filter(
                    runCatching { realDebrid.recentDownloads() }.getOrDefault(emptyList()).mapNotNull { it.toAppMedia() }
                )
            } else emptyList()
            val traktUser = traktUserDeferred.await()
            val rawTraktWatchlist = traktWatchlistDeferred.await()
            val traktWatchlist = MediaPolicy.filter(
                if (tmdb.configured()) {
                    runCatching { tmdb.enrichBatch(rawTraktWatchlist, 40) }.getOrDefault(rawTraktWatchlist)
                } else rawTraktWatchlist
            )

            val rawTraktPlayback = traktPlaybackDeferred.await()
            val traktPlayback = enrichProgress(rawTraktPlayback, 40)
                .filter { MediaPolicy.allows(it.media) }
            traktCloudPlayback = traktPlayback

            val rawUpNext = traktUpNextDeferred.await()
            val upNext = enrichProgress(rawUpNext, 40)
                .filter { MediaPolicy.allows(it.media) }
            traktUpNext = upNext

            val rawNoelList = noelListDeferred.await()
            val noelList = MediaPolicy.filter(
                if (tmdb.configured()) {
                    runCatching { tmdb.enrichBatch(rawNoelList, 50) }.getOrDefault(rawNoelList)
                } else rawNoelList
            )

            val rawSarahList = sarahListDeferred.await()
            val sarahList = MediaPolicy.filter(
                if (tmdb.configured()) {
                    runCatching { tmdb.enrichBatch(rawSarahList, 50) }.getOrDefault(rawSarahList)
                } else rawSarahList
            )

            val iptvChannels = MediaPolicy.filter(iptvDeferred.await())
            val xtreamMovies = MediaPolicy.filter(xtreamMoviesDeferred.await())
            val xtreamSeries = MediaPolicy.filter(xtreamSeriesDeferred.await())
            val iptvSports = iptv.sportsOnly(iptvChannels)
            val addonSports = sportsFromExistingAddons(rawMovies + rawSeries)
            val sportsCatalog = mergeSportsCatalog(iptvSports, addonSports)
            val iptvCategories = iptv.categoryRows(iptvChannels)
            val iptvGuide = if (iptvChannels.isNotEmpty()) {
                runCatching { iptv.loadGuide(iptvChannels) }.getOrDefault(emptyMap())
            } else emptyMap()

            val nmPrefs = nmAccount.playbackPreferences()

            _uiState.value = _uiState.value.copy(
                loading = false,
                addons = installed,
                movies = movies,
                series = series,
                noelList = noelList,
                sarahList = sarahList,
                rdUser = rdUser,
                debridItems = rdItems,
                continueWatching = mergeContinueWatching(playback.load(), traktPlayback, upNext),
                rdDeviceCode = null,
                rdConnecting = false,
                tmdbConfigured = tmdb.configured(),
                tmdbStatus = tmdb.maskedToken(),
                traktConfigured = trakt.credentialsConfigured(),
                traktConnected = trakt.isConnected(),
                traktUser = traktUser,
                traktWatchlist = traktWatchlist,
                traktDeviceCode = null,
                traktConnecting = false,
                iptvChannels = iptvChannels,
                iptvSports = iptvSports,
                sportsCatalog = sportsCatalog,
                xtreamMovies = xtreamMovies,
                xtreamSeries = xtreamSeries,
                iptvCategories = iptvCategories,
                iptvGuide = iptvGuide,
                iptvConfigured = iptv.configured(),
                iptvStatus = iptv.status(),
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
                    if (nmAccount.isLinked()) {
                        runCatching { nmAccount.pushAddonManifests(addons.storedManifestUrls()) }
                    }
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
            if (nmAccount.isLinked()) {
                runCatching { nmAccount.pushAddonManifests(addons.storedManifestUrls()) }
            }
            refreshEverything()
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) {
                _uiState.value = _uiState.value.copy(searchResults = emptyList())
                return@launch
            }
            _uiState.value = _uiState.value.copy(searchLoading = true, message = null)
            val raw = runCatching { addons.search(_uiState.value.addons, query) }.getOrElse {
                _uiState.value = _uiState.value.copy(message = it.message)
                emptyList()
            }
            val results = MediaPolicy.filter(
                if (tmdb.configured()) runCatching { tmdb.enrichBatch(raw, 40) }.getOrDefault(raw) else raw
            )
            _uiState.value = _uiState.value.copy(searchLoading = false, searchResults = results)
        }
    }

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
            val sourceMeta = when {
                item.meta.id.startsWith("xtream:series:") ->
                    runCatching { iptv.loadXtreamSeriesDetails(item) }.getOrDefault(item)
                else ->
                    runCatching { addons.loadMeta(item, _uiState.value.addons) }.getOrDefault(item)
            }
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
                val xtreamEpisodeUrl = iptv.resolveXtreamEpisodeUrl(videoId)
                when {
                    xtreamEpisodeUrl != null -> listOf(
                        StreamOption(
                            addonName = "NM IPTV · Xtream Series",
                            stream = AddonStream(
                                name = item.meta.name,
                                title = "Xtream · ${item.meta.name}",
                                url = xtreamEpisodeUrl
                            )
                        )
                    )
                    !item.directUrl.isNullOrBlank() -> listOf(
                        StreamOption(
                            addonName = item.originAddonName ?: "Direct source",
                            stream = AddonStream(name = item.meta.name, title = item.meta.name, url = item.directUrl)
                        )
                    )
                    else -> runCatching {
                        addons.loadStreams(_uiState.value.addons, item.meta.type, videoId)
                    }.getOrDefault(emptyList())
                }
            }
            val subtitlesDeferred = async {
                if (item.meta.type == "rd" || videoId.startsWith("xtream:episode:") || item.meta.id.startsWith("xtream:vod:")) {
                    emptyList()
                } else {
                    runCatching {
                        addons.loadSubtitles(_uiState.value.addons, item.meta.type, videoId)
                    }.getOrDefault(emptyList())
                }
            }
            val prefs = nmAccount.playbackPreferences()
            val sortedStreams = streamsDeferred.await()
                .sortedWith(
                    compareBy<StreamOption> {
                        it.preferenceScore(
                            preferredQuality = prefs.preferredQuality,
                            preferHttpDebrid = prefs.preferHttpDebrid,
                            preferredAudioLanguage = prefs.preferredAudioLanguage
                        )
                    }.thenBy { it.stream.behaviorHints?.videoSize ?: Long.MAX_VALUE }
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

    fun addToPersonalList(name: String, item: AppMedia) {
        viewModelScope.launch {
            if (!_uiState.value.traktConnected) {
                _uiState.value = _uiState.value.copy(message = "Connect Trakt first")
                return@launch
            }

            _uiState.value = _uiState.value.copy(message = "Adding ${item.meta.name} to $name…")
            runCatching { trakt.addToPersonalList(name, item) }
                .onSuccess {
                    val raw = runCatching { trakt.personalList(name) }.getOrDefault(emptyList())
                    val refreshed = if (tmdb.configured()) {
                        runCatching { tmdb.enrichBatch(raw, 40) }.getOrDefault(raw)
                    } else raw

                    _uiState.value = when (name.lowercase()) {
                        "noel" -> _uiState.value.copy(
                            noelList = refreshed,
                            message = "Added ${item.meta.name} to Noel"
                        )
                        "sarah" -> _uiState.value.copy(
                            sarahList = refreshed,
                            message = "Added ${item.meta.name} to Sarah"
                        )
                        else -> _uiState.value.copy(message = "Added ${item.meta.name} to $name")
                    }
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        message = error.message ?: "Could not add item to $name"
                    )
                }
        }
    }

    fun bestTrailer(item: AppMedia): StreamOption? =
        item.meta.trailers
            .mapNotNull { trailer ->
                val youtubeId = trailer.ytId ?: trailer.source
                if (trailer.url.isNullOrBlank() && youtubeId.isNullOrBlank()) return@mapNotNull null

                val qualityText = trailer.quality?.let { " · ${it}p" }.orEmpty()
                StreamOption(
                    addonName = "Trailer",
                    stream = AddonStream(
                        name = trailer.type ?: "Trailer",
                        title = (trailer.title ?: trailer.name ?: item.meta.name + " Trailer") + qualityText,
                        url = trailer.url,
                        ytId = youtubeId
                    )
                )
            }
            .sortedWith(
                compareBy<StreamOption> {
                    when (it.detectedQuality) {
                        720 -> 0
                        1080 -> 1
                        480 -> 2
                        2160 -> 3
                        null -> 5
                        else -> 4
                    }
                }.thenBy { it.preferenceScore() }
            )
            .firstOrNull()

    fun saveTmdbToken(token: String) {
        tmdb.saveToken(token)
        _uiState.value = _uiState.value.copy(
            tmdbConfigured = tmdb.configured(),
            tmdbStatus = tmdb.maskedToken(),
            message = if (token.isBlank()) "TMDB token removed" else "TMDB artwork enabled"
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
                        if (nmAccount.isLinked()) {
                            runCatching { nmAccount.pushRealDebridAuth(realDebrid.exportAuth()) }
                        }
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
        viewModelScope.launch {
            if (nmAccount.isLinked()) runCatching { nmAccount.pushRealDebridAuth(null) }
        }
        _uiState.value = _uiState.value.copy(
            rdUser = null,
            rdDeviceCode = null,
            rdConnecting = false,
            debridItems = emptyList(),
            message = "Real-Debrid disconnected on all linked devices"
        )
    }

    fun beginTraktSignIn() {
        traktAuthJob?.cancel()
        traktAuthJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(traktConnecting = true, traktDeviceCode = null, message = null)
            val device = runCatching { trakt.startDeviceAuth() }.getOrElse {
                _uiState.value = _uiState.value.copy(traktConnecting = false, message = it.message)
                return@launch
            }
            _uiState.value = _uiState.value.copy(traktDeviceCode = device, traktConnecting = true)
            val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
            while (System.currentTimeMillis() < deadline) {
                val attempt = runCatching { trakt.pollDeviceToken(device.deviceCode) }
                val error = attempt.exceptionOrNull()
                if (error != null) {
                    _uiState.value = _uiState.value.copy(
                        traktConnecting = false,
                        traktDeviceCode = null,
                        message = error.message ?: "Trakt sign-in failed"
                    )
                    return@launch
                }
                val auth = attempt.getOrNull()
                if (auth != null) {
                    if (nmAccount.isLinked()) {
                        runCatching { nmAccount.pushTraktAuth(trakt.exportAuth()) }
                    }
                    lastTraktAuthFingerprint = trakt.exportAuth()?.hashCode() ?: 0
                    val user = runCatching { trakt.getUser() }.getOrNull()
                    _uiState.value = _uiState.value.copy(
                        traktConnecting = false,
                        traktDeviceCode = null,
                        traktConnected = true,
                        traktUser = user,
                        message = "Trakt connected and synced"
                    )
                    refreshEverything()
                    return@launch
                }
                delay(device.interval.coerceAtLeast(6) * 1000L)
            }
            _uiState.value = _uiState.value.copy(traktConnecting = false, traktDeviceCode = null, message = "Trakt sign-in code expired")
        }
    }

    fun disconnectTrakt() {
        traktAuthJob?.cancel()
        trakt.disconnect()
        lastTraktAuthFingerprint = 0
        traktCloudPlayback = emptyList()
        traktUpNext = emptyList()
        viewModelScope.launch {
            if (nmAccount.isLinked()) runCatching { nmAccount.pushTraktAuth(null) }
        }
        _uiState.value = _uiState.value.copy(
            traktConnected = false,
            traktUser = null,
            traktWatchlist = emptyList(),
            noelList = emptyList(),
            sarahList = emptyList(),
            continueWatching = playback.load(),
            traktDeviceCode = null,
            traktConnecting = false,
            message = "Trakt disconnected on all linked devices"
        )
    }

    fun saveIptvM3u(m3uUrl: String, epgUrl: String) {
        viewModelScope.launch {
            runCatching { iptv.saveM3u(m3uUrl, epgUrl) }
                .onSuccess {
                    if (nmAccount.isLinked()) {
                        runCatching { nmAccount.pushIptv(true, iptv.config()) }
                    }
                    _uiState.value = _uiState.value.copy(message = "IPTV playlist saved and synced")
                    refreshEverything()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(message = error.message ?: "Could not save IPTV playlist")
                }
        }
    }

    fun saveIptvXtream(server: String, username: String, password: String) {
        viewModelScope.launch {
            runCatching { iptv.saveXtream(server, username, password) }
                .onSuccess {
                    if (nmAccount.isLinked()) {
                        runCatching { nmAccount.pushIptv(true, iptv.config()) }
                    }
                    _uiState.value = _uiState.value.copy(message = "Xtream IPTV saved and synced")
                    refreshEverything()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(message = error.message ?: "Could not save Xtream IPTV")
                }
        }
    }

    fun clearIptv() {
        iptv.clear()
        viewModelScope.launch {
            if (nmAccount.isLinked()) runCatching { nmAccount.pushIptv(true, null) }
        }
        _uiState.value = _uiState.value.copy(
            iptvChannels = emptyList(),
            iptvSports = emptyList(),
            sportsCatalog = mergeSportsCatalog(_uiState.value.crewSports, emptyList(), sportsFromExistingAddons(_uiState.value.movies + _uiState.value.series)),
            xtreamMovies = emptyList(),
            xtreamSeries = emptyList(),
            iptvCategories = emptyList(),
            iptvGuide = emptyMap(),
            iptvConfigured = false,
            iptvStatus = "Not configured",
            message = "IPTV configuration removed from all linked devices"
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
        viewModelScope.launch {
            if (nmAccount.isLinked()) {
                runCatching { nmAccount.pushPlaybackPreferences(prefs) }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            message = error.message ?: "Saved locally, but cloud sync failed"
                        )
                    }
            }
        }
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
                        nmAccount.pushAllServiceAuth(
                            trakt.exportAuth(),
                            realDebrid.exportAuth()
                        )
                        lastTraktAuthFingerprint = trakt.exportAuth()?.hashCode() ?: 0
                        lastRdAuthFingerprint = realDebrid.exportAuth()?.hashCode() ?: 0
                        applyNmAccountSync(force = true)
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
            runCatching { applyNmAccountSync(force = false) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        nmSyncStatus = "Synced",
                        nmDeviceBlocked = false,
                        message = "NM Account settings are up to date"
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
                delay(15_000)
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

            if (remote.settings.syncIptv) {
                val cloud = remote.settings.iptv
                if (cloud == null) {
                    iptv.clear()
                } else {
                    iptv.replaceConfig(
                        IptvConfig(
                            m3uUrl = cloud.m3uUrl,
                            epgUrl = cloud.epgUrl,
                            xtreamServer = cloud.xtreamServer,
                            xtreamUsername = cloud.xtreamUsername,
                            xtreamPassword = cloud.xtreamPassword
                        )
                    )
                }
            }

            if (remote.settings.traktAuthInitialized) {
                trakt.importAuth(remote.settings.traktAuth)
                lastTraktAuthFingerprint = trakt.exportAuth()?.hashCode() ?: 0
            } else if (trakt.exportAuth() != null) {
                nmAccount.pushTraktAuth(trakt.exportAuth())
                lastTraktAuthFingerprint = trakt.exportAuth()?.hashCode() ?: 0
            }

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
        }
        return changed
    }

    private suspend fun pushChangedServiceAuthIfNeeded() {
        if (!nmAccount.isLinked()) return

        val traktAuth = trakt.exportAuth()
        val traktFingerprint = traktAuth?.hashCode() ?: 0
        if (lastTraktAuthFingerprint == null) {
            lastTraktAuthFingerprint = traktFingerprint
        } else if (traktFingerprint != lastTraktAuthFingerprint) {
            nmAccount.pushTraktAuth(traktAuth)
            lastTraktAuthFingerprint = traktFingerprint
        }

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

    fun resumeCloudPercent(item: AppMedia, videoId: String): Double? =
        traktCloudPlayback.firstOrNull {
            it.media.meta.id == item.meta.id && it.videoId == videoId
        }?.cloudPercent

    fun onPlaybackStarted(item: AppMedia, videoId: String, positionMs: Long, durationMs: Long) {
        if (_uiState.value.traktConnected) {
            val percent = if (durationMs > 0) positionMs * 100.0 / durationMs else 0.0
            viewModelScope.launch { runCatching { trakt.scrobble("start", item, videoId, percent) } }
        }
    }

    fun onPlaybackProgress(item: AppMedia, videoId: String, title: String, positionMs: Long, durationMs: Long) {
        val progress = PlaybackProgress(item, videoId, title, positionMs, durationMs, System.currentTimeMillis())
        if (durationMs > 0 && progress.percent >= 95) playback.complete(item, videoId) else playback.save(progress)
        _uiState.value = _uiState.value.copy(
            continueWatching = mergeContinueWatching(playback.load(), traktCloudPlayback, traktUpNext)
        )
    }

    fun onPlaybackStopped(item: AppMedia, videoId: String, title: String, positionMs: Long, durationMs: Long) {
        onPlaybackProgress(item, videoId, title, positionMs, durationMs)
        if (_uiState.value.traktConnected) {
            val percent = if (durationMs > 0) positionMs * 100.0 / durationMs else 0.0
            viewModelScope.launch { runCatching { trakt.scrobble("stop", item, videoId, percent) } }
        }
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
