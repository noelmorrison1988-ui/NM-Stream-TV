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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
    data class Details(val item: AppMedia) : Screen
    data class Sources(val item: AppMedia, val videoId: String, val title: String) : Screen
    data class AutoPlay(val item: AppMedia, val videoId: String, val title: String, val requestKey: String) : Screen
    data class Player(
        val item: AppMedia,
        val videoId: String,
        val title: String,
        val source: StreamOption,
        val returnToSources: Boolean = true
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
                            val key = viewModel.sourceRequestKey(it.media, it.videoId)
                            viewModel.loadSources(it.media, it.videoId)
                            screen = Screen.AutoPlay(it.media, it.videoId, it.title, key)
                        },
                        onContinueManual = {
                            viewModel.loadDetails(it.media)
                            viewModel.loadSources(it.media, it.videoId)
                            screen = Screen.Sources(it.media, it.videoId, it.title)
                        }
                    )
                }
                Screen.Search -> Shell("Search", { screen = it }) {
                    SearchScreen(state, viewModel::search) {
                        viewModel.loadDetails(it)
                        screen = Screen.Details(it)
                    }
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
                is Screen.Details -> {
                    val detailItem = state.selectedMedia ?: current.item
                    val trailer = viewModel.bestTrailer(detailItem)
                    val selectionKey = seriesSelectionKey(current.item)
                    val rememberedSelection = seriesSelections[selectionKey]
                    DetailsScreen(
                        item = detailItem,
                        loading = state.detailsLoading,
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
                            val key = viewModel.sourceRequestKey(item, id)
                            viewModel.loadSources(item, id)
                            screen = Screen.AutoPlay(item, id, title, key)
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
                    AutoPlayScreen(current.title)
                    LaunchedEffect(
                        current.requestKey,
                        state.sourceRequestKey,
                        state.streamsLoading,
                        state.streamOptions
                    ) {
                        if (
                            state.sourceRequestKey == current.requestKey &&
                            !state.streamsLoading
                        ) {
                            val best = state.streamOptions.firstOrNull { option ->
                                option.playableUrl != null ||
                                    option.youtubeUrl != null ||
                                    !option.stream.externalUrl.isNullOrBlank()
                            }

                            when {
                                best?.playableUrl != null -> {
                                    screen = Screen.Player(
                                        current.item,
                                        current.videoId,
                                        current.title,
                                        best,
                                        returnToSources = false
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
                        source.playableUrl != null -> screen = Screen.Player(current.item, current.videoId, current.title, source)
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
                    preferredAudioLanguage = state.preferredAudioLanguage,
                    preferredSubtitleLanguage = state.preferredSubtitleLanguage,
                    onStarted = { _, _ -> },
                    onProgress = { _, _ -> },
                    onStopped = { _, _ -> }
                )
                is Screen.Player -> PlayerScreen(
                    item = current.item,
                    videoId = current.videoId,
                    title = current.title,
                    url = current.source.playableUrl.orEmpty(),
                    headers = current.source.requestHeaders,
                    subtitles = state.subtitleOptions,
                    resumeMs = viewModel.resumePosition(current.item, current.videoId),
                    resumePercent = viewModel.resumeCloudPercent(current.item, current.videoId),
                    preferredAudioLanguage = state.preferredAudioLanguage,
                    preferredSubtitleLanguage = state.preferredSubtitleLanguage,
                    onStarted = { p, d -> viewModel.onPlaybackStarted(current.item, current.videoId, p, d) },
                    onProgress = { p, d -> viewModel.onPlaybackProgress(current.item, current.videoId, current.title, p, d) },
                    onStopped = { p, d -> viewModel.onPlaybackStopped(current.item, current.videoId, current.title, p, d) }
                )
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
    var webView by remember(youtubeId) { mutableStateOf<WebView?>(null) }
    var playing by remember(youtubeId) { mutableStateOf(true) }
    var playerError by remember(youtubeId) { mutableStateOf<Int?>(null) }
    val playFocus = remember(youtubeId) { FocusRequester() }
    val clientIdentity = "https://github.com/noelmorrison1988-ui/NM-Stream-TV/"
    val externalUrl = remember(youtubeId) { "https://www.youtube.com/watch?v=$youtubeId" }

    val safeYoutubeId = remember(youtubeId) { youtubeId.replace("'", "\\'") }
    val html = remember(safeYoutubeId) {
        """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
          <meta name="referrer" content="strict-origin-when-cross-origin">
          <style>
            html,body,#player { width:100%; height:100%; margin:0; padding:0; overflow:hidden; background:#000; }
          </style>
        </head>
        <body>
          <div id="player"></div>
          <script src="https://www.youtube.com/iframe_api"></script>
          <script>
            var player;
            function onYouTubeIframeAPIReady() {
              player = new YT.Player('player', {
                videoId: '$safeYoutubeId',
                playerVars: {
                  autoplay: 1,
                  controls: 0,
                  rel: 0,
                  playsinline: 1,
                  fs: 0,
                  origin: 'https://github.com',
                  widget_referrer: '$clientIdentity'
                },
                events: {
                  onReady: function(e) {
                    e.target.playVideo();
                  },
                  onError: function(e) {
                    window.location.href = 'nmstream://youtube-error/' + e.data;
                  }
                }
              });
            }
            function nmPlay(){ if(player){ player.playVideo(); } }
            function nmPause(){ if(player){ player.pauseVideo(); } }
            function nmSeek(delta){
              if(player && player.getCurrentTime){
                player.seekTo(Math.max(0, player.getCurrentTime() + delta), true);
              }
            }
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    fun openInYouTube() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    DisposableEffect(youtubeId) {
        onDispose {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
    }

    LaunchedEffect(youtubeId) {
        delay(350)
        runCatching { playFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { webContext ->
                WebView(webContext).apply {
                    webView = this
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return false
                            if (uri.scheme == "nmstream" && uri.host == "youtube-error") {
                                playerError = uri.lastPathSegment?.toIntOrNull()
                                return true
                            }
                            return false
                        }
                    }
                    loadDataWithBaseURL(
                        clientIdentity,
                        html,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            update = { webView = it },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = .12f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = .78f)
                    )
                )
            )
        )

        playerError?.let { errorCode ->
            Column(
                Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEE111319))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Trailer embed unavailable", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("YouTube returned player error $errorCode. You can still open this trailer directly.", color = NmMuted)
                Button(onClick = ::openInYouTube) { Text("Open in YouTube") }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 34.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, color = NmPlatinum, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlayerControl(
                    label = "↶ 10",
                    onClick = { webView?.evaluateJavascript("nmSeek(-10)", null) }
                )
                PlayerControl(
                    label = if (playing) "❚❚" else "▶",
                    primary = true,
                    modifier = Modifier.focusRequester(playFocus),
                    onClick = {
                        playing = !playing
                        webView?.evaluateJavascript(if (playing) "nmPlay()" else "nmPause()", null)
                    }
                )
                PlayerControl(
                    label = "10 ↷",
                    onClick = { webView?.evaluateJavascript("nmSeek(10)", null) }
                )
                PlayerControl(
                    label = "YOUTUBE ↗",
                    onClick = ::openInYouTube
                )
                Spacer(Modifier.weight(1f))
                Text("TRAILER · YOUTUBE", color = NmGold, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
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
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    !enabled -> Color(0xFF2B2E33)
                    focused -> NmGold
                    else -> Color(0xFF22252A)
                }
            )
            .border(
                if (focused && enabled) 2.dp else 1.dp,
                if (focused && enabled) NmPlatinum else Color.White.copy(alpha = .12f),
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
                    selected -> NmGold.copy(alpha = .18f)
                    focused -> Color.White.copy(alpha = .08f)
                    else -> Color.Transparent
                }
            )
            .border(
                if (selected || focused) 1.dp else 0.dp,
                if (selected) NmGold.copy(alpha = .75f) else Color.White.copy(alpha = .18f),
                RoundedCornerShape(20.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) NmGold else NmPlatinum,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun HomeScreen(
    state: MainUiState,
    onOpen: (AppMedia) -> Unit,
    onContinue: (PlaybackProgress) -> Unit,
    onContinueManual: (PlaybackProgress) -> Unit
) {
    if (state.loading) {
        CenterText("Loading NM Stream TV Mobile Lite…")
        return
    }
    val hero = state.movies.firstOrNull()
        ?: state.series.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { if (hero != null) Hero(hero, onOpen) else EmptyHero(state.addons.isEmpty()) }

        item { ContinueRow(state.continueWatching, onContinue, onContinueManual) }

        if (state.trendingMovies.isNotEmpty()) item { MediaRow("Trending Movies", state.trendingMovies, onOpen) }
        if (state.newMovies.isNotEmpty()) item { MediaRow("New Movies", state.newMovies, onOpen) }
        if (state.trendingSeries.isNotEmpty()) item { MediaRow("Trending Series", state.trendingSeries, onOpen) }
        if (state.newSeries.isNotEmpty()) item { MediaRow("New Series", state.newSeries, onOpen) }
        val historyMedia = state.watchHistory.map { it.media }.distinctBy { it.meta.id }
        if (historyMedia.isNotEmpty()) {
            item { MediaRow("Watch History", historyMedia, onOpen) }
        }

        if (state.movies.isNotEmpty()) item { MediaRow("Movies", state.movies, onOpen) }
        if (state.series.isNotEmpty()) item { MediaRow("Series", state.series, onOpen) }
        if (state.debridItems.isNotEmpty()) item { MediaRow("My Real-Debrid Library", state.debridItems, onOpen) }
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
                items(media) { PosterCard(it, onOpen) }
            }
        }
    }
}

@Composable
private fun MediaRow(title: String, media: List<AppMedia>, onOpen: (AppMedia) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 40.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(media) { PosterCard(it, onOpen) }
        }
    }
}

@Composable
private fun PosterCard(item: AppMedia, onOpen: (AppMedia) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "poster")
    Column(Modifier.width(165.dp).graphicsLayer { scaleX = scale; scaleY = scale }.onFocusChanged { focused = it.isFocused }.clickable { onOpen(item) }.focusable()) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(NmPanel).border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))) {
            AsyncImage(model = item.meta.poster ?: item.meta.background, contentDescription = item.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))
        Text(item.meta.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ContinueRow(
    media: List<PlaybackProgress>,
    onOpen: (PlaybackProgress) -> Unit,
    onLongOpen: (PlaybackProgress) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Continue Watching", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 40.dp))
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
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(NmPanel).border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))) {
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
            .background(if (focused) NmPanelFocus else NmPanel)
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
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
    onOpen: (AppMedia) -> Unit
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
                    Button(onClick = { selectCategory(value) }) {
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
                                row.forEach { PosterCard(it, onOpen) }
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
                if (state.addons.isEmpty()) "No add-ons installed."
                else "Only add-ons you manually install appear here.",
                color = NmMuted
            )
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
                        Text(addon.manifestUrl, color = NmMuted.copy(alpha = .72f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { remove(addon.manifestUrl) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: MainUiState, vm: MainViewModel) {
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
                "Pair this device once, then manage synced add-ons, playback language and Real-Debrid settings from your phone.",
                color = NmMuted
            )
            if (state.nmAccountLinked) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = vm::syncNmAccountNow) { Text("Sync now") }
                    Button(onClick = vm::unlinkNmAccount) { Text("Unlink") }
                }
                Text("Sync status: ${state.nmSyncStatus}", color = NmGreen)
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
            Text("These preferences sync to every linked NM Stream TV device.", color = NmMuted)

            Text("Mobile Lite source policy", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Pengu is the default provider. Streams above 1080p are excluded in Mobile Lite.", color = NmMuted, fontSize = 12.sp)

            Text("Preferred audio language", color = Color.White, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth()) { InputBox(audioLang, "en") { audioLang = it } }
            Text("Examples: en, de, fr, es. The player will still let you switch tracks manually.", color = NmMuted, fontSize = 12.sp)

            Text("Preferred subtitle language", color = Color.White, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth()) { InputBox(subtitleLang, "en") { subtitleLang = it } }

            Button(onClick = {
                vm.saveMobileLiteLanguagePreferences(audioLang, subtitleLang)
            }) { Text("Save & sync language preferences") }
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
        item { Text("NM Stream TV Mobile Lite v0.15.1-mobile-lite.8 · Morrison Entertainment", color = NmMuted) }
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
                    item.meta.description?.let { Text(it, color = Color.White.copy(alpha = .9f), fontSize = 17.sp, maxLines = 7, overflow = TextOverflow.Ellipsis) }

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
                            Button(onClick = toggleMyList) {
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
                            Button(onClick = { rememberSeason(season) }) {
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
                                focused -> NmPanelFocus
                                ep.id == rememberedEpisodeId -> NmGold.copy(alpha = .14f)
                                else -> NmPanel
                            })
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
        }
    }
}

@Composable
private fun AutoPlayScreen(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Finding the best source…", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
                if (focused) 2.dp else 0.dp,
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
    val recommended = sources.firstOrNull {
        it.playableUrl != null || it.youtubeUrl != null || !it.stream.externalUrl.isNullOrBlank()
    }
    val httpSources = sources.filter { it.playableUrl != null }
    val p2pSources = sources.filter { it.isP2p }
    val otherSources = sources.filter { it.playableUrl == null && !it.isP2p }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 46.dp),
        contentPadding = PaddingValues(top = 34.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("$subtitleCount subtitle tracks found", color = NmMuted)
            Text("Mobile Lite default: Pengu · maximum 1080p", color = NmGreen, fontSize = 13.sp)
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
        } else if (sources.isEmpty()) {
            item { Text("No streams at 1080p or below were returned.", color = NmMuted) }
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
            .background(if (focused) NmPanelFocus else NmPanel)
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
                if (source.addonName.contains("pengu", ignoreCase = true)) {
                    Text("PENGU", color = NmGold, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
            Text(source.addonName, color = NmRed)
            Text(source.statusText(), color = NmMuted)
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
    preferredAudioLanguage: String,
    preferredSubtitleLanguage: String,
    onStarted: (Long, Long) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onStopped: (Long, Long) -> Unit
) {
    val context = LocalContext.current

    val initialResumeMs = remember(url, videoId) { resumeMs }
    val initialResumePercent = remember(url, videoId) { resumePercent }

    val player = remember(url, videoId, headers, subtitles) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(45_000)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                120_000,
                2_500,
                5_000
            )
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

    var started by remember(player) { mutableStateOf(false) }
    var resumeApplied by remember(player) {
        mutableStateOf(initialResumeMs <= 0 && initialResumePercent == null)
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
    val advisoryItems = remember(item.meta.contentRating, item.meta.contentAdvisories) {
        buildList {
            item.meta.contentRating?.takeIf { it.isNotBlank() }?.let { add("Rated " + it) }
            addAll(item.meta.contentAdvisories.filter { it.isNotBlank() })
        }.distinct()
    }
    var showAdvisory by remember(videoId) { mutableStateOf(advisoryItems.isNotEmpty()) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                trackRevision += 1
            }

            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                controlsRevision = System.currentTimeMillis()
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
            player.playWhenReady = true
            resumeApplied = true
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            val position = player.currentPosition.coerceAtLeast(0L)
            if (!started && duration > 0) {
                onStarted(position, duration)
                started = true
            }
            if (duration > 0) onProgress(position, duration)
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(500)
            playerPositionMs = player.currentPosition.coerceAtLeast(0L)
            playerDurationMs = player.duration.takeIf { it > 0 } ?: 0L
            isPlaying = player.isPlaying
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
            if (duration > 0) onStopped(position, duration)
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

                val progress = if (playerDurationMs > 0) {
                    (playerPositionMs.toFloat() / playerDurationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(formatPlayerTime(playerPositionMs), color = NmMuted, fontSize = 12.sp)
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

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PlayerControl(
                        label = "↶ 10",
                        onClick = {
                            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = if (isPlaying) "❚❚" else "▶",
                        primary = true,
                        modifier = Modifier.focusRequester(playPauseFocus),
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = "10 ↷",
                        onClick = {
                            val limit = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                            player.seekTo((player.currentPosition + 10_000L).coerceAtMost(limit))
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = if (subtitlesEnabled) "CC ON" else "CC OFF",
                        active = showSubtitleMenu || subtitlesEnabled,
                        onClick = {
                            showSubtitleMenu = !showSubtitleMenu
                            showAudioMenu = false
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = "AUDIO",
                        active = showAudioMenu,
                        onClick = {
                            showAudioMenu = !showAudioMenu
                            showSubtitleMenu = false
                            controlsRevision = System.currentTimeMillis()
                        }
                    )
                    PlayerControl(
                        label = if (fillVideo) "FIT" else "FILL",
                        onClick = {
                            fillVideo = !fillVideo
                            controlsRevision = System.currentTimeMillis()
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
                    items(audioTracks) { choice ->
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
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(choice.label)
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
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Off")
                    }
                }
                items(textTracks) { choice ->
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
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(choice.label)
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
                    focused -> NmGold
                    active -> NmGold.copy(alpha = .22f)
                    else -> Color(0xCC16181C)
                }
            )
            .border(
                1.dp,
                when {
                    focused -> NmPlatinum
                    active -> NmGold
                    else -> Color.White.copy(alpha = .14f)
                },
                RoundedCornerShape(40.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (focused) Color.Black else if (active) NmGold else NmPlatinum,
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
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF111319)).border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF343841), RoundedCornerShape(8.dp)).onFocusChanged { focused = it.isFocused }.padding(horizontal = 14.dp, vertical = 12.dp)) {
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
