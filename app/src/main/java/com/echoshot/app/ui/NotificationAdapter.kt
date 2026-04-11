package com.echoshot.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.echoshot.app.R
import com.echoshot.app.auth.models.NotificationDto
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NotificationAdapter(
    private var notifications: List<NotificationDto>,
    private val onItemClick: (NotificationDto, Int) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.NotificationViewHolder>() {

    class NotificationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val unreadIndicator: View = view.findViewById(R.id.unreadIndicator)
        val title: TextView = view.findViewById(R.id.textTitle)
        val content: TextView = view.findViewById(R.id.textContent)
        val date: TextView = view.findViewById(R.id.textDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return NotificationViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        val item = notifications[position]

        holder.title.text = item.title
        holder.content.text = item.content
        
        // 읽음 상태에 따라 표시기 제어
        holder.unreadIndicator.visibility = if (item.isRead) View.GONE else View.VISIBLE

        // 날짜 포맷팅
        holder.date.text = formatTime(item.createdAt)

        // 아이템 클릭 이벤트
        holder.itemView.setOnClickListener {
            onItemClick(item, position)
        }
    }

    override fun getItemCount(): Int = notifications.size

    fun updateList(newList: List<NotificationDto>) {
        notifications = newList
        notifyDataSetChanged()
    }
    
    fun getNotifications(): List<NotificationDto> = notifications
    val currentList: List<NotificationDto> get() = notifications

    private fun formatTime(isoString: String): String {
        return try {
            // 2026-01-19T14:30:00.000Z
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            parser.timeZone = TimeZone.getTimeZone("UTC") // 서버가 UTC라고 가정
            val date = parser.parse(isoString.substringBefore(".")) 
            
            val formatter = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
            formatter.timeZone = TimeZone.getDefault()
            date?.let { formatter.format(it) } ?: isoString
        } catch (e: Exception) {
            isoString
        }
    }
}
