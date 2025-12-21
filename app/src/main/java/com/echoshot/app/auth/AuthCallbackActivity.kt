package com.echoshot.app.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.echoshot.app.CameraActivity
import com.echoshot.app.auth.models.ApiResponseDto
import com.echoshot.app.auth.models.AuthExchangeResponse
import com.google.gson.Gson
import kotlinx.coroutines.launch
import retrofit2.HttpException

class AuthCallbackActivity : AppCompatActivity() {
    
    private lateinit var authRepository: AuthRepository
    private lateinit var tokenManager: TokenManager
    
    companion object {
        private const val TAG = "AuthCallbackActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        authRepository = AuthRepository(this)
        tokenManager = TokenManager(this)
        
        handleDeepLink(intent)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }
    
    private fun handleDeepLink(intent: Intent) {
        val uri: Uri? = intent.data
        
        if (uri != null && uri.scheme == "echoshotx" && uri.host == "auth") {
            val code = uri.getQueryParameter("code")
            
            if (code != null) {
                exchangeCode(code)
            } else {
                handleError("인증 코드가 없습니다.")
            }
        } else {
            handleError("잘못된 딥링크입니다.")
        }
    }
    
    private fun exchangeCode(code: String) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "=== 인증 코드 교환 시작 ===")
                Log.d(TAG, "인증 코드: $code")
                Log.d(TAG, "인증 코드 길이: ${code.length}")
                
                val response = authRepository.exchangeCode(code)
                
                Log.d(TAG, "=== 서버 응답 수신 ===")
                
                Log.d(TAG, "서버 응답 - isSuccess: ${response.isSuccess}, code: ${response.code}, message: ${response.message}")
                
                if (response.isSuccess && response.result != null) {
                    val tokens = response.result!!
                    Log.d(TAG, "토큰 수신 성공 - accessToken 길이: ${tokens.accessToken.length}, refreshToken 길이: ${tokens.refreshToken.length}, expiresIn: ${tokens.expiresIn}")
                    
                    tokenManager.saveTokens(
                        tokens.accessToken,
                        tokens.refreshToken
                    )
                    
                    // 토큰 저장 확인
                    val savedAccessToken = tokenManager.getAccessToken()
                    val savedRefreshToken = tokenManager.getRefreshToken()
                    Log.d(TAG, "토큰 저장 확인 - accessToken 저장됨: ${savedAccessToken != null}, refreshToken 저장됨: ${savedRefreshToken != null}")
                    if (savedAccessToken != null) {
                        Log.d(TAG, "저장된 accessToken 앞 20자: ${savedAccessToken.take(20)}...")
                    }
                    
                    // 로그인 성공 메시지 표시
                    Toast.makeText(this@AuthCallbackActivity, "로그인 성공", Toast.LENGTH_SHORT).show()
                    
                    // 로그인 성공 처리 - 메인 화면으로 이동
                    navigateToMain()
                } else {
                    // 서버 에러 응답 처리
                    Log.e(TAG, "토큰 교환 실패 - code: ${response.code}, message: ${response.message}")
                    val errorMessage = when (response.code) {
                        4058 -> "유효하지 않은 인증 코드입니다."
                        4059 -> "인증 코드가 만료되었습니다."
                        4060 -> "이미 사용된 인증 코드입니다."
                        4100 -> "찾을 수 없는 유저 정보입니다."
                        else -> response.message ?: "인증 실패"
                    }
                    handleError(errorMessage)
                }
            } catch (e: HttpException) {
                // HTTP 에러 응답 처리 (404 등)
                Log.e(TAG, "HTTP 에러 발생 - code: ${e.code()}")
                try {
                    val errorBody = e.response()?.errorBody()?.string()
                    Log.d(TAG, "에러 응답 본문: $errorBody")
                    
                    if (errorBody != null) {
                        val gson = Gson()
                        val errorResponse = gson.fromJson(errorBody, ApiResponseDto::class.java)
                        Log.e(TAG, "파싱된 에러 응답 - code: ${errorResponse.code}, message: ${errorResponse.message}")
                        
                        val errorMessage = when (errorResponse.code) {
                            4058 -> "유효하지 않은 인증 코드입니다."
                            4059 -> "인증 코드가 만료되었습니다."
                            4060 -> "이미 사용된 인증 코드입니다."
                            4100 -> "찾을 수 없는 유저 정보입니다."
                            else -> errorResponse.message ?: "인증 실패"
                        }
                        handleError(errorMessage)
                    } else {
                        handleError("서버 오류가 발생했습니다: ${e.code()}")
                    }
                } catch (parseException: Exception) {
                    Log.e(TAG, "에러 응답 파싱 실패", parseException)
                    handleError("서버 응답을 처리할 수 없습니다: ${e.message}")
                }
            } catch (e: Exception) {
                // 네트워크 에러 등 예외 처리
                Log.e(TAG, "토큰 교환 중 예외 발생", e)
                handleError("네트워크 오류가 발생했습니다: ${e.message}")
            }
        }
    }
    
    private fun navigateToMain() {
        val intent = Intent(this, CameraActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
    
    private fun handleError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        // 에러 발생 시 메인 화면으로 이동
        navigateToMain()
    }
}

