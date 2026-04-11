# 단일 영상 업로드 → 인물 트래킹 직캠 생성 기능

사용자가 외부에서 가져온 **단일 영상**(줌/노줌 페어가 아닌)에서 인물을 트래킹하여 직캠 영상을 만드는 새로운 페이지를 하단 바에 추가합니다.

---

## 현재 파이프라인 분석 및 단일 영상 적용 방안

### 기존 파이프라인 (줌 + 노줌 듀얼 영상)

```
줌 MP4 ─→ YOLO11-pose 디텍션 ─→ pose_log.jsonl
                                      │
tracking_log + ts_log ─→ make_log_pipeline.py ─→ processed.json (줌 보간)
                                      │
pose_log + processed ─→ merge_pose_logs.py ─→ merged.jsonl
                                      │
노줌 MP4 + merged.jsonl ─→ FrameCropper ─→ 최종 직캠 영상
```

**핵심 차이점**: 기존은 줌 영상에서 디텍션 → 줌 좌표를 노줌 좌표로 역변환 → 노줌 영상에서 크롭

### 단일 영상 파이프라인 (새로 만들 것)

```
단일 MP4 ─→ YOLO11-pose 디텍션 ─→ pose_log.jsonl
                                      │
merge_pose_logs.py (zoom=1.0, 좌표변환 불필요)
                                      │
같은 단일 MP4 + merged.jsonl ─→ FrameCropper ─→ 최종 직캠 영상
```

**핵심**: zoom=1.0이므로 좌표 변환이 불필요, 디텍션 소스와 크롭 소스가 **동일한 영상**

---

## 재사용 가능한 파이프라인 컴포넌트

### ✅ 100% 그대로 재사용

| 컴포넌트 | 파일 | 역할 |
|---------|------|------|
| **YOLO11-pose 디텍션** | `VideoPoseDetectFacade.kt` | 단일 영상의 모든 프레임에서 인물 디텍션 |
| **포즈 로그 생성** | `PoseDetectLogManager.kt` | 디텍션 결과를 pose_log.jsonl로 저장 |
| **칼만 필터 + RTS 스무더** | `merge_pose_logs.py` 내부 함수들 | 바운딩박스 스무딩 |
| **9:16 비율 고정** | `merge_pose_logs.py` → `enforce_9x16()` | 최종 크롭 비율 |
| **FrameCropper** | `FrameCropper.kt` | OpenGL 기반 영상 크롭/인코딩 |
| **VideoPipeline** | `VideoPipeline.kt` → `processSessionFromLog()` | 크롭 오케스트레이션 |

### ⚠️ 수정/단순화 필요

| 컴포넌트 | 현재 | 단일 영상에서 |
|---------|------|-------------|
| **좌표 변환** | `center_unzoom()` (줌→노줌) | **불필요** (zoom=1.0이므로 스킵) |
| **PoseLogOrchestrator** | tracking/ts 로그 필요 | tracking/ts 없이 동작하도록 분기 |
| **merge_pose_logs.py** | processed.json(줌 보간) 필요 | 빈 processed.json 또는 pose_log만으로 동작 |

---

## 제안 구조

### 새로 만들 파일

#### [NEW] `SingleVideoFancamFragment.kt`
- 하단 바 새 탭의 메인 화면
- 갤러리에서 단일 영상 선택 (Intent picker 또는 자체 갤러리)
- 영상 미리보기, 크롭 모드(인물중심/와이드) 선택, 해상도 선택
- 처리 시작 시 진행률 다이얼로그 표시
- 처리 완료 시 결과 표시

#### [NEW] `SingleVideoOrchestrator.kt` (또는 PoseLogOrchestrator에 새 메서드 추가)
- 단일 영상 전용 파이프라인 오케스트레이터
- 기존 `PoseLogOrchestrator.makePoseLogsAndMerge()`를 분기:
  - `trackingUri = null`, `tsUri = null`로 호출하면 이미 빈 processed.json 생성하는 코드가 있음 (L117-121)
  - 즉 **현재 코드가 이미 tracking/ts 없는 케이스를 지원함!**

### 수정할 파일

#### [MODIFY] `merge_pose_logs.py`
- `merge_pose_logs()` 함수에서 processed.json이 빈 배열(`[]`)일 때:
  - 줌 좌표 변환 단계를 스킵 (zoom=1.0 처리)
  - pose_log만으로 트래킹 + 스무딩 수행
  - 이미 대부분 구현되어 있으며, zoom 관련 부분만 확인 필요

#### [MODIFY] `nav_graph.xml`
- 새 Fragment 추가

#### [MODIFY] 하단 네비게이션 메뉴
- 새 탭 아이템 추가 (예: "직캠 만들기" 아이콘)

---

## 실제 파이프라인 호출 흐름 (SingleVideoFancamFragment)

```kotlin
// 1. 사용자가 영상 선택
val videoUri: Uri = ... // 갤러리 picker에서 받은 URI

// 2. PoseLogOrchestrator 호출 (기존 코드 그대로!)
val result = PoseLogOrchestrator.makePoseLogsAndMerge(
    ctx = context,
    sessionUuid = UUID.randomUUID().toString(),
    videoUriForDetect = videoUri,  // ← 디텍션 대상 = 업로드한 영상
    trackingUri = null,             // ← 실시간 트래킹 로그 없음
    tsUri = null,                   // ← 타임스탬프 로그 없음
    filesDir = context.filesDir
)
// → 현재 코드에서 trackingUri=null이면 빈 processed.json 생성 (L117-121)
// → merge_pose_logs()가 pose_log만으로 트래킹 수행

// 3. VideoPipeline으로 크롭 (기존 코드 그대로!)
val croppedUri = VideoPipeline.processSessionFromLog(
    context = context,
    sessionId = sessionUuid,
    srcVideoUri = videoUri,         // ← 크롭 대상 = 같은 업로드한 영상
    fps = extractedFps,
    paddingFactor = 3.5f,
    logFile = result.mergedLocalFile,
    format = LogFormat.MERGED_JSONL
)
```

> [!IMPORTANT]
> **핵심 발견**: `PoseLogOrchestrator.makePoseLogsAndMerge()`는 이미 `trackingUri=null`, `tsUri=null`인 경우를 처리하고 있습니다 (빈 `processed.json` 생성). 따라서 **파이프라인 로직 수정이 거의 불필요**하고, **UI(Fragment + Navigation)만 추가하면 됩니다**.

---

## User Review Required

> [!IMPORTANT]
> 1. **하단 바 탭 이름과 아이콘**: "직캠 만들기", "FanCam", 또는 다른 이름을 원하시나요?
> 2. **인물 선택 방식**: 자동(화면 중앙 인물)만 지원할지, 첫 프레임에서 사용자가 인물을 탭해서 선택하는 UI도 필요한가요?
> 3. **기존 하단 바 구조**: 현재 하단 바에 어떤 탭들이 있는지 확인이 필요합니다. 네비게이션 그래프와 bottomNav 메뉴 XML을 확인해야 합니다.

## Open Questions

> [!WARNING]
> **`merge_pose_logs.py`에서 processed.json이 빈 배열일 때의 동작 검증**: 현재 `merge_pose_logs()` 함수가 빈 tracking 데이터로도 정상적으로 스무딩/좌표변환을 수행하는지 확인이 필요합니다. zoom=1.0으로 고정되어 `center_unzoom()`이 항등 변환이 되는지 코드 레벨에서 검증해야 합니다.

## Verification Plan

### Automated Tests
- `merge_pose_logs.py`에 빈 processed.json을 넣고 정상 출력되는지 Chaquopy로 테스트
- 단일 영상으로 전체 파이프라인 실행하여 크롭된 영상 생성 확인

### Manual Verification
- 실제 디바이스에서 외부 영상 업로드 후 직캠 생성 확인
- 인물 추적 품질 확인 (여러 인물 있는 영상에서)
