package io.github.mobdev.data.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.mobdev.data.api.ChatMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val WS_BASE_URL = "wss://faerytea.name/ws/"

class ChatWebSocketClient(
    private val gson: Gson = Gson()
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect(
        username: String,
        token: String,
        onNewMessage: (ChatMessage) -> Unit
    ) {
        disconnect()
        val request = Request.Builder()
            .url("$WS_BASE_URL$username?token=$token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = parseNewMessage(text) ?: return
                onNewMessage(message)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@ChatWebSocketClient.webSocket = null
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "client closing")
        webSocket = null
    }

    private fun parseNewMessage(payload: String): ChatMessage? {
        return runCatching {
            val root = gson.fromJson(payload, JsonObject::class.java) ?: return null
            val newMessage = root.getAsJsonObject("NewMessage") ?: return null
            val msgJson = newMessage.getAsJsonObject("msg") ?: return null
            gson.fromJson(msgJson, ChatMessage::class.java)
        }.getOrNull()
    }
}