package com.katok.smspush

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LOGIN"
        // Креды пользователя SMS_GATEWAY (совпадают с DatabaseInitializer на сервере)
        private const val GATEWAY_PHONE = "+79999999999"
        private const val GATEWAY_PASSWORD = "gateway123"
    }

    private lateinit var etPhone: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar

    private val gson = Gson()
    private val client = OkHttpClient()
    private val BASE_URL = AppConfig.BASE_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tokenManager = TokenManager(this)
        val accessToken = tokenManager.getAccessToken()
        val refreshToken = tokenManager.getRefreshToken()

        // 1. Токены уже есть — тихо уходим в MainActivity
        if (!accessToken.isNullOrEmpty() && !refreshToken.isNullOrEmpty()) {
            Log.d(TAG, "Tokens found, skipping login")
            goToMainAndStartService()
            return
        }

        // 2. Токенов нет — пробуем автологин под SMS_GATEWAY
        Log.d(TAG, "No tokens, performing auto-login")
        performAutoLogin()
    }

    /**
     * Автологин с хардкодными кредами шлюза.
     * Если по какой-то причине не сработает — покажем форму входа,
     * чтобы устройство можно было "спасти" вручную.
     */
    private fun performAutoLogin() {
        val json = """{"phone":"$GATEWAY_PHONE","password":"$GATEWAY_PASSWORD"}"""

        val request = Request.Builder()
            .url("$BASE_URL/api/auth/login")
            .post(RequestBody.create("application/json".toMediaType(), json))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Auto-login network error: ${e.message}")
                runOnUiThread {
                    showLoginForm("Ошибка сети: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                Log.d(TAG, "Auto-login response code: ${response.code}")

                if (!response.isSuccessful) {
                    runOnUiThread {
                        showLoginForm("Автологин не удался (${response.code})")
                    }
                    return
                }

                try {
                    val loginResponse = gson.fromJson(body, LoginResponse::class.java)
                    if (loginResponse.success
                        && loginResponse.data?.accessToken != null
                        && loginResponse.data?.refreshToken != null
                    ) {
                        TokenManager(this@LoginActivity).saveTokens(
                            loginResponse.data.accessToken!!,
                            loginResponse.data.refreshToken!!
                        )
                        Log.d(TAG, "Auto-login successful")
                        runOnUiThread { goToMainAndStartService() }
                    } else {
                        runOnUiThread {
                            showLoginForm(loginResponse.message ?: "Сервер не вернул токены")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-login parse error", e)
                    runOnUiThread { showLoginForm("Ошибка разбора ответа: ${e.message}") }
                }
            }
        })
    }

    /** Запуск сервиса шлюза + переход в MainActivity без анимации. */
    private fun goToMainAndStartService() {
        val startIntent = Intent(this, SmsGatewayService::class.java).apply {
            action = SmsGatewayService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * Показать форму входа (только как fallback при ошибке автологина).
     * До этого момента пользователь не видит никакого UI.
     */
    private fun showLoginForm(errorMessage: String? = null) {
        setContentView(R.layout.activity_login)

        etPhone = findViewById(R.id.etLogin)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        progressBar = findViewById(R.id.progressBar)

        btnLogin.setOnClickListener { performLogin() }

        if (!errorMessage.isNullOrEmpty()) {
            tvError.text = errorMessage
            tvError.visibility = View.VISIBLE
        }
    }

    /**
     * Ручной логин (fallback). Показывается только если автологин упал —
     * например, БД сервера пересоздали и SMS_GATEWAY-юзера нет.
     */
    private fun performLogin() {
        val phone = etPhone.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (phone.isEmpty() || password.isEmpty()) {
            showError("Введите телефон и пароль")
            return
        }

        showLoading(true)
        tvError.visibility = View.GONE

        val json = """{"phone":"$phone","password":"$password"}"""
        val request = Request.Builder()
            .url("$BASE_URL/api/auth/login")
            .post(RequestBody.create("application/json".toMediaType(), json))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    showLoading(false)
                    showError("Ошибка сети: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                runOnUiThread {
                    showLoading(false)
                    if (!response.isSuccessful) {
                        showError("Ошибка входа (код ${response.code}): ${responseBody.take(100)}")
                        return@runOnUiThread
                    }
                    try {
                        val loginResponse = gson.fromJson(responseBody, LoginResponse::class.java)
                        if (loginResponse.success
                            && loginResponse.data?.accessToken != null
                            && loginResponse.data?.refreshToken != null
                        ) {
                            TokenManager(this@LoginActivity).saveTokens(
                                loginResponse.data.accessToken!!,
                                loginResponse.data.refreshToken!!
                            )

                            // Отправить pending FCM-токен, если копился до логина
                            val pendingFcm = TokenManager(this@LoginActivity).getPendingFcmToken()
                            if (pendingFcm != null) {
                                sendPendingFcmToken(pendingFcm)
                            }

                            goToMainAndStartService()
                        } else {
                            showError(loginResponse.message ?: "Сервер не вернул токены")
                        }
                    } catch (e: Exception) {
                        showError("Ошибка разбора ответа: ${e.message}")
                    }
                }
            }
        })
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !show
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun sendPendingFcmToken(fcmToken: String) {
        val accessToken = TokenManager(this).getAccessToken() ?: return
        val json = """{"token":"$fcmToken","platform":"FCM"}"""
        val request = Request.Builder()
            .url("$BASE_URL/api/push/register")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), json))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Не удалось отправить pending FCM: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    TokenManager(this@LoginActivity).clearPendingFcmToken()
                    Log.d(TAG, "Pending FCM-токен отправлен")
                }
            }
        })
    }

    // Структуры ответа сервера
    data class LoginResponse(
        val success: Boolean,
        val message: String?,
        val data: TokenData?
    )

    data class TokenData(
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("refreshToken") val refreshToken: String?
    )
}