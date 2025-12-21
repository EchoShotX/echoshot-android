package com.echoshot.app.network

import com.echoshot.app.auth.models.ApiResponseDto
import com.echoshot.app.auth.models.AuthExchangeRequest
import com.echoshot.app.auth.models.AuthExchangeResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApiService {
    @POST("/auth/exchange")
    suspend fun exchangeCode(
        @Body request: AuthExchangeRequest
    ): ApiResponseDto<AuthExchangeResponse>
}

