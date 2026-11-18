package com.mkcam.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mkcam.core.AudioPeer
import com.mkcam.core.Prefs
import com.mkcam.core.Signaling
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.webrtc.PeerConnection

/**
 * Keeps the Device connected to the server. The microphone is opened ONLY while a
 * Viewer is actually listening. The notification is always visible while this runs.
 */
class DeviceService : Service() {

    companion object {
        val status = MutableStateFlow("Stopped")
        val running = MutableStateFlow(false)
        const val ACTION_STOP = "com.mkcam.device.STOP"
        private const val CHANNEL = "mkcam_device"
        private const val NOTIF_ID = 1
    }

    private var sig: Signaling? = null
    private var peer: AudioPeer? = null
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "MK Cam Device", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Starting..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        running.value = true
        if (sig == null) start()
        return START_STICKY
    }

    private fun start() {
        val prefs = Prefs(this, "mk_device")
        val pairId = prefs.pairId
        val secret = prefs.secret
        if (pairId == null || secret == null || prefs.serverUrl.isEmpty()) {
            setStatus("Not paired")
            stopSelf()
            return
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mkcam:device").apply { acquire() }

        val s = try {
            Signaling(prefs.serverUrl)
        } catch (e: Exception) {
            setStatus("Invalid server address")
            stopSelf()
            return
        }
        sig = s
        s.onConnect = {
            s.emit(
                "join",
                JSONObject().put("pairId", pairId).put("role", "device").put("secret", secret),
            ) { r ->
                if (r.optBoolean("ok")) {
                    ice = AudioPeer.parseIce(r.optJSONArray("iceServers"))
                    setStatus("Connected. Microphone is idle.")
                } else {
                    setStatus("Server rejected this device. Unpair and pair again.")
                }
            }
        }
        s.onDisconnect = {
            stopPeer()
            setStatus("Reconnecting...")
        }
        s.onError = { setStatus("Cannot reach server") }
        s.on("listen:request") { startPeer() }
        s.on("listen:stop") { stopPeer() }
        s.on("signal") { peer?.onSignal(it) }
        s.connect()
    }

    private fun startPeer() {
        val s = sig ?: return
        stopPeer()
        val p = AudioPeer(
            applicationContext, ice, true,
            sendSignal = { s.emit("signal", it) },
            onState = { st ->
                when (st) {
                    "CONNECTED" -> setStatus("MICROPHONE LIVE - someone is listening")
                    "FAILED" -> {
                        stopPeer()
                        setStatus("Audio connection failed. Microphone is idle.")
                    }
                    else -> setStatus("Microphone live, connecting ($st)")
                }
            },
        )
        peer = p
        setStatus("MICROPHONE LIVE - connecting")
        p.startAsDevice()
    }

    private fun stopPeer() {
        peer?.close()
        peer = null
        if (running.value && status.value.startsWith("MICROPHONE")) {
            setStatus("Connected. Microphone is idle.")
        }
    }

    private fun setStatus(text: String) {
        status.value = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DeviceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MK Cam Device")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        stopPeer()
        sig?.close()
        sig = null
        wake?.let { if (it.isHeld) it.release() }
        running.value = false
        status.value = "Stopped"
        super.onDestroy()
    }
}
