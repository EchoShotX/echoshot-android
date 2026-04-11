package com.echoshot.app.network

import com.echoshot.app.auth.models.ApiResponseDto
import com.echoshot.app.auth.models.VideoCompleteUploadRequest
import com.echoshot.app.auth.models.VideoInfo
import com.echoshot.app.auth.models.VideoUploadInitiateRequest
import com.echoshot.app.auth.models.VideoUploadInitiateResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface VideoApiService {
    /**
     * 영상 업로드 시작
     * 업로드 URL과 videoId를 받아옵니다.
     */
    @POST("/videos/upload/initiate")
    suspend fun initiateUpload(
        @Body request: VideoUploadInitiateRequest
    ): ApiResponseDto<VideoUploadInitiateResponse>

    /**
     * 영상 파일 업로드 (S3 등 외부 스토리지에 직접 업로드)
     * uploadUrl은 initiateUpload에서 받은 URL을 사용합니다.
     */
    @Multipart
    @POST
    suspend fun uploadVideoFile(
        @retrofit2.http.Url uploadUrl: String,
        @Part file: MultipartBody.Part
    ): okhttp3.Response

    /**
     * 영상 업로드 완료 및 처리 시작
     */
    @POST("/videos/{videoId}/complete-upload")
    suspend fun completeUpload(
        @Path("videoId") videoId: Long,
        @Body request: VideoCompleteUploadRequest
    ): ApiResponseDto<Unit>

    /**
     * 영상 조회
     * 상태 확인 및 처리 결과를 조회합니다.
     */
    @GET("/videos/{videoId}")
    suspend fun getVideoInfo(
        @Path("videoId") videoId: Long
    ): ApiResponseDto<VideoInfo>
}

