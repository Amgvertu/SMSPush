package com.katok.smspush

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCM"
        //const val BASE_URL = "http://192.168.0.119:8081"

        const val BASE_URL = "https://varamy.online"
    }

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
            SmsGatewayService.getInstance()?.reconnectWebSocket()
        }
    }

    private fun sendTokenToServer(token: String) {
        // Берем access-токен из SharedPreferences
        val tokenManager = TokenManager(this)
        val accessToken = tokenManager.getAccessToken()
        if (accessToken == null) {
            Log.d(TAG, "No access token, cannot send FCM token")
            return
        }

        val json = """{"token":"$token"}"""
        val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(BASE_URL + "/api/fcm/register")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(requestBody)
            .build()

        val client = OkHttpClient()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "Failed to send FCM token", e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "FCM token sent to server")
                } else {
                    Log.e(TAG, "Server responded: ${response.code}")
                }
            }
        })
    }
}