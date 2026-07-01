package com.katok.smspush

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tokenManager: TokenManager
    private lateinit var tvLogs: TextView

    companion object {
        private var logText = ""
        private var mainActivity: MainActivity? = null

        fun appendLog(message: String) {
            logText += "${android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())} $message\n"
            mainActivity?.runOnUiThread {
                mainActivity?.tvLogs?.text = logText
                // Прокручиваем вниз
                val scrollView = mainActivity?.tvLogs?.parent as? android.widget.ScrollView
                scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainActivity = this
        tokenManager = TokenManager(this)
        tvLogs = findViewById(R.id.tvLogs)

        // Если токенов нет – отправляем на экран входа
        if (tokenManager.getAccessToken() == null || tokenManager.getRefreshToken() == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        btnStart.setOnClickListener {
            appendLog("▶️ Запуск шлюза...")
            Intent(this, SmsGatewayService::class.java).also { intent ->
                intent.action = SmsGatewayService.ACTION_START
                startService(intent)
            }
        }

        btnStop.setOnClickListener {
            appendLog("⏹️ Остановка шлюза...")
            Intent(this, SmsGatewayService::class.java).also { intent ->
                intent.action = SmsGatewayService.ACTION_STOP
                startService(intent)
            }
        }

        // Отображаем сохранённые логи
        tvLogs.text = logText
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

    override fun onDestroy() {
        super.onDestroy()
        mainActivity = null
    }
}