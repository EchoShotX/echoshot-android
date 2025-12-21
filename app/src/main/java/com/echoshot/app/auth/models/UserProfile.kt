package com.echoshot.app.auth.models

import com.google.gson.annotations.SerializedName

/**
 * 사용자 프로필 정보
 */
data class UserProfile(
    // 이메일
    val email: String,
    // 가입 날짜 (ISO 8601 형식 문자열, 예: "2025-12-21T10:30:00")
    @SerializedName("joinedAt")
    val joinedAt: String,
    // 내 크레딧
    val credit: Int
)

