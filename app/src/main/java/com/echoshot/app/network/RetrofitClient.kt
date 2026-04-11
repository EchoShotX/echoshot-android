package com.echoshot.app.network

import com.echoshot.app.auth.AuthInterceptor
import com.echoshot.app.auth.TokenManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources

object RetrofitClient {
    const val BASE_URL = "http://ec2-3-35-23-240.ap-northeast-2.compute.amazonaws.com"
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    // 인증이 필요 없는 API용 클라이언트 (로그인, 코드 교환 등)
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl("$BASE_URL/")  // Retrofit baseUrl은 끝에 슬래시 필요
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    // 인증이 필요 없는 API (로그인, 코드 교환)
    val authApi: AuthApiService = retrofit.create(AuthApiService::class.java)
    
    /**
     * 인증이 필요한 API 호출을 위한 Retrofit 클라이언트 생성
     * Authorization 헤더에 액세스 토큰을 자동으로 추가합니다.
     */
    fun createAuthenticatedClient(tokenManager: TokenManager): Retrofit {
        val authInterceptor = AuthInterceptor(tokenManager)
        
        val authenticatedOkHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(authInterceptor)  // 인증 인터셉터 추가
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        
        return Retrofit.Builder()
            .baseUrl("$BASE_URL/")
            .client(authenticatedOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * 인증된 OkHttpClient를 사용하는 EventSource.Factory를 반환합니다.
     * SSE는 gzip 압축과 호환되지 않을 수 있어서 압축 비활성화
     */
    fun createEventSourceFactory(tokenManager: TokenManager): EventSource.Factory {
        val authInterceptor = AuthInterceptor(tokenManager)
        
        // SSE 전용 로깅 (헤더만, 바디 제외)
        val sseLoggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        
        // SSE에서 gzip 압축 비활성화
        val noGzipInterceptor = okhttp3.Interceptor { chain ->
            val originalRequest = chain.request()
            val requestWithNoGzip = originalRequest.newBuilder()
                .header("Accept-Encoding", "identity")  // gzip 대신 압축 안 함
                .build()
            chain.proceed(requestWithNoGzip)
        }

        val authenticatedOkHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(noGzipInterceptor)  // gzip 비활성화
            .addNetworkInterceptor(sseLoggingInterceptor)  // Network 레벨 로깅
            .connectTimeout(0, TimeUnit.SECONDS) // SSE는 무제한 타임아웃 권장
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()

        return EventSources.createFactory(authenticatedOkHttpClient)
    }
}

