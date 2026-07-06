package com.katok.smspush

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCM"
        const val BASE_URL = "https://varamy.online"  // или ваш URL
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        MainActivity.appendLog("🔥 Новый FCM-токен: ${token.take(20)}...")
        sendTokenToServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received")

        val data = remoteMessage.data
        val type = data["type"]

        if (type == "WAKE_UP") {
            Log.d(TAG, "WAKE_UP received, reconnecting WebSocket")
            val intent = Intent(this, SmsGatewayService::class.java).apply {
                action = SmsGatewayService.ACTION_START
            }
            startService(intent)
            // Даём сервису время инициализироваться
            handler.postDelayed({
                SmsGatewayService.getInstance()?.reconnectWebSocket()
            }, 500)
        }
    }

    /**
     * Отправляет FCM-токен на сервер. При получении 401 обновляет access-токен и повторяет.
     */
    private fun sendTokenToServer(token: String) {
        val tokenManager = TokenManager(this)
        var accessToken = tokenManager.getAccessToken()
        if (accessToken == null) {
            Log.d(TAG, "No access token, cannot send FCM token")
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
                    Log.d(TAG, "FCM token sent to server")
                    return
                } else if (response.code == 401 && retries > 1) {
                    // Токен истёк – обновляем и повторяем
                    Log.d(TAG, "Access token expired, refreshing...")
                    if (refreshAccessToken()) {
                        accessToken = tokenManager.getAccessToken()
                        retries--
                        continue
                    } else {
                        Log.e(TAG, "Failed to refresh token")
                        return
                    }
                } else {
                    Log.e(TAG, "Server responded: ${response.code}")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send FCM token", e)
                return
            }
        }
    }

    /**
     * Обновляет пару токенов, используя refresh-токен.
     */
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
            val data = Gson().fromJson(body, TokenRefreshResponse::class.java)
            if (data.accessToken != null && data.refreshToken != null) {
                tokenManager.saveTokens(data.accessToken!!, data.refreshToken!!)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
            false
        }
    }

    data class TokenRefreshResponse(
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("refreshToken") val refreshToken: String?
    )
}