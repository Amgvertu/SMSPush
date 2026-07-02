package com.katok.smspush

import android.content.Intent
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

    private lateinit var etPhone: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar

    private val gson = Gson()
    private val client = OkHttpClient()

    // Замените на актуальный адрес, если нужно
    //private val BASE_URL = "http://192.168.0.119:8081"
    private val BASE_URL = "http" + AppConfig.API_BASE_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etPhone = findViewById(R.id.etLogin)   // не меняем id в макете
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        progressBar = findViewById(R.id.progressBar)

        btnLogin.setOnClickListener { performLogin() }
    }

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
        Log.d("LOGIN", "Request body: $json")

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
                Log.d("LOGIN", "Response code: ${response.code}, body: $responseBody")

                runOnUiThread {
                    showLoading(false)
                    if (!response.isSuccessful) {
                        showError("Ошибка входа (код ${response.code}): ${responseBody.take(100)}")
                        return@runOnUiThread
                    }
                    try {
                        val loginResponse = gson.fromJson(responseBody, LoginResponse::class.java)
                        if (loginResponse.success &&
                            loginResponse.data?.accessToken != null &&
                            loginResponse.data?.refreshToken != null) {
                            TokenManager(this@LoginActivity).saveTokens(
                                loginResponse.data.accessToken!!,
                                loginResponse.data.refreshToken!!
                            )
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
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