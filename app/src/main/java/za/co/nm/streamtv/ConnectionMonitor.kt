package za.co.nm.streamtv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Entirely passive, in-memory network diagnostics. No speed test or extra requests. */
data class ConnectionLogEntry(val time: String, val detail: String)

class ConnectionMonitor(
    context: Context,
    private val record: (ConnectionLogEntry) -> Unit
) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val uid = Process.myUid()

    @Volatile private var playbackActive = false
    @Volatile private var bufferedMs = -1L
    @Volatile private var playerBuffering = false

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        if (!active) {
            bufferedMs = -1L
            playerBuffering = false
        }
    }

    fun updatePlayerBuffer(ms: Long, buffering: Boolean) {
        bufferedMs = ms
        playerBuffering = buffering
    }

    private fun add(detail: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        record(ConnectionLogEntry(time, detail))
    }

    private enum class Status { OFFLINE, UNVERIFIED, ONLINE }

    private fun connectionStatus(): Pair<Status, String> {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return Status.OFFLINE to "No active connection"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return Status.OFFLINE to "No internet-capable network"
        }
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            else -> "Network"
        }
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            Status.ONLINE to transport
        } else {
            Status.UNVERIFIED to transport
        }
    }

    suspend fun run() {
        var previousStatus: Status? = null
        var previousByteCount = TrafficStats.getUidRxBytes(uid)
        var previousSampleMs = SystemClock.elapsedRealtime()
        var typicalRateKb = 0.0
        var dipSamples = 0
        var dipReported = false
        var silenceSamples = 0
        var silenceReported = false

        add("Connection Log started (passive monitoring; speed = app received data)")
        while (currentCoroutineContext().isActive) {
            val (status, transport) = connectionStatus()
            if (status != previousStatus) {
                val detail = when (status) {
                    Status.ONLINE -> "Internet available via $transport"
                    Status.UNVERIFIED -> "Network $transport connected, internet not validated"
                    Status.OFFLINE -> "Connection lost: $transport"
                }
                add(detail)
                previousStatus = status
            }

            val now = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getUidRxBytes(uid)
            val elapsed = (now - previousSampleMs).coerceAtLeast(1L)
            val sampledRateKb = if (rx >= 0L && previousByteCount >= 0L && rx >= previousByteCount) {
                ((rx - previousByteCount) * 1000.0 / elapsed) / 1024.0
            } else null
            previousByteCount = rx
            previousSampleMs = now

            // Only flag throughput reductions while a stream is active and
            // has less than 30 seconds buffered. Paused/fully buffered players
            // naturally stop downloading; that is NOT an internet-speed dip.
            val atRisk = playbackActive && (playerBuffering || bufferedMs in 0L..30_000L)
            if (status != Status.OFFLINE && atRisk && sampledRateKb != null) {
                if (sampledRateKb > 150.0) {
                    typicalRateKb = if (typicalRateKb <= 0.0) sampledRateKb
                    else typicalRateKb * 0.85 + sampledRateKb * 0.15
                }

                if (typicalRateKb >= 150.0 && sampledRateKb < typicalRateKb * 0.3) {
                    dipSamples++
                    if (dipSamples >= 3 && !dipReported) {
                        add(
                            "App receive-rate dip: ${sampledRateKb.toInt()} KB/s " +
                                "(recent typical ${typicalRateKb.toInt()} KB/s; low playback buffer)"
                        )
                        dipReported = true
                    }
                } else {
                    if (dipReported && sampledRateKb >= typicalRateKb * 0.5) {
                        add("App receive rate recovered: ${sampledRateKb.toInt()} KB/s")
                    }
                    dipSamples = 0
                    dipReported = false
                }

                if (sampledRateKb < 1.0 && bufferedMs in 0L..10_000L) {
                    silenceSamples++
                    if (silenceSamples >= 3 && !silenceReported) {
                        add("No incoming app data for about 6 seconds during low-buffer playback")
                        silenceReported = true
                    }
                } else {
                    if (silenceReported && sampledRateKb > 20.0) {
                        add("Incoming app data resumed: ${sampledRateKb.toInt()} KB/s")
                    }
                    silenceSamples = 0
                    silenceReported = false
                }
            } else {
                dipSamples = 0
                silenceSamples = 0
                dipReported = false
                silenceReported = false
            }
            delay(2_000L)
        }
    }
}
