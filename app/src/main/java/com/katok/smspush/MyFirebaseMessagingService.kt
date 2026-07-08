package com.katok.smspush

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCM"
        // Используем тот же адрес сервера, что и в AppConfig
        private val BASE_URL = AppConfig.BASE_URL
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        MainActivity.appendLog("🔥 Получен новый FCM-токен: $token")
        sendTokenToServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Получено FCM-сообщение")
        MainActivity.appendLog("📨 FCM сообщение: ${remoteMessage.data}")

        val data = remoteMessage.data
        val type = data["type"]

        if (type == "WAKE_UP") {
            MainActivity.appendLog("⏰ Получен WAKE_UP – пробуждаем шлюз")
            // Запускаем сервис, если он не активен
            val intent = Intent(this, SmsGatewayService::class.java).apply {
                action = SmsGatewayService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // Даём сервису время инициализироваться (0.5 секунды)
            handler.postDelayed({
                val service = SmsGatewayService.getInstance()
                if (service != null) {
                    MainActivity.appendLog("🔄 Переподключаем WebSocket")
                    service.reconnectWebSocket()
                } else {
                    // Если сервис ещё не создан – пробуем через 1 секунду
                    MainActivity.appendLog("⚠️ Сервис ещё не готов, повтор через 1с")
                    handler.postDelayed({
                        SmsGatewayService.getInstance()?.reconnectWebSocket()
                    }, 1000)
                }
            }, 500)
        }
    }

    private fun sendTokenToServer(token: String) {
        val tokenManager = TokenManager(this)
        var accessToken = tokenManager.getAccessToken()
        if (accessToken == null) {
            MainActivity.appendLog("❌ Нет access-токена, нельзя отправить FCM-токен")
            return
        }

        var retries = 2
        while (retries > 0) {
            val request = Request.Builder()
                .url("$BASE_URL/api/fcm/register")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(
                    """{"token":"$token"}""".toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )
                .build()

            try {
                val response = OkHttpClient().newCall(request).execute()
                if (response.isSuccessful) {
                    MainActivity.appendLog("✅ FCM-токен успешно отправлен на сервер")
                    return
                } else if (response.code == 401 && retries > 1) {
                    MainActivity.appendLog("🔄 Access-токен истёк, обновляем...")
                    if (refreshAccessToken()) {
                        accessToken = tokenManager.getAccessToken()
                        retries--
                        continue
                    } else {
                        MainActivity.appendLog("❌ Не удалось обновить токен")
                        return
                    }
                } else {
                    MainActivity.appendLog("❌ Ошибка отправки FCM-токена: ${response.code}")
                    return
                }
            } catch (e: Exception) {
                MainActivity.appendLog("❌ Ошибка сети при отправке FCM: ${e.message}")
                return
            }
        }
    }

    private fun refreshAccessToken(): Boolean {
        val tokenManager = TokenManager(this)
        val refreshToken = tokenManager.getRefreshToken() ?: return false
        val json = """{"refreshToken":"$refreshToken"}"""
        val request = Request.Builder()
            .url("$BASE_URL/api/auth/refresh")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            val response = OkHttpClient().newCall(request).execute()
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            val type = object : TypeToken<ApiResponse<AuthResponse>>() {}.type
            val apiResponse: ApiResponse<AuthResponse> = Gson().fromJson(body, type)
            if (apiResponse.success && apiResponse.data != null) {
                val auth = apiResponse.data
                if (auth.accessToken != null && auth.refreshToken != null) {
                    tokenManager.saveTokens(auth.accessToken, auth.refreshToken)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления токена", e)
            false
        }
    }
}