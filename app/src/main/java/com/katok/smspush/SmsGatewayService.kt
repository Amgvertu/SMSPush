package com.katok.smspush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType

class SmsGatewayService : Service() {

    companion object {
        const val TAG = "SmsGateway"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "sms_gateway_channel"
        const val NOTIFICATION_ID = 1
        const val WS_URL = "ws://192.168.0.119:8081/ws"
        const val REFRESH_URL = "http://192.168.0.119:8081/auth/refresh"
        // Интервал обновления токена (25 минут, чтобы успеть до истечения 30 мин)
        const val TOKEN_REFRESH_INTERVAL = 25 * 60 * 1000L
    }

    private lateinit var tokenManager: TokenManager
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var tokenRefreshRunnable: Runnable? = null
    private var isRefreshingToken = false

    private lateinit var notificationManager: NotificationManager
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Шлюз запущен"))
                connectWebSocket()
                scheduleTokenRefresh()
            }
            ACTION_STOP -> {
                disconnectWebSocket()
                cancelAllTimers()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ---------- WebSocket ----------
    private fun connectWebSocket() {
        val accessToken = tokenManager.getAccessToken()
        if (accessToken == null) {
            Log.e(TAG, "Access token is missing! Please login first.")
            updateNotification("Нет токена – авторизуйтесь")
            return
        }

        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                updateNotification("Шлюз подключён")
                cancelReconnect()

                // Подписка на личную очередь команд
                val subscribeFrame = """
                    SUBSCRIBE
                    id:sub-0
                    destination:/user/queue/sms-commands
                    
                """.trimIndent() + "\n\n\u0000"
                webSocket.send(subscribeFrame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.startsWith("MESSAGE")) {
                    handleStompMessage(text)
                } else if (text.contains("ERROR")) {
                    Log.e(TAG, "STOMP error frame: $text")
                    // Возможно, ошибка аутентификации – пробуем обновить токен
                    tryRefreshAndReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}, response=${response?.code}")
                if (response?.code == 401) {
                    // Точно проблема с токеном
                    tryRefreshAndReconnect()
                } else {
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                scheduleReconnect()
            }
        })
        webSocket?.send("CONNECT\naccept-version:1.1,1.0\n\n\u0000")
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        cancelReconnect()
    }

    private fun handleStompMessage(raw: String) {
        try {
            val body = raw.substringAfter("\n\n").trim()
            if (body.isEmpty()) return
            val command = gson.fromJson(body, SmsCommand::class.java)
            Log.d(TAG, "Parsed command: $command")

            val success = sendSms(command.phone, command.code)
            val response = SmsResponse(
                requestId = command.requestId,
                success = success,
                errorMessage = if (success) null else "SMS send failed"
            )
            sendResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle STOMP message", e)
        }
    }

    private fun sendResponse(response: SmsResponse) {
        val json = gson.toJson(response)
        val frame = """
            SEND
            destination:/app/sms-response
            content-type:application/json
            
            $json
        """.trimIndent() + "\n\n\u0000"
        webSocket?.send(frame)
    }

    // ---------- SMS ----------
    private fun sendSms(phone: String, code: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            val message = "Код подтверждения: $code"
            smsManager.sendTextMessage(phone, null, message, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Token Refresh ----------
    private fun tryRefreshAndReconnect() {
        if (isRefreshingToken) return
        isRefreshingToken = true
        Thread {
            val success = refreshToken()
            handler.post {
                isRefreshingToken = false
                if (success) {
                    Log.d(TAG, "Token refreshed, reconnecting...")
                    webSocket = null
                    connectWebSocket()
                } else {
                    Log.e(TAG, "Token refresh failed – will retry later")
                    scheduleReconnect()
                }
            }
        }.start()
    }

    private fun refreshToken(): Boolean {
        val refreshToken = tokenManager.getRefreshToken() ?: return false
        val jsonBody = """{"refreshToken":"$refreshToken"}"""
        val request = Request.Builder()
            .url(REFRESH_URL)
            .post(RequestBody.create("application/json".toMediaType(), jsonBody))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            val tokenResponse = gson.fromJson(body, TokenRefreshResponse::class.java)
            if (tokenResponse.accessToken != null && tokenResponse.refreshToken != null) {
                tokenManager.saveTokens(tokenResponse.accessToken!!, tokenResponse.refreshToken!!)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
        }
        return false
    }

    // Классы для парсинга ответа сервера (зависят от формата вашего API)
    data class TokenRefreshResponse(
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("refreshToken") val refreshToken: String?
    )

    // Периодическое обновление токена, чтобы не допустить истечения во время работы
    private fun scheduleTokenRefresh() {
        tokenRefreshRunnable = Runnable {
            Log.d(TAG, "Proactive token refresh")
            tryRefreshAndReconnect()
            // Заново планируем следующее обновление
            handler.postDelayed(tokenRefreshRunnable!!, TOKEN_REFRESH_INTERVAL)
        }
        handler.postDelayed(tokenRefreshRunnable!!, TOKEN_REFRESH_INTERVAL)
    }

    // ---------- Reconnect ----------
    private fun scheduleReconnect() {
        cancelReconnect()
        reconnectRunnable = Runnable {
            Log.d(TAG, "Attempting reconnect...")
            connectWebSocket()
        }
        handler.postDelayed(reconnectRunnable!!, 10_000)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun cancelAllTimers() {
        cancelReconnect()
        tokenRefreshRunnable?.let { handler.removeCallbacks(it) }
    }

    // ---------- Notification ----------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Gateway",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}