package com.katok.smspush

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

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

    override fun onStart() {
        super.onStart()
        requestPermissions(arrayOf(android.Manifest.permission.SEND_SMS), 100)
    }
}