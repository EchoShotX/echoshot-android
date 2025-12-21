package com.echoshot.app.network

import com.echoshot.app.auth.models.ApiResponseDto
import com.echoshot.app.auth.models.UserProfile
import retrofit2.http.GET

interface UserApiService {
    /**
     * 현재 로그인한 사용자 정보를 조회합니다.
     * Authorization 헤더에 액세스 토큰이 필요합니다.
     */
    @GET("/member/me")
    suspend fun getUserProfile(): ApiResponseDto<UserProfile>
}

