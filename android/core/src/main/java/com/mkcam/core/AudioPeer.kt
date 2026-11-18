package com.mkcam.core

import android.content.Context
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

private open class Sdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/**
 * Audio-only WebRTC connection.
 * isDevice = true  -> captures the microphone and sends the offer (send-only).
 * isDevice = false -> waits for the offer, answers, plays the remote audio (receive-only).
 */
class AudioPeer(
    context: Context,
    private val iceServers: List<PeerConnection.IceServer>,
    private val isDevice: Boolean,
    private val sendSignal: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val factory = sharedFactory(context)
    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var remoteSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    private fun createPc(): PeerConnection {
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(r: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate) {
                sendSignal(
                    JSONObject().put("type", "ice")
                        .put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex)
                        .put("candidate", c.sdp)
                )
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                onState(s?.name ?: "UNKNOWN")
            }
        }
        return factory.createPeerConnection(cfg, observer)
            ?: throw IllegalStateException("PeerConnection create failed")
    }

    /** Device side: open the mic and create the offer. */
    fun startAsDevice() {
        val p = createPc().also { pc = it }
        val src = factory.createAudioSource(MediaConstraints()).also { source = it }
        val t = factory.createAudioTrack("mkmic", src).also { track = it }
        p.addTransceiver(
            t,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                listOf("mkstream"),
            ),
        )
        p.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                p.setLocalDescription(Sdp(), d)
                sendSignal(JSONObject().put("type", "offer").put("sdp", d.description))
            }
        }, MediaConstraints())
    }

    /** Both sides: feed incoming signaling messages here. */
    fun onSignal(d: JSONObject) {
        when (d.optString("type")) {
            "offer" -> if (!isDevice) {
                val p = pc ?: createPc().also { pc = it }
                p.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        markRemoteSet(p)
                        p.createAnswer(object : Sdp() {
                            override fun onCreateSuccess(a: SessionDescription?) {
                                if (a == null) return
                                p.setLocalDescription(Sdp(), a)
                                sendSignal(JSONObject().put("type", "answer").put("sdp", a.description))
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, d.getString("sdp")))
            }
            "answer" -> if (isDevice) {
                val p = pc ?: return
                p.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        markRemoteSet(p)
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, d.getString("sdp")))
            }
            "ice" -> {
                val c = IceCandidate(d.optString("sdpMid"), d.optInt("sdpMLineIndex"), d.getString("candidate"))
                val p = pc
                synchronized(pendingIce) {
                    if (remoteSet && p != null) p.addIceCandidate(c) else pendingIce.add(c)
                }
            }
        }
    }

    private fun markRemoteSet(p: PeerConnection) {
        synchronized(pendingIce) {
            remoteSet = true
            pendingIce.forEach { p.addIceCandidate(it) }
            pendingIce.clear()
        }
    }

    fun close() {
        try {
            pc?.close()
            pc?.dispose()
            track?.dispose()
            source?.dispose()
        } catch (_: Exception) {
        }
        pc = null
        track = null
        source = null
    }

    companion object {
        @Volatile
        private var shared: PeerConnectionFactory? = null

        private fun sharedFactory(ctx: Context): PeerConnectionFactory =
            shared ?: synchronized(this) {
                shared ?: run {
                    val app = ctx.applicationContext
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
                    )
                    val adm = JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
                    PeerConnectionFactory.builder()
                        .setAudioDeviceModule(adm)
                        .createPeerConnectionFactory()
                        .also { shared = it }
                }
            }

        fun parseIce(arr: JSONArray?): List<PeerConnection.IceServer> {
            val out = mutableListOf<PeerConnection.IceServer>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.get("urls")
                val urls = if (u is JSONArray) List(u.length()) { u.getString(it) } else listOf(u.toString())
                out += PeerConnection.IceServer.builder(urls)
                    .setUsername(o.optString("username", ""))
                    .setPassword(o.optString("credential", ""))
                    .createIceServer()
            }
            return out
        }
    }
}

object AudioRoute {
    /** Viewer: play incoming audio through the loudspeaker. */
    fun speaker(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (on) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } else {
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }
}
