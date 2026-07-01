package com.katok.smspush

import android.content.Intent
import android.os.Bundle
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

    private lateinit var etLogin: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar

    private val gson = Gson()
    private val client = OkHttpClient()

    // Замените на актуальный адрес, если нужно
    private val BASE_URL = "http://192.168.0.119:8081"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etLogin = findViewById(R.id.etLogin)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        progressBar = findViewById(R.id.progressBar)

        btnLogin.setOnClickListener { performLogin() }
    }

    private fun performLogin() {
        val login = etLogin.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (login.isEmpty() || password.isEmpty()) {
            showError("Введите логин и пароль")
            return
        }

        showLoading(true)
        tvError.visibility = View.GONE

        val json = """{"login":"$login","password":"$password"}"""
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
                runOnUiThread {
                    showLoading(false)
                    if (!response.isSuccessful) {
                        showError("Ошибка входа (код ${response.code})")
                        return@runOnUiThread
                    }
                    val body = response.body?.string()
                    if (body == null) {
                        showError("Пустой ответ сервера")
                        return@runOnUiThread
                    }
                    try {
                        val tokenResponse = gson.fromJson(body, TokenResponse::class.java)
                        if (tokenResponse.accessToken != null && tokenResponse.refreshToken != null) {
                            // Сохраняем токены
                            TokenManager(this@LoginActivity).saveTokens(
                                tokenResponse.accessToken!!,
                                tokenResponse.refreshToken!!
                            )
                            // Переходим на главный экран
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        } else {
                            showError("Сервер не вернул токены")
                        }
                    } catch (e: Exception) {
                        showError("Ошибка разбора ответа")
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

    // Структура ответа от /api/auth/login (может отличаться – подгоните под ваш сервер)
    data class TokenResponse(
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("refreshToken") val refreshToken: String?
    )
}