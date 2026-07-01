package com.katok.smspush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

class SmsGatewayService : Service() {

    companion object {
        const val TAG = "SmsGateway"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "sms_gateway_channel"
        const val NOTIFICATION_ID = 1
        const val WS_URL = "wss://varamy.online/ws"
        const val REFRESH_URL = "https://varamy.online/api/auth/refresh"
        const val TOKEN_REFRESH_INTERVAL = 25 * 60 * 1000L
    }

    private lateinit var tokenManager: TokenManager
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var tokenRefreshRunnable: Runnable? = null
    private var isRefreshingToken = false

    private lateinit var notificationManager: NotificationManager
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Шлюз запущен"))
                requestBatteryOptimizationExemption()
                // Отключаем старое соединение перед подключением
                WebSocketManager.getInstance().disconnect()
                connectWebSocket()
                scheduleTokenRefresh()
            }
            ACTION_STOP -> {
                WebSocketManager.getInstance().disconnect()
                cancelAllTimers()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun connectWebSocket() {
        val accessToken = tokenManager.getAccessToken()
        if (accessToken == null) {
            Log.e(TAG, "[AUTH] Access token is missing!")
            updateNotification("Нет токена – авторизуйтесь")
            return
        }
        Log.d(TAG, "[WS] Connecting to $WS_URL")
        // Сначала отключаем старое соединение
        WebSocketManager.getInstance().disconnect()
        WebSocketManager.getInstance().connect(
            url = WS_URL,
            token = accessToken,
            listener = { rawMessage ->
                handleStompMessage(rawMessage)
            }
        )
    }

    private fun handleStompMessage(raw: String) {
        Log.d(TAG, "[WS] Handling STOMP message: $raw")
        try {
            val bodyStart = raw.indexOf("\n\n")
            if (bodyStart == -1) {
                Log.w(TAG, "No body found in STOMP message")
                return
            }
            var body = raw.substring(bodyStart + 2).trim()
            body = body.replace("\u0000", "").trim()
            if (body.isEmpty()) {
                Log.w(TAG, "Empty body")
                return
            }
            Log.d(TAG, "[WS] Parsing JSON: $body")
            val command = gson.fromJson(body, SmsCommand::class.java)
            Log.d(TAG, "[CMD] Received: phone=${command.phone}, code=${command.code}, purpose=${command.purpose}")

            val success = sendSms(command.phone, command.code)
            val response = SmsResponse(
                requestId = command.requestId,
                success = success,
                errorMessage = if (success) null else "SMS send failed"
            )
            sendResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "[CMD] Failed to handle STOMP message", e)
        }
    }

    private fun sendResponse(response: SmsResponse) {
        val json = gson.toJson(response)
        WebSocketManager.getInstance().sendResponse(json)
        Log.d(TAG, "[RESP] Sent: $json")
    }

    private fun sendSms(phone: String, code: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            val message = "Код подтверждения: $code"
            smsManager.sendTextMessage(phone, null, message, null, null)
            Log.d(TAG, "[SMS] Sent to $phone: $message")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[SMS] Failed to send to $phone: ${e.message}")
            false
        }
    }

    private fun tryRefreshAndReconnect() {
        if (isRefreshingToken) return
        isRefreshingToken = true
        Thread {
            val success = refreshToken()
            handler.post {
                isRefreshingToken = false
                if (success) {
                    Log.d(TAG, "[AUTH] Token refreshed, reconnecting...")
                    WebSocketManager.getInstance().disconnect()
                    connectWebSocket()
                } else {
                    Log.e(TAG, "[AUTH] Token refresh failed – will retry later")
                    scheduleReconnect()
                }
            }
        }.start()
    }

    private fun refreshToken(): Boolean {
        val refreshToken = tokenManager.getRefreshToken() ?: return false
        val jsonBody = """{"refreshToken":"$refreshToken"}"""
        val request = okhttp3.Request.Builder()
            .url(REFRESH_URL)
            .post(okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaType(), jsonBody))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            val tokenResponse = gson.fromJson(body, TokenRefreshResponse::class.java)
            if (tokenResponse.accessToken != null && tokenResponse.refreshToken != null) {
                tokenManager.saveTokens(tokenResponse.accessToken!!, tokenResponse.refreshToken!!)
                Log.d(TAG, "[AUTH] Tokens updated")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AUTH] Error refreshing token", e)
        }
        return false
    }

    data class TokenRefreshResponse(
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("refreshToken") val refreshToken: String?
    )

    private fun scheduleTokenRefresh() {
        tokenRefreshRunnable = Runnable {
            Log.d(TAG, "[AUTH] Proactive token refresh")
            tryRefreshAndReconnect()
            handler.postDelayed(tokenRefreshRunnable!!, TOKEN_REFRESH_INTERVAL)
        }
        handler.postDelayed(tokenRefreshRunnable!!, TOKEN_REFRESH_INTERVAL)
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        reconnectRunnable = Runnable {
            Log.d(TAG, "[WS] Attempting reconnect...")
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

    private fun acquireWakeLock() {
        if (checkSelfPermission(android.Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmsGateway::WakeLock"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d(TAG, "[POWER] WakeLock acquired")
        } else {
            Log.w(TAG, "[POWER] WAKE_LOCK permission not granted")
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "[POWER] Requesting battery optimization exemption")
            }
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

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