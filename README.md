
EchoShot - AI-Powered 인물 중심 영상 자동 크롭 앱
==================================================

EchoShot은 모바일에서 인물 중심 영상을 촬영할 때 **배경 누락 없이 완벽한 인물 중심 영상을 생성**하는 Android 애플리케이션입니다.

## 🎯 핵심 기능: 인물 중심 영상 자동 크롭

### 문제 해결
일반적인 모바일 앱들은 인물 중심 영상을 만들 때 **영상의 한계로 배경이 잘리는 문제**가 발생합니다. EchoShot은 이 문제를 해결하기 위해 **촬영 단계에서 와이드(노줌) 영상을 함께 저장**하고, 촬영한 영상에서 트래킹한 정보에 **패딩을 적용**하여 배경 누락 없이 인물 중심 영상을 생성합니다.

### 작동 원리
1. **촬영 단계**: 줌 영상과 노줌(와이드) 영상을 동시에 저장
2. **트래킹**: YOLOv11-pose로 인물의 골격 정보 추출 및 추적
3. **ReID**: 골격 유사도와 위치 유사도를 기반으로 동일 인물 판단
4. **패딩 적용**: 트래킹된 위치에 패딩을 적용하여 배경 누락 방지
5. **크롭**: 노줌 영상에서 패딩이 적용된 영역을 크롭하여 최종 영상 생성

---

## 핵심 기능

### 🎥 듀얼 비디오 녹화
- **Camera2 API** 기반 고품질 비디오 녹화
- **줌 영상**과 **노줌(와이드) 영상** 동시 저장
- 동일한 타임스탬프로 프레임 동기화
- 해상도, 프레임 레이트, 카메라 선택 가능
- HDR/SDR 포맷 지원

### 🤖 AI 기반 인물 추적
- **YOLOv11n-pose**: 17개 관절점(keypoints) 기반 인물 탐지
- **골격 유사도 기반 ReID**: 정규화된 스켈레톤 형태 비교로 위치/크기 무관 동일 인물 판단
- **위치 유사도 기반 ReID**: 위치 연속성을 고려한 정확한 추적
- **체형 비율 비교**: 어깨/엉덩이 비율 등 체형 특징 비교
- 상체 기반 바운딩 박스로 팔다리 움직임 영향 최소화

### 🎬 인물 중심 영상 생성
- **패딩 적용**: 모드별 패딩 팩터 적용 (인물중심: 1.5~3배, 와이드: 2.5~4배)
- **9:16 비율 고정**: 세로형 영상 최적화
- **부드러운 추적**: 칼만 필터 및 RTS 스무더로 자연스러운 카메라 움직임
- **배경 보존**: 노줌 영상에서 크롭하여 배경 누락 없음

---

## 기술 스택

### AI 모델
- **YOLOv11n-pose** (`yolo11n-pose_float16.tflite`)
  - 입력: 640×640 RGB 이미지
  - 출력: 바운딩 박스 + 17개 관절점 (코, 눈, 귀, 어깨, 팔꿈치, 손목, 엉덩이, 무릎, 발목)
  - GPU Delegate 지원

### 추적 알고리즘
- **골격 유사도 계산**:
  - 정규화된 스켈레톤 형태 유사도 (35%)
  - 체형 비율 유사도 (25%)
  - 위치 연속성 (20%)
  - 절대 좌표 OKS (20%)

### 후처리
- **칼만 필터**: 위치 및 속도 기반 스무딩
- **RTS 스무더**: 역방향 필터링으로 더 부드러운 궤적
- **노이즈 제거**: 3단계 스파이크 제거 + 2단계 스무딩

---

## 시스템 아키텍처

### 전체 파이프라인
```
촬영 단계
├── 줌 영상 (사용자가 보는 영상)
└── 노줌 영상 (와이드, 배경 포함)
    │
    ▼
YOLOv11-pose 탐지
├── 17개 관절점 추출
└── 바운딩 박스 생성
    │
    ▼
트래킹 및 ReID
├── 골격 유사도 계산
├── 위치 유사도 계산
└── 동일 인물 판단
    │
    ▼
패딩 적용
├── 인물중심 모드: 1.5~3배
└── 와이드 모드: 2.5~4배
    │
    ▼
노줌 영상에서 크롭
└── 최종 인물 중심 영상 (배경 누락 없음)
```

### 주요 컴포넌트

#### 📁 **fragments/** - 촬영 및 UI 제어
- **CustomPreviewFragment.kt**: 카메라 프리뷰 및 녹화 제어
- **CustomHardwarePipeline.kt**: 하드웨어 파이프라인 구현 (듀얼 녹화)
- **MakeAutoDetactionFragment.kt**: 자동 인물 탐지 및 크롭
- **HybridPickerDialogFragment.kt**: 인물 선택 모드 (하이브리드)
- **GalleryFragment.kt**: 갤러리 관리 및 비디오 처리 UI

#### 🤖 **mp4detact/** - AI 추론 및 후처리
- **VideoPoseDetectFacade.kt**: YOLOv11-pose 기반 비디오 포즈 감지
- **Yolo11PoseTflite.kt**: YOLOv11n-pose TFLite 추론 엔진
- **PoseLogOrchestrator.kt**: 포즈 로그 생성 및 병합 오케스트레이터
- **PoseDetectLogManager.kt**: 포즈 감지 로그 생성 매니저
- **FrameCropper.kt**: 최종 영상 크롭 및 인코딩
- **VideoPipeline.kt**: 전체 비디오 처리 파이프라인

#### 🐍 **python/** - 로그 처리 및 스무딩
- **merge_pose_logs.py**: YOLOv11-pose 로그 병합 (골격/위치 유사도 기반 ReID)
- **make_log_pipeline.py**: 줌 보간 및 칼만 필터 스무딩
- **make_pose_log_pipeline.py**: 포즈 로그 후처리

---

## 사용법

### 자동 인물 탐지 모드
1. 비디오 녹화 또는 기존 비디오 파일 선택
2. "고성능 추적" 모드 선택
3. YOLOv11-pose가 자동으로 인물 탐지 및 추적
4. 모드 선택 (인물중심/와이드)
5. 패딩이 적용된 인물 중심 영상 자동 생성

### 인물 선택 모드 (하이브리드)
1. 비디오 녹화 또는 기존 비디오 파일 선택
2. "인물 선택" 모드 선택
3. 첫 프레임에서 추적할 인물 직접 선택
4. YOLO + MoveNet + FaceNet 멀티모달 조합으로 정밀 추적
5. 최종 영상 생성

---

## 시스템 요구사항

- Android SDK 33+
- Android Studio 3.6+
- 비디오 캡처 가능한 디바이스
- TensorFlow Lite for YOLOv11-pose 모델 추론
- OpenCV for Android for 이미지 처리

---

## 프로젝트 구조

### 📁 핵심 폴더별 기능

#### 🎥 **fragments/** - 촬영 및 UI 제어
- **CustomPreviewFragment.kt**: 카메라 프리뷰 및 녹화 제어
- **CustomHardwarePipeline.kt**: 하드웨어 파이프라인 구현 (듀얼 녹화)
- **MakeAutoDetactionFragment.kt**: 자동 인물 탐지 및 크롭
- **HybridPickerDialogFragment.kt**: 인물 선택 모드
- **GalleryFragment.kt**: 갤러리 관리 및 비디오 처리 UI

#### 🤖 **mp4detact/** - AI 추론 및 후처리
- **VideoPoseDetectFacade.kt**: YOLOv11-pose 기반 비디오 포즈 감지
- **models/pose/Yolo11PoseTflite.kt**: YOLOv11n-pose TFLite 추론 엔진
- **PoseLogOrchestrator.kt**: 포즈 로그 생성 및 병합
- **FrameCropper.kt**: 최종 영상 크롭 및 인코딩
- **VideoPipeline.kt**: 전체 비디오 처리 파이프라인

#### 🐍 **python/** - 로그 처리 및 스무딩
- **merge_pose_logs.py**: 골격/위치 유사도 기반 ReID 및 로그 병합
- **make_log_pipeline.py**: 줌 보간 및 칼만 필터 스무딩
- **make_pose_log_pipeline.py**: 포즈 로그 후처리

### 🔧 시스템 아키텍처

#### 듀얼 녹화 시스템
```
CustomHardwarePipeline
├── 카메라 입력
├── 렌더 (줌 적용)
└── 동시 인코딩
    ├── 원본 트랙 (노줌, 와이드)
    └── 줌 트랙 (사용자 프리뷰)
```

#### 인물 추적 시스템
```
YOLOv11-pose 탐지
├── 17개 관절점 추출
├── 골격 유사도 계산
├── 위치 유사도 계산
└── 동일 인물 판단 (ReID)
    │
    ▼
칼만 필터 + RTS 스무더
└── 부드러운 궤적 생성
```

#### 크롭 시스템
```
로그 파일 (JSONL)
├── 프레임별 크롭 좌표
├── 줌 정보
└── 패딩 팩터
    │
    ▼
FrameCropper
├── 노줌 영상 읽기
├── OpenGL 크롭
└── 최종 영상 인코딩
```

### 🎯 핵심 알고리즘

#### 1. 듀얼 녹화 로직 (fragments/)
- **하드웨어 파이프라인**: GPU 기반 실시간 처리
- **듀얼 스트림**: 줌 영상 + 노줌 영상 동시 녹화
- **타임스탬프 동기화**: 동일한 PTS로 프레임 매칭
- **메모리 최적화**: 효율적인 버퍼 관리

#### 2. 인물 추적 로직 (mp4detact/)
- **YOLOv11-pose**: 17개 관절점 기반 인물 탐지
- **골격 유사도**: 정규화된 스켈레톤 형태 비교
- **위치 유사도**: 위치 연속성 고려
- **체형 비율**: 어깨/엉덩이 비율 비교
- **상체 기반 박스**: 팔다리 움직임 영향 최소화

#### 3. 후처리 로직 (python/)
- **칼만 필터**: 위치 및 속도 기반 스무딩
- **RTS 스무더**: 역방향 필터링으로 부드러운 궤적
- **노이즈 제거**: 3단계 스파이크 제거
- **패딩 적용**: 모드별 패딩 팩터 적용

### 🚀 성능 최적화
- **GPU 가속**: OpenGL ES 기반 렌더링
- **TensorFlow Lite**: 빠른 모델 추론
- **메모리 관리**: 효율적인 버퍼 할당/해제
- **멀티스레딩**: 비동기 처리로 성능 향상

---

## 업데이트 이력

### 최신 업데이트
- **YOLOv11-pose 도입**: YOLOv8에서 YOLOv11n-pose로 업그레이드
- **골격 유사도 기반 ReID**: 정규화된 스켈레톤 형태 비교로 정확도 향상
- **위치 유사도 기반 ReID**: 위치 연속성을 고려한 추적 안정화
- **듀얼 녹화 시스템**: 줌 영상과 노줌 영상 동시 저장으로 배경 보존
- **패딩 시스템**: 모드별 패딩 팩터 적용으로 배경 누락 방지

---

---

## 파일 디렉토리 상세 설명

각 기능이 어떤 파일에서 어떻게 구현되는지 상세히 설명합니다.

### 🎥 1. 듀얼 비디오 녹화 (줌 + 노줌 동시 저장)

#### 역할
촬영 시 사용자가 보는 줌 영상과 배경이 포함된 노줌(와이드) 영상을 동시에 저장합니다.

#### 구현 파일
- **`app/src/main/java/com/echoshot/app/CustomHardwarePipeline.kt`**
  - `onFrameAvailableImpl()` 메서드 (라인 1493-1523)
  - 카메라 프레임이 들어올 때마다 호출되어 두 개의 인코더에 동시에 전송

#### 핵심 코드
```kotlin
// 같은 ptsNs로 원본/줌 둘 다 찍기
if (currentlyRecording) {
    // 원본 트랙(줌 미적용) - 노줌 영상
    if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
        copyRenderToEncodeOriginal(ptsNs)
    }
    // 줌 트랙(렌더텍스처, 줌 적용 상태) - 사용자가 보는 영상
    if (eglEncoderSurfaceZoomed != EGL14.EGL_NO_SURFACE) {
        copyRenderToEncode(ptsNs)
    }
}
```

#### 관련 파일
- **`app/src/main/java/com/echoshot/app/EncoderWrapper.kt`**
  - 실제 인코딩 수행
  - CFR(Constant Frame Rate) 강제로 두 영상의 프레임 동기화 보장 (라인 814-836)

---

### 🤖 2. YOLOv11-pose 인물 탐지

#### 역할
비디오의 각 프레임에서 인물을 탐지하고 17개 관절점(keypoints)을 추출합니다.

#### 구현 파일
- **`app/src/main/java/com/echoshot/app/mp4detact/models/pose/Yolo11PoseTflite.kt`**
  - YOLOv11n-pose TFLite 모델 추론 엔진
  - 입력: 640×640 RGB 이미지
  - 출력: 바운딩 박스 + 17개 관절점 (코, 눈, 귀, 어깨, 팔꿈치, 손목, 엉덩이, 무릎, 발목)

- **`app/src/main/java/com/echoshot/app/mp4detact/VideoPoseDetectFacade.kt`**
  - 비디오 파일을 프레임별로 디코딩하여 YOLOv11-pose 모델에 전달
  - 각 프레임의 탐지 결과를 로그 파일로 저장

- **`app/src/main/java/com/echoshot/app/mp4detact/PoseDetectLogManager.kt`**
  - 포즈 감지 로그 생성 및 관리
  - JSONL 형식으로 저장 (`pose_log.jsonl`)

#### 핵심 코드
```kotlin
// Yolo11PoseTflite.kt - 모델 추론
val output = Array(1) { Array(8400) { FloatArray(56) } }
interpreter.run(inputBuffer, output)

// 출력 디코딩: [1, 8400, 56]
// 56 = 4(bbox) + 1(conf) + 51(17 keypoints × 3)
```

---

### 🔍 3. 골격 유사도 기반 ReID (동일 인물 판단)

#### 역할
정규화된 스켈레톤 형태를 비교하여 위치와 크기와 무관하게 동일 인물을 판단합니다.

#### 구현 파일
- **`app/src/main/python/merge_pose_logs.py`**
  - `PoseTracker` 클래스 (라인 323-405)
  - `_compute_combined_similarity()` 메서드 (라인 364-402)

#### 핵심 코드
```python
def _compute_combined_similarity(self, det: Dict) -> float:
    """
    종합 유사도 계산:
    - 35%: 정규화된 스켈레톤 형태 유사도 (체형 비교, 위치 무관)
    - 25%: 체형 비율 유사도 (어깨/엉덩이/상체 비율)
    - 20%: 위치 연속성 (가까울수록 높음)
    - 20%: 절대 좌표 OKS (빠른 움직임 필터링)
    """
    # 1. 정규화된 스켈레톤 형태 유사도
    shape_sim = compute_skeleton_shape_similarity(self.anchor_kps, kps)
    
    # 2. 체형 비율 유사도
    anchor_props = compute_body_proportions(self.anchor_kps)
    det_props = compute_body_proportions(kps)
    proportion_sim = compare_body_proportions(anchor_props, det_props)
    
    # 3. 위치 연속성
    pos_sim = max(0.0, 1.0 - dist / max_dist)
    
    # 4. 절대 좌표 OKS
    oks = compute_oks(self.anchor_kps, kps, area)
    
    # 종합: 체형 비교 비중 높임 (60%), 위치는 보조 (40%)
    combined = 0.35 * shape_sim + 0.25 * proportion_sim + 0.20 * pos_sim + 0.20 * oks
    return combined
```

#### 관련 함수
- **`normalize_skeleton()`** (라인 33-64): 스켈레톤을 bbox 기준으로 정규화 (0~1 범위)
- **`compute_skeleton_shape_similarity()`** (라인 67-120): 정규화된 스켈레톤 형태 유사도 계산
- **`compute_body_proportions()`**: 체형 비율 계산 (어깨 너비, 엉덩이 너비 등)

---

### 📍 4. 위치 유사도 기반 ReID

#### 역할
위치 연속성을 고려하여 갑자기 멀리 점프하는 것을 필터링하고 정확한 추적을 수행합니다.

#### 구현 파일
- **`app/src/main/python/merge_pose_logs.py`**
  - `PoseTracker._compute_combined_similarity()` 메서드 내 위치 연속성 계산 (라인 387-394)

- **`app/src/main/java/com/echoshot/app/mp4detact/tracking/Association.kt`**
  - `matchWithIoUAndDistance()` 메서드 (라인 31-65)
  - IoU + 면적 유사도 + 위치 유사도 복합 점수 계산

#### 핵심 코드
```python
# merge_pose_logs.py - 위치 연속성 계산
if self.anchor_box:
    dist = self._center_distance(self.anchor_box, det_box)
    diag = math.sqrt(area) if area > 0 else 100
    max_dist = diag * 3  # 박스 대각선의 3배까지 허용
    pos_sim = max(0.0, 1.0 - dist / max_dist)
```

```kotlin
// Association.kt - 위치 유사도 계산
private fun calculatePositionSimilarity(a: Rect, b: Rect): Float {
    val center1X = a.x + a.width / 2.0
    val center1Y = a.y + a.height / 2.0
    val center2X = b.x + b.width / 2.0
    val center2Y = b.y + b.height / 2.0
    
    val distance = sqrt(
        ((center1X - center2X).pow(2) + (center1Y - center2Y).pow(2))
    ).toFloat()
    
    val maxDistance = sqrt((imgW * imgW + imgH * imgH).toDouble()).toFloat()
    val normalizedDistance = distance / maxDistance
    
    return 1.0f - normalizedDistance.coerceIn(0f, 1f)
}
```

---

### 🎯 5. 패딩 적용

#### 역할
트래킹된 바운딩 박스 주변에 여유 공간을 추가하여 배경이 잘리지 않도록 합니다.

#### 구현 파일
- **`app/src/main/java/com/echoshot/app/FrameCropper.kt`**
  - `cropAll()` 메서드 (라인 334-340)

- **`app/src/main/java/com/echoshot/app/fragments/MakeAutoDetactionFragment.kt`**
  - 패딩 팩터 설정 (라인 153-157)
  - 인물중심 모드: 3.5배, 와이드 모드: 5배

- **`app/src/main/java/com/echoshot/app/fragments/HybridPickerDialogFragment.kt`**
  - 패딩 팩터 설정 (라인 204-208)
  - 인물중심 모드: 2배, 와이드 모드: 3배

#### 핵심 코드
```kotlin
// FrameCropper.kt - 패딩 적용
val paddedFrames = if (paddingFactor == 1f) filled else filled.map { f ->
    val cx = (f.x1 + f.x2) * 0.5  // 중심점 유지
    val cy = (f.y1 + f.y2) * 0.5
    val w = (f.x2 - f.x1) * paddingFactor  // 크기 확장
    val h = (f.y2 - f.y1) * paddingFactor
    f.copy(
        x1 = cx - w * 0.5,  // 새로운 박스
        y1 = cy - h * 0.5,
        x2 = cx + w * 0.5,
        y2 = cy + h * 0.5
    )
}
```

#### 패딩 팩터 설정 위치
- **MakeAutoDetactionFragment.kt**: 자동 인물 탐지 모드에서 사용
- **HybridPickerDialogFragment.kt**: 인물 선택 모드에서 사용

---

### ✂️ 6. 영상 크롭 및 인코딩

#### 역할
노줌 영상에서 패딩이 적용된 영역을 크롭하여 최종 인물 중심 영상을 생성합니다.

#### 구현 파일
- **`app/src/main/java/com/echoshot/app/FrameCropper.kt`**
  - `cropAll()` 메서드 (라인 329-822)
  - 로그 파일 읽기, 누락 프레임 보간, OpenGL 크롭, 인코딩 수행

- **`app/src/main/java/com/echoshot/app/VideoPipeline.kt`**
  - `processSessionFromLog()` 메서드 (라인 56-142)
  - 전체 파이프라인 오케스트레이션

#### 핵심 코드
```kotlin
// FrameCropper.kt - 크롭 프로세스
fun cropAll() {
    // 1. 로그 파일 읽기
    val raw = loadFramesFromLog()
    
    // 2. 누락 프레임 보간
    val filled = fillMissing(raw)
    
    // 3. 패딩 적용
    val paddedFrames = if (paddingFactor == 1f) filled else filled.map { ... }
    
    // 4. MediaExtractor로 노줌 MP4 읽기
    val extractor = MediaExtractor().apply { setDataSource(srcPath) }
    
    // 5. OpenGL ES를 이용한 크롭
    // 각 프레임마다:
    // - MediaCodec 디코더로 프레임 디코딩 → SurfaceTexture
    // - 텍스처 변환 행렬 계산 (스크린 좌표 → 픽셀 좌표)
    // - OpenGL로 크롭 영역 렌더링
    
    // 6. MediaCodec 인코더로 최종 영상 인코딩
}
```

#### 관련 파일
- **`app/src/main/java/com/echoshot/app/mp4detact/io/MediaCodecOesDecoder.kt`**
  - 비디오 프레임 디코딩
  - SurfaceTexture를 통한 OpenGL 텍스처 생성

---

### 📊 7. 로그 처리 및 스무딩

#### 역할
트래킹 로그를 처리하고 칼만 필터 및 RTS 스무더를 적용하여 부드러운 카메라 움직임을 생성합니다.

#### 구현 파일
- **`app/src/main/python/make_log_pipeline.py`**
  - `process_video()` 함수 (라인 272-607)
  - 칼만 필터 및 RTS 스무더 적용

- **`app/src/main/python/merge_pose_logs.py`**
  - `merge_pose_logs()` 함수 (라인 781-1062)
  - 포즈 로그와 줌 로그 병합

#### 핵심 코드
```python
# make_log_pipeline.py - 칼만 필터 및 RTS 스무더
def process_video(tracking_json, ts_json, output_json, ...):
    # 1. 프레임 타임스탬프 로드
    # 2. 트래킹 로드 + 역줌 변환
    # 3. 칼만 필터 적용 (위치 및 속도 기반 스무딩)
    # 4. RTS 스무더 적용 (역방향 필터링)
    # 5. 9:16 비율 고정
    # 6. 출력
```

---

### 🎬 8. 전체 파이프라인 오케스트레이션

#### 역할
전체 프로세스를 조율하고 각 단계를 순차적으로 실행합니다.

#### 구현 파일
- **`app/src/main/java/com/echoshot/app/mp4detact/PoseLogOrchestrator.kt`**
  - `generatePoseLog()` 메서드
  - 포즈 탐지 → 로그 병합 → 최종 로그 생성

- **`app/src/main/java/com/echoshot/app/VideoPipeline.kt`**
  - `processSessionFromLog()` 메서드
  - 로그 파일 기반으로 최종 영상 생성

#### 파이프라인 흐름
```
1. VideoPoseDetectFacade → YOLOv11-pose 탐지 → pose_log.jsonl
2. PoseLogOrchestrator → make_log_pipeline.py → processed.json
3. PoseLogOrchestrator → merge_pose_logs.py → merged.jsonl
4. VideoPipeline → FrameCropper → 최종 크롭 영상
```

---

## 관련 문서

- [인물 중심 영상 자동 크롭 시스템 상세 설명](MAKE_PERSON_VIDEO_README.md)
- [모델 디렉토리 구조 및 파이프라인 설명](app/src/main/java/com/echoshot/app/mp4detact/models/README.md)
