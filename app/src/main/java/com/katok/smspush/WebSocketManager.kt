package com.katok.smspush

import android.util.Log
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import okhttp3.OkHttpClient
import ua.naiksoftware.stomp.Stomp
import ua.naiksoftware.stomp.StompClient
import ua.naiksoftware.stomp.dto.LifecycleEvent
import ua.naiksoftware.stomp.dto.StompHeader
import java.util.concurrent.TimeUnit

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

    interface Listener {
        fun onMessage(payload: String)
        fun onConnected()
        fun onClosed()
        fun onError(throwable: Throwable?)
    }

    private var stompClient: StompClient? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false
    private var listener: Listener? = null
    private val compositeDisposable = CompositeDisposable()

    @Synchronized
    fun resetState() {
        MainActivity.appendLog("🔄 Сброс состояния WebSocket")
        isConnected = false
        isConnecting = false
        compositeDisposable.clear()
        stompClient?.disconnect()
        stompClient = null
    }

    @Synchronized
    fun connect(url: String, token: String, listener: Listener) {
        // Принудительно сбрасываем состояние, чтобы можно было переподключиться
        resetState()

        this.listener = listener
        isConnecting = true
        MainActivity.appendLog("🌐 Подключение к $url")

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

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
                        listener.onConnected()
                        // Подписка на очередь
                        stompClient?.topic("/user/queue/sms-commands")
                            ?.subscribeOn(Schedulers.io())
                            ?.observeOn(AndroidSchedulers.mainThread())
                            ?.subscribe({ message ->
                                MainActivity.appendLog("📩 Сообщение получено")
                                listener.onMessage(message.payload)
                            }, { error ->
                                MainActivity.appendLog("❌ Ошибка подписки: ${error.message}")
                            })?.let { compositeDisposable.add(it) }
                        MainActivity.appendLog("📡 Подписан на /user/queue/sms-commands")
                    }
                    LifecycleEvent.Type.CLOSED -> {
                        MainActivity.appendLog("🔴 STOMP CLOSED")
                        // Сбрасываем состояние при закрытии
                        resetState()
                        listener.onClosed()
                    }
                    LifecycleEvent.Type.ERROR -> {
                        MainActivity.appendLog("❌ STOMP ERROR: ${event.exception?.message}")
                        resetState()
                        listener.onError(event.exception)
                    }
                    else -> {}
                }
            }, { error ->
                MainActivity.appendLog("❌ Ошибка жизненного цикла: ${error.message}")
                resetState()
                listener.onError(error)
            })?.let { compositeDisposable.add(it) }

        stompClient?.connect(connectHeaders)
        MainActivity.appendLog("⏳ Отправлен CONNECT")
    }

    @Synchronized
    fun disconnect() {
        MainActivity.appendLog("🔌 Отключение WebSocket")
        resetState()
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