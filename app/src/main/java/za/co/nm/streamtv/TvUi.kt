package za.co.nm.streamtv

import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NmBg = Color(0xFF050607)
private val NmPanel = Color(0xFF121417)
private val NmPanelFocus = Color(0xFF1D2025)
private val NmRed = Color(0xFFE2182D)
private val NmGold = Color(0xFFD6A84B)
private val NmPlatinum = Color(0xFFD8DCE3)
private val NmMuted = Color(0xFF9EA5AF)
private val NmGreen = Color(0xFF71D6A0)

private sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object MyList : Screen
    data object Addons : Screen
    data object Settings : Screen
    data class ExpandedRow(val title: String, val key: String, val page: Int) : Screen
    data class Details(val item: AppMedia) : Screen
    data class Sources(val item: AppMedia, val videoId: String, val title: String) : Screen
    data class AutoPlay(
        val item: AppMedia,
        val videoId: String,
        val title: String,
        val requestKey: String,
        val excludedUrls: Set<String> = emptySet(),
        val resumeMsOverride: Long? = null,
        val resumeSubtitles: List<SubtitleOption>? = null,
        val switchFromLabel: String? = null,
        val switchReason: String? = null
    ) : Screen
    data class Player(
        val item: AppMedia,
        val videoId: String,
        val title: String,
        val source: StreamOption,
        val returnToSources: Boolean = true,
        val resumeMsOverride: Long? = null,
        val resumeSubtitles: List<SubtitleOption>? = null,
        val excludedUrls: Set<String> = emptySet(),
        val switchNotice: String? = null,
        // Ownership of a prepared standby player moves to the new screen.
        val prewarmedPlayer: ExoPlayer? = null
    ) : Screen
    data class Trailer(val item: AppMedia, val title: String, val source: StreamOption) : Screen
    data class YouTubeTrailer(val item: AppMedia, val title: String, val youtubeId: String) : Screen
}

private data class SeriesSelection(
    val season: Int,
    val episodeId: String? = null
)

private fun seriesSelectionKey(item: AppMedia): String =
    "${item.meta.type}|" + item.meta.name.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

@Composable
fun NMStreamApp(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var screen: Screen by remember { mutableStateOf(Screen.Home) }
    var seriesSelections by remember { mutableStateOf<Map<String, SeriesSelection>>(emptyMap()) }

    fun playOrRestoreLast(item: AppMedia, videoId: String, title: String) {
        val last = viewModel.lastPlaybackSession(item, videoId)
        if (last != null) {
            screen = Screen.Player(
                item = item,
                videoId = videoId,
                title = title,
                source = last.source,
                returnToSources = false,
                resumeMsOverride = last.positionMs,
                resumeSubtitles = last.subtitles
            )
        } else {
            val key = viewModel.sourceRequestKey(item, videoId)
            viewModel.loadSources(item, videoId)
            screen = Screen.AutoPlay(item, videoId, title, key)
        }
    }

    BackHandler(screen !is Screen.Home) {
        screen = when (val current = screen) {
            is Screen.Player -> if (current.returnToSources) {
                Screen.Sources(current.item, current.videoId, current.title)
            } else {
                Screen.Details(current.item)
            }
            is Screen.AutoPlay -> Screen.Details(current.item)
            is Screen.Trailer -> Screen.Details(current.item)
            is Screen.YouTubeTrailer -> Screen.Details(current.item)
            is Screen.Sources -> Screen.Details(current.item)
            else -> Screen.Home
        }
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4000)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.nmDeviceBlocked) {
        if (state.nmDeviceBlocked) screen = Screen.Home
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(NmBg)) {
            if (state.nmDeviceBlocked) {
                DeviceBlockedScreen()
            } else {
                when (val current = screen) {
                Screen.Home -> Shell("Home", { screen = it }) {
                    HomeScreen(state,
                        onOpen = {
                            viewModel.loadDetails(it)
                            screen = Screen.Details(it)
                        },
                        onContinue = {
                            viewModel.loadDetails(it.media)
                            playOrRestoreLast(it.media, it.videoId, it.title)
                        },
                        onContinueManual = {
                            viewModel.loadDetails(it.media)
                            viewModel.loadSources(it.media, it.videoId)
                            screen = Screen.Sources(it.media, it.videoId, it.title)
                        },
                        onExpand = { title, key ->
                            viewModel.loadExpandedRow(key, 1)
                            screen = Screen.ExpandedRow(title, key, 1)
                        },
                        onToggleMyList = viewModel::toggleMyList
                    )
                }
                Screen.Search -> Shell("Search", { screen = it }) {
                    SearchScreen(
                        state = state,
                        onSearch = viewModel::search,
                        onOpen = {
                            viewModel.loadDetails(it)
                            screen = Screen.Details(it)
                        },
                        onToggleMyList = viewModel::toggleMyList
                    )
                }
                Screen.MyList -> Shell("My List", { screen = it }) {
                    MyListScreen(state.myList) {
                        viewModel.loadDetails(it)
                        screen = Screen.Details(it)
                    }
                }
                Screen.Addons -> Shell("Add-ons", { screen = it }) {
                    AddonsScreen(
                        state = state,
                        install = viewModel::installAddon,
                        remove = viewModel::removeAddon
                    )
                }
                Screen.Settings -> Shell("Settings", { screen = it }) {
                    SettingsScreen(state, viewModel)
                }
                is Screen.ExpandedRow -> ExpandedRowScreen(
                    title = current.title,
                    page = current.page,
                    loading = state.expandedRowLoading,
                    items = state.expandedRowItems,
                    continueItems = state.expandedContinueItems,
                    hasPrevious = current.page > 1,
                    hasNext = state.expandedRowHasNext,
                    onHome = { screen = Screen.Home },
                    onPrevious = {
                        val previous = (current.page - 1).coerceAtLeast(1)
                        viewModel.loadExpandedRow(current.key, previous)
                        screen = current.copy(page = previous)
                    },
                    onNext = {
                        val next = current.page + 1
                        viewModel.loadExpandedRow(current.key, next)
                        screen = current.copy(page = next)
                    },
                    onOpen = {
                        viewModel.loadDetails(it)
                        screen = Screen.Details(it)
                    },
                    onContinue = {
                        viewModel.loadDetails(it.media)
                        playOrRestoreLast(it.media, it.videoId, it.title)
                    },
                    onContinueManual = {
                        viewModel.loadDetails(it.media)
                        viewModel.loadSources(it.media, it.videoId)
                        screen = Screen.Sources(it.media, it.videoId, it.title)
                    },
                    myList = state.myList,
                    onToggleMyList = viewModel::toggleMyList
                )
                is Screen.Details -> {
                    val detailItem = state.selectedMedia ?: current.item
                    val trailer = viewModel.bestTrailer(detailItem)
                    val selectionKey = seriesSelectionKey(current.item)
                    val rememberedSelection = seriesSelections[selectionKey]
                    DetailsScreen(
                        item = detailItem,
                        loading = state.detailsLoading,
                        recommendations = state.selectedRecommendations,
                        onOpenRecommendation = { suggested ->
                            viewModel.loadDetails(suggested)
                            screen = Screen.Details(suggested)
                        },
                        trailer = trailer,
                        inMyList = state.myList.any { mediaMatches(it, detailItem) },
                        rememberedSeason = rememberedSelection?.season,
                        rememberedEpisodeId = rememberedSelection?.episodeId,
                        toggleMyList = { viewModel.toggleMyList(detailItem) },
                        rememberSeason = { season ->
                            val previous = seriesSelections[selectionKey]
                            seriesSelections = seriesSelections + (selectionKey to SeriesSelection(
                                season = season,
                                episodeId = previous?.episodeId?.takeIf { id ->
                                    detailItem.meta.videos.any { video -> video.id == id && (video.season ?: 1) == season }
                                }
                            ))
                        },
                        rememberEpisode = { episode ->
                            seriesSelections = seriesSelections + (selectionKey to SeriesSelection(
                                season = episode.season ?: rememberedSelection?.season ?: 1,
                                episodeId = episode.id
                            ))
                        },
                        play = { item, id, title ->
                            playOrRestoreLast(item, id, title)
                        },
                        chooseManual = { item, id, title ->
                            viewModel.loadSources(item, id)
                            screen = Screen.Sources(item, id, title)
                        },
                        playTrailer = { source ->
                            when {
                                source.playableUrl != null -> screen = Screen.Trailer(detailItem, "Trailer · ${detailItem.meta.name}", source)
                                !source.stream.ytId.isNullOrBlank() -> screen = Screen.YouTubeTrailer(
                                    detailItem,
                                    "Trailer · ${detailItem.meta.name}",
                                    source.stream.ytId.orEmpty()
                                )
                                !source.stream.externalUrl.isNullOrBlank() -> runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.stream.externalUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        }
                    )
                }
                is Screen.AutoPlay -> {
                    AutoPlayScreen(current.title, switching = current.excludedUrls.isNotEmpty())
                    LaunchedEffect(
                        current.requestKey,
                        state.sourceRequestKey,
                        state.streamsLoading,
                        state.streamOptions,
                        current.excludedUrls
                    ) {
                        if (
                            state.sourceRequestKey == current.requestKey &&
                            !state.streamsLoading
                        ) {
                            val eligible = state.streamOptions.filter { option ->
                                val playable = option.playableUrl != null ||
                                    option.youtubeUrl != null ||
                                    !option.stream.externalUrl.isNullOrBlank()
                                val ready = !option.isKnownUncached
                                val notFailed = option.playableUrl == null ||
                                    option.playableUrl !in current.excludedUrls
                                playable && ready && notFailed
                            }
                            // After a stall or provider placeholder, try another actual
                            // HTTP provider before choosing a second result from the
                            // provider that just failed. Retain 720p/1080p ordering.
                            val previousProvider = current.switchFromLabel?.substringBefore(" · ")
                            val alternateHttp = if (previousProvider.isNullOrBlank()) {
                                emptyList()
                            } else {
                                eligible.filter { it.addonName != previousProvider && it.playableUrl != null }
                            }
                            val best = alternateHttp.firstOrNull() ?: eligible.firstOrNull()

                            when {
                                best?.playableUrl != null -> {
                                    val notice = current.switchFromLabel?.let { from ->
                                        val action = if (current.switchReason == "Skipped source manually") {
                                            "SOURCE CHANGED MANUALLY"
                                        } else {
                                            "SOURCE SWITCHED AUTOMATICALLY"
                                        }
                                        "$action · ${current.switchReason ?: "Recovery"}" +
                                            "\nFROM: $from" +
                                            "\nTO: ${best.switchIdentityLabel()}"
                                    }
                                    screen = Screen.Player(
                                        current.item,
                                        current.videoId,
                                        current.title,
                                        best,
                                        returnToSources = false,
                                        resumeMsOverride = current.resumeMsOverride,
                                        resumeSubtitles = current.resumeSubtitles,
                                        excludedUrls = current.excludedUrls,
                                        switchNotice = notice
                                    )
                                }
                                best?.youtubeUrl != null -> {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(best.youtubeUrl))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                    screen = Screen.Details(current.item)
                                }
                                !best?.stream?.externalUrl.isNullOrBlank() -> {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(best?.stream?.externalUrl))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                    screen = Screen.Details(current.item)
                                }
                                else -> {
                                    screen = Screen.Sources(current.item, current.videoId, current.title)
                                }
                            }
                        }
                    }
                }
                is Screen.Sources -> SourcesScreen(current.title, state.streamsLoading, state.streamOptions, state.subtitleOptions.size) { source ->
                    when {
                        source.playableUrl != null -> screen = Screen.Player(
                            current.item,
                            current.videoId,
                            current.title,
                            source,
                            excludedUrls = emptySet()
                        )
                        source.youtubeUrl != null -> runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.youtubeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        !source.stream.externalUrl.isNullOrBlank() -> runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.stream.externalUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                is Screen.YouTubeTrailer -> YouTubeTrailerScreen(
                    title = current.title,
                    youtubeId = current.youtubeId
                )
                is Screen.Trailer -> PlayerScreen(
                    item = current.item,
                    videoId = "trailer:${current.item.meta.id}",
                    title = current.title,
                    url = current.source.playableUrl.orEmpty(),
                    headers = current.source.requestHeaders,
                    subtitles = emptyList(),
                    resumeMs = 0L,
                    resumePercent = null,
                    sourceNotice = null,
                    preferredAudioLanguage = state.preferredAudioLanguage,
                    preferredSubtitleLanguage = state.preferredSubtitleLanguage,
                    onStarted = { _, _ -> },
                    onProgress = { _, _ -> },
                    onStopped = { _, _ -> }
                )
                is Screen.Player -> {
                    DisposableEffect(current.videoId, current.source.playableUrl) {
                        viewModel.setPlaybackMonitoringActive(true)
                        onDispose { viewModel.setPlaybackMonitoringActive(false) }
                    }
                    val activeSubtitles = current.resumeSubtitles ?: state.subtitleOptions
                    val currentKey = viewModel.sourceRequestKey(current.item, current.videoId)
                    val matchingSources = if (state.sourceRequestKey == currentKey &&
                        !state.streamsLoading) state.streamOptions else emptyList()
                    val nextSource = matchingSources
                        .filter { candidate ->
                            val nextUrl = candidate.playableUrl
                            nextUrl != null && nextUrl != current.source.playableUrl &&
                                nextUrl !in current.excludedUrls && !candidate.isKnownUncached
                        }
                        .sortedWith(compareBy<StreamOption> { candidate ->
                            if (candidate.addonName == current.source.addonName) 1 else 0
                        })
                        .firstOrNull()
                    PlayerScreen(
                        item = current.item,
                        videoId = current.videoId,
                        title = current.title,
                        url = current.source.playableUrl.orEmpty(),
                        headers = current.source.requestHeaders,
                        subtitles = activeSubtitles,
                        resumeMs = current.resumeMsOverride
                            ?: viewModel.resumePosition(current.item, current.videoId),
                        resumePercent = if (current.resumeMsOverride != null) {
                            null
                        } else {
                            viewModel.resumeCloudPercent(current.item, current.videoId)
                        },
                        sourceNotice = current.switchNotice,
                        sourceProvider = current.source.addonName,
                        nextSource = nextSource,
                        prewarmedPlayer = current.prewarmedPlayer,
                        preferredAudioLanguage = state.preferredAudioLanguage,
                        preferredSubtitleLanguage = state.preferredSubtitleLanguage,
                        onStarted = { p, d ->
                            viewModel.onPlaybackStarted(
                                current.item,
                                current.videoId,
                                current.title,
                                current.source,
                                activeSubtitles,
                                p,
                                d
                            )
                        },
                        onProgress = { p, d ->
                            viewModel.onPlaybackProgress(
                                current.item,
                                current.videoId,
                                current.title,
                                current.source,
                                activeSubtitles,
                                p,
                                d
                            )
                        },
                        onPlaybackHealth = { bufferMs, isBuffering ->
                            viewModel.reportPlaybackBuffer(bufferMs, isBuffering)
                        },
                        onStopped = { p, d ->
                            viewModel.onPlaybackStopped(
                                current.item,
                                current.videoId,
                                current.title,
                                current.source,
                                activeSubtitles,
                                p,
                                d
                            )
                        },
                        onSourceSwitch = { positionMs, reason, standby, standbySource ->
                            viewModel.forgetLastPlaybackSession(current.item, current.videoId)
                            val requestKey = viewModel.sourceRequestKey(current.item, current.videoId)
                            val failedUrl = current.source.playableUrl
                            val excluded = if (failedUrl.isNullOrBlank()) {
                                current.excludedUrls
                            } else {
                                current.excludedUrls + failedUrl
                            }
                            if (standby != null && standbySource != null) {
                                val notice = "SOURCE SWITCHED · $reason" +
                                    "\nFROM: ${current.source.switchIdentityLabel()}" +
                                    "\nTO: ${standbySource.switchIdentityLabel()}"
                                screen = Screen.Player(
                                    item = current.item,
                                    videoId = current.videoId,
                                    title = current.title,
                                    source = standbySource,
                                    returnToSources = false,
                                    resumeMsOverride = positionMs,
                                    resumeSubtitles = activeSubtitles,
                                    excludedUrls = excluded,
                                    switchNotice = notice,
                                    prewarmedPlayer = standby
                                )
                            } else {
                                // Reuse already available source results. Requery only
                                // when we have no matching results to choose from.
                                if (state.sourceRequestKey != requestKey ||
                                    state.streamOptions.isEmpty()) {
                                    viewModel.loadSources(current.item, current.videoId)
                                }
                                screen = Screen.AutoPlay(
                                    item = current.item,
                                    videoId = current.videoId,
                                    title = current.title,
                                    requestKey = requestKey,
                                    excludedUrls = excluded,
                                    resumeMsOverride = positionMs,
                                    resumeSubtitles = activeSubtitles,
                                    switchFromLabel = current.source.switchIdentityLabel(),
                                    switchReason = reason
                                )
                            }
                        }
                    )
                }
            }
                state.message?.let {
                    Box(Modifier.align(Alignment.BottomCenter).padding(24.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xEE22252B)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text(it, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeTrailerScreen(
    title: String,
    youtubeId: String
) {
    val context = LocalContext.current
    var launchError by remember(youtubeId) { mutableStateOf<String?>(null) }
    var launched by remember(youtubeId) { mutableStateOf(false) }
    val youtubeUrl = remember(youtubeId) { "https://www.youtube.com/watch?v=$youtubeId" }

    fun launchYouTube() {
        val tvIntent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl)).apply {
            setPackage("com.google.android.youtube.tv")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val opened = runCatching {
            context.startActivity(tvIntent)
            true
        }.getOrElse {
            runCatching {
                context.startActivity(genericIntent)
                true
            }.getOrDefault(false)
        }

        launched = opened
        launchError = if (opened) null else "No YouTube app or browser handler was available."
    }

    LaunchedEffect(youtubeId) {
        delay(120)
        launchYouTube()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(36.dp)
        ) {
            Text("TRAILER", color = NmGold, fontSize = 13.sp, fontWeight = FontWeight.Black)
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                if (launchError != null) launchError!!
                else if (launched) "Opened in YouTube TV. Press Back to return to NM Stream TV."
                else "Opening trailer in YouTube TV…",
                color = NmMuted,
                fontSize = 15.sp
            )
            Button(onClick = ::launchYouTube) { Text("Open trailer in YouTube") }
        }
    }
}
@Composable
private fun DeviceBlockedScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("NM STREAM TV", color = NmGold, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(28.dp))
        Text(
            "Device Blocked by NM",
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Access to this device has been paused from NM Account.",
            color = NmMuted,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Only the NM administrator can restore access to this device.",
            color = NmMuted,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun Shell(selected: String, navigate: (Screen) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .height(74.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF111317), Color(0xFF08090B), Color(0xFF050607))
                    )
                )
                .border(0.5.dp, NmGold.copy(alpha = .22f))
                .padding(horizontal = 30.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NM", color = NmPlatinum, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Text(" STREAM", color = NmGold, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(24.dp))
            listOf(
                "Home" to Screen.Home,
                "My List" to Screen.MyList,
                "Search" to Screen.Search,
                "Add-ons" to Screen.Addons,
                "Settings" to Screen.Settings
            ).forEach { (label, target) ->
                NavChip(label, selected == label) { navigate(target) }
                Spacer(Modifier.width(5.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("MORRISON ENTERTAINMENT", color = NmMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    !enabled -> Color(0xFF2B2E33)
                    focused || selected -> NmRed
                    else -> Color(0xFF22252A)
                }
            )
            .border(
                if ((focused || selected) && enabled) 3.dp else 1.dp,
                if ((focused || selected) && enabled) Color.White else Color.White.copy(alpha = .16f),
                RoundedCornerShape(8.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .focusable(enabled)
            .padding(horizontal = 17.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        content = content
    )
}

@Composable
private fun NavChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> NmRed
                    selected -> NmGold.copy(alpha = .34f)
                    else -> Color.Transparent
                }
            )
            .border(
                when {
                    focused -> 3.dp
                    selected -> 2.dp
                    else -> 0.dp
                },
                when {
                    focused -> Color.White
                    selected -> NmGold
                    else -> Color.Transparent
                },
                RoundedCornerShape(20.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (focused) Color.White else if (selected) NmGold else NmPlatinum,
            fontWeight = if (focused || selected) FontWeight.Black else FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun HomeScreen(
    state: MainUiState,
    onOpen: (AppMedia) -> Unit,
    onContinue: (PlaybackProgress) -> Unit,
    onContinueManual: (PlaybackProgress) -> Unit,
    onExpand: (String, String) -> Unit,
    onToggleMyList: (AppMedia) -> Unit
) {
    if (state.loading) {
        CenterText("Loading NM Stream TV TV Box Lite…")
        return
    }
    val hero = state.movies.firstOrNull() ?: state.series.firstOrNull()
    val historyMedia = state.watchHistory.map { it.media }.distinctBy { it.meta.id }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item { if (hero != null) Hero(hero, onOpen) else EmptyHero(state.addons.isEmpty()) }

        item {
            ContinueRow(
                media = state.continueWatching,
                onOpen = onContinue,
                onLongOpen = onContinueManual,
                onExpand = { onExpand("Continue Watching", "continue_watching") }
            )
        }

        if (state.trendingMovies.isNotEmpty()) item {
            MediaRow(
                title = "Trending Movies",
                media = state.trendingMovies,
                onOpen = onOpen,
                myList = state.myList,
                onToggleMyList = onToggleMyList,
                onExpand = { onExpand("Trending Movies", "trending_movies") }
            )
        }
        if (state.newMovies.isNotEmpty()) item {
            MediaRow(
                title = "New Movies",
                media = state.newMovies,
                onOpen = onOpen,
                myList = state.myList,
                onToggleMyList = onToggleMyList,
                onExpand = { onExpand("New Movies", "new_movies") }
            )
        }
        if (state.trendingSeries.isNotEmpty()) item {
            MediaRow(
                title = "Trending Series",
                media = state.trendingSeries,
                onOpen = onOpen,
                myList = state.myList,
                onToggleMyList = onToggleMyList,
                onExpand = { onExpand("Trending Series", "trending_series") }
            )
        }
        if (state.newSeries.isNotEmpty()) item {
            MediaRow(
                title = "New Series",
                media = state.newSeries,
                onOpen = onOpen,
                myList = state.myList,
                onToggleMyList = onToggleMyList,
                onExpand = { onExpand("New Series", "new_series") }
            )
        }
        if (state.nowAiringSeries.isNotEmpty()) item {
            MediaRow(
                title = "Now Airing TV Shows",
                media = state.nowAiringSeries,
                onOpen = onOpen,
                myList = state.myList,
                onToggleMyList = onToggleMyList,
                onExpand = { onExpand("Now Airing TV Shows", "now_airing_series") }
            )
        }
        if (historyMedia.isNotEmpty()) item {
            MediaRow(
                title = "Watch History",
                media = historyMedia,
                onOpen = onOpen,
                myList = state.myList,
                onToggleMyList = onToggleMyList,
                onExpand = { onExpand("Watch History", "watch_history") }
            )
        }
        if (state.movies.isNotEmpty()) item {
            MediaRow(
                title = "Movies",
                media = state.movies,
                onOpen = onOpen,
                myList = state.myList,
                onToggleMyList = onToggleMyList,
                onExpand = { onExpand("Movies", "movies") }
            )
        }
        if (state.series.isNotEmpty()) item {
            MediaRow(
                title = "Series",
                media = state.series,
                onOpen = onOpen,
                myList = state.myList,
                onToggleMyList = onToggleMyList,
                onExpand = { onExpand("Series", "series") }
            )
        }
        if (state.debridItems.isNotEmpty()) item {
            MediaRow(
                title = "My Real-Debrid Library",
                media = state.debridItems,
                onOpen = onOpen,
                myList = state.myList,
                onToggleMyList = onToggleMyList,
                onExpand = { onExpand("My Real-Debrid Library", "real_debrid") }
            )
        }
    }
}
@Composable
private fun ExpandedRowScreen(
    title: String,
    page: Int,
    loading: Boolean,
    items: List<AppMedia>,
    continueItems: List<PlaybackProgress>,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onHome: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: (AppMedia) -> Unit,
    onContinue: (PlaybackProgress) -> Unit,
    onContinueManual: (PlaybackProgress) -> Unit,
    myList: List<AppMedia>,
    onToggleMyList: (AppMedia) -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(NmBg).padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
                Text("Page $page", color = NmMuted, fontSize = 13.sp)
            }
            Button(onClick = onHome) { Text("⌂  HOME") }
            Button(onClick = onPrevious, enabled = hasPrevious && !loading) { Text("←  PREVIOUS") }
            Button(onClick = onNext, enabled = hasNext && !loading) { Text("NEXT  →") }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading page $page…", color = NmMuted, fontSize = 18.sp)
            }
        } else if (items.isEmpty() && continueItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No more results on this page.", color = NmMuted, fontSize = 18.sp)
            }
        } else if (continueItems.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                gridItems(continueItems, key = { "${it.media.meta.id}|${it.videoId}" }) { progress ->
                    ExpandedContinueCard(progress, onContinue, onContinueManual)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                gridItems(items, key = { "${it.meta.type}|${it.meta.tmdbId ?: it.meta.id}" }) { media ->
                    ExpandedPosterCard(
                        item = media,
                        onOpen = onOpen,
                        inMyList = myList.any { mediaMatches(it, media) },
                        onToggleMyList = onToggleMyList
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedPosterCard(
    item: AppMedia,
    onOpen: (AppMedia) -> Unit,
    inMyList: Boolean,
    onToggleMyList: (AppMedia) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var showQuickMenu by remember(item.meta.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvActivation(
                onClick = { onOpen(item) },
                onLongClick = {
                    if (item.meta.type == "movie" || item.meta.type == "series") showQuickMenu = true
                    else onOpen(item)
                }
            )
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(NmPanel)
                .border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = item.meta.poster ?: item.meta.background,
                contentDescription = item.meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(item.meta.name, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.meta.releaseInfo?.let { Text(it, color = NmMuted, fontSize = 11.sp) }
    }

    if (showQuickMenu) {
        QuickMyListMenu(
            item = item,
            inMyList = inMyList,
            onToggle = {
                onToggleMyList(item)
                showQuickMenu = false
            },
            onDismiss = { showQuickMenu = false }
        )
    }
}

@Composable
private fun ExpandedContinueCard(
    progress: PlaybackProgress,
    onOpen: (PlaybackProgress) -> Unit,
    onLongOpen: (PlaybackProgress) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvActivation(
                onClick = { onOpen(progress) },
                onLongClick = { onLongOpen(progress) }
            )
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(NmPanel)
                .border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = progress.media.meta.background ?: progress.media.meta.poster,
                contentDescription = progress.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = .20f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.percent.coerceAtLeast(1) / 100f).background(NmRed))
            }
        }
        Text(progress.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${progress.percent}% · hold for sources", color = NmMuted, fontSize = 11.sp)
    }
}
@Composable
private fun Hero(item: AppMedia, onOpen: (AppMedia) -> Unit) {
    Box(Modifier.fillMaxWidth().height(400.dp)) {
        AsyncImage(model = item.meta.background ?: item.meta.poster, contentDescription = item.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NmBg, NmBg.copy(alpha = .82f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, NmBg))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 52.dp).widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NM FEATURED", color = NmRed, fontWeight = FontWeight.Black)
            Text(item.meta.name, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item.meta.releaseInfo?.let { Text(it, color = NmMuted) }
                item.meta.imdbRating?.let { Text("★ " + it, color = Color.White, fontWeight = FontWeight.Bold) }
                item.meta.genres.take(2).forEach { Text(it, color = NmMuted) }
            }
            item.meta.description?.let { Text(it, color = Color.White.copy(alpha = .9f), fontSize = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            Button(onClick = { onOpen(item) }) { Text("▶  Watch") }
        }
    }
}

@Composable
private fun EmptyHero(noAddons: Boolean) {
    Box(Modifier.fillMaxWidth().height(300.dp).background(Brush.horizontalGradient(listOf(Color(0xFF191B22), NmBg)))) {
        Column(Modifier.align(Alignment.CenterStart).padding(52.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("NM STREAM TV", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text(if (noAddons) "Install a compatible Stremio add-on to populate your home screen." else "No home catalogue was returned.", color = NmMuted, fontSize = 18.sp)
        }
    }
}

@Composable
private fun PersonalMediaRow(
    title: String,
    media: List<AppMedia>,
    emptyText: String,
    onOpen: (AppMedia) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(10.dp))
            Text("TRAKT LIST", color = NmRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        if (media.isEmpty()) {
            Box(
                Modifier
                    .padding(horizontal = 40.dp)
                    .fillMaxWidth()
                    .height(82.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NmPanel)
                    .padding(18.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(emptyText, color = NmMuted)
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(media) { item ->
                    PosterCard(item = item, onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun MediaRow(
    title: String,
    media: List<AppMedia>,
    onOpen: (AppMedia) -> Unit,
    myList: List<AppMedia> = emptyList(),
    onToggleMyList: ((AppMedia) -> Unit)? = null,
    onExpand: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            onExpand?.let { action ->
                Button(onClick = action) { Text("EXPAND  ↗") }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(media) { item ->
                PosterCard(
                    item = item,
                    onOpen = onOpen,
                    inMyList = myList.any { mediaMatches(it, item) },
                    onToggleMyList = onToggleMyList
                )
            }
        }
    }
}
@Composable
private fun PosterCard(
    item: AppMedia,
    onOpen: (AppMedia) -> Unit,
    inMyList: Boolean = false,
    onToggleMyList: ((AppMedia) -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    var showQuickMenu by remember(item.meta.id) { mutableStateOf(false) }
    val canQuickList = onToggleMyList != null && (item.meta.type == "movie" || item.meta.type == "series")
    val scale by animateFloatAsState(if (focused) 1.10f else 1f, label = "poster")

    Column(
        Modifier.width(165.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .tvActivation(
                onClick = { onOpen(item) },
                onLongClick = {
                    if (canQuickList) showQuickMenu = true else onOpen(item)
                }
            )
            .focusable()
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(NmPanel)
                .border(
                    if (focused) 4.dp else 0.dp,
                    if (focused) Color.White else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
        ) {
            AsyncImage(
                model = item.meta.poster ?: item.meta.background,
                contentDescription = item.meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(item.meta.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    if (showQuickMenu && onToggleMyList != null) {
        QuickMyListMenu(
            item = item,
            inMyList = inMyList,
            onToggle = {
                onToggleMyList(item)
                showQuickMenu = false
            },
            onDismiss = { showQuickMenu = false }
        )
    }
}

@Composable
private fun QuickMyListMenu(
    item: AppMedia,
    inMyList: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { firstFocus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(min = 360.dp, max = 520.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF111319))
                .border(3.dp, Color.White, RoundedCornerShape(14.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                item.meta.name,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Quick action",
                color = NmMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onToggle,
                selected = inMyList,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
            ) {
                Text(if (inMyList) "✓ Remove from My List" else "+ Add to My List")
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue browsing")
            }
        }
    }
}

@Composable
private fun ContinueRow(
    media: List<PlaybackProgress>,
    onOpen: (PlaybackProgress) -> Unit,
    onLongOpen: (PlaybackProgress) -> Unit,
    onExpand: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Continue Watching", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            onExpand?.let { action -> Button(onClick = action) { Text("EXPAND  ↗") } }
        }
        if (media.isEmpty()) {
            Box(
                Modifier
                    .padding(horizontal = 40.dp)
                    .fillMaxWidth()
                    .height(82.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NmPanel)
                    .padding(18.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("Movies and episodes you start watching will appear here.", color = NmMuted)
            }
        } else LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(media) { p ->
                var focused by remember { mutableStateOf(false) }
                Column(
                    Modifier.width(240.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .tvActivation(
                            onClick = { onOpen(p) },
                            onLongClick = { onLongOpen(p) }
                        )
                        .focusable()
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(NmPanel).border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))) {
                        AsyncImage(model = p.media.meta.background ?: p.media.meta.poster, contentDescription = p.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = .2f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(p.percent.coerceAtLeast(1) / 100f).background(NmRed))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(p.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (p.source?.let { "$it · " } ?: "") + p.percent + "% · hold for sources",
                        color = NmMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvScreen(state: MainUiState, onOpen: (AppMedia) -> Unit) {
    val guideChannels = state.iptvChannels
        .filter { state.iptvGuide[it.meta.id].orEmpty().isNotEmpty() }
        .take(40)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 26.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 42.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live TV & Sports", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(
                    if (state.iptvConfigured) state.iptvStatus + " · " + state.iptvChannels.size + " channels loaded"
                    else "Add your M3U playlist or Xtream account in Settings.",
                    color = if (state.iptvConfigured) NmGreen else NmMuted
                )
                Text(
                    "Dedicated Rugby, F1 & Motorsport, Soccer and Cricket categories are prioritised above your provider's own channel groups.",
                    color = NmMuted
                )
            }
        }

        if (guideChannels.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 42.dp)) {
                    Text("TV Guide · Now & Next", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Programme data from your XMLTV/Xtream EPG.", color = NmMuted)
                }
            }
            items(guideChannels, key = { "guide-${it.meta.id}" }) { channel ->
                EpgChannelRow(channel, state.iptvGuide[channel.meta.id].orEmpty(), onOpen)
            }
        }

        state.iptvCategories.forEach { category ->
            item(key = "category-${category.name}") {
                MediaRow(category.name, category.channels.take(120), onOpen)
            }
        }

        if (state.iptvChannels.isNotEmpty()) item {
            MediaRow("All Channels", state.iptvChannels.take(180), onOpen)
        }

        if (state.iptvConfigured && state.iptvChannels.isEmpty()) item {
            Text(
                "No channels could be loaded from the configured IPTV source.",
                color = NmMuted,
                modifier = Modifier.padding(horizontal = 42.dp)
            )
        }
    }
}

@Composable
private fun EpgChannelRow(
    channel: AppMedia,
    programmes: List<EpgProgramme>,
    onOpen: (AppMedia) -> Unit
) {
    val nowMs = System.currentTimeMillis()
    val current = programmes.firstOrNull { it.isLive(nowMs) }
    val next = programmes.firstOrNull { it.startMs >= (current?.stopMs ?: nowMs) }
    var focused by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) NmRed else NmPanel)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = .10f), RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onOpen(channel) }
            .focusable()
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AsyncImage(
            model = channel.meta.poster ?: channel.meta.background,
            contentDescription = channel.meta.name,
            modifier = Modifier.size(74.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit
        )
        Column(Modifier.width(220.dp)) {
            Text(channel.meta.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(channel.meta.genres.firstOrNull() ?: "Live TV", color = NmRed, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (current != null) {
                Text(
                    "NOW · ${formatGuideTime(current.startMs)}–${formatGuideTime(current.stopMs)}",
                    color = NmGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(current.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                current.description?.let {
                    Text(it, color = NmMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
                val duration = (current.stopMs - current.startMs).coerceAtLeast(1L)
                val progress = ((nowMs - current.startMs).coerceIn(0L, duration).toFloat() / duration.toFloat())
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .15f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(NmRed))
                }
            } else {
                Text("NO CURRENT EPG PROGRAMME", color = NmMuted, fontSize = 12.sp)
            }
            next?.let {
                Text(
                    "NEXT · ${formatGuideTime(it.startMs)}  ${it.title}",
                    color = NmMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp
                )
            }
        }
        Text("WATCH", color = NmGreen, fontWeight = FontWeight.Black)
    }
}

private fun formatGuideTime(timeMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))

@Composable
private fun SearchScreen(
    state: MainUiState,
    onSearch: (String, SearchCategory) -> Unit,
    onOpen: (AppMedia) -> Unit,
    onToggleMyList: (AppMedia) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(SearchCategory.MOVIE) }
    var submittedCategory by remember { mutableStateOf(SearchCategory.MOVIE) }
    var editing by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(editing) {
        if (editing) {
            delay(80)
            runCatching { searchFocus.requestFocus() }
            keyboard?.show()
        }
    }

    fun submit(value: String) {
        val submitted = value.trim()
        if (submitted.isBlank()) return
        query = submitted
        submittedQuery = submitted
        submittedCategory = category
        editing = false
        keyboard?.hide()
        onSearch(submitted, category)
    }

    fun selectCategory(next: SearchCategory) {
        category = next
        if (submittedQuery.isNotBlank()) {
            submittedCategory = next
            onSearch(submittedQuery, next)
        }
    }

    val resultLabel = when (submittedCategory) {
        SearchCategory.MOVIE -> "Movie results"
        SearchCategory.SERIES -> "TV Series results"
        SearchCategory.PERSON -> "Titles featuring this actor/person"
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 42.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text("Search", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            if (!editing) {
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF111319))
                        .border(1.dp, Color(0xFF343841), RoundedCornerShape(8.dp))
                        .clickable { editing = true }
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Text(
                        if (query.isBlank()) "Tap here to search" else query,
                        color = if (query.isBlank()) NmMuted.copy(alpha = .7f) else Color.White,
                        fontSize = 16.sp
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF111319))
                            .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (query.isBlank()) Text("Search by title or person name", color = NmMuted.copy(alpha = .7f))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(NmRed),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                        )
                    }
                    Button(
                        onClick = { submit(query) },
                        enabled = query.isNotBlank() && !state.searchLoading
                    ) { Text(if (state.searchLoading) "Searching…" else "Search") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SearchCategory.MOVIE to "Movie",
                    SearchCategory.SERIES to "TV Series",
                    SearchCategory.PERSON to "Actor / Person"
                ).forEach { (value, label) ->
                    Button(
                        onClick = { selectCategory(value) },
                        selected = category == value
                    ) {
                        Text(if (category == value) "✓ $label" else label)
                    }
                }
            }
        }

        if (!editing && state.recentSearches.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Recent searches", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Stored only on this device · latest 5 only", color = NmMuted, fontSize = 12.sp)
                    state.recentSearches.forEach { recent ->
                        Button(onClick = { submit(recent) }, modifier = Modifier.fillMaxWidth()) {
                            Text("↻  $recent")
                        }
                    }
                }
            }
        }

        if (state.searchLoading) {
            item { Text("Searching…", color = NmMuted) }
        } else if (submittedQuery.isNotBlank()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(resultLabel, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (submittedCategory == SearchCategory.PERSON) {
                        Text("Showing movies and series credited to the closest TMDB person match.", color = NmMuted, fontSize = 12.sp)
                    }
                }
            }
            item {
                if (state.searchResults.isEmpty()) {
                    Text("No results found for “$submittedQuery”.", color = NmMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        state.searchResults.chunked(6).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                                row.forEach { media ->
                                    PosterCard(
                                        item = media,
                                        onOpen = onOpen,
                                        inMyList = state.myList.any { mediaMatches(it, media) },
                                        onToggleMyList = onToggleMyList
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun MyListScreen(media: List<AppMedia>, onOpen: (AppMedia) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 42.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("My List", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text("Saved only on this Mobile Lite installation. Nothing in My List is synced to other devices.", color = NmMuted)
        }
        if (media.isEmpty()) {
            item {
                CardBox {
                    Text("Your list is empty", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Open any movie or series and choose + My List.", color = NmMuted)
                }
            }
        } else {
            items(media.chunked(6)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    row.forEach { PosterCard(it, onOpen) }
                }
            }
        }
    }
}
@Composable
private fun AddonsScreen(
    state: MainUiState,
    install: (String) -> Unit,
    remove: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }

    LaunchedEffect(state.addonInstallStatus) {
        if (state.addonInstallStatus?.startsWith("Installed ") == true) url = ""
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 42.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("Add-ons", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(
                "Manual installation only. Paste the manifest URL for the add-on you want to use.",
                color = NmMuted
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    InputBox(url, "https://example.com/manifest.json") { url = it }
                }
                Button(
                    onClick = { install(url) },
                    enabled = url.isNotBlank() && !state.addonInstalling
                ) {
                    Text(if (state.addonInstalling) "Installing…" else "Install add-on")
                }
            }

            state.addonInstallStatus?.let { status ->
                Spacer(Modifier.height(8.dp))
                Text(
                    status,
                    color = when {
                        status.startsWith("Installed ") -> NmGreen
                        status == "Checking manifest…" -> NmMuted
                        status == "Add-on removed" -> NmMuted
                        else -> NmRed
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            Text("Installed add-ons", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.addons.isEmpty()) "No add-ons currently responding."
                else "Providers that responded to the latest manifest check.",
                color = NmMuted
            )
            if (state.pendingAddonHosts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Saved but not responding: " + state.pendingAddonHosts.joinToString(", "),
                    color = NmRed,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "These add-ons remain saved. Settings → NM Account → Sync now retries them, or restart the app to refresh.",
                    color = NmMuted
                )
            }
        }

        items(state.addons) { addon ->
            CardBox {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(addon.manifest.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            addon.manifest.description ?: addon.manifestUrl,
                            color = NmMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            runCatching { java.net.URI(addon.manifestUrl).host }.getOrNull() ?: "Configured add-on",
                            color = NmMuted.copy(alpha = .72f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(onClick = { remove(addon.manifestUrl) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: MainUiState, vm: MainViewModel) {
    var showConnectionLog by remember { mutableStateOf(false) }
    if (showConnectionLog) {
        Dialog(onDismissRequest = { showConnectionLog = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NmPanel)
                    .border(1.dp, NmGold, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Connection Log", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(
                    "This session only · device connection and observed app download-rate dips. " +
                        "Not an internet speed test; buffering may naturally pause downloads.",
                    color = NmMuted,
                    fontSize = 12.sp
                )
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (state.connectionLog.isEmpty()) {
                        item { Text("No events yet.", color = NmMuted) }
                    } else {
                        items(state.connectionLog.asReversed()) { entry ->
                            Text(
                                "${entry.time}  ·  ${entry.detail}",
                                color = if (
                                    entry.detail.contains("lost", true) ||
                                    entry.detail.contains("dip", true) ||
                                    entry.detail.contains("No incoming", true)
                                ) NmGold else NmPlatinum,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                Button(onClick = { showConnectionLog = false }) { Text("Close log") }
            }
        }
    }
    var audioLang by remember(state.preferredAudioLanguage) { mutableStateOf(state.preferredAudioLanguage) }
    var subtitleLang by remember(state.preferredSubtitleLanguage) { mutableStateOf(state.preferredSubtitleLanguage) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentPadding = PaddingValues(top = 26.dp, bottom = 55.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Settings", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black) }
        item { CardBox {
            Text("NM Account", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.nmAccountLinked) {
                    "Linked" + (state.nmAccountName?.let { " · $it" } ?: "")
                } else {
                    "Not linked"
                },
                color = if (state.nmAccountLinked) NmGreen else NmMuted
            )
            Text(
                "Cloud sync is manual after pairing. Changes on this TV stay local until you press Sync now. " +
                    "Phone-account changes are pulled when you press Sync now.",
                color = NmMuted
            )
            if (state.nmAccountLinked) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = vm::syncNmAccountNow) { Text("Sync now") }
                    Button(onClick = vm::unlinkNmAccount) { Text("Unlink") }
                }
                Text("Sync status: ${state.nmSyncStatus}", color = NmGreen)
                Text("Sync now pushes pending local add-ons, language preferences and Real-Debrid changes; then fetches account settings.", color = NmMuted, fontSize = 12.sp)
            } else {
                Button(
                    onClick = vm::beginNmAccountPairing,
                    enabled = !state.nmPairing
                ) { Text(if (state.nmPairing) "Waiting for phone…" else "Link this device") }

                state.nmPairCode?.let { code ->
                    DeviceCode("NM Account", code)
                }
            }
        } }
        item { CardBox {
            Text("Playback & language", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Changes are saved locally. Use Settings → Sync now to send them to linked devices.", color = NmMuted)

            Text("TV Box Lite source policy", color = Color.White, fontWeight = FontWeight.Bold)
            Text("No fixed provider preference. 720p first, then 1080p fallback. Streams above 1080p are excluded.", color = NmMuted, fontSize = 12.sp)

            Text("Preferred audio language", color = Color.White, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth()) { InputBox(audioLang, "en") { audioLang = it } }
            Text("Examples: en, de, fr, es. The player will still let you switch tracks manually.", color = NmMuted, fontSize = 12.sp)

            Text("Preferred subtitle language", color = Color.White, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth()) { InputBox(subtitleLang, "en") { subtitleLang = it } }

            Button(onClick = {
                vm.saveMobileLiteLanguagePreferences(audioLang, subtitleLang)
            }) { Text("Save language preferences") }
        } }
        item { CardBox {
            Text("TMDB metadata, actor search & discovery", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Status: " + state.tmdbStatus, color = if (state.tmdbConfigured) NmGreen else NmMuted)
            Text(
                "TMDB is built into Mobile Lite and works automatically for artwork, actor search, New Movies, Trending Movies, New Series and Trending Series.",
                color = NmMuted
            )
        } }
        item { CardBox {
            Text("Real-Debrid", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(state.rdUser?.let { "Connected as " + it.username } ?: "Not connected", color = if (state.rdUser != null) NmGreen else NmMuted)
            if (state.rdUser != null) Button(onClick = vm::disconnectRealDebrid) { Text("Disconnect Real-Debrid everywhere") } else Button(onClick = vm::beginRealDebridSignIn) { Text(if (state.rdConnecting) "Waiting…" else "Connect Real-Debrid") }
            state.rdDeviceCode?.let { DeviceCode("Real-Debrid", it.userCode, it.verificationUrl) }
        } }
        item { CardBox {
            Text("Connection diagnostics", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Timestamped connection losses, recovery and app download-rate dips. Clears when this app session ends.", color = NmMuted)
            Button(onClick = { showConnectionLog = true }) { Text("Connection Log") }
            Text("${state.connectionLog.size} events this session", color = NmMuted, fontSize = 12.sp)
        } }
        item { Text("NM Stream TV TV Box Lite v1.0.0-tvbox.15 · Morrison Entertainment", color = NmMuted) }
    }
}

@Composable
private fun DeviceCode(service: String, code: String, url: String? = null) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0C0D11)).padding(14.dp)) {
        Text("Connect " + service, color = Color.White, fontWeight = FontWeight.Bold)
        if (!url.isNullOrBlank()) {
            Text("Visit " + url + " and enter:", color = NmMuted)
        } else {
            Text("Enter this code in the private NM administrator panel.", color = NmMuted)
        }
        Text(code, color = NmRed, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun DetailsScreen(
    item: AppMedia,
    loading: Boolean,
    recommendations: List<AppMedia>,
    onOpenRecommendation: (AppMedia) -> Unit,
    trailer: StreamOption?,
    inMyList: Boolean,
    rememberedSeason: Int?,
    rememberedEpisodeId: String?,
    toggleMyList: () -> Unit,
    rememberSeason: (Int) -> Unit,
    rememberEpisode: (VideoItem) -> Unit,
    play: (AppMedia, String, String) -> Unit,
    chooseManual: (AppMedia, String, String) -> Unit,
    playTrailer: (StreamOption) -> Unit
) {
    val seasons = remember(item.meta.id, item.meta.videos) {
        item.meta.videos.map { it.season ?: 1 }.distinct().sorted()
    }
    val selectedSeason = rememberedSeason?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1
    val seasonEpisodes = remember(item.meta.videos, selectedSeason) {
        item.meta.videos
            .filter { (it.season ?: 1) == selectedSeason }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }
    val detailsListState = rememberLazyListState()

    LaunchedEffect(item.meta.id, selectedSeason, rememberedEpisodeId) {
        val episodeIndex = seasonEpisodes.indexOfFirst { it.id == rememberedEpisodeId }
        if (episodeIndex >= 0) {
            delay(80)
            detailsListState.scrollToItem(4 + episodeIndex)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(model = item.meta.background ?: item.meta.poster, contentDescription = item.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NmBg, NmBg.copy(alpha = .9f), NmBg.copy(alpha = .4f)))))
        LazyColumn(Modifier.fillMaxSize().padding(48.dp), state = detailsListState, contentPadding = PaddingValues(bottom = 50.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.meta.name, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
                    if (loading) Text("Loading enhanced metadata…", color = NmRed)
                    val facts = buildList {
                        item.meta.fullReleaseDate?.takeIf { it.isNotBlank() }
                            ?.let { add("Released $it") }
                            ?: item.meta.releaseInfo?.let { add(it) }
                        item.meta.runtimeMinutes?.takeIf { it > 0 }?.let { minutes ->
                            add(if (item.meta.type == "series") "Episode · $minutes min"
                                else "${minutes / 60}h ${minutes % 60}m")
                        }
                        item.meta.imdbRating?.let { add("★ $it / 10") }
                        item.meta.contentRating?.let { add(it) }
                    }
                    if (facts.isNotEmpty()) Text(
                        facts.joinToString("  ·  "),
                        color = NmGold, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                    if (item.meta.ratingCount != null) Text(
                        "${item.meta.ratingCount} TMDB votes",
                        color = NmMuted, fontSize = 12.sp
                    )
                    if (item.meta.genres.isNotEmpty()) Text(
                        item.meta.genres.joinToString("  ·  "),
                        color = NmPlatinum, fontSize = 14.sp
                    )
                    item.meta.description?.takeIf { it.isNotBlank() }?.let { plot ->
                        Text("PLOT", color = NmGold, fontWeight = FontWeight.Black, fontSize = 13.sp)
                        Text(plot, color = Color.White.copy(alpha = .95f), fontSize = 17.sp, lineHeight = 25.sp)
                    }
                    item.meta.director?.let { credit ->
                        Text(
                            (if (item.meta.type == "series") "Created by: " else "Director: ") + credit,
                            color = NmPlatinum, fontSize = 14.sp
                        )
                    }
                    if (item.meta.cast.isNotEmpty()) {
                        Text("CAST", color = NmGold, fontWeight = FontWeight.Black, fontSize = 13.sp)
                        Text(
                            item.meta.cast.joinToString("  ·  "),
                            color = NmPlatinum, fontSize = 14.sp, lineHeight = 21.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (item.meta.type != "series" || item.meta.videos.isEmpty()) {
                            HoldActionButton(
                                label = "▶  Play",
                                onClick = { play(item, item.meta.id, item.meta.name) },
                                onLongClick = { chooseManual(item, item.meta.id, item.meta.name) }
                            )
                        }
                        trailer?.let {
                            Button(onClick = { playTrailer(it) }) {
                                Text("▶  Trailer · 1080p preferred")
                            }
                        }
                        if (item.meta.type == "movie" || item.meta.type == "series") {
                            Button(onClick = toggleMyList, selected = inMyList) {
                                Text(if (inMyList) "✓ My List" else "+ My List")
                            }
                        }
                    }

                    Text(
                        "Press OK/Play to use the best source up to 1080p · Hold OK/Play to choose manually",
                        color = NmGreen,
                        fontSize = 12.sp
                    )

                }
            }
            if (item.meta.videos.isNotEmpty()) {
                item { Text("Seasons", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(seasons) { season ->
                            Button(
                                onClick = { rememberSeason(season) },
                                selected = selectedSeason == season
                            ) {
                                Text(if (selectedSeason == season) "✓ Season $season" else "Season $season")
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Season $selectedSeason · ${seasonEpisodes.size} Episodes",
                        color = Color.White,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(seasonEpisodes) { ep ->
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(when {
                                focused -> NmRed
                                ep.id == rememberedEpisodeId -> NmGold.copy(alpha = .24f)
                                else -> NmPanel
                            })
                            .border(
                                if (focused) 3.dp else if (ep.id == rememberedEpisodeId) 2.dp else 0.dp,
                                if (focused) Color.White else if (ep.id == rememberedEpisodeId) NmGold else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .onFocusChanged { focused = it.isFocused }
                            .tvActivation(
                                onClick = {
                                    rememberEpisode(ep)
                                    play(item, ep.id, ep.displayName())
                                },
                                onLongClick = {
                                    rememberEpisode(ep)
                                    chooseManual(item, ep.id, ep.displayName())
                                }
                            )
                            .focusable()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = ep.thumbnail,
                            contentDescription = ep.displayName(),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(180.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ep.displayName(), color = Color.White, fontWeight = FontWeight.Bold)
                            ep.overview?.let {
                                Text(it, color = NmMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (recommendations.isNotEmpty()) {
                item {
                    Text("More Like This", color = Color.White,
                        fontSize = 26.sp, fontWeight = FontWeight.Black)
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(recommendations) { suggested ->
                            var focused by remember { mutableStateOf(false) }
                            Column(
                                Modifier.width(165.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (focused) NmPanelFocus else NmPanel)
                                    .border(if (focused) 3.dp else 1.dp,
                                        if (focused) NmGold else Color.White.copy(alpha = .12f),
                                        RoundedCornerShape(10.dp))
                                    .onFocusChanged { focused = it.isFocused }
                                    .tvActivation(
                                        onClick = { onOpenRecommendation(suggested) },
                                        onLongClick = { onOpenRecommendation(suggested) }
                                    )
                                    .focusable()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AsyncImage(
                                    model = suggested.meta.poster ?: suggested.meta.background,
                                    contentDescription = suggested.meta.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                                Text(suggested.meta.name, color = Color.White,
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(suggested.meta.releaseInfo,
                                        suggested.meta.imdbRating?.let { "★ $it" })
                                        .joinToString(" · "),
                                    color = NmMuted, fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoPlayScreen(title: String, switching: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                if (switching) "Stream froze · switching source…" else "Finding the best source…",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(title, color = NmMuted, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HoldActionButton(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) NmRed else NmPanelFocus)
            .border(
                if (focused) 3.dp else 0.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .tvActivation(onClick = onClick, onLongClick = onLongClick)
            .focusable()
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

private fun Modifier.tvActivation(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier = composed {
    var pressedAtMs by remember { mutableStateOf<Long?>(null) }

    this
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
        .onPreviewKeyEvent { event ->
            val supported = event.key == Key.DirectionCenter || event.key == Key.Enter

            if (!supported) {
                false
            } else {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (pressedAtMs == null) {
                            pressedAtMs = System.currentTimeMillis()
                        }
                        true
                    }

                    KeyEventType.KeyUp -> {
                        val started = pressedAtMs
                        pressedAtMs = null
                        val heldForMs = started?.let { System.currentTimeMillis() - it } ?: 0L
                        if (heldForMs >= 550L) onLongClick() else onClick()
                        true
                    }

                    else -> false
                }
            }
        }
}

@Composable
private fun SourcesScreen(
    title: String,
    loading: Boolean,
    sources: List<StreamOption>,
    subtitleCount: Int,
    select: (StreamOption) -> Unit
) {
    val visibleSources = sources.filterNot { it.isKnownUncached }
    val recommended = visibleSources.firstOrNull {
        it.playableUrl != null || it.youtubeUrl != null || !it.stream.externalUrl.isNullOrBlank()
    }
    val httpSources = visibleSources.filter { it.playableUrl != null }
    val p2pSources = visibleSources.filter { it.isP2p }
    val otherSources = visibleSources.filter { it.playableUrl == null && !it.isP2p }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 46.dp),
        contentPadding = PaddingValues(top = 34.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("$subtitleCount subtitle tracks found", color = NmMuted)
            Text("TV Box Lite: 720p preferred · 1080p fallback · cached/ready only · auto failover", color = NmGreen, fontSize = 13.sp)
        }

        if (!loading && recommended != null) {
            item {
                Button(onClick = { select(recommended) }) {
                    Text("▶  PLAY DEFAULT · ${recommended.addonName} · ${recommended.qualityLabel()}")
                }
            }
        }

        if (loading) {
            item { Text("Checking installed sources…", color = NmMuted) }
        } else if (visibleSources.isEmpty()) {
            item { Text("No cached/ready streams at 1080p or below are available yet.", color = NmMuted) }
        } else {
            if (httpSources.isNotEmpty()) {
                item { SourceSectionHeading("HTTP", "Direct HTTP and debrid-ready streams") }
                items(httpSources) { source -> SourceResultRow(source, source == recommended, select) }
            }
            if (p2pSources.isNotEmpty()) {
                item { SourceSectionHeading("P2P", "Peer-to-peer sources") }
                items(p2pSources) { source -> SourceResultRow(source, source == recommended, select) }
            }
            if (otherSources.isNotEmpty()) {
                item { SourceSectionHeading("OTHER", "External, YouTube or unclassified sources") }
                items(otherSources) { source -> SourceResultRow(source, source == recommended, select) }
            }
        }
    }
}

@Composable
private fun SourceSectionHeading(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = NmGold, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(description, color = NmMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SourceResultRow(source: StreamOption, isDefault: Boolean, select: (StreamOption) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) NmRed else NmPanel)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) Color.White else Color.White.copy(alpha = .10f),
                RoundedCornerShape(8.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { select(source) }
            .focusable()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(source.displayTitle(), color = Color.White, fontWeight = FontWeight.Bold)
                if (isDefault) Text("DEFAULT", color = NmGreen, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Text(source.addonName, color = NmRed)
            Text(source.statusText() + " · Link #${source.safeLinkId()}", color = NmMuted)
        }
        when {
            source.playableUrl != null || source.youtubeUrl != null -> Text("PLAY", color = NmGreen, fontWeight = FontWeight.Black)
            source.isP2p -> Text("P2P", color = NmMuted, fontWeight = FontWeight.Bold)
            else -> Text("OTHER", color = NmMuted, fontWeight = FontWeight.Bold)
        }
    }
}
private data class PlayerTrackChoice(
    val group: Tracks.Group,
    val trackIndex: Int,
    val label: String,
    val language: String?
)

private fun trackChoiceLabel(group: Tracks.Group, index: Int, fallback: String): String {
    val format = group.getTrackFormat(index)
    val label = format.label?.takeIf { it.isNotBlank() }
    val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
    return listOfNotNull(label, language?.uppercase()).joinToString(" · ").ifBlank { fallback }
}

@Composable
private fun PlayerScreen(
    item: AppMedia,
    videoId: String,
    title: String,
    url: String,
    headers: Map<String, String>,
    subtitles: List<SubtitleOption>,
    resumeMs: Long,
    resumePercent: Double?,
    sourceNotice: String?,
    sourceProvider: String? = null,
    nextSource: StreamOption? = null,
    prewarmedPlayer: ExoPlayer? = null,
    preferredAudioLanguage: String,
    preferredSubtitleLanguage: String,
    onStarted: (Long, Long) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onStopped: (Long, Long) -> Unit,
    onPlaybackHealth: (Long, Boolean) -> Unit = { _, _ -> },
    onSourceSwitch: (Long, String, ExoPlayer?, StreamOption?) -> Unit =
        { _, _, _, _ -> }
) {
    val context = LocalContext.current

    val initialResumeMs = remember(url, videoId) { resumeMs }
    val initialResumePercent = remember(url, videoId) { resumePercent }

    val player = remember(url, videoId, headers, subtitles, prewarmedPlayer) {
        if (prewarmedPlayer != null) {
            prewarmedPlayer.playWhenReady = true
            prewarmedPlayer
        } else {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(90_000)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60_000,
                150_000,
                3_500,
                8_000
            )
            .setBackBuffer(30_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)
            )
            .build()
            .apply {
                val subs = subtitles.mapIndexed { index, option ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(option.subtitle.url))
                        .setId(option.subtitle.id.ifBlank { "sub-" + index })
                        .setLanguage(option.subtitle.lang)
                        .setLabel(option.subtitle.lang.uppercase() + " · " + option.addonName)
                        .setMimeType(subtitleMime(option.subtitle.url))
                        .setSelectionFlags(0)
                        .build()
                }

                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage(preferredAudioLanguage)
                    .setPreferredTextLanguage(preferredSubtitleLanguage)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()

                setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setSubtitleConfigurations(subs)
                        .build()
                )
                prepare()
                playWhenReady = true
            }
        }
    }

    var started by remember(player) { mutableStateOf(false) }
    var resumeApplied by remember(player) {
        mutableStateOf(initialResumeMs <= 0 && initialResumePercent == null)
    }
    // Keep the last real episode position even if a failed player resets to zero.
    var lastReliablePositionMs by remember(player) {
        mutableLongStateOf(initialResumeMs.coerceAtLeast(0L))
    }
    var trackRevision by remember(player) { mutableIntStateOf(0) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    var subtitlesEnabled by remember(player) { mutableStateOf(false) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var controlsRevision by remember(player) { mutableLongStateOf(System.currentTimeMillis()) }
    val playPauseFocus = remember(player) { FocusRequester() }
    var playerPositionMs by remember(player) { mutableLongStateOf(0L) }
    var playerDurationMs by remember(player) { mutableLongStateOf(0L) }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var fillVideo by remember(player) { mutableStateOf(false) }
    var remoteControlIndex by remember(player) { mutableIntStateOf(1) }
    var audioMenuIndex by remember(player) { mutableIntStateOf(0) }
    var subtitleMenuIndex by remember(player) { mutableIntStateOf(0) }
    var scrubMode by remember(player) { mutableStateOf(false) }
    var scrubPositionMs by remember(player) { mutableLongStateOf(0L) }
    var bufferingSinceMs by remember(player) { mutableLongStateOf(0L) }
    var countedBufferStartMs by remember(player) { mutableLongStateOf(0L) }
    var freezeEventsMs by remember(player) { mutableStateOf<List<Long>>(emptyList()) }
    var sourceSwitchRequested by remember(player) { mutableStateOf(false) }
    var recoveryMessage by remember(player) { mutableStateOf<String?>(null) }
    var showSourceNotice by remember(player, sourceNotice) {
        mutableStateOf(!sourceNotice.isNullOrBlank())
    }
    val advisoryItems = remember(item.meta.contentRating, item.meta.contentAdvisories) {
        buildList {
            item.meta.contentRating?.takeIf { it.isNotBlank() }?.let { add("Rated " + it) }
            addAll(item.meta.contentAdvisories.filter { it.isNotBlank() })
        }.distinct()
    }
    var showAdvisory by remember(videoId) { mutableStateOf(advisoryItems.isNotEmpty()) }

    // One paused alternate player. Preparing it in advance lets a genuine
    // buffered source take over without a new stream search and player setup.
    val standbyPlayer = remember(player, nextSource?.playableUrl) {
        nextSource?.playableUrl?.let { backupUrl ->
            val dataSource = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(nextSource.requestHeaders)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(20_000)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(3_000, 12_000, 1_000, 2_000)
                .setBackBuffer(0, false)
                .build()
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)
                )
                .build().apply {
                    val subs = subtitles.mapIndexed { index, option ->
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(option.subtitle.url))
                            .setId(option.subtitle.id.ifBlank { "backup-sub-$index" })
                            .setLanguage(option.subtitle.lang)
                            .setLabel(option.subtitle.lang.uppercase() + " · " + option.addonName)
                            .setMimeType(subtitleMime(option.subtitle.url))
                            .build()
                    }
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguage(preferredAudioLanguage)
                        .setPreferredTextLanguage(preferredSubtitleLanguage)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    setMediaItem(
                        MediaItem.Builder().setUri(backupUrl)
                            .setSubtitleConfigurations(subs).build()
                    )
                    playWhenReady = false
                }
        }
    }
    var standbyPrepareRequested by remember(player, standbyPlayer) { mutableStateOf(false) }
    var standbyTransferred by remember(player, standbyPlayer) { mutableStateOf(false) }
    var lowBufferSamples by remember(player) { mutableIntStateOf(0) }

    fun switchWithStandby(positionMs: Long, reason: String) {
        val pos = maxOf(positionMs.coerceAtLeast(0L), lastReliablePositionMs)
        val standby = standbyPlayer
        val valid = standby != null && nextSource != null && standbyPrepareRequested &&
            standby.playbackState == Player.STATE_READY &&
            standby.duration !in 118_000L..122_000L &&
            pos >= standby.currentPosition - 3_000L &&
            standby.bufferedPosition >= pos + 1_500L
        if (valid && standby != null) {
            standbyTransferred = true
            standby.seekTo(pos)
            standby.playWhenReady = true
            onSourceSwitch(pos, reason, standby, nextSource)
        } else {
            onSourceSwitch(pos, reason, null, null)
        }
    }

    DisposableEffect(standbyPlayer) {
        onDispose { if (!standbyTransferred) standbyPlayer?.release() }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                trackRevision += 1
            }

            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                controlsRevision = System.currentTimeMillis()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSinceMs == 0L) bufferingSinceMs = System.currentTimeMillis()
                } else if (playbackState == Player.STATE_READY) {
                    bufferingSinceMs = 0L
                    countedBufferStartMs = 0L
                    recoveryMessage = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                recoveryMessage = "Stream interrupted · reconnecting…"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, preferredAudioLanguage, preferredSubtitleLanguage) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setPreferredAudioLanguage(preferredAudioLanguage)
            .setPreferredTextLanguage(preferredSubtitleLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        subtitlesEnabled = false
    }

    LaunchedEffect(player, sourceNotice) {
        showSourceNotice = !sourceNotice.isNullOrBlank()
        if (showSourceNotice) {
            delay(7_000L)
            showSourceNotice = false
        }
    }

    val audioTracks = remember(player, trackRevision) {
        player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMapIndexed { groupIndex, group ->
                (0 until group.length).map { trackIndex ->
                    PlayerTrackChoice(
                        group = group,
                        trackIndex = trackIndex,
                        label = trackChoiceLabel(group, trackIndex, "Audio ${groupIndex + 1}.${trackIndex + 1}"),
                        language = group.getTrackFormat(trackIndex).language
                    )
                }
            }
    }

    val textTracks = remember(player, trackRevision) {
        player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMapIndexed { groupIndex, group ->
                (0 until group.length).map { trackIndex ->
                    PlayerTrackChoice(
                        group = group,
                        trackIndex = trackIndex,
                        label = trackChoiceLabel(group, trackIndex, "Subtitle ${groupIndex + 1}.${trackIndex + 1}"),
                        language = group.getTrackFormat(trackIndex).language
                    )
                }
            }
    }

    BackHandler(showAudioMenu || showSubtitleMenu || scrubMode) {
        scrubMode = false
        showAudioMenu = false
        showSubtitleMenu = false
        controlsRevision = System.currentTimeMillis()
    }

    DisposableEffect(
        player,
        controlsVisible,
        showAudioMenu,
        showSubtitleMenu,
        remoteControlIndex,
        audioMenuIndex,
        subtitleMenuIndex,
        scrubMode,
        scrubPositionMs,
        audioTracks,
        textTracks,
        subtitlesEnabled,
        fillVideo
    ) {
        val remoteHandler: (android.view.KeyEvent) -> Boolean = { event ->
            val code = event.keyCode
            val supported = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                code == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                code == android.view.KeyEvent.KEYCODE_ENTER ||
                code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
                code == android.view.KeyEvent.KEYCODE_BUTTON_A ||
                code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ||
                code == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE ||
                code == android.view.KeyEvent.KEYCODE_MEDIA_REWIND ||
                code == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD

            if (!supported) {
                false
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                true
            } else if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                true
            } else if (event.repeatCount > 0 && !scrubMode) {
                true
            } else {
                val now = System.currentTimeMillis()
                when (code) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        controlsVisible = true
                        if (player.isPlaying) player.pause() else player.play()
                        controlsRevision = now
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        controlsVisible = true
                        player.play()
                        controlsRevision = now
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        controlsVisible = true
                        player.pause()
                        controlsRevision = now
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        controlsVisible = true
                        player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0L))
                        controlsRevision = now
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        controlsVisible = true
                        val limit = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + 30_000L).coerceAtMost(limit))
                        controlsRevision = now
                        true
                    }
                    else -> {
                        if (!controlsVisible) {
                            controlsVisible = true
                            remoteControlIndex = 1
                            if (code == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                scrubMode = true
                                scrubPositionMs = player.currentPosition.coerceAtLeast(0L)
                            }
                            controlsRevision = now
                            true
                        } else if (scrubMode) {
                            val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                            when (code) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                                    scrubPositionMs = (scrubPositionMs - 30_000L).coerceAtLeast(0L)
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                                    scrubPositionMs = (scrubPositionMs + 30_000L).coerceAtMost(duration)
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                                android.view.KeyEvent.KEYCODE_BUTTON_A -> {
                                    player.seekTo(scrubPositionMs.coerceAtMost(duration))
                                    scrubMode = false
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    scrubMode = false
                                    scrubPositionMs = player.currentPosition.coerceAtLeast(0L)
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> Unit
                            }
                            controlsRevision = now
                            true
                        } else if (showSubtitleMenu) {
                            val maxIndex = textTracks.size
                            when (code) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> subtitleMenuIndex = (subtitleMenuIndex - 1).coerceAtLeast(0)
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> subtitleMenuIndex = (subtitleMenuIndex + 1).coerceAtMost(maxIndex)
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> showSubtitleMenu = false
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                                android.view.KeyEvent.KEYCODE_BUTTON_A -> {
                                    if (subtitleMenuIndex == 0) {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                            .build()
                                        subtitlesEnabled = false
                                    } else {
                                        textTracks.getOrNull(subtitleMenuIndex - 1)?.let { choice ->
                                            val override = TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.trackIndex))
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                .setOverrideForType(override)
                                                .build()
                                            subtitlesEnabled = true
                                        }
                                    }
                                    showSubtitleMenu = false
                                }
                            }
                            controlsRevision = now
                            true
                        } else if (showAudioMenu) {
                            val maxIndex = (audioTracks.size - 1).coerceAtLeast(0)
                            when (code) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> audioMenuIndex = (audioMenuIndex - 1).coerceAtLeast(0)
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> audioMenuIndex = (audioMenuIndex + 1).coerceAtMost(maxIndex)
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> showAudioMenu = false
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                                android.view.KeyEvent.KEYCODE_BUTTON_A -> {
                                    audioTracks.getOrNull(audioMenuIndex)?.let { choice ->
                                        val override = TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.trackIndex))
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                            .setOverrideForType(override)
                                            .build()
                                    }
                                    showAudioMenu = false
                                }
                            }
                            controlsRevision = now
                            true
                        } else {
                            when (code) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> remoteControlIndex = (remoteControlIndex - 1).coerceAtLeast(0)
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> remoteControlIndex = (remoteControlIndex + 1).coerceAtMost(6)
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                    scrubMode = true
                                    scrubPositionMs = player.currentPosition.coerceAtLeast(0L)
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> Unit
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                                android.view.KeyEvent.KEYCODE_BUTTON_A -> {
                                    when (remoteControlIndex) {
                                        0 -> player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0L))
                                        1 -> if (player.isPlaying) player.pause() else player.play()
                                        2 -> {
                                            val limit = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                            player.seekTo((player.currentPosition + 30_000L).coerceAtMost(limit))
                                        }
                                        3 -> {
                                            subtitleMenuIndex = 0
                                            showSubtitleMenu = true
                                            showAudioMenu = false
                                        }
                                        4 -> {
                                            audioMenuIndex = 0
                                            showAudioMenu = true
                                            showSubtitleMenu = false
                                        }
                                        5 -> fillVideo = !fillVideo
                                        6 -> if (!sourceSwitchRequested) {
                                            sourceSwitchRequested = true
                                            switchWithStandby(
                                                player.currentPosition.coerceAtLeast(0L),
                                                "Skipped source manually"
                                            )
                                        }
                                    }
                                }
                            }
                            controlsRevision = now
                            true
                        }
                    }
                }
            }
        }

        TvRemoteKeyRouter.handler = remoteHandler
        onDispose {
            if (TvRemoteKeyRouter.handler === remoteHandler) TvRemoteKeyRouter.handler = null
        }
    }
    LaunchedEffect(player) {
        while (!resumeApplied) {
            delay(250)
            val duration = player.duration.takeIf { it > 0 } ?: continue
            val target = when {
                initialResumeMs > 0 -> initialResumeMs
                initialResumePercent != null ->
                    (duration * (initialResumePercent.coerceIn(0.0, 99.0) / 100.0)).toLong()
                else -> 0L
            }.coerceIn(0L, (duration - 1L).coerceAtLeast(0L))

            player.seekTo(target)
            lastReliablePositionMs = maxOf(lastReliablePositionMs, target)
            player.playWhenReady = true
            resumeApplied = true
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(500)
            if (sourceSwitchRequested) continue

            val now = System.currentTimeMillis()
            val bufferStart = bufferingSinceMs
            val stalledForMs = if (bufferStart > 0L) now - bufferStart else 0L
            val playerError = player.playerError
            val bufferAhead = player.totalBufferedDuration.coerceAtLeast(0L)
            val remaining = player.duration.takeIf { it > 0L }
                ?.minus(player.currentPosition) ?: Long.MAX_VALUE
            val playbackInProgress = started && player.currentPosition > 5_000L &&
                remaining > 20_000L

            // Low-buffer warning is also a useful proxy for a data source
            // that has slowed or stopped delivering bytes.
            if (standbyPlayer != null && !standbyPrepareRequested &&
                playbackInProgress && bufferAhead < 25_000L
            ) {
                standbyPrepareRequested = true
                standbyPlayer.seekTo(player.currentPosition.coerceAtLeast(0L))
                standbyPlayer.prepare()
            }
            if (standbyPrepareRequested && standbyPlayer != null &&
                standbyPlayer.playbackState == Player.STATE_READY &&
                player.currentPosition > standbyPlayer.bufferedPosition - 2_500L
            ) {
                // Reposition only when the playhead is catching the warm
                // buffer, avoiding constant reloads on the backup connection.
                standbyPlayer.seekTo(player.currentPosition.coerceAtLeast(0L))
            }

            if (player.playbackState == Player.STATE_READY && player.isPlaying &&
                playbackInProgress && bufferAhead in 1L..4_000L
            ) {
                lowBufferSamples += 1
            } else {
                lowBufferSamples = 0
            }
            if (lowBufferSamples >= 4 && standbyPlayer != null &&
                standbyPlayer.playbackState == Player.STATE_READY &&
                standbyPlayer.bufferedPosition > player.currentPosition + 1_500L
            ) {
                sourceSwitchRequested = true
                recoveryMessage = "Buffer low · switching to prebuffered source…"
                switchWithStandby(player.currentPosition, "Predicted buffer underrun")
                continue
            }

            // Some add-ons return a playable 2:00 error/blocked-content video
            // instead of an HTTP error. Recognise that duration regardless of
            // the add-on name (Comet, MediaFusion, etc.). Allow a two-second
            // tolerance for differing container metadata and player rounding.
            // Only apply this to movies and series; never to trailers.
            val briefDuration = player.duration
            val contentIsFeature = item.meta.type == "movie" || item.meta.type == "series"
            val isAddonPlayback = sourceProvider != null && !videoId.startsWith("trailer:")
            val twoMinutePlaceholder = briefDuration in 118_000L..122_000L
            val hostedSource = sourceProvider?.contains("comet", ignoreCase = true) == true ||
                sourceProvider?.contains("elfhosted", ignoreCase = true) == true
            // Preserve the existing short-hosted-video guard for other
            // provider placeholder durations, not just exactly 2:00.
            val shortHostedPlaceholder = hostedSource && briefDuration in 1L..179_999L
            if (contentIsFeature && isAddonPlayback &&
                player.playbackState == Player.STATE_READY &&
                (twoMinutePlaceholder || shortHostedPlaceholder)
            ) {
                sourceSwitchRequested = true
                recoveryMessage = "Provider placeholder detected · trying next source…"
                // Do not store the error video's time as the episode progress.
                // The existing failover excludes this URL for the full chain.
                switchWithStandby(
                    lastReliablePositionMs,
                    if (twoMinutePlaceholder) "Two-minute provider placeholder"
                    else "Short hosted-provider placeholder"
                )
                continue
            }

            if (playerError != null) {
                sourceSwitchRequested = true
                val resumeAt = player.currentPosition.coerceAtLeast(0L)
                val errorText = listOfNotNull(
                    playerError.message,
                    playerError.cause?.message
                ).joinToString(" ")

                val reason = if (
                    errorText.contains("MEDIA_NOT_CACHED_YET", ignoreCase = true) ||
                    errorText.contains("not cached", ignoreCase = true)
                ) {
                    "Media not cached yet"
                } else {
                    "Playback error"
                }

                recoveryMessage = if (reason == "Media not cached yet") {
                    "Source is not cached · switching immediately…"
                } else {
                    "Stream error · switching source…"
                }
                switchWithStandby(resumeAt, reason)
                continue
            }

            if (!started) {
                if (bufferStart > 0L && stalledForMs >= 25_000L) {
                    sourceSwitchRequested = true
                    recoveryMessage = "Source did not start · switching source…"
                    switchWithStandby(
                        player.currentPosition.coerceAtLeast(0L),
                        "Source did not start within 25 seconds"
                    )
                }
                continue
            }

            if (bufferStart > 0L && stalledForMs >= 3_000L && countedBufferStartMs != bufferStart) {
                countedBufferStartMs = bufferStart
                val recent = (freezeEventsMs + now).filter { now - it <= 5 * 60_000L }
                freezeEventsMs = recent
                recoveryMessage = "Buffering detected · " + recent.size + "/4 in 5 min"

                if (recent.size > 3) {
                    sourceSwitchRequested = true
                    val resumeAt = player.currentPosition.coerceAtLeast(0L)
                    recoveryMessage = "Repeated freezes · switching source…"
                    switchWithStandby(resumeAt, "More than 3 freezes in 5 minutes")
                    continue
                }
            }

            if (stalledForMs >= 8_000L) {
                sourceSwitchRequested = true
                val resumeAt = player.currentPosition.coerceAtLeast(0L)
                recoveryMessage = "Buffer stalled too long · switching source…"
                switchWithStandby(resumeAt, "Buffering exceeded 8 seconds")
            }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            val position = player.currentPosition.coerceAtLeast(0L)
            if (resumeApplied && !sourceSwitchRequested &&
                duration > 180_000L && player.playbackState == Player.STATE_READY
            ) {
                lastReliablePositionMs = maxOf(lastReliablePositionMs, position)
                if (!started) {
                    onStarted(position, duration)
                    started = true
                }
                onProgress(position, duration)
            }
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(500)
            playerPositionMs = player.currentPosition.coerceAtLeast(0L)
            playerDurationMs = player.duration.takeIf { it > 0 } ?: 0L
            isPlaying = player.isPlaying
            if (resumeApplied && player.playbackState == Player.STATE_READY &&
                player.duration > 180_000L && !sourceSwitchRequested
            ) {
                lastReliablePositionMs = maxOf(lastReliablePositionMs, player.currentPosition)
            }
            onPlaybackHealth(
                player.totalBufferedDuration,
                player.playbackState == Player.STATE_BUFFERING
            )
        }
    }

    LaunchedEffect(player) {
        delay(250)
        runCatching { playPauseFocus.requestFocus() }
    }

    LaunchedEffect(controlsRevision, showAudioMenu, showSubtitleMenu) {
        controlsVisible = true
        if (!showAudioMenu && !showSubtitleMenu) {
            delay(3_500)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible, showAudioMenu, showSubtitleMenu) {
        if (controlsVisible && !showAudioMenu && !showSubtitleMenu) {
            delay(90)
            runCatching { playPauseFocus.requestFocus() }
        }
    }

    LaunchedEffect(videoId, advisoryItems) {
        showAdvisory = advisoryItems.isNotEmpty()

        if (showAdvisory) {
            delay(7_000)
            showAdvisory = false
        }
    }

    DisposableEffect(player) {
        onDispose {
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            val position = player.currentPosition.coerceAtLeast(0L)
            // A failing source must not overwrite the episode's saved
            // position with zero, or the timestamp of a placeholder video.
            if (duration > 180_000L && resumeApplied && !sourceSwitchRequested) {
                onStopped(maxOf(position, lastReliablePositionMs), duration)
            }
            player.release()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .pointerInput(player) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            controlsVisible = true
                            controlsRevision = System.currentTimeMillis()
                        }
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else if (!controlsVisible) {
                    controlsVisible = true
                    controlsRevision = System.currentTimeMillis()
                    true
                } else {
                    controlsRevision = System.currentTimeMillis()
                    false
                }
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                    resizeMode = if (fillVideo) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            update = {
                it.player = player
                it.isFocusable = false
                it.isFocusableInTouchMode = false
                it.keepScreenOn = true
                it.useController = false
                it.resizeMode = if (fillVideo) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize()
        )

        val statusMessage = recoveryMessage
            ?: sourceNotice?.takeIf { showSourceNotice }
        statusMessage?.let { message ->
            Box(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 22.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xEE111319))
                    .border(2.dp, NmGold, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                .widthIn(max = 760.dp)
            ) {
                Text(
                    message,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 19.sp,
                    maxLines = 5
                )
            }
        }
        if (showAdvisory) {
            Column(
                Modifier.align(Alignment.TopStart)
                    .padding(start = 30.dp, top = 28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = .78f))
                    .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 15.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text("CONTENT ADVISORY", color = NmGold, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(advisoryItems.joinToString("  ·  "), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (controlsVisible) {
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = .28f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = .86f)
                            )
                        )
                    )
            )

            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 34.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Text(title, color = NmPlatinum, fontSize = 20.sp, fontWeight = FontWeight.Bold)

                val displayedPositionMs = if (scrubMode) scrubPositionMs else playerPositionMs
                val progress = if (playerDurationMs > 0) {
                    (displayedPositionMs.toFloat() / playerDurationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(formatPlayerTime(displayedPositionMs), color = if (scrubMode) NmGold else NmMuted, fontSize = 12.sp)
                    Box(
                        Modifier.weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = .20f))
                    ) {
                        Box(
                            Modifier.fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(Brush.horizontalGradient(listOf(NmGold, NmPlatinum)))
                        )
                    }
                    Text(formatPlayerTime(playerDurationMs), color = NmMuted, fontSize = 12.sp)
                }

                if (scrubMode) {
                    Text(
                        "SEEK · ◀ / ▶ move 30 sec · OK apply · ↓ cancel",
                        color = NmGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        "↑ timeline",
                        color = NmMuted,
                        fontSize = 11.sp
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PlayerControl(
                        label = "↶ 30",
                        selected = remoteControlIndex == 0,
                        onClick = {
                            player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0L))
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = if (isPlaying) "❚❚" else "▶",
                        primary = true,
                        selected = remoteControlIndex == 1,
                        modifier = Modifier.focusRequester(playPauseFocus),
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = "30 ↷",
                        selected = remoteControlIndex == 2,
                        onClick = {
                            val limit = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                            player.seekTo((player.currentPosition + 30_000L).coerceAtMost(limit))
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = if (subtitlesEnabled) "CC ON" else "CC OFF",
                        active = showSubtitleMenu || subtitlesEnabled,
                        selected = remoteControlIndex == 3,
                        onClick = {
                            showSubtitleMenu = !showSubtitleMenu
                            showAudioMenu = false
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = "AUDIO",
                        active = showAudioMenu,
                        selected = remoteControlIndex == 4,
                        onClick = {
                            showAudioMenu = !showAudioMenu
                            showSubtitleMenu = false
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = if (fillVideo) "FIT" else "FILL",
                        selected = remoteControlIndex == 5,
                        onClick = {
                            fillVideo = !fillVideo
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = "NEXT SOURCE",
                        selected = remoteControlIndex == 6,
                        onClick = {
                            if (!sourceSwitchRequested) {
                                sourceSwitchRequested = true
                                switchWithStandby(
                                    player.currentPosition.coerceAtLeast(0L),
                                    "Skipped source manually"
                                )
                            }
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "NM STREAM",
                        color = NmGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        if (showAudioMenu) {
            LazyColumn(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 82.dp, end = 24.dp)
                    .widthIn(min = 280.dp, max = 440.dp)
                    .heightIn(max = 520.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xEE101218))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "Audio tracks · preferred ${preferredAudioLanguage.uppercase()}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (audioTracks.isEmpty()) {
                    item { Text("This source exposes only one/default audio track.", color = NmMuted) }
                } else {
                    itemsIndexed(audioTracks) { index, choice ->
                        Button(onClick = {
                            val override = TrackSelectionOverride(
                                choice.group.mediaTrackGroup,
                                listOf(choice.trackIndex)
                            )
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setOverrideForType(override)
                                .build()
                            showAudioMenu = false
                        }, modifier = Modifier.fillMaxWidth(), selected = audioMenuIndex == index) {
                            Text(if (audioMenuIndex == index) "▶  ${choice.label}" else choice.label)
                        }
                    }
                }
            }
        }

        if (showSubtitleMenu) {
            LazyColumn(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 82.dp, end = 24.dp)
                    .widthIn(min = 280.dp, max = 440.dp)
                    .heightIn(max = 520.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xEE101218))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "Subtitle tracks · preferred ${preferredSubtitleLanguage.uppercase()}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    Button(onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .build()
                        subtitlesEnabled = false
                        showSubtitleMenu = false
                    }, modifier = Modifier.fillMaxWidth(), selected = subtitleMenuIndex == 0) {
                        Text(if (subtitleMenuIndex == 0) "▶  Off" else "Off")
                    }
                }
                itemsIndexed(textTracks) { index, choice ->
                    Button(onClick = {
                        val override = TrackSelectionOverride(
                            choice.group.mediaTrackGroup,
                            listOf(choice.trackIndex)
                        )
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(override)
                            .build()
                        subtitlesEnabled = true
                        showSubtitleMenu = false
                    }, modifier = Modifier.fillMaxWidth(), selected = subtitleMenuIndex == index + 1) {
                        Text(if (subtitleMenuIndex == index + 1) "▶  ${choice.label}" else choice.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControl(
    label: String,
    primary: Boolean = false,
    active: Boolean = false,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.height(if (primary) 52.dp else 44.dp)
            .widthIn(min = if (primary) 58.dp else 54.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(
                when {
                    focused || selected -> NmRed
                    active -> NmGold.copy(alpha = .28f)
                    else -> Color(0xCC16181C)
                }
            )
            .border(
                if (focused || selected) 3.dp else 1.dp,
                when {
                    focused || selected -> Color.White
                    active -> NmGold
                    else -> Color.White.copy(alpha = .14f)
                },
                RoundedCornerShape(40.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .tvActivation(
                onClick = onClick,
                onLongClick = onClick
            )
            .focusable()
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (focused || selected) Color.White else if (active) NmGold else NmPlatinum,
            fontWeight = FontWeight.Black,
            fontSize = if (primary) 18.sp else 12.sp
        )
    }
}

private fun formatPlayerTime(valueMs: Long): String {
    val totalSeconds = (valueMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

private fun subtitleMime(url: String): String = when {
    url.substringBefore('?').endsWith(".srt", true) -> "application/x-subrip"
    url.substringBefore('?').endsWith(".vtt", true) -> "text/vtt"
    url.substringBefore('?').endsWith(".ttml", true) || url.substringBefore('?').endsWith(".xml", true) -> "application/ttml+xml"
    url.substringBefore('?').endsWith(".ssa", true) || url.substringBefore('?').endsWith(".ass", true) -> "text/x-ssa"
    else -> "text/vtt"
}

@Composable
private fun InputBox(
    value: String,
    placeholder: String,
    password: Boolean = false,
    change: (String) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF111319)).border(if (focused) 3.dp else 1.dp, if (focused) NmGold else Color(0xFF343841), RoundedCornerShape(8.dp)).onFocusChanged { focused = it.isFocused }.padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isBlank()) Text(placeholder, color = NmMuted.copy(alpha = .7f))
        BasicTextField(
            value = value,
            onValueChange = change,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(NmRed),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NmPanel).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

private fun mediaMatches(a: AppMedia, b: AppMedia): Boolean =
    a.meta.id == b.meta.id ||
        (a.meta.type == b.meta.type && a.meta.name.equals(b.meta.name, ignoreCase = true))

@Composable
private fun CenterText(value: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(value, color = NmMuted, fontSize = 20.sp) }
}
