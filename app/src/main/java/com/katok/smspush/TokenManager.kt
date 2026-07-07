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

    fun isTokenExpired(token: String?): Boolean {
        if (token == null) return true
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return true
            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
            val json = org.json.JSONObject(payload)
            val exp = json.getLong("exp") * 1000 // в миллисекунды
            val now = System.currentTimeMillis()
            // Добавляем буфер 5 минут, чтобы не обновлять слишком рано
            now > exp - 5 * 60 * 1000
        } catch (e: Exception) {
            true // при ошибке считаем истёкшим
        }
    }
}