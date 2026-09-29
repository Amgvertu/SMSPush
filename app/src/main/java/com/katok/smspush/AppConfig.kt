package com.katok.smspush

import android.content.Context

/**
 * Единый источник адреса бэкенда.
 * Адрес хранится в SharedPreferences и переключается из MainActivity.
 */
object AppConfig {
    // Глобальный адрес (через интернет)
    const val GLOBAL_URL = "https://varamy.online"
    // Локальный адрес (в одной сети с бэкендом)
    const val LOCAL_URL = "http://192.168.0.119:8081"

    private const val PREFS_NAME = "app_config"
    private const val KEY_SERVER = "server_url"

    /** Текущий baseUrl. По умолчанию — глобальный. */
    fun getBaseUrl(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER, GLOBAL_URL) ?: GLOBAL_URL
    }

    /** Сохранить выбранный адрес. */
    fun setBaseUrl(context: Context, url: String) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER, url).apply()
    }

    /** Адрес WebSocket (ws:// или wss://). */
    fun getWsUrl(context: Context): String =
        getBaseUrl(context).replace("http", "ws") + "/ws"

    fun getRefreshUrl(context: Context): String =
        getBaseUrl(context) + "/api/auth/refresh"

    fun getLoginUrl(context: Context): String =
        getBaseUrl(context) + "/api/auth/login"

    fun getPushRegisterUrl(context: Context): String =
        getBaseUrl(context) + "/api/push/register"
}
