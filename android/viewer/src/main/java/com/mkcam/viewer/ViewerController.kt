package com.mkcam.viewer

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mkcam.core.AudioPeer
import com.mkcam.core.AudioRoute
import com.mkcam.core.Prefs
import com.mkcam.core.Signaling
import org.json.JSONObject
import org.webrtc.PeerConnection

class ViewerApp : Application() {
    val controller: ViewerController by lazy { ViewerController(this) }
}

/** All Viewer logic lives here so it survives screen rotation / activity restarts. */
class ViewerController(private val app: Context) {
    private val prefs = Prefs(app, "mk_viewer")
    private val main = Handler(Looper.getMainLooper())

    var paired by mutableStateOf(prefs.pairId != null)
        private set
    var pin by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf(if (prefs.pairId != null) "Connecting..." else "Not paired")
        private set
    var peerOnline by mutableStateOf(false)
        private set
    var listening by mutableStateOf(false)
        private set
    var serverUrl by mutableStateOf(prefs.serverUrl)

    private var sig: Signaling? = null
    private var peer: AudioPeer? = null
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private var pairing = false
    private var tmpId: String? = null
    private var tmpSecret: String? = null

    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    /** Called when the app opens. */
    fun resume() {
        if (paired && sig == null) connect()
    }

    fun startPairing(url: String) {
        prefs.serverUrl = url
        serverUrl = url.trim()
        pairing = true
        sig?.close()
        sig = null
        status = "Connecting..."
        connect()
    }

    fun cancelPairing() {
        pairing = false
        sig?.close()
        sig = null
        pin = null
        status = "Not paired"
    }

    private fun connect() {
        val s = try {
            Signaling(prefs.serverUrl)
        } catch (e: Exception) {
            status = "Invalid server address"
            pairing = false
            return
        }
        sig = s
        s.onConnect = {
            if (paired) join() else if (pairing) createPair()
        }
        s.onDisconnect = {
            ui {
                peerOnline = false
                status = "Reconnecting..."
            }
            stopListeningLocal()
        }
        s.onError = { ui { status = "Cannot reach server" } }
        s.on("pair:claimed") {
            prefs.pairId = tmpId
            prefs.secret = tmpSecret
            pairing = false
            ui {
                paired = true
                pin = null
                status = "Paired"
            }
            join()
        }
        s.on("peer:online") {
            ui {
                peerOnline = true
                status = "Camera device is online"
            }
        }
        s.on("peer:offline") {
            ui {
                peerOnline = false
                status = "Device went offline"
            }
            stopListeningLocal()
        }
        s.on("listen:stop") { stopListeningLocal() }
        s.on("signal") { peer?.onSignal(it) }
        s.connect()
    }

    private fun createPair() {
        sig?.emit("pair:create") { r ->
            if (r.optBoolean("ok")) {
                tmpId = r.getString("pairId")
                tmpSecret = r.getString("secret")
                ice = AudioPeer.parseIce(r.optJSONArray("iceServers"))
                ui {
                    pin = r.getString("pin")
                    status = "Enter this PIN in the Device app"
                }
            } else {
                ui { status = "Error: " + r.optString("error") }
            }
        }
    }

    private fun join() {
        val id = prefs.pairId ?: return
        val secret = prefs.secret ?: return
        sig?.emit(
            "join",
            JSONObject().put("pairId", id).put("role", "viewer").put("secret", secret),
        ) { r ->
            if (r.optBoolean("ok")) {
                ice = AudioPeer.parseIce(r.optJSONArray("iceServers"))
                val online = r.optBoolean("peerOnline")
                ui {
                    peerOnline = online
                    status = if (online) "Camera device is online" else "Device is offline"
                }
            } else {
                ui { status = "Server rejected pairing. Unpair and pair again." }
            }
        }
    }

    fun startListening() {
        val s = sig ?: return
        if (!peerOnline || listening) return
        peer = AudioPeer(
            app, ice, false,
            sendSignal = { s.emit("signal", it) },
            onState = { st ->
                ui {
                    status = when (st) {
                        "CONNECTED" -> "Listening live"
                        "DISCONNECTED" -> "Audio interrupted"
                        "FAILED" -> "Audio connection failed"
                        else -> "Connecting audio ($st)"
                    }
                }
                if (st == "FAILED") stopListeningLocal()
            },
        )
        AudioRoute.speaker(app, true)
        ViewerService.start(app)
        listening = true
        status = "Asking the device to start..."
        s.emit("listen:request")
    }

    fun stopListening() {
        sig?.emit("listen:stop")
        stopListeningLocal()
    }

    private fun stopListeningLocal() {
        peer?.close()
        peer = null
        AudioRoute.speaker(app, false)
        ViewerService.stop(app)
        ui {
            if (listening) {
                listening = false
                if (peerOnline) status = "Stopped"
            }
        }
    }

    fun unpair() {
        stopListeningLocal()
        sig?.close()
        sig = null
        prefs.clearPairing()
        paired = false
        peerOnline = false
        pin = null
        status = "Not paired"
    }
}
