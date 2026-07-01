package com.katok.smspush

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper


class WebSocketManager private constructor() {

    companion object {
        private const val TAG = "WebSocketManager"
        @Volatile
        private var instance: WebSocketManager? = null

        private var heartbeatRunnable: Runnable? = null
        private val heartbeatHandler = Handler(Looper.getMainLooper())

        fun getInstance(): WebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketManager().also { instance = it }
            }
        }
    }

    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false
    private var messageListener: ((String) -> Unit)? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun connect(url: String, token: String, listener: (String) -> Unit) {
        // Если уже подключены или в процессе подключения, не создаём новое соединение
        if (isConnected || isConnecting) {
            Log.d(TAG, "Already connected or connecting, skipping")
            return
        }
        // Закрываем старое соединение (на всякий случай)
        disconnect()

        isConnecting = true
        messageListener = listener

        // HTTP-запрос БЕЗ заголовка Authorization
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                // Формируем STOMP CONNECT-фрейм точно по спецификации
                val connectFrame = "CONNECT\naccept-version:1.1,1.0\nheart-beat:10000,10000\nAuthorization:Bearer $token\n\n\u0000"
                Log.d(TAG, "Sending CONNECT frame: $connectFrame")
                webSocket.send(connectFrame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Raw message: $text")
                when {
                    text.startsWith("CONNECTED") -> {
                        Log.d(TAG, "STOMP CONNECTED")
                        isConnected = true
                        isConnecting = false
                        val subscribeFrame = "SUBSCRIBE\nid:sub-0\ndestination:/user/queue/sms-commands\n\n\u0000"
                        webSocket.send(subscribeFrame)
                        Log.d(TAG, "Subscribed to /user/queue/sms-commands")
                    }
                    text.startsWith("CONNECTED") -> {
                        Log.d(TAG, "STOMP CONNECTED")
                        isConnected = true
                        isConnecting = false
                        val subscribeFrame = "SUBSCRIBE\nid:sub-0\ndestination:/user/queue/sms-commands\n\n\u0000"
                        webSocket.send(subscribeFrame)
                        Log.d(TAG, "Subscribed to /user/queue/sms-commands")
                        // Запускаем heartbeat
                        startHeartbeat()
                    }
                    text.contains("ERROR") -> {
                        Log.e(TAG, "STOMP error: $text")
                        isConnected = false
                        isConnecting = false
                    }
                    else -> {
                        // Игнорируем другие фреймы (например, RECEIPT)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                isConnected = false
                isConnecting = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
                isConnecting = false
            }
        })
    }

    @Synchronized
    fun disconnect() {
        webSocket?.close(1000, "Manual disconnect")
        webSocket = null
        isConnected = false
        isConnecting = false
    }

    @Synchronized
    fun sendResponse(response: String) {
        if (!isConnected) {
            Log.w(TAG, "Cannot send response, not connected")
            return
        }
        val frame = """
            SEND
            destination:/app/sms-response
            content-type:application/json
            
            $response
        """.trimIndent() + "\n\n\u0000"
        webSocket?.send(frame)
    }

    fun isConnected(): Boolean = isConnected

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = Runnable {
            if (isConnected) {
                // Отправляем пустую строку с нулевым байтом как heartbeat
                webSocket?.send("\n")
                Log.d(TAG, "Heartbeat sent")
                heartbeatHandler.postDelayed(heartbeatRunnable!!, 10000)
            }
        }
        heartbeatHandler.postDelayed(heartbeatRunnable!!, 10000)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }
}