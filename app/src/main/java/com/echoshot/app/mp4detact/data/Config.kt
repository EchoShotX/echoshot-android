package com.echoshot.app.mp4detact.data

/** 하이브리드(Detector × Pose × Face) 파이프라인 기본 설정 */
data class HybridConfig(
    // 실행 주기 - 성능 최적화
    val detStride: Int = 2,             // Detector: 매 프레임 (2 → 1, NULL 프레임 감소)
    val poseStride: Int = 4,           // Pose/Face: 3프레임마다
    
    // 초기 얼굴 학습 강화
    val initialLearningFrames: Int = 30,  // 초기 30프레임에서 강화 학습
    val initialLearningInterval: Int = 3, // 3프레임마다 얼굴 학습 갱신

    val hardIoUGate: Float = 0.70f,   // IoU 70% 이상이면 따라가기
    val minSwitchHold: Int = 2,       // 2프레임 연속 실패 시만 스위치 고려
    // 매칭 게이팅 - IoU 우선
    val iouMatchThresh: Float = 0.50f,  // Det 매칭 최소 IoU 50%
    val centerGateRatio: Float = 0.05f, // 거리 게이팅 5%
    
    // 적응형 IoU 게이트 - 적당한 수준으로 조정
    val adaptiveIoUBase: Float = 0.70f,     // 기본 IoU 임계치 70%
    val adaptiveIoUFloor: Float = 0.40f,     // 최소 IoU 임계치 40%
    val adaptiveIoUDecay: Float = 0.008f,     // 연속 홀드당 0.8% 감소
    val maxLowIoUStreak: Int = 10,           // 최대 10프레임 연속 홀드 허용

    // 겹침/분리 이벤트 - IoU 기반 판단
    val overlapAmbiguous: Float = 0.15f, // 타인과 IoU>15%면 AMBIGUOUS
    val splitDropIoU: Float = 0.20f,     // 직전 대비 IoU 20% 하락 시 분리 판단
    val reidMargin: Float = 0.05f,
    
    // 겹침 감지 시 얼굴 분석 최소화 (IoU 우선)
    val overlapFaceAnalysis: Boolean = false,  // IoU로 판단하므로 얼굴 분석 축소
    
    // AMBIGUOUS 판단 기준 - IoU 차이 기반
    val ambiguousIoUGap: Float = 0.10f,  // 1등과 2등 IoU 차이 10% 미만이면 AMBIGUOUS
    
    // 얼굴 비교 트리거 - 적당한 수준으로 조정
    val smartReidMaxIoU: Float = 0.65f,       // 최고 IoU가 65% 미만이어야 얼굴 분석
    val smartReidMinIoU: Float = 0.40f,       // IoU 40% 이상 후보에만 얼굴 분석
    val smartReidMaxGap: Float = 0.20f,      // 1등과 2등 IoU 차이 20% 이하여야 얼굴 분석
    val smartReidMaxSizeRatio: Float = 0.7f, // 크기 유사도 70% 이상이어야 얼굴 분석

    // ReID (IoU 우선, 얼굴 보조) - IoU가 우선이므로 얼굴 가중치 감소
    val faceAlpha: Float = 0.30f,        // 최종 점수 = 30%얼굴 + 70%IoU (IoU 우선)
    val reidCosThresh: Float = 0.75f,    // 동일인 판정 임계치 75% (더 엄격)
    
    // ReID 호출 억제 규칙 - 적당한 수준으로 조정
    val reidHardGateIoU: Float = 0.75f,  // IoU ≥ 75%이면 ReID 호출 금지
    val reidMarginThreshold: Float = 0.15f, // cos_A - cos_B ≥ 15%이면 즉시 자기 유지

    // 로버스트네스 - 적당한 수준으로 조정
    val keepVelocityFrames: Int = 3,     // 매칭 실패시 3프레임 관성 유지
    val maxLostFrames: Int = 20,         // 20프레임 미검출 시 LOST
    
    // 속도 기반 필터링 (점프 억제) - 적당한 수준
    val maxVelocityChange: Float = 120f,     // 프레임당 최대 속도 변화 120px
    val maxJumpDistance: Float = 150f,      // 최대 점프 거리 150px
    val jumpPenaltyFactor: Float = 0.6f,    // 점프 시 60% 패널티

    // 임베딩 갱신 - 초기 학습과 정상 학습 분리
    val faceEmaAlpha: Float = 0.05f,     // E_ref ← (1-α)E_ref + α*E_now (정상 학습)
    val faceEmaAlphaInitial: Float = 0.15f, // 초기 30프레임 동안 더 적극적 학습
    
    // 얼굴 품질 검증
    val faceQualityThreshold: Float = 0.3f,  // 얼굴 임베딩 품질 임계치
    val faceSizeMin: Int = 32,               // 최소 얼굴 크기 (픽셀)
    val faceSizeMax: Int = 200,              // 최대 얼굴 크기 (픽셀)

    // 파이프라인 설정
    val inputSize: Int = 640,            // 입력 이미지 크기
    val useGpu: Boolean = true,           // GPU 사용 여부
    val warmupYoloRuns: Int = 1,         // YOLO 워밍업 횟수
    val warmupDecodeFrames: Int = 1,     // 디코딩 워밍업 프레임 수
    val decodeTimeoutUs: Long = 50_000,  // 디코딩 타임아웃
    val minInferIntervalMs: Long = 0L    // 최소 추론 간격
)
