package com.echoshot.app.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.echoshot.app.R
import com.echoshot.app.auth.TokenManager
import com.echoshot.app.auth.models.NotificationDto
import com.echoshot.app.network.NotificationApiService
import com.echoshot.app.network.RetrofitClient
import com.echoshot.app.ui.NotificationAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class NotificationBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: NotificationAdapter
    private lateinit var tokenManager: TokenManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notification_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tokenManager = TokenManager(requireContext())
        recyclerView = view.findViewById(R.id.recyclerViewNotifications)
        emptyView = view.findViewById(R.id.emptyView)
        
        view.findViewById<View>(R.id.btnReadAll).setOnClickListener {
            markAllAsRead()
        }

        setupRecyclerView()
        fetchNotifications()
    }

    private fun setupRecyclerView() {
        adapter = NotificationAdapter(emptyList()) { item, position ->
            markAsRead(item, position)
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
    }

    private fun markAsRead(item: NotificationDto, position: Int) {
        if (item.isRead) return // 이미 읽음이면 스킵

        lifecycleScope.launch {
            try {
                val authenticatedRetrofit = RetrofitClient.createAuthenticatedClient(tokenManager)
                val notificationApi = authenticatedRetrofit.create(NotificationApiService::class.java)
                val response = notificationApi.markAsRead(item.id)
                
                if (response.isSuccess) {
                    // 로컬 리스트 업데이트 (UI 반영)
                    val newList = adapter.currentList.toMutableList()
                    val updatedItem = item.copy(isRead = true)
                    newList[position] = updatedItem
                    adapter.updateList(newList)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark as read", e)
            }
        }
    }

    private fun markAllAsRead() {
        lifecycleScope.launch {
            try {
                val authenticatedRetrofit = RetrofitClient.createAuthenticatedClient(tokenManager)
                val notificationApi = authenticatedRetrofit.create(NotificationApiService::class.java)
                val response = notificationApi.markAllAsRead()
                
                if (response.isSuccess) {
                    // 전체 리스트를 순회하며 isRead = true로 변경
                    val currentList = adapter.getNotifications() // getter 필요
                    val newList = currentList.map { it.copy(isRead = true) }
                    adapter.updateList(newList)
                    android.widget.Toast.makeText(requireContext(), "모든 알림을 읽음 처리했습니다.", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark all as read", e)
            }
        }
    }

    private fun fetchNotifications() {
        lifecycleScope.launch {
            try {
                if (!tokenManager.isLoggedIn()) {
                    showEmptyState()
                    return@launch
                }

                val authenticatedRetrofit = RetrofitClient.createAuthenticatedClient(tokenManager)
                val notificationApi = authenticatedRetrofit.create(NotificationApiService::class.java)

                val response = notificationApi.getAllNotifications()

                if (response.isSuccess && response.result != null) {
                    val list = response.result!!
                    if (list.isNotEmpty()) {
                        adapter.updateList(list)
                        recyclerView.visibility = View.VISIBLE
                        emptyView.visibility = View.GONE
                    } else {
                        showEmptyState()
                    }
                } else {
                    showEmptyState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching notifications", e)
                showEmptyState()
            }
        }
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }

    companion object {
        const val TAG = "NotificationBottomSheet"
        fun newInstance(): NotificationBottomSheetFragment {
            return NotificationBottomSheetFragment()
        }
    }
}
