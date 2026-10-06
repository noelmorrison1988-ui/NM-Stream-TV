package za.co.nm.streamtv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

private val PremiumBg = Color(0xFF050607)
private val PremiumPanel = Color(0xFF121417)
private val PremiumPanelRaised = Color(0xFF1A1D21)
private val PremiumGold = Color(0xFFD6A84B)
private val PremiumPlatinum = Color(0xFFD8DCE3)
private val PremiumMuted = Color(0xFF9EA5AF)
private val PremiumGreen = Color(0xFF71D6A0)

@Composable
internal fun SportsHubScreen(
    state: MainUiState,
    onOpen: (AppMedia) -> Unit,
    onCrew: (AppMedia) -> Unit
) {
    val live = state.sportsCatalog.filter { item ->
        item.meta.genres.any { it.equals("Live", true) } ||
            item.originAddonName?.contains("IPTV", true) == true
    }
    val replays = state.sportsCatalog.filter { item ->
        item.meta.genres.any { it.equals("Replay", true) } ||
            item.meta.name.contains("replay", true)
    }

    fun group(name: String): List<AppMedia> = state.sportsCatalog.filter { item ->
        item.meta.genres.any { it.equals(name, true) } ||
            item.meta.name.contains(name, true) ||
            item.meta.description.orEmpty().contains(name, true)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(PremiumBg),
        contentPadding = PaddingValues(top = 24.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF15181D), Color(0xFF090A0D), PremiumBg)
                        )
                    )
                    .padding(horizontal = 42.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("NM SPORTS", color = PremiumGold, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Text("Live. Replay. One catalogue.", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                Text(
                    "The Crew through your local Kodi install + IPTV/Xtream + compatible NM sources.",
                    color = PremiumMuted,
                    fontSize = 15.sp
                )
                Text(
                    state.kodiCrewStatus,
                    color = if (state.kodiCrewConnected) PremiumGreen else PremiumMuted,
                    fontSize = 13.sp
                )
            }
        }

        if (live.isNotEmpty()) item { SportsRow("● LIVE NOW", live.take(80), onOpen, onCrew) }
        if (group("Rugby").isNotEmpty()) item { SportsRow("Rugby", group("Rugby"), onOpen, onCrew) }
        if (group("Motorsport").isNotEmpty()) item { SportsRow("F1 & Motorsport", group("Motorsport"), onOpen, onCrew) }
        if (group("Football").isNotEmpty()) item { SportsRow("Football", group("Football"), onOpen, onCrew) }
        if (group("Cricket").isNotEmpty()) item { SportsRow("Cricket", group("Cricket"), onOpen, onCrew) }
        if (group("Combat Sports").isNotEmpty()) item { SportsRow("Combat Sports", group("Combat Sports"), onOpen, onCrew) }
        if (group("Tennis").isNotEmpty()) item { SportsRow("Tennis", group("Tennis"), onOpen, onCrew) }
        if (replays.isNotEmpty()) item { SportsRow("Recent Replays", replays.take(100), onOpen, onCrew) }

        if (state.sportsCatalog.isEmpty()) {
            item {
                PremiumInfoCard(
                    title = "No sports catalogue loaded yet",
                    body = "IPTV/Xtream sports appear automatically. For The Crew, start Kodi and enable Settings → Services → Control → Allow remote control via HTTP."
                )
            }
        }
    }
}

@Composable
private fun SportsRow(
    title: String,
    media: List<AppMedia>,
    onOpen: (AppMedia) -> Unit,
    onCrew: (AppMedia) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 42.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 42.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(media.distinctBy { it.meta.id }.take(80)) { item ->
                SportsCard(item) {
                    if (item.originAddonName == "Kodi · The Crew") onCrew(item) else onOpen(item)
                }
            }
        }
    }
}

@Composable
private fun SportsCard(item: AppMedia, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(280.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) PremiumPanelRaised else PremiumPanel)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) PremiumGold else Color.White.copy(alpha = .08f),
                shape = RoundedCornerShape(14.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .then(Modifier)
            .focusable()
            .premiumActivation(onClick)
            .padding(bottom = 12.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0A0B0D))) {
            AsyncImage(
                model = item.meta.background ?: item.meta.poster,
                contentDescription = item.meta.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            val isLive = item.meta.genres.any { it.equals("Live", true) } ||
                item.originAddonName?.contains("IPTV", true) == true
            Text(
                if (isLive) "LIVE" else "REPLAY",
                color = if (isLive) PremiumGreen else PremiumGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(9.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = .78f))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.meta.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.originAddonName ?: "NM Sports",
                color = PremiumGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun YouTubeHubScreen() {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().background(PremiumBg).padding(horizontal = 42.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("YOUTUBE", color = PremiumGold, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text("Your YouTube corner", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Text(
                "Premium remains attached to your Google/YouTube account in the official apps.",
                color = PremiumMuted
            )
        }
        item {
            ServiceHeroCard(
                eyebrow = "MUSIC FIRST",
                title = "YouTube Music",
                body = "Open your personalized mixes, library, playlists and Premium music experience.",
                action = "OPEN MUSIC"
            ) {
                launchFirstInstalled(
                    context,
                    listOf("com.google.android.apps.youtube.music", "com.google.android.youtube.tv"),
                    "https://music.youtube.com/"
                )
            }
        }
        item {
            ServiceHeroCard(
                eyebrow = "VIDEO",
                title = "YouTube Premium",
                body = "Open the signed-in YouTube TV experience with your subscriptions, recommendations and Premium benefits.",
                action = "OPEN YOUTUBE"
            ) {
                launchFirstInstalled(
                    context,
                    listOf("com.google.android.youtube.tv", "com.google.android.youtube"),
                    "https://www.youtube.com/"
                )
            }
        }
        item {
            PremiumInfoCard(
                title = "Why this opens the official YouTube apps",
                body = "Google does not expose the full personalized Home feed, YouTube Music catalogue, or your Premium entitlement to third-party TV apps. NM Stream keeps YouTube as a premium one-click destination instead of pretending to mirror data the public APIs do not provide."
            )
        }
    }
}

@Composable
internal fun ServicesHubScreen() {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().background(PremiumBg).padding(horizontal = 42.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("NM SERVICES", color = PremiumGold, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text("Premium apps, one doorway", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Text("Launch supported official streaming apps without leaving the NM Stream navigation concept.", color = PremiumMuted)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ServiceTile("Netflix", "Films & series", Modifier.weight(1f)) {
                    launchFirstInstalled(
                        context,
                        listOf("com.netflix.ninja", "com.netflix.mediaclient"),
                        "https://www.netflix.com/"
                    )
                }
                ServiceTile("F1 TV", "Live F1 & archive", Modifier.weight(1f)) {
                    launchFirstInstalled(
                        context,
                        listOf("com.formulaone.production"),
                        "https://f1tv.formula1.com/"
                    )
                }
                ServiceTile("DStv Stream", "Live TV & Catch Up", Modifier.weight(1f)) {
                    launchFirstInstalled(
                        context,
                        listOf("com.dstvmobile.android", "com.dstv.android"),
                        "https://now.dstv.com/"
                    )
                }
            }
        }

        item {
            PremiumInfoCard(
                title = "Protected services stay protected",
                body = "NM Stream can launch and hand off to Netflix, F1 TV and DStv. Their DRM video, account entitlements and private catalogues cannot be re-broadcast inside the NM player without each provider's commercial integration and DRM authorization."
            )
        }
    }
}

@Composable
private fun ServiceHeroCard(
    eyebrow: String,
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        if (focused) Color(0xFF292316) else Color(0xFF17191D),
                        Color(0xFF0E1013)
                    )
                )
            )
            .border(if (focused) 2.dp else 1.dp, if (focused) PremiumGold else Color.White.copy(alpha = .08f), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .premiumActivation(onClick)
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(eyebrow, color = PremiumGold, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(body, color = PremiumMuted, fontSize = 14.sp)
        }
        Text(action, color = PremiumGold, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ServiceTile(title: String, body: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (focused) PremiumPanelRaised else PremiumPanel)
            .border(if (focused) 2.dp else 1.dp, if (focused) PremiumGold else Color.White.copy(alpha = .08f), RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .premiumActivation(onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(body, color = PremiumMuted)
        Spacer(Modifier.height(6.dp))
        Text("OPEN", color = PremiumGold, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}

@Composable
private fun PremiumInfoCard(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PremiumPanel)
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = PremiumPlatinum, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(body, color = PremiumMuted, fontSize = 14.sp)
    }
}

private fun Modifier.premiumActivation(onClick: () -> Unit): Modifier =
    androidx.compose.foundation.clickable(onClick = onClick)

private fun launchFirstInstalled(context: Context, packages: List<String>, fallbackUrl: String) {
    packages.firstNotNullOfOrNull { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)
    }?.let { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        return
    }

    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
