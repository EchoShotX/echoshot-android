package com.echoshot.app.auth

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * API 요청 시 Authorization 헤더에 액세스 토큰을 자동으로 추가하는 인터셉터
 */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    
    companion object {
        private const val TAG = "AuthInterceptor"
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val accessToken = tokenManager.getAccessToken()
        
        val authenticatedRequest = if (accessToken != null) {
            Log.d(TAG, "Authorization 헤더 추가 - 토큰 앞 20자: ${accessToken.take(20)}...")
            request.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            Log.d(TAG, "토큰이 없어 Authorization 헤더를 추가하지 않음")
            request
        }
        
        return chain.proceed(authenticatedRequest)
    }
}

