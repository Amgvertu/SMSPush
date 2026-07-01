package com.katok.smspush

data class SmsCommand(
    val requestId: String,
    val phone: String,
    val code: String,
    val purpose: String
)

data class SmsResponse(
    val requestId: String,
    val success: Boolean,
    val errorMessage: String? = null
)