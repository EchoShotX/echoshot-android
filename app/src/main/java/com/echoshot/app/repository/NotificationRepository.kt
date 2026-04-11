package com.echoshot.app.repository

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.echoshot.app.auth.TokenManager
import com.echoshot.app.network.RetrofitClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

object NotificationRepository {
    private const val TAG = "NotificationRepository"
    private var eventSource: EventSource? = null

    // 알림 이벤트 Flow - replay=1로 마지막 이벤트를 유지
    private val _notificationFlow = MutableSharedFlow<String>(
        replay = 1,  // 마지막 1개 이벤트를 새 구독자에게 전달
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val notificationFlow = _notificationFlow.asSharedFlow()

    // 읽지 않은 알림 존재 여부 (간단한 상태 관리)
    @Volatile
    var hasUnreadNotification: Boolean = false
        private set
    
    fun clearUnreadFlag() {
        hasUnreadNotification = false
    }
    
    // onOpen 콜백이 호출됐는지 여부 (실제 연결 확인)
    @Volatile
    var isActuallyConnected: Boolean = false
        private set
    
    fun isConnected(): Boolean = eventSource != null
    fun isConnectionEstablished(): Boolean = isActuallyConnected

    fun connect(tokenManager: TokenManager) {
        if (eventSource != null) {
            Log.d(TAG, "SSE is already connected or connecting. Skipping.")
            return
        }

        val accessToken = tokenManager.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot connect to SSE: Access token is missing.")
            return
        }

        Log.d(TAG, "Starting SSE connection...")

        val factory = RetrofitClient.createEventSourceFactory(tokenManager)
        val request = Request.Builder()
            .url("${RetrofitClient.BASE_URL}/notifications/subscribe")
            .header("Accept", "text/event-stream")
            // AuthInterceptor will add the Authorization header automatically, 
            // but we can ensure it here if needed. 
            // Since createEventSourceFactory uses the authenticated client with AuthInterceptor, we are good.
            .build()

        Log.d(TAG, "📡 Creating EventSource with URL: ${RetrofitClient.BASE_URL}/notifications/subscribe")

        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                super.onOpen(eventSource, response)
                isActuallyConnected = true
                Log.d(TAG, "✅ SSE Connected! Response: ${response.code} - ${response.message}")
                Log.d(TAG, "🔗 isActuallyConnected = $isActuallyConnected")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                super.onEvent(eventSource, id, type, data)
                Log.d(TAG, "")
                Log.d(TAG, "╔══════════════════════════════════════════════╗")
                Log.d(TAG, "║  🔔 SSE 이벤트 체인 시작                      ║")
                Log.d(TAG, "╚══════════════════════════════════════════════╝")
                Log.d(TAG, "")
                Log.d(TAG, "📍 [1/5] OkHttp EventSource에서 데이터 수신")
                Log.d(TAG, "   └─ Event ID: $id")
                Log.d(TAG, "   └─ Event Type: $type")
                Log.d(TAG, "   └─ Event Data: $data")
                Log.d(TAG, "")
                
                Log.d(TAG, "📍 [2/5] onEvent 콜백 호출됨")
                Log.d(TAG, "   └─ 현재 스레드: ${Thread.currentThread().name}")
                Log.d(TAG, "")
                
                Log.d(TAG, "📍 [3/5] hasUnreadNotification 플래그 설정")
                hasUnreadNotification = true
                Log.d(TAG, "   └─ hasUnreadNotification = $hasUnreadNotification")
                Log.d(TAG, "")
                
                Log.d(TAG, "📍 [4/5] Flow에 이벤트 emit 시도")
                val emitted = _notificationFlow.tryEmit(data)
                Log.d(TAG, "   └─ tryEmit 결과: $emitted")
                Log.d(TAG, "   └─ (true=성공, false=버퍼 가득 참)")
                Log.d(TAG, "")
                
                Log.d(TAG, "📍 [5/5] Flow로 전파 완료 → Fragment에서 collect 대기 중")
                Log.d(TAG, "   └─ 이제 ProfileFragment/HomeFragment의 collect 블록이 호출됩니다")
                Log.d(TAG, "")
                Log.d(TAG, "══════════════════════════════════════════════════")
            }

            override fun onClosed(eventSource: EventSource) {
                super.onClosed(eventSource)
                Log.d(TAG, "SSE Connection Closed")
                NotificationRepository.eventSource = null
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                super.onFailure(eventSource, t, response)
                Log.e(TAG, "❌ SSE Connection Failed!")
                Log.e(TAG, "Error: ${t?.message}", t)
                if (response != null) {
                    Log.e(TAG, "Response Code: ${response.code}, Message: ${response.message}")
                    try {
                        Log.e(TAG, "Response Body: ${response.peekBody(1024).string()}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not read response body")
                    }
                } else {
                    Log.e(TAG, "Response was null - network error or timeout")
                }
                // Retry logic could be implemented here if needed
                NotificationRepository.eventSource = null
            }
        })
    }

    fun disconnect() {
        if (eventSource != null) {
            Log.d(TAG, "Stopping SSE connection...")
            eventSource?.cancel()
            eventSource = null
        }
    }
}
