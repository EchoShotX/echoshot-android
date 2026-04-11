package com.echoshot.app.network

import com.echoshot.app.auth.models.ApiResponseDto
import com.echoshot.app.auth.models.AuthExchangeRequest
import com.echoshot.app.auth.models.AuthExchangeResponse
import com.echoshot.app.auth.models.LogoutRequest
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApiService {
    @POST("/auth/exchange")
    suspend fun exchangeCode(
        @Body request: AuthExchangeRequest
    ): ApiResponseDto<AuthExchangeResponse>

    @POST("/auth/logout")
    suspend fun logout(
        @Body request: LogoutRequest
    ): ApiResponseDto<String>
}

