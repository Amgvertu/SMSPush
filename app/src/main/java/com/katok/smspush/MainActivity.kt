package com.katok.smspush

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tokenManager: TokenManager
    private lateinit var tvLogs: TextView
    private lateinit var scrollView: ScrollView
    private val logHandler = Handler(Looper.getMainLooper())

    companion object {
        private var logText = ""
        private var activityRef: MainActivity? = null

        fun appendLog(message: String) {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logText = "$logText\n$timestamp $message"
            // Обновляем UI из любого потока
            activityRef?.runOnUiThread {
                activityRef?.updateLogs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        activityRef = this

        tokenManager = TokenManager(this)

        // Если токенов нет – отправляем на экран входа
        if (tokenManager.getAccessToken() == null || tokenManager.getRefreshToken() == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        tvLogs = findViewById(R.id.tvLogs)
        scrollView = findViewById(R.id.scrollView)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnClear = findViewById<Button>(R.id.btnClearLogs)

        btnStart.setOnClickListener {
            appendLog("Нажата кнопка 'Запустить шлюз'")
            Intent(this, SmsGatewayService::class.java).also { intent ->
                intent.action = SmsGatewayService.ACTION_START
                startService(intent)
            }
        }

        btnStop.setOnClickListener {
            appendLog("Нажата кнопка 'Остановить шлюз'")
            Intent(this, SmsGatewayService::class.java).also { intent ->
                intent.action = SmsGatewayService.ACTION_STOP
                startService(intent)
            }
        }

        btnClear.setOnClickListener {
            logText = ""
            tvLogs.text = "Логи очищены"
        }

        // Начальные логи
        appendLog("Приложение запущено")
        appendLog("Токен: ${tokenManager.getAccessToken()?.take(20)}...")


        // Запрос на отключение оптимизации батареи
        requestBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        activityRef = this
        updateLogs()
        //requestBatteryOptimization()
    }

    override fun onDestroy() {
        activityRef = null
        super.onDestroy()
    }

    private fun updateLogs() {
        tvLogs.text = logText
        // Прокрутка вниз
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun requestBatteryOptimization() {
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