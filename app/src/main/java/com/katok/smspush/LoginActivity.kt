package com.katok.smspush

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        // Параметры автологина
        private const val MAX_AUTO_LOGIN_ATTEMPTS = 5         // сколько раз пробовать
        private const val AUTO_LOGIN_RETRY_DELAY_MS = 3000L   // пауза между попытками
    }

    private lateinit var etPhone: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar

    private val gson = Gson()
    private val client = OkHttpClient()
    private val BASE_URL = AppConfig.BASE_URL
    private val uiHandler = Handler(Looper.getMainLooper())

    private var autoLoginAttempt = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tokenManager = TokenManager(this)
        val accessToken = tokenManager.getAccessToken()
        val refreshToken = tokenManager.getRefreshToken()

// 1. Токены уже есть и access ещё живой — тихо уходим в MainActivity
        if (!accessToken.isNullOrEmpty()
            && !refreshToken.isNullOrEmpty()
            && !tokenManager.isTokenExpired(accessToken)) {
            Log.d(TAG, "Tokens found and valid, skipping login")
            goToMainAndStartService()
            return
        }

// 2. Токенов нет или access просрочен — пробуем автологин
        Log.d(TAG, "No tokens or access expired, performing auto-login")
        performAutoLogin()
    }

    /**
     * Автологин с хардкодными кредами шлюза.
     * До MAX_AUTO_LOGIN_ATTEMPTS попыток с паузой AUTO_LOGIN_RETRY_DELAY_MS,
     * чтобы пережить момент, когда Wi-Fi ещё не поднялся при старте приложения.
     */
    private fun performAutoLogin() {
        if (isFinishing || isDestroyed) return

        autoLoginAttempt++
        Log.d(TAG, "Auto-login attempt #$autoLoginAttempt")

        val json = """{"phone":"${GatewayCredentials.PHONE}","password":"${GatewayCredentials.PASSWORD}"}"""

        val request = Request.Builder()
            .url("$BASE_URL/api/auth/login")
            .post(RequestBody.create("application/json".toMediaType(), json))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Auto-login network error (attempt #$autoLoginAttempt): ${e.message}")
                scheduleAutoLoginRetry("Ошибка сети: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                Log.d(TAG, "Auto-login response code (attempt #$autoLoginAttempt): ${response.code}")

                if (!response.isSuccessful) {
                    // 5xx и 408/429 — сервер/сеть «приходят в себя», ретраим.
                    // 400/401/403 — неверные креды, ретраить бессмысленно.
                    if (response.code >= 500 || response.code == 408 || response.code == 429) {
                        scheduleAutoLoginRetry("Сервер недоступен (${response.code})")
                    } else {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                showLoginForm("Автологин не удался (${response.code})")
                            }
                        }
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
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                goToMainAndStartService()
                            }
                        }
                    } else {
                        // Сервер ответил успешно, но без токенов — покажем форму.
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                showLoginForm(loginResponse.message ?: "Сервер не вернул токены")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-login parse error", e)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            showLoginForm("Ошибка разбора ответа: ${e.message}")
                        }
                    }
                }
            }
        })
    }

    private fun scheduleAutoLoginRetry(lastErrorMessage: String) {
        if (isFinishing || isDestroyed) return

        if (autoLoginAttempt >= MAX_AUTO_LOGIN_ATTEMPTS) {
            Log.w(TAG, "Auto-login failed after $MAX_AUTO_LOGIN_ATTEMPTS attempts: $lastErrorMessage")
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    showLoginForm(lastErrorMessage)
                }
            }
            return
        }

        Log.d(TAG, "Scheduling auto-login retry in ${AUTO_LOGIN_RETRY_DELAY_MS}ms")
        uiHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                performAutoLogin()
            }
        }, AUTO_LOGIN_RETRY_DELAY_MS)
    }

    /** Запуск сервиса шлюза + переход в MainActivity. */
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

    /** Показать форму входа (fallback, если автологин не сработал). */
    private fun showLoginForm(errorMessage: String? = null) {
        // setContentView мог уже вызываться — не страшно, повторный вызов перезапишет
        setContentView(R.layout.activity_login)

        etPhone = findViewById(R.id.etLogin)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        progressBar = findViewById(R.id.progressBar)

        // Заранее заполняем поля, чтобы оператору не пришлось вводить руками
        etPhone.setText(GatewayCredentials.PHONE)
        etPassword.setText(GatewayCredentials.PASSWORD)

        btnLogin.setOnClickListener { performLogin() }

        if (!errorMessage.isNullOrEmpty()) {
            tvError.text = errorMessage
            tvError.visibility = View.VISIBLE
        }
    }

    /** Ручной логин (fallback). */
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
                    if (!isFinishing && !isDestroyed) {
                        showLoading(false)
                        showError("Ошибка сети: ${e.message}")
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
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

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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