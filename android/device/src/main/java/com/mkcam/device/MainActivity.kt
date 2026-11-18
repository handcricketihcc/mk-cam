package com.mkcam.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mkcam.core.BigButton
import com.mkcam.core.BigOutlinedButton
import com.mkcam.core.MkTheme
import com.mkcam.core.Prefs
import com.mkcam.core.Signaling
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this, "mk_device")
        setContent {
            MkTheme {
                Surface(Modifier.fillMaxSize()) { DeviceScreen(prefs) }
            }
        }
    }
}

private fun startService(ctx: Context) {
    ContextCompat.startForegroundService(ctx, Intent(ctx, DeviceService::class.java))
}

@Composable
private fun DeviceScreen(prefs: Prefs) {
    val ctx = LocalContext.current
    var paired by remember { mutableStateOf(prefs.pairId != null) }
    var server by remember { mutableStateOf(prefs.serverUrl) }
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val running by DeviceService.running.collectAsState()
    val status by DeviceService.status.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startService(ctx)
        else msg = "Microphone permission is required."
    }

    fun pair() {
        val main = Handler(Looper.getMainLooper())
        msg = "Connecting..."
        val s = try {
            Signaling(server.trim())
        } catch (e: Exception) {
            msg = "Invalid server address"
            return
        }
        s.onError = {
            s.close()
            main.post { msg = "Cannot reach server" }
        }
        s.onConnect = {
            s.emit("pair:claim", JSONObject().put("pin", pin.trim())) { r ->
                s.close()
                main.post {
                    if (r.optBoolean("ok")) {
                        prefs.serverUrl = server
                        prefs.pairId = r.getString("pairId")
                        prefs.secret = r.getString("secret")
                        paired = true
                        msg = ""
                    } else {
                        msg = "Pairing failed: " + r.optString("error")
                    }
                }
            }
        }
        s.connect()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("MK Cam Device", style = MaterialTheme.typography.headlineLarge)
        Text(
            "This phone's microphone will be heard on your Viewer phone.",
            style = MaterialTheme.typography.bodyLarge,
        )

        if (!paired) {
            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("Server address, for example https://example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                label = { Text("6 digit PIN from the Viewer app") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            BigButton("Pair with Viewer", enabled = pin.length == 6 && server.isNotBlank()) { pair() }
        } else {
            Text(
                if (running) status else "Service is stopped",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!running) {
                BigButton("Start service") {
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                    permissionLauncher.launch(perms.toTypedArray())
                }
            } else {
                BigButton("Stop service") {
                    ctx.startService(
                        Intent(ctx, DeviceService::class.java).setAction(DeviceService.ACTION_STOP)
                    )
                }
            }
            BigOutlinedButton("Battery settings (allow unrestricted)") {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            BigOutlinedButton("Unpair this device", enabled = !running) {
                prefs.clearPairing()
                paired = false
            }
            Text(
                "Keep this phone plugged in. The microphone opens only while the Viewer is listening.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (msg.isNotEmpty()) {
            Text(
                msg, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
