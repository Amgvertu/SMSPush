package com.katok.smspush

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.channels.ChannelResult.Companion.success


class TokenManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sms_gateway_tokens", Context.MODE_PRIVATE)

    fun saveTokens(accessToken: String, refreshToken: String) {
        val success = prefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .commit()
        Log.d("TokenManager", "saveTokens: success=$success, refresh=$refreshToken")
    }

    fun getAccessToken(): String? = prefs.getString("access_token", null)
    fun getRefreshToken(): String? {
        val token = prefs.getString("refresh_token", null)
        Log.d("TokenManager", "getRefreshToken: $token")
        return token
    }

    fun clearTokens() {
        prefs.edit().clear().apply()
    }

    fun savePendingFcmToken(token: String) {
        prefs.edit().putString("pending_fcm_token", token).apply()
    }

    fun getPendingFcmToken(): String? = prefs.getString("pending_fcm_token", null)

    fun clearPendingFcmToken() {
        prefs.edit().remove("pending_fcm_token").apply()
    }

    fun isTokenExpired(token: String?): Boolean {
        if (token == null) return true
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return true

            val payloadBytes = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or
                        android.util.Base64.NO_WRAP or
                        android.util.Base64.NO_PADDING
            )
            val payload = String(payloadBytes, Charsets.UTF_8)
            val json = org.json.JSONObject(payload)
            val exp = json.getLong("exp") * 1000
            val now = System.currentTimeMillis()
            // Буфер 5 минут, чтобы не обновлять слишком рано
            val expired = now > exp - 5 * 60 * 1000
            Log.d("TokenManager", "isTokenExpired: exp=$exp, now=$now, expired=$expired")
            expired
        } catch (e: Exception) {
            Log.e("TokenManager", "isTokenExpired error: ${e.message}", e)
            true
        }
    }
}