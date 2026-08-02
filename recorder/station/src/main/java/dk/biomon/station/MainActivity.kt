package dk.biomon.station

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.net.NetworkInterface
import java.net.URL

/**
 * A glance, not a dashboard — the real UI is the phone's own web server (API.md), open
 * from any device on the LAN. This screen exists to (1) get the two permissions the
 * service cannot request for itself, (2) show the URL to open elsewhere, (3) confirm
 * the service is actually alive without needing a second device.
 */
class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startStationService()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) needed += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(needed.toTypedArray())

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { StationScreen() }
            }
        }
    }

    private fun startStationService() {
        val i = Intent(this, StationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    }.getOrNull()

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @Composable
    private fun StationScreen() {
        var ip by remember { mutableStateOf<String?>(null) }
        var healthText by remember { mutableStateOf("checking…") }
        var battOk by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }

        LaunchedEffect(Unit) { ip = localIp() }
        LaunchedEffect(Unit) {
            while (true) {
                healthText = runCatching {
                    val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        URL("http://127.0.0.1:8848/api/health").readText()
                    }
                    val o = org.json.JSONObject(body)
                    val listening = o.optBoolean("listening")
                    val modelLoaded = o.optJSONObject("model")?.optBoolean("loaded", false) ?: false
                    when {
                        !modelLoaded -> "model not loaded: ${o.optJSONObject("model")?.optString("error")}"
                        listening -> "listening — ${o.optJSONObject("inference")?.optLong("windows_total")} windows analysed"
                        else -> "service up, not listening"
                    }
                }.getOrElse { "server not reachable yet…" }
                battOk = isIgnoringBatteryOptimizations()
                delay(3000)
            }
        }

        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("Biomon Station", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(healthText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Dashboard", fontWeight = FontWeight.SemiBold)
                    Text(if (ip != null) "http://$ip:8848" else "waiting for network…",
                        style = MaterialTheme.typography.bodyLarge)
                    Text("Open this address from any device on the same wifi.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (!battOk) {
                Button(onClick = {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                }) { Text("Allow to run in the background") }
                Spacer(Modifier.height(8.dp))
                Text("OxygenOS will otherwise kill this after a while — required for an always-on station.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            } else {
                Text("Background execution: allowed", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
