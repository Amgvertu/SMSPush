package com.katok.smspush

data class ApiResponse<T>(
    val success: Boolean,
    val message: String?,
    val data: T?
)