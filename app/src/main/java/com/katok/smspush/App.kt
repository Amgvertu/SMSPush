package com.katok.smspush

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import ru.rustore.sdk.pushclient.RuStorePushClient

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // Инициализация RuStore Push SDK
        try {
            RuStorePushClient.init(
                application = this,
                projectId = "McgN1q5Z1TpHNgUM54Ag-C96s4C8jsEf",
            )
            Log.d("SmsPushApp", "✅ RuStore Push SDK initialized")
        } catch (e: Exception) {
            Log.e("SmsPushApp", "❌ Failed to init RuStore Push SDK", e)
        }
    }
}