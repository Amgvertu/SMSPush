package com.katok.smspush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import android.content.BroadcastReceiver


class SmsGatewayService : Service() {

    companion object {
        const val TAG = "SmsGateway"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "sms_gateway_channel"
        const val NOTIFICATION_ID = 1
        const val WS_URL = "ws" + AppConfig.API_BASE_URL + "/ws"
        const val REFRESH_URL = "http" + AppConfig.API_BASE_URL + "/api/auth/refresh"
        // Увеличиваем интервал обновления до 23 часов (при 7-дневном access-токене)
        const val TOKEN_REFRESH_INTERVAL = 23 * 60 * 60 * 1000L

        private var instance: SmsGatewayService? = null
        fun getInstance(): SmsGatewayService? = instance
    }

    private lateinit var tokenManager: TokenManager
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var tokenRefreshRunnable: Runnable? = null
    private var isRefreshingToken = false
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5

    private lateinit var healthCheckRunnable: Runnable

    private lateinit var notificationManager: NotificationManager
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var wakeLock: PowerManager.WakeLock? = null

    private val tokenUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val accessToken = intent.getStringExtra("access_token")
            val refreshToken = intent.getStringExtra("refresh_token")
            if (accessToken != null && refreshToken != null) {
                tokenManager.saveTokens(accessToken, refreshToken)
                MainActivity.appendLog("🔄 Токены обновлены через Broadcast")
                if (WebSocketManager.getInstance().isConnected()) {
                    WebSocketManager.getInstance().disconnect()
                    connectWebSocket()
                }
            }
        }
    }


    override fun onCreate() {
        super.onCreate()
        instance = this
        tokenManager = TokenManager(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        healthCheckRunnable = Runnable {
            checkHealth()
            handler.postDelayed(healthCheckRunnable, 30000)
        }
        acquireWakeLock()
        MainActivity.appendLog("Сервис создан")

        // Регистрация BroadcastReceiver с учётом версии Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(tokenUpdateReceiver, IntentFilter("UPDATE_TOKENS"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(tokenUpdateReceiver, IntentFilter("UPDATE_TOKENS"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    startForeground(NOTIFICATION_ID, buildNotification("Шлюз запущен"))
                } catch (e: Exception) {
                    MainActivity.appendLog("❌ Ошибка startForeground: ${e.message}")
                    e.printStackTrace()
                }
                MainActivity.appendLog("Сервис запущен")
                requestBatteryOptimizationExemption()
                connectWebSocket()
                scheduleTokenRefresh()
                startHealthCheck()   // ✅ Запускаем health check
            }
            ACTION_STOP -> {
                MainActivity.appendLog("Сервис останавливается")
                stopHealthCheck()    // ✅ Останавливаем health check
                WebSocketManager.getInstance().disconnect()
                cancelAllTimers()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ---------- Health Check ----------
    private fun startHealthCheck() {
        handler.post(healthCheckRunnable)
    }

    private fun stopHealthCheck() {
        handler.removeCallbacks(healthCheckRunnable)
    }

    private fun checkHealth() {
        if (!WebSocketManager.getInstance().isConnected() && !isRefreshingToken) {
            MainActivity.appendLog("🩺 Обнаружено разорванное соединение, переподключаемся")
            tryRefreshAndReconnect()
        }
    }

    // ---------- WebSocket подключение ----------
    private fun connectWebSocket() {
        val accessToken = tokenManager.getAccessToken()
        if (accessToken == null) {
            MainActivity.appendLog("❌ Нет токена! Авторизуйтесь.")
            return
        }
        reconnectAttempts = 0
        MainActivity.appendLog("Подключение к WebSocket...")
        WebSocketManager.getInstance().connect(
            url = WS_URL,
            token = accessToken,
            listener = object : WebSocketManager.Listener {
                override fun onMessage(payload: String) {
                    handleStompMessage(payload)
                }
                override fun onConnected() {
                    MainActivity.appendLog("✅ WebSocket соединён")
                }
                override fun onClosed() {
                    MainActivity.appendLog("🔴 WebSocket закрыт")
                    scheduleReconnect()
                }
                override fun onError(throwable: Throwable?) {
                    MainActivity.appendLog("❌ WebSocket ошибка: ${throwable?.message}")
                    scheduleReconnect()
                }
            }
        )
    }

    fun reconnectWebSocket() {
        MainActivity.appendLog("🔄 Переподключение WebSocket по запросу")
        WebSocketManager.getInstance().disconnect()
        connectWebSocket()
    }

    // ---------- Обработка сообщений ----------
    private fun handleStompMessage(raw: String) {
        MainActivity.appendLog("📩 Получено сообщение от сервера: $raw")
        try {
            val command = gson.fromJson(raw, SmsCommand::class.java)
            MainActivity.appendLog("📱 Команда: телефон=${command.phone}, код=${command.code}, цель=${command.purpose}")

            val success = sendSms(command.phone, command.code)
            val response = SmsResponse(
                requestId = command.requestId,
                success = success,
                errorMessage = if (success) null else "Ошибка отправки SMS"
            )
            sendResponse(response)
        } catch (e: Exception) {
            MainActivity.appendLog("❌ Ошибка обработки команды: ${e.message}")
            Log.e(TAG, "Failed to handle STOMP message", e)
        }
    }

    private fun sendResponse(response: SmsResponse) {
        val json = gson.toJson(response)
        WebSocketManager.getInstance().sendResponse(json)
        MainActivity.appendLog("✅ Ответ отправлен: success=${response.success}")
    }

    private fun sendSms(phone: String, code: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            val message = "Код подтверждения: $code"
            smsManager.sendTextMessage(phone, null, message, null, null)
            MainActivity.appendLog("📤 SMS отправлено на $phone")
            true
        } catch (e: Exception) {
            MainActivity.appendLog("❌ Ошибка отправки SMS: ${e.message}")
            Log.e(TAG, "Failed to send SMS", e)
            false
        }
    }

    // ---------- Обновление токена ----------
    private fun tryRefreshAndReconnect() {
        if (isRefreshingToken) return
        // Проверяем, истёк ли текущий access-токен
        val currentToken = tokenManager.getAccessToken()
        if (!tokenManager.isTokenExpired(currentToken)) {
            MainActivity.appendLog("ℹ️ Токен ещё действителен, обновление не требуется")
            // Если соединение разорвано, просто переподключаемся с текущим токеном
            if (!WebSocketManager.getInstance().isConnected()) {
                WebSocketManager.getInstance().resetState()
                connectWebSocket()
            }
            return
        }
        MainActivity.appendLog("🔄 Токен истёк, обновляем...")
        isRefreshingToken = true
        Thread {
            val success = refreshToken()
            handler.post {
                isRefreshingToken = false
                if (success) {
                    reconnectAttempts = 0
                    MainActivity.appendLog("🔄 Токен обновлён, переподключаемся")
                    WebSocketManager.getInstance().resetState()
                    connectWebSocket()
                } else {
                    MainActivity.appendLog("❌ Не удалось обновить токен, повтор через 10 сек")
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
        return try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            // Парсим обёртку ApiResponse<AuthResponse>
            val apiResponse = gson.fromJson(body, ApiResponse::class.java) as ApiResponse<AuthResponse>
            if (apiResponse.success && apiResponse.data != null) {
                val auth = apiResponse.data
                if (auth.accessToken != null && auth.refreshToken != null) {
                    tokenManager.saveTokens(auth.accessToken, auth.refreshToken)
                    MainActivity.appendLog("✅ Токены обновлены")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            MainActivity.appendLog("❌ Ошибка обновления токена: ${e.message}")
            false
        }
    }

    data class TokenRefreshResponse(
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("refreshToken") val refreshToken: String?
    )

    // ---------- Планировщики ----------
    private fun scheduleTokenRefresh() {
        tokenRefreshRunnable = Runnable {
            MainActivity.appendLog("⏰ Запланированное обновление токена")
            tryRefreshAndReconnect()
            handler.postDelayed(tokenRefreshRunnable!!, TOKEN_REFRESH_INTERVAL)
        }
        handler.postDelayed(tokenRefreshRunnable!!, TOKEN_REFRESH_INTERVAL)
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            MainActivity.appendLog("⚠️ Превышено число попыток переподключения")
            reconnectAttempts = 0
            return
        }
        reconnectAttempts++
        val delay = 5000L * reconnectAttempts
        reconnectRunnable = Runnable {
            MainActivity.appendLog("🔄 Попытка переподключения #$reconnectAttempts")
            tryRefreshAndReconnect()
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun cancelAllTimers() {
        cancelReconnect()
        tokenRefreshRunnable?.let { handler.removeCallbacks(it) }
        stopHealthCheck()
    }

    // ---------- Вспомогательное ----------
    private fun acquireWakeLock() {
        if (checkSelfPermission(android.Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmsGateway::WakeLock"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
        } else {
            MainActivity.appendLog("⚠️ Нет разрешения WAKE_LOCK")
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                MainActivity.appendLog("⚠️ Оптимизация батареи включена! Отключите вручную.")
            }
        }
    }

    override fun onDestroy() {
        instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        MainActivity.appendLog("Сервис уничтожен")
        unregisterReceiver(tokenUpdateReceiver)   // <-- добавить
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

    override fun onBind(intent: Intent?): IBinder? = null
}