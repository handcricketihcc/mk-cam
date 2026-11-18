package com.mkcam.viewer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mkcam.core.BigButton
import com.mkcam.core.BigOutlinedButton
import com.mkcam.core.MkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as ViewerApp).controller
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        controller.resume()
        setContent {
            MkTheme {
                Surface(Modifier.fillMaxSize()) { ViewerScreen(controller) }
            }
        }
    }
}

@Composable
private fun ViewerScreen(c: ViewerController) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("MK Cam Viewer", style = MaterialTheme.typography.headlineLarge)
        Text(
            c.status,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        if (!c.paired) {
            OutlinedTextField(
                value = c.serverUrl, onValueChange = { c.serverUrl = it },
                label = { Text("Server address, for example https://example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            val pin = c.pin
            if (pin == null) {
                BigButton("Generate pairing PIN", enabled = c.serverUrl.isNotBlank()) {
                    c.startPairing(c.serverUrl)
                }
            } else {
                Text(
                    "PIN: " + pin.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "Pairing PIN " + pin.toList().joinToString(", ")
                    },
                )
                Text(
                    "Type this PIN in the Device app within 10 minutes.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                BigOutlinedButton("Cancel pairing") { c.cancelPairing() }
            }
        } else {
            Text(
                if (c.peerOnline) "Device: ONLINE" else "Device: OFFLINE",
                style = MaterialTheme.typography.titleMedium,
            )
            BigButton(
                if (c.listening) "Stop listening" else "Start listening",
                enabled = c.listening || c.peerOnline,
            ) {
                if (c.listening) c.stopListening() else c.startListening()
            }
            BigOutlinedButton("Unpair") { c.unpair() }
        }
    }
}
