package com.echoshot.app.utils

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.echoshot.app.R

/**
 * 네비게이션 바 관련 유틸리티 함수들
 */
object NavigationBarUtils {
    
    // 촬영 모드에서 버튼 펼침 상태 추적
    private var isExpanded = false
    
    // dp -> px 변환
    private fun View.dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    
    /**
     * 네비게이션 바의 클릭 이벤트를 설정합니다.
     */
    fun setupNavigationBar(
        rootView: View,
        currentPage: String,
        onHomeClick: (() -> Unit)? = null,
        onGalleryClick: (() -> Unit)? = null,
        onCameraClick: (() -> Unit)? = null,
        onArchiveClick: (() -> Unit)? = null,
        onProfileClick: (() -> Unit)? = null,
        isRecording: (() -> Boolean)? = null
    ) {
        val navHome = rootView.findViewById<ImageView>(R.id.nav_home)
        val navGallery = rootView.findViewById<ImageView>(R.id.nav_gallery)
        val navCamera = rootView.findViewById<ImageView>(R.id.nav_camera)
        // 프로필은 이제 Container(FrameLayout)로 감싸져 있음
        val navProfileContainer = rootView.findViewById<View>(R.id.nav_profile_container)
        val navProfile = rootView.findViewById<ImageView>(R.id.nav_profile)
        val navArchive = rootView.findViewById<ImageView>(R.id.nav_archive)
        val navBarBackground = rootView.findViewById<View>(R.id.nav_bar_background)
        
        // 배포모드 체크
        val isDeploymentMode = DeploymentModeManager.isDeploymentMode()
        
        // 기본 색상 - 검은색
        val iconColor = Color.BLACK
        navHome?.imageTintList = ColorStateList.valueOf(iconColor)
        navGallery?.imageTintList = ColorStateList.valueOf(iconColor)
        navCamera?.imageTintList = ColorStateList.valueOf(iconColor)
        navProfile?.imageTintList = ColorStateList.valueOf(iconColor)
        navArchive?.imageTintList = ColorStateList.valueOf(iconColor)
        
        // 촬영 모드에서 초기 상태 설정
        if (currentPage == "camera") {
            // 카메라 버튼만 보이고, 바 배경은 카메라 버튼 크기
            navGallery?.visibility = View.GONE
            navHome?.visibility = View.GONE
            navProfileContainer?.visibility = View.GONE
            navArchive?.visibility = View.GONE
            navGallery?.translationX = 0f
            navHome?.translationX = 0f
            navProfileContainer?.translationX = 0f
            navArchive?.translationX = 0f
            navBarBackground?.let {
                val params = it.layoutParams
                params.width = it.dpToPx(48)
                it.layoutParams = params
            }
            isExpanded = false
        } else {
            // 3단 대칭 구조: 갤러리(좌) | 카메라(중) | 직캠 편집(우)
            navGallery?.visibility = View.VISIBLE
            navArchive?.visibility = View.VISIBLE
            
            // 홈과 프로필은 하단바 편입에서 제외 (영원히 숨김)
            navHome?.visibility = View.GONE
            navProfileContainer?.visibility = View.GONE
            
            navBarBackground?.let {
                val screenWidth = it.resources.displayMetrics.widthPixels
                val expandedWidth = (screenWidth * 0.95f).toInt() // 원래대로 95% 꽉 차게
                val params = it.layoutParams
                params.width = expandedWidth
                it.layoutParams = params
                
                // 버튼 위치 계산: 기존 홈과 갤러리 자리(양 끝)로 넓게 배치
                val symmetricOffset = expandedWidth * 0.4f
                navGallery?.translationX = -symmetricOffset
                navArchive?.translationX = symmetricOffset
                navHome?.translationX = 0f
                navProfileContainer?.translationX = 0f
            }
            isExpanded = true
        }
        
        // 갤러리 클릭
        navGallery?.setOnClickListener {
            if (currentPage != "gallery") {
                onGalleryClick?.invoke()
            }
        }
        
        // 카메라 클릭
        navCamera?.setOnClickListener {
            if (currentPage == "camera") {
                // 촬영 모드에서 카메라 버튼 클릭 시 버튼 펼치기/접기
                if (isRecording?.invoke() == true) {
                    // 동영상 촬영 중이면 아무 일도 하지 않음
                    return@setOnClickListener
                }
                
                
                // 펼치기/접기 애니메이션 (5등분 구조: 갤러리-업로드-프로필-홈)
                toggleNavigationBar(navBarBackground, navGallery, navArchive, navHome, navProfileContainer, isDeploymentMode)
            } else {
                // 다른 페이지에서 카메라로 이동
                onCameraClick?.invoke()
            }
        }
        
        // 홈 클릭
        navHome?.setOnClickListener {
            if (currentPage != "home") {
                onHomeClick?.invoke()
            }
        }
        
        // 팬캠 에딧 클릭 - 배포모드 여부와 관계없이 항상 활성화
        navArchive?.setOnClickListener {
            if (currentPage != "upload") {
                onArchiveClick?.invoke()
            }
        }
        
        // 프로필 클릭 - 배포모드일 때는 클릭 비활성화
        navProfile?.setOnClickListener {
            if (isDeploymentMode) {
                // 배포모드일 때는 클릭 무시
                return@setOnClickListener
            }
            if (currentPage != "profile") {
                onProfileClick?.invoke()
            }
        }
    }
    
    /**
     * 네비게이션 바를 접습니다 (강제 접기).
     * 동영상 촬영 시작 시 펼쳐진 상태에서 접기 위해 사용됩니다.
     */
    fun collapseNavigationBar(rootView: View) {
        val navBarBackground = rootView.findViewById<View>(R.id.nav_bar_background)
        val navGallery = rootView.findViewById<ImageView>(R.id.nav_gallery)
        val navArchive = rootView.findViewById<ImageView>(R.id.nav_archive)
        val navHome = rootView.findViewById<ImageView>(R.id.nav_home)
        val navProfileContainer = rootView.findViewById<View>(R.id.nav_profile_container)
        
        if (navBarBackground == null) return
        
        // 이미 접혀있으면 아무것도 하지 않음
        if (!isExpanded) return
        
        // 접기 애니메이션 실행
        val density = navBarBackground.resources.displayMetrics.density
        val collapsedWidth = (48 * density).toInt()
        val screenWidth = navBarBackground.resources.displayMetrics.widthPixels
        val expandedWidth = (screenWidth * 0.95f).toInt()
        
        // 배포모드 확인
        val isDeploymentMode = DeploymentModeManager.isDeploymentMode()
        
        // 접기 애니메이션
        navGallery?.animate()
            ?.alpha(0f)
            ?.scaleX(0.5f)
            ?.scaleY(0.5f)
            ?.translationX(0f)
            ?.setDuration(200)
            ?.withEndAction { navGallery.visibility = View.GONE }
            ?.start()
        
        // 팬캠 에딧 버튼 항상 애니메이션
        navArchive?.animate()
            ?.alpha(0f)
            ?.scaleX(0.5f)
            ?.scaleY(0.5f)
            ?.translationX(0f)
            ?.setDuration(200)
            ?.withEndAction { navArchive.visibility = View.GONE }
            ?.start()
        
        navHome?.animate()
            ?.alpha(0f)
            ?.scaleX(0.5f)
            ?.scaleY(0.5f)
            ?.translationX(0f)
            ?.setDuration(200)
            ?.withEndAction { navHome.visibility = View.GONE }
            ?.start()
        
        if (!isDeploymentMode) {
            navProfileContainer?.animate()
                ?.alpha(0f)
                ?.scaleX(0.5f)
                ?.scaleY(0.5f)
                ?.translationX(0f)
                ?.setDuration(200)
                ?.withEndAction { navProfileContainer.visibility = View.GONE }
                ?.start()
        } else {
            navProfileContainer?.visibility = View.GONE
        }
        
        // 바 배경 줄이기
        ValueAnimator.ofInt(expandedWidth, collapsedWidth).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val params = navBarBackground.layoutParams
                params.width = animator.animatedValue as Int
                navBarBackground.layoutParams = params
            }
            startDelay = 50
            start()
        }
        
        isExpanded = false
    }
    
    /**
     * 네비게이션 바 펼치기/접기 애니메이션
     * - 하얀색 바가 가운데서 양옆으로 펼쳐지고
     * - 5등분 구조: 갤러리(-2/5) | 업로드(-1/5) | 카메라(0) | 프로필(+1/5) | 홈(+2/5)
     * - 배포모드일 때는 프로필 버튼 숨김
     */
    private fun toggleNavigationBar(
        navBarBackground: View?,
        navGallery: ImageView?,
        navArchive: ImageView?,
        navHome: ImageView?,
        navProfileContainer: View?,
        isDeploymentMode: Boolean = false
    ) {
        if (navBarBackground == null) return
        
        val collapsedWidth = navBarBackground.dpToPx(48)
        // 화면 너비의 95%로 펼쳐짐 (원래대로 꽉 차게)
        val screenWidth = navBarBackground.resources.displayMetrics.widthPixels
        val expandedWidth = (screenWidth * 0.95f).toInt()
        
        // 대칭 확산 거리 계산 (기존의 양 끝 자리인 0.4 사용)
        val symmetricOffset = expandedWidth * 0.4f
        
        if (isExpanded) {
            // 접기 애니메이션
            // 1. 버튼들 가운데로 이동하면서 페이드아웃
            navGallery?.animate()
                ?.alpha(0f)
                ?.scaleX(0.5f)
                ?.scaleY(0.5f)
                ?.translationX(0f)
                ?.setDuration(200)
                ?.withEndAction { navGallery.visibility = View.GONE }
                ?.start()
            
            navArchive?.animate()
                ?.alpha(0f)
                ?.scaleX(0.5f)
                ?.scaleY(0.5f)
                ?.translationX(0f)
                ?.setDuration(200)
                ?.withEndAction { navArchive.visibility = View.GONE }
                ?.start()
            
            navHome?.visibility = View.GONE
            navProfileContainer?.visibility = View.GONE
            
            // 2. 바 배경 줄이기
            ValueAnimator.ofInt(expandedWidth, collapsedWidth).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val params = navBarBackground.layoutParams
                    params.width = animator.animatedValue as Int
                    navBarBackground.layoutParams = params
                }
                startDelay = 50
                start()
            }
            
            isExpanded = false
        } else {
            // 펼치기 애니메이션
            // 1. 바 배경 늘리기
            ValueAnimator.ofInt(collapsedWidth, expandedWidth).apply {
                duration = 350
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val params = navBarBackground.layoutParams
                    params.width = animator.animatedValue as Int
                    navBarBackground.layoutParams = params
                }
                start()
            }
            
            // 2. 버튼들 가운데서 좌우 대칭으로 페이드인
            navGallery?.apply {
                visibility = View.VISIBLE
                alpha = 0f
                scaleX = 0.5f
                scaleY = 0.5f
                translationX = 0f
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(-symmetricOffset)
                    .setDuration(300)
                    .setStartDelay(100)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            
            navArchive?.apply {
                visibility = View.VISIBLE
                alpha = 0f
                scaleX = 0.5f
                scaleY = 0.5f
                translationX = 0f
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(symmetricOffset)
                    .setDuration(300)
                    .setStartDelay(100)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            
            navHome?.visibility = View.GONE
            navProfileContainer?.visibility = View.GONE
            
            isExpanded = true
        }
    }
    
    /**
     * 알림 배지 초기화: hasUnreadNotification 상태에 따라 배지를 표시합니다.
     */
    fun initializeBadge(rootView: View) {
        val badge = rootView.findViewById<View>(R.id.nav_profile_badge)
        if (badge != null) {
            val hasUnread = com.echoshot.app.repository.NotificationRepository.hasUnreadNotification
            badge.visibility = if (hasUnread) View.VISIBLE else View.GONE
        }
    }
    
    /**
     * 알림 배지 표시
     */
    fun showBadge(rootView: View) {
        val badge = rootView.findViewById<View>(R.id.nav_profile_badge)
        badge?.visibility = View.VISIBLE
    }
    
    /**
     * 알림 배지 숨김
     */
    fun hideBadge(rootView: View) {
        val badge = rootView.findViewById<View>(R.id.nav_profile_badge)
        badge?.visibility = View.GONE
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
    onProfileClick: (() -> Unit)? = null,
    isRecording: (() -> Boolean)? = null
) {
    view?.let {
        NavigationBarUtils.setupNavigationBar(
            rootView = it,
            currentPage = currentPage,
            onHomeClick = onHomeClick,
            onGalleryClick = onGalleryClick,
            onCameraClick = onCameraClick,
            onArchiveClick = onArchiveClick,
            onProfileClick = onProfileClick,
            isRecording = isRecording
        )
        
        // 배지 초기화
        NavigationBarUtils.initializeBadge(it)
    }
}

/**
 * 알림 배지 관련 확장 함수들
 */
object NotificationBadgeHelper {
    /**
     * 초기화 시 배지 상태 확인 및 업데이트
     */
    fun initializeBadge(rootView: View) {
        NavigationBarUtils.initializeBadge(rootView)
    }
    
    /**
     * 배지 표시
     */
    fun showBadge(rootView: View) {
        NavigationBarUtils.showBadge(rootView)
    }
    
    /**
     * 배지 숨김
     */
    fun hideBadge(rootView: View) {
        NavigationBarUtils.hideBadge(rootView)
    }
}
