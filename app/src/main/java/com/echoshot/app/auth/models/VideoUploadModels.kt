package com.echoshot.app.auth.models

import com.google.gson.annotations.SerializedName

/**
 * 영상 업로드 시작 요청
 */
data class VideoUploadInitiateRequest(
    val fileName: String,
    @SerializedName("filesSizeBytes")
    val filesSizeBytes: Long,
    val contentType: String = "video/mp4",
    val processingType: String = "AI_UPSCALING"
)

/**
 * 영상 업로드 시작 응답
 */
data class VideoUploadInitiateResponse(
    val videoId: Long,
    val uploadId: String? = null,
    val uploadUrl: String,
    val s3Key: String? = null,
    val expiresAt: String? = null,
    val contentType: String? = null,
    val maxSizeBytes: Long? = null
)

/**
 * 영상 업로드 완료 요청
 */
data class VideoCompleteUploadRequest(
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val codec: String,
    val bitrate: Int,
    val frameRate: Int
)

/**
 * 영상 정보
 */
data class VideoInfo(
    val videoId: Long,
    val originalFileName: String? = null,
    val s3OriginalKey: String? = null,
    val s3ProcessedKey: String? = null,
    val s3ThumbnailKey: String? = null,
    val fileSizeBytes: Long? = null,
    val status: String, // "PENDING_UPLOAD" | "UPLOADING" | "PROCESSING" | "COMPLETED" | "FAILED"
    val processingType: String? = null,
    val metadata: VideoMetadata? = null,
    val uploadedAt: String? = null,
    val updatedAt: String? = null,
    val streamingUrl: String? = null,
    val downloadUrl: String? = null,
    val thumbnailUrl: String? = null,
    val urlExpiresAt: String? = null
)

/**
 * 비디오 메타데이터
 */
data class VideoMetadata(
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val frameRate: Int? = null
)

/**
 * AI 처리 결과
 */
data class ProcessingResult(
    val success: Boolean,
    val resultUrl: String? = null,
    val metadata: Map<String, Any>? = null
)

