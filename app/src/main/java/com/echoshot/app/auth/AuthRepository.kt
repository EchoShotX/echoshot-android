package com.echoshot.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.echoshot.app.auth.models.ApiResponseDto
import com.echoshot.app.auth.models.AuthExchangeRequest
import com.echoshot.app.auth.models.AuthExchangeResponse
import com.echoshot.app.network.RetrofitClient

class AuthRepository(private val context: Context) {
    private val authApi = RetrofitClient.authApi
    
    companion object {
        private const val TAG = "AuthRepository"
        private val BASE_URL = RetrofitClient.BASE_URL
        private val GOOGLE_OAUTH_URL = "$BASE_URL/oauth2/authorization/google"
    }
    
    /**
     * 소셜 로그인을 시작합니다.
     * Custom Tab을 통해 OAuth2 인증 URL을 엽니다.
     * Custom Tab이 지원되지 않는 경우 기본 브라우저로 폴백합니다.
     */
    fun startGoogleLogin() {
        val url = GOOGLE_OAUTH_URL
        Log.d(TAG, "Starting Google login with URL: $url")
        
        // URL 유효성 검사
        if (url.isBlank()) {
            Log.e(TAG, "Google OAuth URL is blank")
            return
        }
        
        val uri = Uri.parse(url)
        if (uri == null || uri.scheme == null || uri.host == null) {
            Log.e(TAG, "Invalid URI: $url")
            return
        }
        
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            
            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Custom Tab", e)
            // Custom Tab이 실패한 경우 기본 브라우저로 폴백
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to launch browser", e2)
            }
        }
    }
    
    /**
     * 인증 코드를 JWT 토큰으로 교환합니다.
     */
    suspend fun exchangeCode(code: String): ApiResponseDto<AuthExchangeResponse> {
        val request = AuthExchangeRequest(code)
        val endpoint = "/auth/exchange"
        val fullUrl = "$BASE_URL$endpoint"
        
        Log.d(TAG, "=== API 요청 정보 ===")
        Log.d(TAG, "URL: $fullUrl")
        Log.d(TAG, "Method: POST")
        Log.d(TAG, "Endpoint: $endpoint")
        Log.d(TAG, "Base URL: $BASE_URL")
        Log.d(TAG, "Request Body: {\"code\":\"$code\"}")
        Log.d(TAG, "Content-Type: application/json; charset=UTF-8")
        Log.d(TAG, "====================")
        
        return authApi.exchangeCode(request)
    }
}

