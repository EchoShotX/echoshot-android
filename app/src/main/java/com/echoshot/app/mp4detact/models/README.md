# 📁 mp4detact/models 디렉토리 구조

## 📋 개요

인물 탐지 및 트래킹에 사용되는 모델 어댑터들을 관리하는 폴더입니다.

---

## 📂 디렉토리 구조

```
models/
├── README.md              ← 현재 파일
├── Interfaces.kt          ← 공통 인터페이스 정의
├── face/
│   └── FaceEmbedder.kt    ← MobileFaceNet 인물 선택 후 얼굴 임베딩 (ReID용)
├── pose/
│   ├── PoseMoveNetAdapter.kt   ← MoveNet 모델 인물선택
│   └── Yolo11PoseTflite.kt     ← YOLO11n-pose 모델 ⭐ 현재 사용
└── yolo/
    └── YoloTflite.kt      ← YOLOv8 모델 (고성능추적1, UI 숨김)
```

---

## 🔄 파이프라인 변경 이력

### 기존 구조 (고성능추적1 - YOLOv8 기반)

```
📹 영상
  ↓
🤖 YoloTflite.kt (YOLOv8) - 바운딩 박스만 추출
  ↓
📝 DetectLogManager → JsonLogger
  ↓
🐍 make_log_pipeline.py (zoom 보간)
  ↓
🐍 merge_offline_logs.py (로그 병합)
  ↓
🎬 VideoPipeline (크롭 영상 생성)
```

**특징:**
- 바운딩 박스(4점) 기반 트래킹
- IoU/면적 비교로 동일 인물 판단
- 팔다리 움직임에 민감 → 트래킹 불안정

### 현재 구조 (고성능추적 - YOLO11n-pose 기반) ⭐

```
📹 영상
  ↓
🤖 Yolo11PoseTflite.kt (YOLO11n-pose) - 17 keypoints + bbox
  ↓
📝 PoseDetectLogManager → PoseJsonLogger
  ↓
🐍 make_log_pipeline.py (zoom 보간)
  ↓
🐍 merge_pose_logs.py (체형 기반 트래킹 + 노이즈 제거)
  ↓
🎬 VideoPipeline (크롭 영상 생성)
```

**개선점:**
- 17개 관절점(keypoints) 기반 트래킹
- 정규화된 스켈레톤 형태 비교 → 위치 무관 동일인물 판단
- 체형 비율(어깨/엉덩이) 비교 → 더 정확한 ReID
- 상체 기반 바운딩 박스 → 팔다리 움직임 영향 최소화
- 3단계 노이즈 제거 + 2단계 스무딩

### 하이브리드 파이프라인 (인물 선택 모드)

```
📹 영상 + 👆 사용자 인물 선택
  ↓
🤖 YoloTflite.kt (YOLOv8) - 바운딩 박스
  ↓
🦴 PoseMoveNetAdapter.kt (MoveNet) - 17 keypoints
  ↓
👤 FaceEmbedder.kt (MobileFaceNet) - 얼굴 임베딩
  ↓
🔗 HybridProcessor.kt - 멀티모달 트래킹
  ↓
📝 JsonLogger → HybridLogOrchestrator
  ↓
🐍 merge_tracks_pipeline.py
  ↓
🎬 VideoPipeline (크롭 영상 생성)
```

**특징:**
- 사용자가 첫 프레임에서 추적할 인물 직접 선택
- YOLO + MoveNet + FaceNet 멀티모달 조합
- 선택된 인물만 지속 트래킹

---

## 📄 파일별 상세 설명

### `Interfaces.kt`
공통 인터페이스 정의 (Detector, Embedder 등)

### `face/FaceEmbedder.kt`
- **모델**: MobileFaceNet
- **용도**: 얼굴 임베딩 벡터 추출 (ReID)
- **상태**: 선택적 사용 (인물 선택 모드)

### `pose/Yolo11PoseTflite.kt` ⭐ 현재 메인
- **모델**: `yolo11n-pose_float16.tflite`
- **입력**: 640×640 RGB
- **출력**: [1, 56, 8400] (4 bbox + 1 conf + 51 keypoints)
- **키포인트**: 17개 (코, 눈, 귀, 어깨, 팔꿈치, 손목, 엉덩이, 무릎, 발목)
- **특징**:
  - Letterbox 스케일링 → 역변환
  - NMS 적용
  - GPU Delegate 지원

### `pose/PoseMoveNetAdapter.kt`
- **모델**: MoveNet Thunder/Lightning
- **용도**: 하이브리드 파이프라인 (인물 선택 모드)
- **사용처**: `HybridPickerDialogFragment` → `HybridProcessor`
- **특징**: 
  - 사용자가 선택한 인물을 정밀 트래킹
  - YoloTflite + PoseMoveNet + FaceEmbedder 조합
- **상태**: 활성 (하이브리드 모드 전용)

### `yolo/YoloTflite.kt`
- **모델**: YOLOv8n (`yolov8n_int8.tflite`)
- **용도**:
  1. ~~기존 고성능추적1 (UI 숨김)~~
  2. **하이브리드 파이프라인** (인물 선택 모드) ⭐ 활성
- **사용처**: `HybridPickerDialogFragment` → `HybridProcessor`
- **특징**: 바운딩 박스 탐지 후 PoseMoveNet/FaceEmbedder와 조합
- **고성능추적1 복원 방법**: 
  1. `MakeAutoDetactionFragment.kt`의 `TrackMode.HIGH1` 주석 해제
  2. `dialog_locked_thumbnail.xml`에 `btnHighSpec1` 버튼 복원

---

## 🔧 관련 파일들

| 용도 | Kotlin | Python |
|------|--------|--------|
| 고성능추적 (Pose) | `PoseLogOrchestrator.kt` | `merge_pose_logs.py` |
| 고성능추적1 (YOLOv8, 숨김) | `LogOrchestrator.kt` | `merge_offline_logs.py` |
| 하이브리드 (인물 선택) | `HybridProcessor.kt`, `HybridLogOrchestrator.kt` | `merge_tracks_pipeline.py` |
| 포즈 감지 | `VideoPoseDetectFacade.kt` | - |
| 객체 감지 | `VideoDetectFacade.kt` | - |
| 로그 작성 | `PoseJsonLogger.kt`, `JsonLogger.kt` | - |

---

## 📊 모드별 비교

| 항목         | 빠른추적             | 고성능추적 ⭐     | 하이브리드 (인물선택)       |
|--------------|--------------------|------------------|--------------------------|
| 진입점        | MakeAutoDetaction | MakeAutoDetaction | HybridPickerDialog       |
| 모델          | MoveNet(촬영중)     | YOLO11n-pose     | YOLO + MoveNet + FaceNet |
| 인물 선택     | 자동 (가장 큰)       | 자동 (체형 매칭)    | 수동 (사용자 터치)         |
| 동일인물 판단  | 없음                | 체형 형태 + 비율   |  크기,얼굴 비교(최적화안됨) |
| 노이즈 제거    |       기본          |     3단계 스파이크 |            기본          | 
| 속도          |       즉시          |      느림         |    느림                  |
| 정확도        | 낮음                 |    매우 높음 ⭐  |            높음 ⭐       |

### 레거시: 고성능추적1 (UI 숨김)
| 항목 | 값 |
|------|-----|
| 모델 | YOLOv8n |
| 출력 | bbox (4점) |
| 동일인물 판단 | IoU/면적 |
| 상태 | 코드 유지, UI 숨김 |

---

## 🗓️ 업데이트 이력

- **2025-12-13**: YOLO11n-pose 파이프라인 추가, UI에서 고성능추적1 숨김
- **초기**: YOLOv8 + MoveNet 조합으로 시작

