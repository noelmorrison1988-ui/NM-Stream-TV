package za.co.nm.streamtv

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

private val NmBg = Color(0xFF050609)
private val NmPanel = Color(0xFF15171D)
private val NmPanelFocus = Color(0xFF252830)
private val NmRed = Color(0xFFE2182D)
private val NmMuted = Color(0xFFB6BBC5)
private val NmGreen = Color(0xFF69D39A)

private sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object LiveTv : Screen
    data object Addons : Screen
    data object Settings : Screen
    data class Details(val item: AppMedia) : Screen
    data class Sources(val item: AppMedia, val videoId: String, val title: String) : Screen
    data class Player(val item: AppMedia, val videoId: String, val title: String, val source: StreamOption) : Screen
}

@Composable
fun NMStreamApp(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var screen: Screen by remember { mutableStateOf(Screen.Home) }

    BackHandler(screen !is Screen.Home) {
        screen = when (val current = screen) {
            is Screen.Player -> Screen.Sources(current.item, current.videoId, current.title)
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

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(NmBg)) {
            when (val current = screen) {
                Screen.Home -> Shell("Home", { screen = it }) {
                    HomeScreen(state,
                        onOpen = {
                            viewModel.loadDetails(it)
                            screen = Screen.Details(it)
                        },
                        onContinue = {
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
                Screen.LiveTv -> Shell("Live TV", { screen = it }) {
                    LiveTvScreen(state) {
                        viewModel.loadDetails(it)
                        screen = Screen.Details(it)
                    }
                }
                Screen.Addons -> Shell("Add-ons", { screen = it }) {
                    AddonsScreen(state, viewModel::installAddon, viewModel::removeAddon)
                }
                Screen.Settings -> Shell("Settings", { screen = it }) {
                    SettingsScreen(state, viewModel)
                }
                is Screen.Details -> DetailsScreen(state.selectedMedia ?: current.item, state.detailsLoading) { item, id, title ->
                    viewModel.loadSources(item, id)
                    screen = Screen.Sources(item, id, title)
                }
                is Screen.Sources -> SourcesScreen(current.title, state.streamsLoading, state.streamOptions, state.subtitleOptions.size) { source ->
                    when {
                        source.playableUrl != null -> screen = Screen.Player(current.item, current.videoId, current.title, source)
                        !source.stream.externalUrl.isNullOrBlank() -> runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.stream.externalUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                is Screen.Player -> PlayerScreen(
                    item = current.item,
                    videoId = current.videoId,
                    title = current.title,
                    url = current.source.playableUrl.orEmpty(),
                    headers = current.source.requestHeaders,
                    subtitles = state.subtitleOptions,
                    resumeMs = viewModel.resumePosition(current.item, current.videoId),
                    resumePercent = viewModel.resumeCloudPercent(current.item, current.videoId),
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

@Composable
private fun Shell(selected: String, navigate: (Screen) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(70.dp).background(Color(0xFF090A0E)).padding(horizontal = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NM", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Text(" STREAM", color = NmRed, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(28.dp))
            listOf("Home" to Screen.Home, "Search" to Screen.Search, "Live TV" to Screen.LiveTv, "Add-ons" to Screen.Addons, "Settings" to Screen.Settings).forEach { (label, target) ->
                NavChip(label, selected == label) { navigate(target) }
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("NM DIGITAL", color = NmMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun NavChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(if (selected) NmRed else if (focused) NmPanelFocus else Color.Transparent)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).focusable().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun HomeScreen(state: MainUiState, onOpen: (AppMedia) -> Unit, onContinue: (PlaybackProgress) -> Unit) {
    if (state.loading) {
        CenterText("Loading NM Stream TV…")
        return
    }
    val hero = state.movies.firstOrNull() ?: state.series.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { if (hero != null) Hero(hero, onOpen) else EmptyHero(state.addons.isEmpty()) }
        if (state.continueWatching.isNotEmpty()) item { ContinueRow(state.continueWatching, onContinue) }
        if (state.traktWatchlist.isNotEmpty()) item { MediaRow("My Trakt Watchlist", state.traktWatchlist, onOpen) }
        if (state.iptvSports.isNotEmpty()) item { MediaRow("Live Sports · Rugby · F1 · Soccer · Cricket", state.iptvSports.take(40), onOpen) }
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
private fun ContinueRow(media: List<PlaybackProgress>, onOpen: (PlaybackProgress) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Continue Watching", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 40.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(media) { p ->
                var focused by remember { mutableStateOf(false) }
                Column(Modifier.width(240.dp).onFocusChanged { focused = it.isFocused }.clickable { onOpen(p) }.focusable()) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(NmPanel).border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))) {
                        AsyncImage(model = p.media.meta.background ?: p.media.meta.poster, contentDescription = p.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = .2f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(p.percent.coerceAtLeast(1) / 100f).background(NmRed))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(p.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (p.source?.let { "$it · " } ?: "") + p.percent + "%",
                        color = if (p.source == "Trakt") NmGreen else NmMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvScreen(state: MainUiState, onOpen: (AppMedia) -> Unit) {
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
                Text("Sports prioritises rugby, Formula 1, soccer, cricket and other sports channels.", color = NmMuted)
            }
        }
        if (state.iptvSports.isNotEmpty()) item {
            MediaRow("Sports", state.iptvSports.take(100), onOpen)
        }
        if (state.iptvChannels.isNotEmpty()) item {
            MediaRow("All Live TV", state.iptvChannels.take(160), onOpen)
        }
        if (state.iptvConfigured && state.iptvChannels.isEmpty()) item {
            Text("No channels could be loaded from the configured IPTV source.", color = NmMuted, modifier = Modifier.padding(horizontal = 42.dp))
        }
    }
}

@Composable
private fun SearchScreen(state: MainUiState, onSearch: (String) -> Unit, onOpen: (AppMedia) -> Unit) {
    var query by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 42.dp), contentPadding = PaddingValues(top = 26.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text("Search", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp)); InputBox(query, "Search movies and series") { query = it; onSearch(it) }
        }
        if (state.searchLoading) item { Text("Searching…", color = NmMuted) }
        else if (query.isNotBlank()) item {
            if (state.searchResults.isEmpty()) Text("No results found.", color = NmMuted)
            else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { state.searchResults.chunked(6).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) { row.forEach { PosterCard(it, onOpen) } } } }
        }
    }
}

@Composable
private fun AddonsScreen(state: MainUiState, install: (String) -> Unit, remove: (String) -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }

    LaunchedEffect(state.addonInstallStatus) {
        if (state.addonInstallStatus?.startsWith("Installed ") == true) url = ""
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 42.dp), contentPadding = PaddingValues(top = 26.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Add-ons", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text("Paste an https:// or stremio:// manifest link. NM Stream TV will validate it before saving.", color = NmMuted)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(700.dp)) { InputBox(url, "https://example.com/manifest.json") { url = it } }
                Button(
                    onClick = { install(url) },
                    enabled = url.isNotBlank() && !state.addonInstalling
                ) { Text(if (state.addonInstalling) "Installing…" else "Install") }
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
            Text("Curated add-on catalog", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Core add-ons plus SportStream/Sports Streams, StremVerse and IPTV options. Configurable services open their setup page; paste the generated manifest above.", color = NmMuted)
        }
        items(AddonCatalog.presets) { preset ->
            CardBox {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(preset.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(preset.category.uppercase(), color = if (preset.sports) NmGreen else NmRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(preset.description, color = NmMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        preset.note?.let { Text(it, color = NmMuted.copy(alpha = .8f), fontSize = 12.sp) }
                    }
                    preset.manifestUrl?.let { manifest ->
                        Button(onClick = { install(manifest) }) { Text("Install") }
                    }
                    preset.setupUrl?.let { setup ->
                        Button(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(setup)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Configure") }
                    }
                }
            }
        }
        item {
            Text("Installed add-ons", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        items(state.addons) { addon ->
            CardBox {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(addon.manifest.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(addon.manifest.description ?: addon.manifestUrl, color = NmMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { remove(addon.manifestUrl) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: MainUiState, vm: MainViewModel) {
    var tmdb by remember { mutableStateOf("") }
    var traktId by remember { mutableStateOf("") }
    var traktSecret by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var xtreamServer by remember { mutableStateOf("") }
    var xtreamUser by remember { mutableStateOf("") }
    var xtreamPass by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 42.dp), contentPadding = PaddingValues(top = 26.dp, bottom = 55.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Settings", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black) }
        item { CardBox {
            Text("TMDB artwork", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Status: " + state.tmdbStatus, color = if (state.tmdbConfigured) NmGreen else NmMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(640.dp)) { InputBox(tmdb, "TMDB API Read Access Token") { tmdb = it } }
                Button(onClick = { vm.saveTmdbToken(tmdb); tmdb = "" }) { Text("Save") }
                if (state.tmdbConfigured) Button(onClick = { vm.saveTmdbToken("") }) { Text("Remove") }
            }
        } }
        item { CardBox {
            Text("Live TV / IPTV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Status: " + state.iptvStatus, color = if (state.iptvConfigured) NmGreen else NmMuted)
            Text("Use an IPTV source you are authorized to access. M3U/M3U8 and Xtream live TV are supported; sports are automatically prioritised.", color = NmMuted)
            Text("M3U / XMLTV", color = Color.White, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(760.dp)) { InputBox(m3uUrl, "M3U / M3U8 playlist URL") { m3uUrl = it } }
                Box(Modifier.width(760.dp)) { InputBox(epgUrl, "XMLTV EPG URL (optional)") { epgUrl = it } }
                Button(onClick = {
                    vm.saveIptvM3u(m3uUrl, epgUrl)
                    m3uUrl = ""
                    epgUrl = ""
                }) { Text("Save M3U") }
            }
            Text("Xtream Codes", color = Color.White, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(760.dp)) { InputBox(xtreamServer, "Portal/server URL") { xtreamServer = it } }
                Box(Modifier.width(520.dp)) { InputBox(xtreamUser, "Username") { xtreamUser = it } }
                Box(Modifier.width(520.dp)) { InputBox(xtreamPass, "Password", password = true) { xtreamPass = it } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        vm.saveIptvXtream(xtreamServer, xtreamUser, xtreamPass)
                        xtreamServer = ""
                        xtreamUser = ""
                        xtreamPass = ""
                    }) { Text("Save Xtream") }
                    if (state.iptvConfigured) Button(onClick = vm::clearIptv) { Text("Remove IPTV") }
                }
            }
        } }
        item { CardBox {
            Text("Trakt", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.traktConnected) "Connected"
                else if (state.traktConfigured) "Credentials saved"
                else "Client ID + Client Secret required",
                color = if (state.traktConnected) NmGreen else NmMuted
            )
            Text("Native Trakt: device sign-in, automatic token refresh, watchlist sync and playback scrobbling. Enter the Client ID and Client Secret from your Trakt API app; they are stored encrypted on this device.", color = NmMuted)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(640.dp)) { InputBox(traktId, "Trakt Client ID") { traktId = it } }
                Box(Modifier.width(640.dp)) { InputBox(traktSecret, "Trakt Client Secret", password = true) { traktSecret = it } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        vm.saveTraktCredentials(traktId, traktSecret)
                        traktId = ""
                        traktSecret = ""
                    }) { Text("Save credentials") }
                    if (state.traktConfigured) Button(onClick = vm::clearTraktCredentials) { Text("Remove") }
                }
            }
            if (state.traktConnected) {
                Button(onClick = vm::disconnectTrakt) { Text("Disconnect Trakt") }
            } else {
                Button(
                    onClick = vm::beginTraktSignIn,
                    enabled = state.traktConfigured && !state.traktConnecting
                ) { Text(if (state.traktConnecting) "Waiting…" else "Connect Trakt") }
            }
            state.traktDeviceCode?.let { DeviceCode("Trakt", it.userCode, it.verificationUrl) }
        } }
        item { CardBox {
            Text("Real-Debrid", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(state.rdUser?.let { "Connected as " + it.username } ?: "Not connected", color = if (state.rdUser != null) NmGreen else NmMuted)
            if (state.rdUser != null) Button(onClick = vm::disconnectRealDebrid) { Text("Disconnect Real-Debrid") } else Button(onClick = vm::beginRealDebridSignIn) { Text(if (state.rdConnecting) "Waiting…" else "Connect Real-Debrid") }
            state.rdDeviceCode?.let { DeviceCode("Real-Debrid", it.userCode, it.verificationUrl) }
        } }
        item { Text("NM Stream TV v0.3.0 · an NM Digital product", color = NmMuted) }
    }
}

@Composable
private fun DeviceCode(service: String, code: String, url: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0C0D11)).padding(14.dp)) {
        Text("Connect " + service, color = Color.White, fontWeight = FontWeight.Bold)
        Text("Visit " + url + " and enter:", color = NmMuted)
        Text(code, color = NmRed, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun DetailsScreen(item: AppMedia, loading: Boolean, choose: (AppMedia, String, String) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(model = item.meta.background ?: item.meta.poster, contentDescription = item.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NmBg, NmBg.copy(alpha = .9f), NmBg.copy(alpha = .4f)))))
        LazyColumn(Modifier.fillMaxSize().padding(48.dp), contentPadding = PaddingValues(bottom = 50.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.meta.name, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
                    if (loading) Text("Loading enhanced metadata…", color = NmRed)
                    item.meta.description?.let { Text(it, color = Color.White.copy(alpha = .9f), fontSize = 17.sp, maxLines = 7, overflow = TextOverflow.Ellipsis) }
                    if (item.meta.type != "series" || item.meta.videos.isEmpty()) Button(onClick = { choose(item, item.meta.id, item.meta.name) }) { Text("▶  Choose source") }
                }
            }
            if (item.meta.videos.isNotEmpty()) {
                item { Text("Episodes", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
                items(item.meta.videos) { ep ->
                    var focused by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (focused) NmPanelFocus else NmPanel).onFocusChanged { focused = it.isFocused }.clickable { choose(item, ep.id, ep.displayName()) }.focusable().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = ep.thumbnail, contentDescription = ep.displayName(), contentScale = ContentScale.Crop, modifier = Modifier.width(180.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) { Text(ep.displayName(), color = Color.White, fontWeight = FontWeight.Bold); ep.overview?.let { Text(it, color = NmMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesScreen(title: String, loading: Boolean, sources: List<StreamOption>, subtitleCount: Int, select: (StreamOption) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 46.dp), contentPadding = PaddingValues(top = 34.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black); Text(subtitleCount.toString() + " subtitle tracks found", color = NmMuted) }
        if (loading) item { Text("Checking installed sources…", color = NmMuted) }
        else if (sources.isEmpty()) item { Text("No stream sources were returned.", color = NmMuted) }
        else items(sources) { source ->
            var focused by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (focused) NmPanelFocus else NmPanel).onFocusChanged { focused = it.isFocused }.clickable { select(source) }.focusable().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(source.displayTitle(), color = Color.White, fontWeight = FontWeight.Bold); Text(source.addonName, color = NmRed); Text(source.statusText(), color = NmMuted) }
                if (source.playableUrl != null) Text("PLAY", color = NmGreen, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun PlayerScreen(item: AppMedia, videoId: String, title: String, url: String, headers: Map<String, String>, subtitles: List<SubtitleOption>, resumeMs: Long, resumePercent: Double?, onStarted: (Long, Long) -> Unit, onProgress: (Long, Long) -> Unit, onStopped: (Long, Long) -> Unit) {
    val context = LocalContext.current
    val player = remember(url, headers, subtitles, resumeMs, resumePercent) {
        val dataSource = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)).build().apply {
            val subs = subtitles.mapIndexed { index, option ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(option.subtitle.url))
                    .setId(option.subtitle.id.ifBlank { "sub-" + index })
                    .setLanguage(option.subtitle.lang)
                    .setLabel(option.subtitle.lang.uppercase() + " · " + option.addonName)
                    .setMimeType(subtitleMime(option.subtitle.url))
                    .setSelectionFlags(if (index == 0 && option.subtitle.lang.startsWith("en", true)) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
            setMediaItem(MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).build())
            prepare()
            playWhenReady = resumeMs <= 0 && resumePercent == null
        }
    }
    var started by remember(player) { mutableStateOf(false) }
    var resumeApplied by remember(player) { mutableStateOf(resumeMs <= 0 && resumePercent == null) }

    LaunchedEffect(player, resumeMs, resumePercent) {
        while (!resumeApplied) {
            delay(250)
            val duration = player.duration.takeIf { it > 0 } ?: continue
            val target = when {
                resumeMs > 0 -> resumeMs
                resumePercent != null -> (duration * (resumePercent.coerceIn(0.0, 99.0) / 100.0)).toLong()
                else -> 0L
            }.coerceIn(0L, (duration - 1L).coerceAtLeast(0L))
            player.seekTo(target)
            player.playWhenReady = true
            resumeApplied = true
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            val d = player.duration.takeIf { it > 0 } ?: 0L
            val p = player.currentPosition.coerceAtLeast(0L)
            if (!started && d > 0) { onStarted(p, d); started = true }
            if (d > 0) onProgress(p, d)
        }
    }
    DisposableEffect(player) {
        onDispose {
            val d = player.duration.takeIf { it > 0 } ?: 0L
            val p = player.currentPosition.coerceAtLeast(0L)
            if (d > 0) onStopped(p, d)
            player.release()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { useController = true; this.player = player } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp).background(Color.Black.copy(alpha = .55f)).padding(10.dp))
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

@Composable
private fun CenterText(value: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(value, color = NmMuted, fontSize = 20.sp) }
}
