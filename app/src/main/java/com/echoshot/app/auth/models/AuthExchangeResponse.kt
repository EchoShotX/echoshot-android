package com.echoshot.app.auth.models

data class AuthExchangeResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int  // 초 단위 (기본값: 1800 = 30분)
)

