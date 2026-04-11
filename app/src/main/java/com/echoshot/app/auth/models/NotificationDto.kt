package com.echoshot.app.auth.models

import com.google.gson.annotations.SerializedName

data class NotificationDto(
    val id: Long,
    val type: String,
    val category: String?,
    val title: String,
    val content: String,
    val isRead: Boolean,
    val status: String,
    val retryCount: Int,
    val videoId: Long?,
    val creditHistoryId: Long?,
    val createdAt: String

//    val notificationId: Long,
//   val memberId: Long,
//   val delivered: Boolean,
//   val message: String,

)
