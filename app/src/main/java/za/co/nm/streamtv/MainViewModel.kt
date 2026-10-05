package za.co.nm.streamtv

import android.app.Application
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
    val movies: List<AppMedia> = emptyList(),
    val series: List<AppMedia> = emptyList(),
    val debridItems: List<AppMedia> = emptyList(),
    val continueWatching: List<PlaybackProgress> = emptyList(),
    val searchResults: List<AppMedia> = emptyList(),
    val searchLoading: Boolean = false,
    val selectedMedia: AppMedia? = null,
    val detailsLoading: Boolean = false,
    val streamOptions: List<StreamOption> = emptyList(),
    val subtitleOptions: List<SubtitleOption> = emptyList(),
    val streamsLoading: Boolean = false,
    val rdUser: RdUser? = null,
    val rdDeviceCode: RdDeviceCode? = null,
    val rdConnecting: Boolean = false,
    val tmdbConfigured: Boolean = false,
    val tmdbStatus: String = "Not configured",
    val traktConfigured: Boolean = false,
    val traktConnected: Boolean = false,
    val traktUser: TraktUser? = null,
    val traktDeviceCode: TraktDeviceCode? = null,
    val traktConnecting: Boolean = false,
    val message: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val addons = AddonRepository(application)
    private val realDebrid = RealDebridRepository(application)
    private val tmdb = TmdbRepository(application)
    private val trakt = TraktRepository(application)
    private val playback = PlaybackStore(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var rdAuthJob: Job? = null
    private var traktAuthJob: Job? = null

    init {
        refreshEverything()
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

            val (rawMovies, rawSeries) = homeDeferred.await()
            val movies = if (tmdb.configured()) runCatching { tmdb.enrichBatch(rawMovies, 24) }.getOrDefault(rawMovies) else rawMovies
            val series = if (tmdb.configured()) runCatching { tmdb.enrichBatch(rawSeries, 24) }.getOrDefault(rawSeries) else rawSeries

            val rdUser = rdUserDeferred.await()
            val rdItems = if (rdUser != null) {
                runCatching { realDebrid.recentDownloads() }.getOrDefault(emptyList()).mapNotNull { it.toAppMedia() }
            } else emptyList()
            val traktUser = traktUserDeferred.await()

            _uiState.value = _uiState.value.copy(
                loading = false,
                addons = installed,
                movies = movies,
                series = series,
                rdUser = rdUser,
                debridItems = rdItems,
                continueWatching = playback.load(),
                rdDeviceCode = null,
                rdConnecting = false,
                tmdbConfigured = tmdb.configured(),
                tmdbStatus = tmdb.maskedToken(),
                traktConfigured = trakt.clientIdConfigured(),
                traktConnected = trakt.isConnected(),
                traktUser = traktUser,
                traktDeviceCode = null,
                traktConnecting = false
            )
        }
    }

    fun installAddon(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(message = "Installing add-on…")
            runCatching { addons.install(url) }
                .onSuccess { installed ->
                    _uiState.value = _uiState.value.copy(message = "Installed ${installed.manifest.name}")
                    refreshEverything()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(message = error.message ?: "Could not install add-on")
                }
        }
    }

    fun removeAddon(manifestUrl: String) {
        addons.remove(manifestUrl)
        _uiState.value = _uiState.value.copy(message = "Add-on removed")
        refreshEverything()
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
            val results = if (tmdb.configured()) runCatching { tmdb.enrichBatch(raw, 20) }.getOrDefault(raw) else raw
            _uiState.value = _uiState.value.copy(searchLoading = false, searchResults = results)
        }
    }

    fun loadDetails(item: AppMedia) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(selectedMedia = item, detailsLoading = true, message = null)
            val addonMeta = runCatching { addons.loadMeta(item, _uiState.value.addons) }.getOrDefault(item)
            val loaded = if (tmdb.configured()) runCatching { tmdb.enrich(addonMeta) }.getOrDefault(addonMeta) else addonMeta
            _uiState.value = _uiState.value.copy(selectedMedia = loaded, detailsLoading = false)
        }
    }

    fun loadSources(item: AppMedia, videoId: String = item.meta.id) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                streamOptions = emptyList(),
                subtitleOptions = emptyList(),
                streamsLoading = true,
                message = null
            )
            val streamsDeferred = async {
                if (!item.directUrl.isNullOrBlank()) {
                    listOf(
                        StreamOption(
                            addonName = item.originAddonName ?: "Real-Debrid",
                            stream = AddonStream(name = item.meta.name, title = item.meta.name, url = item.directUrl)
                        )
                    )
                } else {
                    runCatching { addons.loadStreams(_uiState.value.addons, item.meta.type, videoId) }.getOrDefault(emptyList())
                }
            }
            val subtitlesDeferred = async {
                if (item.meta.type == "rd") emptyList()
                else runCatching { addons.loadSubtitles(_uiState.value.addons, item.meta.type, videoId) }.getOrDefault(emptyList())
            }
            _uiState.value = _uiState.value.copy(
                streamOptions = streamsDeferred.await(),
                subtitleOptions = subtitlesDeferred.await(),
                streamsLoading = false
            )
        }
    }

    fun saveTmdbToken(token: String) {
        tmdb.saveToken(token)
        _uiState.value = _uiState.value.copy(
            tmdbConfigured = tmdb.configured(),
            tmdbStatus = tmdb.maskedToken(),
            message = if (token.isBlank()) "TMDB token removed" else "TMDB artwork enabled"
        )
        refreshEverything()
    }

    fun saveTraktClientId(clientId: String) {
        traktAuthJob?.cancel()
        trakt.saveClientId(clientId)
        _uiState.value = _uiState.value.copy(
            traktConfigured = trakt.clientIdConfigured(),
            traktConnected = false,
            traktUser = null,
            message = if (clientId.isBlank()) "Trakt configuration removed" else "Trakt Client ID saved"
        )
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
        _uiState.value = _uiState.value.copy(
            rdUser = null,
            rdDeviceCode = null,
            rdConnecting = false,
            debridItems = emptyList(),
            message = "Real-Debrid disconnected"
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
                val auth = runCatching { trakt.pollDeviceToken(device.deviceCode) }.getOrNull()
                if (auth != null) {
                    val user = runCatching { trakt.getUser() }.getOrNull()
                    _uiState.value = _uiState.value.copy(
                        traktConnecting = false,
                        traktDeviceCode = null,
                        traktConnected = true,
                        traktUser = user,
                        message = "Trakt connected"
                    )
                    return@launch
                }
                delay(device.interval.coerceAtLeast(5) * 1000L)
            }
            _uiState.value = _uiState.value.copy(traktConnecting = false, traktDeviceCode = null, message = "Trakt sign-in code expired")
        }
    }

    fun disconnectTrakt() {
        traktAuthJob?.cancel()
        trakt.disconnect()
        _uiState.value = _uiState.value.copy(
            traktConnected = false,
            traktUser = null,
            traktDeviceCode = null,
            traktConnecting = false,
            message = "Trakt disconnected"
        )
    }

    fun resumePosition(item: AppMedia, videoId: String): Long = playback.resumePosition(item, videoId)

    fun onPlaybackStarted(item: AppMedia, videoId: String, positionMs: Long, durationMs: Long) {
        if (_uiState.value.traktConnected) {
            val percent = if (durationMs > 0) positionMs * 100.0 / durationMs else 0.0
            viewModelScope.launch { runCatching { trakt.scrobble("start", item, videoId, percent) } }
        }
    }

    fun onPlaybackProgress(item: AppMedia, videoId: String, title: String, positionMs: Long, durationMs: Long) {
        val progress = PlaybackProgress(item, videoId, title, positionMs, durationMs, System.currentTimeMillis())
        if (durationMs > 0 && progress.percent >= 95) playback.complete(item, videoId) else playback.save(progress)
        _uiState.value = _uiState.value.copy(continueWatching = playback.load())
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
