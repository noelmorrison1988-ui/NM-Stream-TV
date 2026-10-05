package za.co.nm.streamtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
    val artwork = remember { SplashArtwork.decode() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        artwork?.let {
            Image(
                bitmap = it,
                contentDescription = "NM Stream TV · Morrison Entertainment",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}
