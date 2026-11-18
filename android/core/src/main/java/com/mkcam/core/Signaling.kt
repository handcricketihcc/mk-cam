package com.mkcam.core

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

/** Thin wrapper over the Socket.IO connection to the signaling server. */
class Signaling(url: String) {
    private val socket: Socket

    var onConnect: (() -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        val opts = IO.Options().apply {
            reconnection = true
            reconnectionDelay = 2000
            reconnectionDelayMax = 15000
            transports = arrayOf("websocket")
        }
        socket = IO.socket(url, opts)
        socket.on(Socket.EVENT_CONNECT) { onConnect?.invoke() }
        socket.on(Socket.EVENT_DISCONNECT) { onDisconnect?.invoke() }
        socket.on(Socket.EVENT_CONNECT_ERROR) { a ->
            onError?.invoke(a.firstOrNull()?.toString() ?: "connect error")
        }
    }

    fun on(event: String, handler: (JSONObject) -> Unit) {
        socket.on(event) { a -> handler((a.firstOrNull() as? JSONObject) ?: JSONObject()) }
    }

    fun emit(event: String, data: JSONObject = JSONObject(), ack: ((JSONObject) -> Unit)? = null) {
        if (ack == null) {
            socket.emit(event, data)
        } else {
            socket.emit(event, arrayOf<Any>(data), Ack { a ->
                ack((a.firstOrNull() as? JSONObject) ?: JSONObject())
            })
        }
    }

    fun connect() {
        socket.connect()
    }

    fun close() {
        socket.off()
        socket.disconnect()
    }
}
