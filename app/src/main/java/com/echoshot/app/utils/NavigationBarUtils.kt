package com.echoshot.app.utils

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.echoshot.app.R

/**
 * 네비게이션 바 관련 유틸리티 함수들
 */
object NavigationBarUtils {
    
    /**
     * 네비게이션 바의 클릭 이벤트를 설정합니다.
     * 
     * @param rootView 네비게이션 바가 포함된 루트 뷰
     * @param currentPage 현재 페이지 타입 ("home", "gallery", "camera", "archive", "profile")
     * @param onHomeClick 홈 클릭 시 실행할 함수
     * @param onGalleryClick 갤러리 클릭 시 실행할 함수
     * @param onCameraClick 카메라 클릭 시 실행할 함수
     * @param onArchiveClick 아카이브 클릭 시 실행할 함수
     * @param onProfileClick 프로필 클릭 시 실행할 함수
     */
    fun setupNavigationBar(
        rootView: View,
        currentPage: String,
        onHomeClick: (() -> Unit)? = null,
        onGalleryClick: (() -> Unit)? = null,
        onCameraClick: (() -> Unit)? = null,
        onArchiveClick: (() -> Unit)? = null,
        onProfileClick: (() -> Unit)? = null
    ) {
        val navHome = rootView.findViewById<ImageView>(R.id.nav_home)
        val navGallery = rootView.findViewById<ImageView>(R.id.nav_gallery)
        val navCamera = rootView.findViewById<ImageView>(R.id.nav_camera)
        val navArchive = rootView.findViewById<View>(R.id.nav_archive) // View로 변경 (아이콘 없음)
        val navProfile = rootView.findViewById<View>(R.id.nav_profile) // View로 변경 (아이콘 없음)
        
        // 기본 색상 (비활성화)
        val inactiveColor = Color.WHITE
        // 활성화 색상 (선택된 아이콘)
        val activeColor = Color.parseColor("#FF6B6B") // 빨간색 계열, 필요시 변경 가능
        
        // 모든 아이콘을 기본 색상으로 초기화 (ImageView인 경우만)
        navHome?.imageTintList = ColorStateList.valueOf(inactiveColor)
        navGallery?.imageTintList = ColorStateList.valueOf(inactiveColor)
        navCamera?.imageTintList = ColorStateList.valueOf(inactiveColor)
        // archive와 profile은 View이므로 색상 설정 불필요
        
        // 현재 페이지 활성화 표시 (ImageView인 경우만)
        when (currentPage) {
            "home" -> navHome?.imageTintList = ColorStateList.valueOf(activeColor)
            "gallery" -> navGallery?.imageTintList = ColorStateList.valueOf(activeColor)
            "camera" -> navCamera?.imageTintList = ColorStateList.valueOf(activeColor)
            // archive와 profile은 아이콘이 없으므로 활성화 표시 불필요
        }
        
        // 갤러리 클릭
        navGallery?.setOnClickListener {
            if (currentPage != "gallery") {
                onGalleryClick?.invoke()
            }
        }
        
        // 아카이브 클릭 (플레이스홀더)
        navArchive?.setOnClickListener {
            if (currentPage != "archive") {
                onArchiveClick?.invoke()
            }
        }
        
        // 카메라 클릭
        navCamera?.setOnClickListener {
            if (currentPage != "camera") {
                onCameraClick?.invoke()
            }
        }
        
        // 프로필 클릭 (플레이스홀더)
        navProfile?.setOnClickListener {
            if (currentPage != "profile") {
                onProfileClick?.invoke()
            }
        }
        
        // 홈 클릭
        navHome?.setOnClickListener {
            if (currentPage != "home") {
                onHomeClick?.invoke()
            }
        }
    }
}

/**
 * Fragment 확장 함수: 네비게이션 바 설정을 쉽게 할 수 있도록 합니다.
 */
fun Fragment.setupBottomNavigationBar(
    currentPage: String,
    onHomeClick: (() -> Unit)? = null,
    onGalleryClick: (() -> Unit)? = null,
    onCameraClick: (() -> Unit)? = null,
    onArchiveClick: (() -> Unit)? = null,
    onProfileClick: (() -> Unit)? = null
) {
    view?.let {
        NavigationBarUtils.setupNavigationBar(
            rootView = it,
            currentPage = currentPage,
            onHomeClick = onHomeClick,
            onGalleryClick = onGalleryClick,
            onCameraClick = onCameraClick,
            onArchiveClick = onArchiveClick,
            onProfileClick = onProfileClick
        )
    }
}

