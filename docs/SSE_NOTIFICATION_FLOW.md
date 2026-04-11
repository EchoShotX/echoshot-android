# SSE 실시간 알림 수신 흐름 (상세 문서)

## 📚 목차
1. [개요](#개요)
2. [SSE란?](#sse란)
3. [시스템 아키텍처](#시스템-아키텍처)
4. [상세 코드 흐름](#상세-코드-흐름)
5. [주요 컴포넌트](#주요-컴포넌트)
6. [서버 API 명세](#서버-api-명세)
7. [디버깅 가이드](#디버깅-가이드)
8. [트러블슈팅](#트러블슈팅)

---

## 개요

이 문서는 EchoShot Android 앱에서 Server-Sent Events (SSE)를 사용하여 서버로부터 실시간 알림을 수신하는 전체 과정을 상세히 설명합니다.

### 주요 기능
- 실시간 알림 수신
- 하단 네비게이션 바에 알림 배지 표시
- 프로필 페이지 알림 벨에 배지 표시
- 알림 목록 조회 및 읽음 처리

---

## SSE란?

**Server-Sent Events (SSE)**는 서버에서 클라이언트로 단방향 실시간 데이터를 전송하는 웹 기술입니다.

### SSE vs 다른 기술 비교

| 특징 | SSE | WebSocket | Polling |
|------|-----|-----------|---------|
| 방향 | 서버 → 클라이언트 (단방향) | 양방향 | 클라이언트 → 서버 |
| 프로토콜 | HTTP | WS/WSS | HTTP |
| 복잡도 | 낮음 | 높음 | 낮음 |
| 실시간성 | 높음 | 높음 | 낮음 |
| 재연결 | 자동 지원 | 수동 구현 | N/A |

### SSE 메시지 형식
```
event: notification
data: {"id":1,"title":"알림입니다"}

```
- `event:` - 이벤트 타입 (선택사항)
- `data:` - 실제 데이터 (필수)
- 빈 줄 - 메시지 종료 구분자

---

## 시스템 아키텍처

### 전체 흐름도

```
┌────────────────────────────────────────────────────────────────────┐
│                         ANDROID APP                                │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  ┌─────────────┐    ┌───────────────────────┐    ┌──────────────┐ │
│  │   MyApp     │───▶│ NotificationRepository │◀──▶│ RetrofitClient│
│  │ (App Start) │    │     (SSE Manager)      │    │ (EventSource)│ │
│  └─────────────┘    └───────────────────────┘    └──────────────┘ │
│                              │                                     │
│                              │ SharedFlow                          │
│                              ▼                                     │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │                      FRAGMENTS                               │  │
│  │  ┌─────────────────┐              ┌──────────────────────┐  │  │
│  │  │  HomeFragment   │              │   ProfileFragment    │  │  │
│  │  │  ─────────────  │              │  ──────────────────  │  │  │
│  │  │  collect {      │              │  collect {           │  │  │
│  │  │    showBadge()  │              │    showBadge()       │  │  │
│  │  │  }              │              │  }                   │  │  │
│  │  └─────────────────┘              └──────────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
                              │
                              │ HTTPS (SSE)
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│                         SPRING SERVER                              │
├────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────┐    ┌────────────────────────────────┐   │
│  │ NotificationController│    │    SseConnectionManager       │   │
│  │  /subscribe           │    │    ─────────────────────────  │   │
│  │  /send               │────▶│    emitters: Map<memberId,    │   │
│  └──────────────────────┘    │                 SseEmitter>    │   │
│                               │    sendToMember(id, data)     │   │
│                               └────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────┘
```

---

## 상세 코드 흐름

### 1️⃣ SSE 연결 시작

#### 1.1 앱 시작 시 자동 연결 (MyApp.kt)

```kotlin
// MyApp.kt
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 배포 모드가 아닐 때만 SSE 연결
        if (!DeploymentModeManager.isDeploymentMode()) {
            val tokenManager = TokenManager(this)
            if (tokenManager.isLoggedIn()) {
                NotificationRepository.connect(tokenManager)
            }
        }
    }
}
```

**시점:** 앱 프로세스 시작 시
**조건:** 
- 배포 모드가 아닐 것
- 사용자가 로그인 되어 있을 것 (토큰 존재)

#### 1.2 로그인 성공 후 연결 (ProfileFragment.kt)

```kotlin
// ProfileFragment.kt
private fun fetchUserProfile() {
    lifecycleScope.launch {
        val response = userApi.getUserProfile()
        if (response.isSuccess) {
            // 사용자 정보 표시 후 SSE 연결
            NotificationRepository.connect(tokenManager)
        }
    }
}
```

**시점:** 구글 로그인 성공 후 프로필 정보 조회 완료 시

---

### 2️⃣ SSE 연결 설정 (NotificationRepository.kt)

#### 2.1 EventSource Factory 생성

```kotlin
// RetrofitClient.kt
fun createEventSourceFactory(tokenManager: TokenManager): EventSource.Factory {
    val authInterceptor = AuthInterceptor(tokenManager)

    val authenticatedOkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(authInterceptor)  // Authorization 헤더 자동 추가
        .connectTimeout(0, TimeUnit.SECONDS)  // SSE는 무제한
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    return EventSources.createFactory(authenticatedOkHttpClient)
}
```

**설정 포인트:**
- `AuthInterceptor`가 모든 요청에 `Authorization: Bearer {token}` 헤더 추가
- 타임아웃 0 = 무제한 (SSE는 장시간 연결 유지)

#### 2.2 EventSource 생성 및 리스너 등록

```kotlin
// NotificationRepository.kt
fun connect(tokenManager: TokenManager) {
    // 중복 연결 방지
    if (eventSource != null) {
        Log.d(TAG, "SSE is already connected or connecting. Skipping.")
        return
    }

    val factory = RetrofitClient.createEventSourceFactory(tokenManager)
    val request = Request.Builder()
        .url("${RetrofitClient.BASE_URL}/notifications/subscribe")
        .header("Accept", "text/event-stream")
        .build()

    eventSource = factory.newEventSource(request, object : EventSourceListener() {
        // 콜백 구현...
    })
}
```

---

### 3️⃣ SSE 이벤트 콜백

#### 3.1 연결 성공 (onOpen)

```kotlin
override fun onOpen(eventSource: EventSource, response: Response) {
    super.onOpen(eventSource, response)
    isActuallyConnected = true
    Log.d(TAG, "✅ SSE Connected! Response: ${response.code} - ${response.message}")
}
```

**호출 시점:** 서버가 SSE 연결을 수락하고 응답할 때
**서버에서:** `SseEmitter` 객체가 생성되고 초기 이벤트 전송

#### 3.2 이벤트 수신 (onEvent)

```kotlin
override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
    // [1/5] 데이터 수신
    Log.d(TAG, "📍 [1/5] OkHttp EventSource에서 데이터 수신")
    Log.d(TAG, "   └─ Event Type: $type")
    Log.d(TAG, "   └─ Event Data: $data")
    
    // [2/5] 콜백 호출됨
    Log.d(TAG, "📍 [2/5] onEvent 콜백 호출됨")
    Log.d(TAG, "   └─ 현재 스레드: ${Thread.currentThread().name}")
    
    // [3/5] 플래그 설정
    hasUnreadNotification = true
    Log.d(TAG, "📍 [3/5] hasUnreadNotification = true")
    
    // [4/5] Flow emit
    val emitted = _notificationFlow.tryEmit(data)
    Log.d(TAG, "📍 [4/5] tryEmit 결과: $emitted")
    
    // [5/5] Fragment에서 수신 대기
    Log.d(TAG, "📍 [5/5] Flow로 전파 완료")
}
```

**파라미터 설명:**
- `id`: 이벤트 ID (서버에서 설정, 선택사항)
- `type`: 이벤트 타입 (예: "notification", "connected")
- `data`: 실제 데이터 (JSON 문자열)

#### 3.3 연결 종료 (onClosed)

```kotlin
override fun onClosed(eventSource: EventSource) {
    super.onClosed(eventSource)
    Log.d(TAG, "SSE Connection Closed")
    isActuallyConnected = false
    eventSource = null
}
```

**호출 시점:** 정상적인 연결 종료 시

#### 3.4 연결 실패 (onFailure)

```kotlin
override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
    Log.e(TAG, "❌ SSE Connection Failed!")
    Log.e(TAG, "Error: ${t?.message}")
    if (response != null) {
        Log.e(TAG, "Response Code: ${response.code}")
    }
    isActuallyConnected = false
    eventSource = null
    // TODO: 재연결 로직 구현 필요
}
```

**주요 실패 원인:**
- 네트워크 오류
- 서버 다운
- 인증 토큰 만료 (401)
- 서버 오류 (500, 502)

---

### 4️⃣ SharedFlow를 통한 이벤트 전파

#### 4.1 Flow 정의

```kotlin
// NotificationRepository.kt
private val _notificationFlow = MutableSharedFlow<String>(
    replay = 1,           // 마지막 1개 이벤트를 새 구독자에게 전달
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
val notificationFlow = _notificationFlow.asSharedFlow()
```

**설정 설명:**
- `replay = 1`: 늦게 구독해도 마지막 이벤트는 받을 수 있음
- `extraBufferCapacity = 1`: 추가 버퍼 1개
- `DROP_OLDEST`: 버퍼 가득 차면 가장 오래된 것 삭제

#### 4.2 이벤트 emit

```kotlin
// onEvent 콜백 내부
val emitted = _notificationFlow.tryEmit(data)
```

- `tryEmit`: 비동기로 즉시 전송 시도
- 반환값: `true` (성공), `false` (버퍼 가득 참)

---

### 5️⃣ Fragment에서 이벤트 수신

#### 5.1 Flow 구독 (collect)

```kotlin
// ProfileFragment.kt
lifecycleScope.launch {
    NotificationRepository.notificationFlow.collect { data ->
        Log.d(TAG, "✨ ProfileFragment에서 SSE 이벤트 수신: $data")
        
        // UI 업데이트는 메인 스레드에서
        withContext(Dispatchers.Main) {
            // 하단 바 프로필 배지 표시
            view?.findViewById<View>(R.id.nav_profile_badge)?.visibility = View.VISIBLE
            // 알림 벨 배지 표시
            view?.findViewById<View>(R.id.notificationBadge)?.visibility = View.VISIBLE
        }
    }
}
```

**주의사항:**
- `collect`는 suspend 함수이므로 코루틴 스코프 내에서 호출
- `lifecycleScope` 사용으로 Fragment 생명주기에 맞춰 자동 취소
- UI 업데이트는 반드시 `Dispatchers.Main`에서

#### 5.2 HomeFragment에서의 구독

```kotlin
// HomeFragment.kt
// 이미 있는 알림 확인 (Fragment 시작 시)
if (NotificationRepository.hasUnreadNotification) {
    view.findViewById<View>(R.id.nav_profile_badge)?.visibility = View.VISIBLE
}

// 실시간 알림 감지
lifecycleScope.launch {
    NotificationRepository.notificationFlow.collect { data ->
        withContext(Dispatchers.Main) {
            view.findViewById<View>(R.id.nav_profile_badge)?.visibility = View.VISIBLE
        }
    }
}
```

---

## 주요 컴포넌트

### 파일 구조

```
app/src/main/java/com/echoshot/app/
├── MyApp.kt                           # 앱 시작 시 SSE 연결
├── network/
│   ├── RetrofitClient.kt              # EventSourceFactory 생성
│   └── NotificationApiService.kt      # 알림 API 인터페이스
├── repository/
│   └── NotificationRepository.kt      # SSE 연결 관리 (싱글톤)
├── fragments/
│   ├── HomeFragment.kt                # 홈 화면, Flow 구독
│   ├── ProfileFragment.kt             # 프로필 화면, Flow 구독
│   └── NotificationBottomSheetFragment.kt  # 알림 목록 표시
├── ui/
│   └── NotificationAdapter.kt         # 알림 RecyclerView 어댑터
└── auth/
    └── models/
        └── NotificationDto.kt         # 알림 데이터 모델
```

### 주요 클래스 역할

| 클래스 | 역할 | 싱글톤 여부 |
|--------|------|-------------|
| `NotificationRepository` | SSE 연결 관리, 이벤트 브로드캐스트 | ✅ object |
| `RetrofitClient` | HTTP/SSE 클라이언트 생성 | ✅ object |
| `NotificationAdapter` | 알림 목록 RecyclerView 어댑터 | ❌ |
| `NotificationBottomSheetFragment` | 알림 목록 UI | ❌ |

---

## 서버 API 명세

### 1. SSE 구독

```http
GET /notifications/subscribe
Authorization: Bearer {accessToken}
Accept: text/event-stream
```

**응답:** `text/event-stream` (연결 유지)

**서버 이벤트 형식:**
```
event: connected
data: SSE connection established

event: notification
data: {"id":1,"type":"VIDEO","category":"upload","title":"업로드 완료","content":"영상이 업로드되었습니다.","isRead":false,"status":"SENT","retryCount":0,"videoId":123,"creditHistoryId":null,"createdAt":"2026-01-19T18:00:00"}
```

### 2. 전체 알림 조회

```http
GET /notifications
Authorization: Bearer {accessToken}
```

**응답:**
```json
{
  "isSuccess": true,
  "code": 2000,
  "message": "성공",
  "result": [
    {
      "id": 1,
      "type": "VIDEO",
      "category": "upload",
      "title": "업로드 완료",
      "content": "영상이 업로드되었습니다.",
      "isRead": false,
      ...
    }
  ]
}
```

### 3. 읽지 않은 알림 조회

```http
GET /notifications/unread
Authorization: Bearer {accessToken}
```

### 4. 알림 읽음 처리

```http
POST /notifications/{id}/read
Authorization: Bearer {accessToken}
```

### 5. 전체 읽음 처리

```http
POST /notifications/read-all
Authorization: Bearer {accessToken}
```

---

## 디버깅 가이드

### Logcat 필터

| 목적 | 필터 |
|------|------|
| SSE 전체 로그 | `tag:NotificationRepository` |
| HTTP 요청/응답 | `tag:okhttp.OkHttpClient` |
| 프로필 화면 | `tag:ProfileFragment` |
| 홈 화면 | `tag:HomeFragment` |

### 주요 로그 메시지

| 로그 | 의미 | 정상 여부 |
|------|------|-----------|
| `📡 Creating EventSource` | SSE 연결 시도 중 | 정상 |
| `✅ SSE Connected!` | 서버 연결 성공 | ✅ 정상 |
| `🔔 SSE 이벤트 체인 시작` | 알림 이벤트 수신됨 | ✅ 정상 |
| `✨ Fragment에서 SSE 이벤트 수신` | Fragment에서 수신 완료 | ✅ 정상 |
| `SSE is already connected` | 중복 연결 시도 차단 | 정상 (스킵) |
| `❌ SSE Connection Failed!` | 연결 실패 | ❌ 오류 |

### 연결 상태 확인

```kotlin
// ProfileFragment에서 확인
Log.d(TAG, "eventSource 존재: ${NotificationRepository.isConnected()}")
Log.d(TAG, "실제 연결됨: ${NotificationRepository.isConnectionEstablished()}")
```

- `isConnected() = true` + `isConnectionEstablished() = false`: 요청은 보냈으나 서버 응답 대기 중
- `isConnected() = true` + `isConnectionEstablished() = true`: 완전히 연결됨

---

## 트러블슈팅

### 문제 1: SSE 이벤트가 수신되지 않음

**증상:** `✅ SSE Connected!` 로그는 보이지만 `🔔 SSE 이벤트 체인 시작`이 안 보임

**원인:**
1. 서버에서 이벤트를 보내지 않음
2. 서버 SSE 형식이 잘못됨

**해결:**
- 서버 로그에서 `Notification sent to member: X` 확인
- 서버가 `data:` 접두어를 붙여 데이터를 보내는지 확인

### 문제 2: isConnected()는 true인데 isConnectionEstablished()가 false

**증상:** 연결 시도는 했으나 `onOpen` 콜백이 호출되지 않음

**원인:**
1. 서버가 응답하지 않음
2. 네트워크 방화벽/프록시 문제
3. 서버 인증 실패

**해결:**
- 서버 로그에서 `SSE connection request from member` 확인
- 서버가 401/403 에러를 반환하는지 확인

### 문제 3: Fragment에서 이벤트를 받지 못함

**증상:** `🔔 SSE 이벤트 체인 시작`은 보이지만 `✨ Fragment에서 SSE 이벤트 수신`이 안 보임

**원인:**
1. Fragment가 이미 destroy됨
2. collect 전에 이벤트가 emit됨 (replay=0 문제)

**해결:**
- `replay = 1`로 설정되어 있는지 확인
- `hasUnreadNotification` 플래그로 Fragment 시작 시 배지 표시

### 문제 4: UI 배지가 표시되지 않음

**증상:** 로그는 정상인데 UI가 안 바뀜

**원인:**
1. View가 null
2. 메인 스레드가 아닌 곳에서 UI 업데이트

**해결:**
- `withContext(Dispatchers.Main)` 사용 확인
- `view?.findViewById` 결과가 null인지 확인

---

## 개선 필요 사항

### TODO 목록

- [ ] SSE 연결 실패 시 자동 재연결 로직
- [ ] 앱이 백그라운드로 갈 때 연결 유지/재연결 처리
- [ ] 알림 클릭 시 해당 콘텐츠로 이동
- [ ] 읽음 처리 시 배지 숨김
- [ ] 오프라인 알림 캐싱

---

## 참고 자료

- [OkHttp SSE Documentation](https://square.github.io/okhttp/sse/)
- [Spring SseEmitter Documentation](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html)
- [Kotlin SharedFlow](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html#shared-mutable-state)
