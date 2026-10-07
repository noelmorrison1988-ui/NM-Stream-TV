package za.co.nm.streamtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
}

@Composable
private fun StartupSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF090A0E),
                        Color(0xFF141821),
                        Color(0xFF050609)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                "NM",
                color = Color(0xFFD6A84B),
                fontSize = 86.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "STREAM TV MOBILE LITE",
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .width(240.dp)
                    .height(4.dp)
                    .background(Color(0xFFE2182D))
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "MOBILE LITE · MORRISON ENTERTAINMENT",
                color = Color(0xFFD6A84B),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
