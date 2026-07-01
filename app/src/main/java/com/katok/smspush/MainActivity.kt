package com.katok.smspush

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.provider.Settings
import android.net.Uri


class MainActivity : AppCompatActivity() {

    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenManager = TokenManager(this)

        // Если токенов нет – отправляем на экран входа
        if (tokenManager.getAccessToken() == null || tokenManager.getRefreshToken() == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        btnStart.setOnClickListener {
            Intent(this, SmsGatewayService::class.java).also { intent ->
                intent.action = SmsGatewayService.ACTION_START
                startService(intent)
            }
        }

        btnStop.setOnClickListener {
            Intent(this, SmsGatewayService::class.java).also { intent ->
                intent.action = SmsGatewayService.ACTION_STOP
                startService(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Отключите оптимизацию батареи")
                    .setMessage("Для надёжной работы шлюза и мгновенного получения SMS, " +
                            "пожалуйста, разрешите приложению работать в фоне без ограничений.")
                    .setPositiveButton("Перейти к настройкам") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requestPermissions(arrayOf(android.Manifest.permission.SEND_SMS), 100)
    }
}