package com.katok.smspush

import android.util.Log
import ua.naiksoftware.stomp.Stomp
import ua.naiksoftware.stomp.StompClient
import ua.naiksoftware.stomp.dto.LifecycleEvent
import ua.naiksoftware.stomp.dto.StompHeader

class WebSocketManager private constructor() {

    companion object {
        private const val TAG = "WebSocketManager"
        @Volatile
        private var instance: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketManager().also { instance = it }
            }
        }
    }

    private var stompClient: StompClient? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false
    private var messageListener: ((String) -> Unit)? = null

    @Synchronized
    fun connect(url: String, token: String, listener: (String) -> Unit) {
        if (isConnected || isConnecting) {
            Log.d(TAG, "Already connected or connecting, skipping")
            return
        }
        disconnect()

        isConnecting = true
        messageListener = listener

        stompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, url)

        // Настраиваем heartbeat
        stompClient?.withClientHeartbeat(10000)
        stompClient?.withServerHeartbeat(10000)

        val connectHeaders = listOf(
            StompHeader("Authorization", "Bearer $token")
        )

        stompClient?.lifecycle()?.subscribe({ event ->
            Log.d(TAG, "Lifecycle: ${event.type}")
            when (event.type) {
                LifecycleEvent.Type.OPENED -> {
                    Log.d(TAG, "STOMP OPENED")
                    isConnected = true
                    isConnecting = false
                    // Подписываемся на очередь
                    stompClient?.topic("/user/queue/sms-commands")?.subscribe({ message ->
                        Log.d(TAG, "Received message: ${message.payload}")
                        listener(message.payload)
                    }, { error ->
                        Log.e(TAG, "Subscription error", error)
                    })
                    Log.d(TAG, "Subscribed to /user/queue/sms-commands")
                }
                LifecycleEvent.Type.CLOSED -> {
                    Log.d(TAG, "STOMP CLOSED")
                    isConnected = false
                    isConnecting = false
                }
                LifecycleEvent.Type.ERROR -> {
                    Log.e(TAG, "STOMP ERROR", event.exception)
                    isConnected = false
                    isConnecting = false
                }
                else -> {}
            }
        }, { error ->
            Log.e(TAG, "Lifecycle error", error)
            isConnected = false
            isConnecting = false
        })

        stompClient?.connect(connectHeaders)
    }

    @Synchronized
    fun disconnect() {
        stompClient?.disconnect()
        stompClient = null
        isConnected = false
        isConnecting = false
    }

    @Synchronized
    fun sendResponse(response: String) {
        if (!isConnected || stompClient == null) {
            Log.w(TAG, "Cannot send response, not connected")
            return
        }
        stompClient?.send("/app/sms-response", response)
            ?.subscribe({ Log.d(TAG, "Response sent") }, { error ->
                Log.e(TAG, "Failed to send response", error)
            })
    }

    fun isConnected(): Boolean = isConnected
}