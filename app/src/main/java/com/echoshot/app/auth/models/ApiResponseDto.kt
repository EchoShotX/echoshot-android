package com.echoshot.app.auth.models

data class ApiResponseDto<T>(
    val isSuccess: Boolean,
    val code: Int,
    val message: String,
    val result: T?
)

