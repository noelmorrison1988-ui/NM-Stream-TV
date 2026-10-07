package za.co.nm.streamtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveNavigation()
        setContent {
            val vm: MainViewModel = viewModel()
            val state = vm.uiState.collectAsStateWithLifecycle().value

            var minimumSplashElapsed by remember { mutableStateOf(false) }
            var maximumSplashElapsed by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                delay(2_200)
                minimumSplashElapsed = true
            }
            LaunchedEffect(Unit) {
                delay(4_500)
                maximumSplashElapsed = true
            }

            val showSplash =
                !minimumSplashElapsed || (state.loading && !maximumSplashElapsed)

            Crossfade(
                targetState = showSplash,
                label = "nm-stream-startup"
            ) { splashVisible ->
                if (splashVisible) {
                    StartupSplash()
                } else {
                    NMStreamApp(state = state, viewModel = vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveNavigation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveNavigation()
    }

    private fun applyImmersiveNavigation() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun StartupSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF24150D),
                        Color(0xFF4A2C18),
                        Color(0xFF170B07)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val dark = Color(0xFF120805)
            val tan = Color(0xFFC27B42)
            val stepX = 120f
            val stepY = 92f
            var row = 0
            var y = 15f
            while (y < size.height + stepY) {
                var x = if (row % 2 == 0) 20f else 75f
                while (x < size.width + stepX) {
                    drawCircle(dark.copy(alpha = .78f), 28f, Offset(x, y))
                    drawCircle(tan.copy(alpha = .86f), 16f, Offset(x + 2f, y))
                    drawCircle(dark.copy(alpha = .72f), 6f, Offset(x + 5f, y + 1f))
                    drawCircle(dark.copy(alpha = .5f), 9f, Offset(x - 25f, y + 17f))
                    x += stepX
                }
                row += 1
                y += stepY
            }
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = .10f),
                        Color.Black.copy(alpha = .20f),
                        Color.Black.copy(alpha = .48f)
                    )
                )
            )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                "NM",
                color = Color(0xFFFFF1E7),
                fontSize = 86.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "STREAM TV",
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "The Sarah Edition",
                color = Color(0xFFFF2D95),
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.graphicsLayer { rotationZ = -8f }
            )
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .width(260.dp)
                    .height(4.dp)
                    .background(Color(0xFFFF2D95))
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "MORRISON ENTERTAINMENT",
                color = Color(0xFFD8A45C),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
