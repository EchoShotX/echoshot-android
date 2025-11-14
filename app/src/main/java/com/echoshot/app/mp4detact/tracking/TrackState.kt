package com.echoshot.app.mp4detact.tracking

/** 단일 타깃 추적 상태 */
enum class TrackState {
    /** 정상 추적 */
    TRACKING,
    /** 타인과 심하게 겹침(분리 대기) */
    AMBIGUOUS,
    /** 분리 직후 동일인 재선택(ReID) 수행 중 */
    REID,
    /** 타깃 유실 */
    LOST
}
