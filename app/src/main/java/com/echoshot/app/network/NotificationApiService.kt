package com.echoshot.app.network

import com.echoshot.app.auth.models.ApiResponseDto
import com.echoshot.app.auth.models.NotificationDto
import retrofit2.http.GET

interface NotificationApiService {
    @GET("/notifications")
    suspend fun getAllNotifications(): ApiResponseDto<List<NotificationDto>>

    @GET("/notifications/unread")
    suspend fun getUnreadNotifications(): ApiResponseDto<List<NotificationDto>>

    @retrofit2.http.PATCH("/notifications/{notificationId}/read")
    suspend fun markAsRead(
        @retrofit2.http.Path("notificationId") notificationId: Long
    ): ApiResponseDto<Any>

    @retrofit2.http.PATCH("/notifications/read-all")
    suspend fun markAllAsRead(): ApiResponseDto<Any>
}
