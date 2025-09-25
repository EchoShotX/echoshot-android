
EchoShot - AI-Powered Video Analysis App
========================================

EchoShot is an Android application that combines video recording with advanced AI-powered analysis capabilities including object detection, pose estimation, and single object tracking (SOT) using SiamRPN++ Mobile model.

## 핵심 기능

### 🎥 비디오 녹화
- Camera2 API 기반 고품질 비디오 녹화
- 해상도, 프레임 레이트, 카메라 선택 가능
- HDR/SDR 포맷 지원
- 프리뷰 안정화

### 🤖 AI 기반 분석
- **객체 탐지**: YOLOv8 기반 실시간 객체 탐지
- **포즈 추정**: MoveNet 기반 인체 포즈 추정
- **단일 객체 추적 (SOT)**: SiamRPN++ Mobile 기반 추적
  - MobileNetV2 백본 (width_mult: 1.4)
  - 사용 레이어: [3, 5, 7]
  - 템플릿 크기: 127x127, 검색 크기: 255x255
  - 앵커 설정: 5개 앵커, 비율 [0.33, 0.5, 1, 2, 3], 스케일 [8]

### 🎯 SOT (Single Object Tracking) 시스템

#### 아키텍처
- **VideoSotFacade**: 비디오 디코딩과 SOT 추적을 통합한 파사드 클래스
- **SiamRpnTs**: SiamRPN++ TorchScript 백엔드 구현
- **SotLogManager**: MP4 → JSONL 변환 및 로깅 관리

#### 주요 특징
1. **안정화된 추적 알고리즘**:
   - 초기 박스 비율(w/h) 고정으로 drift 방지
   - Hanning window + scale/ratio penalty 적용
   - LR 스무딩으로 부드러운 추적
   - 템플릿 갱신 gating 강화

2. **고성능 모델**:
   - SiamRPN++ Mobile 모델 (`siamrpnpp_mobile.ptl`)
   - TorchScript 기반 추론
   - 25x25 출력 해상도
   - 동적 stride 계산

3. **좌표 변환 시스템**:
   - 원본 비디오 → 레터박스(640x640) → 모델 입력
   - 모델 출력 → 레터박스 → 원본 좌표 복원
   - 정확한 좌표 매핑과 클램핑

#### SOT 설정
```
META_ARC: "siamrpn_mobilev2_l234_dwxcorr"
BACKBONE: MobileNetV2 (width_mult: 1.4, used_layers: [3, 5, 7])
ADJUST: AdjustAllLayer (in_channels: [44, 134, 448], out_channels: [256, 256, 256])
RPN: MultiRPN (anchor_num: 5, in_channels: [256, 256, 256])
ANCHOR: stride=8, ratios=[0.33, 0.5, 1, 2, 3], scales=[8]
TRACK: penalty_k=0.04, window_influence=0.4, lr=0.5, context_amount=0.5
```


## 사용법

### SOT (Single Object Tracking) 사용법
1. 비디오 녹화 또는 기존 비디오 파일 선택
2. SOT 피커로 추적할 초기 바운딩 박스 선택
3. 앱이 추적 결과를 JSONL 파일로 생성
4. 각 프레임마다 신뢰도 점수와 바운딩 박스 좌표 포함



## 시스템 요구사항

- Android SDK 33+
- Android Studio 3.6+
- 비디오 캡처 가능한 디바이스 (또는 에뮬레이터)
- PyTorch Mobile for SiamRPN++ 모델 추론
- OpenCV for Android for 이미지 처리

## 스크린샷

<img src="screenshots/main.png" height="400" alt="Screenshot"/>

## 빌드 및 실행

이 프로젝트는 Gradle 빌드 시스템을 사용합니다. 프로젝트를 빌드하려면 "gradlew build" 명령을 사용하거나 Android Studio에서 "Import Project"를 사용하세요.

## 프로젝트 구조

### 📁 핵심 폴더별 기능

#### 🎥 **fragments/** - 동시 저장 로직의 중심
- **GalleryFragment.kt**: 갤러리 관리 및 비디오 처리 UI
- **CustomPreviewFragment.kt**: 카메라 프리뷰 및 녹화 제어
- **CustomHardwarePipeline.kt**: 하드웨어 파이프라인 구현
- **SotPickerDialogFragment.kt**: SOT 초기 박스 선택 UI

#### 🤖 **ml/** - 오토줌 로직 구현
- **MoveNet.kt**: MoveNet 기반 포즈 추정
- **MoveNetMultiPose.kt**: 다중 포즈 추정
- **PoseClassifier.kt**: 포즈 분류 및 분석
- **PoseDetector.kt**: 포즈 탐지 인터페이스
- **PoseNet.kt**: PoseNet 모델 구현

#### 🎬 **mp4detact/** - 동영상 후처리 및 SOT
- **VideoSotFacade.kt**: SOT 추적 파사드
- **SiamRpnTs.kt**: SiamRPN++ TorchScript 백엔드
- **SotLogManager.kt**: MP4 → JSONL 변환
- **VideoDetectFacade.kt**: 객체 탐지 파사드
- **YoloTflite.kt**: YOLO 객체 탐지
- **MediaCodecOesDecoder.kt**: 비디오 디코딩
- **GlLetterboxFbo.kt**: OpenGL 레터박스 렌더링

### 🔧 시스템 아키텍처

#### 동시 저장 시스템
```
CustomHardwarePipeline
├── GalleryFragment (UI 제어)
├── CustomPreviewFragment (카메라 제어)
└── 동시 저장 로직
    ├── 메인 스트림 
    ├── 서브 스트림 
    └── 실시간 처리
```

#### 오토줌 시스템
```
ml/ 폴더
├── MoveNet.kt (포즈 추정)
├── PoseClassifier.kt (포즈 분석)
└── 오토줌 로직
    ├── 포즈 감지
    ├── 프레임 분석
    └── 줌 제어
```

#### SOT 시스템
```
mp4detact/ 폴더
├── VideoSotFacade.kt (파사드)
├── SiamRpnTs.kt (추적 백엔드)
└── SotLogManager.kt (로깅)
    ├── 비디오 디코딩
    ├── SOT 추적
    └── JSONL 출력
```

### 🎯 핵심 알고리즘

#### 1. 동시 저장 로직 (fragments/)
- **하드웨어 파이프라인**: GPU 기반 실시간 처리
- **듀얼 스트림**: 메인(고품질) + 서브(저품질) 동시 녹화
- **메모리 최적화**: 효율적인 버퍼 관리

#### 2. 오토줌 로직 (ml/)
- **포즈 추정**: MoveNet 기반 실시간 포즈 감지
- **분석**: 포즈 분류 및 프레임 분석
- **줌 제어**: 적응적 줌 레벨 조정

#### 3. SOT 로직 (mp4detact/)
- **템플릿 매칭**: 초기 프레임에서 객체 템플릿 추출
- **검색 영역**: 동적 검색 영역 계산
- **모델 추론**: SiamRPN++ 모델로 위치 탐지
- **후처리**: penalty, window influence, LR 스무딩

### 🚀 성능 최적화
- **GPU 가속**: OpenGL ES 기반 렌더링
- **TorchScript**: 빠른 모델 추론
- **메모리 관리**: 효율적인 버퍼 할당/해제
- **멀티스레딩**: 비동기 처리로 성능 향상


### 🔍 **fragments/** 폴더 - 동시 저장 로직의 핵심
이 폴더는 **동시 저장 시스템의 중심**입니다.

**주요 파일들:**
- `GalleryFragment.kt`: 갤러리 UI와 비디오 처리 로직
- `CustomPreviewFragment.kt`: 카메라 프리뷰 및 녹화 제어
- `CustomHardwarePipeline.kt`: 하드웨어 파이프라인 구현
- `SotPickerDialogFragment.kt`: SOT 초기 박스 선택 UI

**핵심 기능:**
- 듀얼 스트림 동시 녹화 (고품질 + 저품질)
- GPU 기반 실시간 처리
- 메모리 효율적인 버퍼 관리

### 🤖 **ml/** 폴더 - 오토줌 로직 구현
이 폴더는 **오토줌 시스템의 핵심**입니다.

**주요 파일들:**
- `MoveNet.kt`: MoveNet 기반 포즈 추정
- `MoveNetMultiPose.kt`: 다중 포즈 추정
- `PoseClassifier.kt`: 포즈 분류 및 분석
- `PoseDetector.kt`: 포즈 탐지 인터페이스
- `PoseNet.kt`: PoseNet 모델 구현

**핵심 기능:**
- 실시간 포즈 감지
- 포즈 분류 및 분석
- 적응적 줌 레벨 조정

### 🎬 **mp4detact/** 폴더 - 동영상 후처리 및 SOT
이 폴더는 **동영상 후처리와 SOT의 핵심**입니다.

**주요 파일들:**
- `VideoSotFacade.kt`: SOT 추적 파사드
- `SiamRpnTs.kt`: SiamRPN++ TorchScript 백엔드
- `SotLogManager.kt`: MP4 → JSONL 변환
- `VideoDetectFacade.kt`: 객체 탐지 파사드
- `YoloTflite.kt`: YOLO 객체 탐지
- `MediaCodecOesDecoder.kt`: 비디오 디코딩
- `GlLetterboxFbo.kt`: OpenGL 레터박스 렌더링

**핵심 기능:**
- MP4 비디오 디코딩
- SOT (Single Object Tracking)
- 객체 탐지 및 분석
- JSONL 형식으로 결과 출력
