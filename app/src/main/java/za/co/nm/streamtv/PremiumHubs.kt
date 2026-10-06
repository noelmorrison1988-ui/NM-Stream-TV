package za.co.nm.streamtv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    onOpen: (AppMedia) -> Unit
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
                    "IPTV/Xtream + compatible manually installed NM sources.",
                    color = PremiumMuted,
                    fontSize = 15.sp
                )
            }
        }

        if (live.isNotEmpty()) item { SportsRow("● LIVE NOW", live.take(80), onOpen) }
        if (group("Rugby").isNotEmpty()) item { SportsRow("Rugby", group("Rugby"), onOpen) }
        if (group("Motorsport").isNotEmpty()) item { SportsRow("F1 & Motorsport", group("Motorsport"), onOpen) }
        if (group("Football").isNotEmpty()) item { SportsRow("Football", group("Football"), onOpen) }
        if (group("Cricket").isNotEmpty()) item { SportsRow("Cricket", group("Cricket"), onOpen) }
        if (group("Combat Sports").isNotEmpty()) item { SportsRow("Combat Sports", group("Combat Sports"), onOpen) }
        if (group("Tennis").isNotEmpty()) item { SportsRow("Tennis", group("Tennis"), onOpen) }
        if (replays.isNotEmpty()) item { SportsRow("Recent Replays", replays.take(100), onOpen) }

        if (state.sportsCatalog.isEmpty()) {
            item {
                PremiumInfoCard(
                    title = "No sports catalogue loaded yet",
                    body = "IPTV/Xtream sports appear automatically. Compatible manually installed add-ons can also contribute sports catalogues."
                )
            }
        }
    }
}

@Composable
private fun SportsRow(
    title: String,
    media: List<AppMedia>,
    onOpen: (AppMedia) -> Unit
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
                SportsCard(item) { onOpen(item) }
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
    this.clickable(onClick = onClick)
