package com.katok.smspush

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.rustore.sdk.pushclient.messaging.model.RemoteMessage
import ru.rustore.sdk.pushclient.messaging.service.RuStoreMessagingService

/**
 * Наследник RuStoreMessagingService из SDK.
 * Назван иначе, чтобы не конфликтовать с именем базового класса.
 */
class KatokRuStoreMessagingService : RuStoreMessagingService() {

    companion object {
        private const val TAG = "RuStorePush"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "🔥 New RuStore token: ${token.take(20)}...")
        MainActivity.appendLog("🔥 RuStore-токен: ${token.take(20)}...")
        sendTokenToServer(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        Log.d(TAG, "📩 RuStore message: $data")
        MainActivity.appendLog("📨 RuStore сообщение: $data")

        val type = data["type"]
        if (type == "WAKE_UP") {
            MainActivity.appendLog("⏰ RuStore WAKE_UP — пробуждаем шлюз")

            val intent = Intent(this, SmsGatewayService::class.java).apply {
                action = SmsGatewayService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            handler.postDelayed({
                val service = SmsGatewayService.getInstance()
                if (service != null) {
                    MainActivity.appendLog("🔄 Переподключаем WebSocket")
                    service.reconnectWebSocket()
                } else {
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
            MainActivity.appendLog("❌ Нет access-токена, сохраняем RuStore-токен до логина")
            tokenManager.savePendingFcmToken(token) // используем тот же key, что и для FCM
            return
        }

        var retries = 2
        while (retries > 0) {
            val request = okhttp3.Request.Builder()
                .url("${AppConfig.BASE_URL}/api/push/register")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(
                    """{"token":"$token","platform":"RUSTORE"}"""
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()

            try {
                val response = okhttp3.OkHttpClient().newCall(request).execute()
                if (response.isSuccessful) {
                    MainActivity.appendLog("✅ RuStore-токен отправлен на сервер")
                    return
                } else if (response.code == 401 && retries > 1) {
                    MainActivity.appendLog("🔄 Access-токен истёк, обновляем...")
                    if (refreshAccessToken(tokenManager)) {
                        accessToken = tokenManager.getAccessToken()
                        retries--
                        continue
                    } else {
                        return
                    }
                } else {
                    MainActivity.appendLog("❌ Ошибка отправки RuStore-токена: ${response.code}")
                    return
                }
            } catch (e: Exception) {
                MainActivity.appendLog("❌ Сеть: ${e.message}")
                return
            }
        }
    }

    private fun refreshAccessToken(tokenManager: TokenManager): Boolean {
        val refreshToken = tokenManager.getRefreshToken() ?: return false
        val json = """{"refreshToken":"$refreshToken"}"""
        val request = okhttp3.Request.Builder()
            .url("${AppConfig.BASE_URL}/api/auth/refresh")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            val type = object : com.google.gson.reflect.TypeToken<ApiResponse<AuthResponse>>() {}.type
            val apiResponse: ApiResponse<AuthResponse> =
                com.google.gson.Gson().fromJson(body, type)
            if (apiResponse.success && apiResponse.data != null) {
                val auth = apiResponse.data
                if (auth.accessToken.isNotEmpty() && auth.refreshToken.isNotEmpty()) {
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