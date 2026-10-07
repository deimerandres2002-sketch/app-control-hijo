package com.example.parentalagent

import android.util.Base64
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

object SocketManager {
    private var socket: Socket? = null
    private val connected = AtomicBoolean(false)
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onParentCommand: ((String, JSONObject) -> Unit)? = null

    fun connect(serverUrl: String, deviceId: String, deviceToken: String) {
        disconnect()

        val options = IO.Options.builder()
            .setReconnection(true)
            .setReconnectionAttempts(Int.MAX_VALUE)
            .setReconnectionDelay(1000)
            .setReconnectionDelayMax(10000)
            .setAuth(mapOf(
                "role" to "child",
                "deviceId" to deviceId,
                "deviceToken" to deviceToken
            ))
            .build()

        socket = IO.socket(serverUrl, options).apply {
            on(Socket.EVENT_CONNECT) {
                connected.set(true)
                onConnectionChanged?.invoke(true)
            }
            on(Socket.EVENT_DISCONNECT) {
                connected.set(false)
                onConnectionChanged?.invoke(false)
            }
            on(Socket.EVENT_CONNECT_ERROR) {
                connected.set(false)
                onConnectionChanged?.invoke(false)
            }
            on("parent_command") { args ->
                val obj = args.firstOrNull() as? JSONObject ?: return@on
                val type = obj.optString("type")
                val payload = obj.optJSONObject("payload") ?: JSONObject()
                onParentCommand?.invoke(type, payload)
            }
            connect()
        }
    }

    fun sendStatus(status: JSONObject) {
        socket?.emit("child_status", status)
    }

    fun sendLimits(limits: JSONObject) {
        socket?.emit("child_limits", limits)
    }

    fun sendScreenFrame(jpeg: ByteArray) {
        if (!connected.get()) return
        socket?.emit("screen_frame", jpeg)
    }

    fun isConnected(): Boolean = connected.get()

    fun disconnect() {
        socket?.disconnect()
        socket = null
        connected.set(false)
    }
}
