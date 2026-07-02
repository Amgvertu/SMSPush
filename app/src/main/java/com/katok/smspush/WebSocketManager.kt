package com.katok.smspush

import android.util.Log
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
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
    private val compositeDisposable = CompositeDisposable()

    @Synchronized
    fun connect(url: String, token: String, listener: (String) -> Unit) {
        if (isConnected || isConnecting) {
            MainActivity.appendLog("⚠️ Уже подключены или подключаемся")
            return
        }
        disconnect()

        isConnecting = true
        messageListener = listener
        MainActivity.appendLog("🌐 Подключение к $url")

        stompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, url)
        stompClient?.withClientHeartbeat(10000)
        stompClient?.withServerHeartbeat(10000)

        val connectHeaders = listOf(
            StompHeader("Authorization", "Bearer $token"),
            StompHeader("accept-version", "1.1,1.0")
        )

        stompClient?.lifecycle()
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ event ->
                MainActivity.appendLog("🔄 STOMP событие: ${event.type}")
                when (event.type) {
                    LifecycleEvent.Type.OPENED -> {
                        MainActivity.appendLog("✅ STOMP OPENED")
                        isConnected = true
                        isConnecting = false
                        stompClient?.topic("/user/queue/sms-commands")
                            ?.subscribeOn(Schedulers.io())
                            ?.observeOn(AndroidSchedulers.mainThread())
                            ?.subscribe({ message ->
                                MainActivity.appendLog("📩 Сообщение получено")
                                listener(message.payload)
                            }, { error ->
                                MainActivity.appendLog("❌ Ошибка подписки: ${error.message}")
                            })?.let { compositeDisposable.add(it) }
                        MainActivity.appendLog("📡 Подписан на /user/queue/sms-commands")
                    }
                    LifecycleEvent.Type.CLOSED -> {
                        MainActivity.appendLog("🔴 STOMP CLOSED")
                        isConnected = false
                        isConnecting = false
                    }
                    LifecycleEvent.Type.ERROR -> {
                        MainActivity.appendLog("❌ STOMP ERROR: ${event.exception?.message}")
                        isConnected = false
                        isConnecting = false
                    }
                    else -> {}
                }
            }, { error ->
                MainActivity.appendLog("❌ Ошибка жизненного цикла: ${error.message}")
                isConnected = false
                isConnecting = false
            })?.let { compositeDisposable.add(it) }

        stompClient?.connect(connectHeaders)
        MainActivity.appendLog("⏳ Отправлен CONNECT")
    }

    @Synchronized
    fun disconnect() {
        MainActivity.appendLog("🔌 Отключение WebSocket")
        compositeDisposable.clear()
        stompClient?.disconnect()
        stompClient = null
        isConnected = false
        isConnecting = false
    }

    @Synchronized
    fun sendResponse(response: String) {
        if (!isConnected || stompClient == null) {
            MainActivity.appendLog("⚠️ Не удалось отправить ответ: не подключены")
            return
        }
        stompClient?.send("/app/sms-response", response)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({
                MainActivity.appendLog("✅ Ответ отправлен")
            }, { error ->
                MainActivity.appendLog("❌ Ошибка отправки ответа: ${error.message}")
            })?.let { compositeDisposable.add(it) }
    }

    fun isConnected(): Boolean = isConnected
}