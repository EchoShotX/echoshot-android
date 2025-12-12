package com.echoshot.app.utils

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
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
        val navBarBackground = rootView.findViewById<View>(R.id.nav_bar_background)
        
        // 기본 색상 - 검은색
        val iconColor = Color.BLACK
        navHome?.imageTintList = ColorStateList.valueOf(iconColor)
        navGallery?.imageTintList = ColorStateList.valueOf(iconColor)
        navCamera?.imageTintList = ColorStateList.valueOf(iconColor)
        
        // 촬영 모드에서 초기 상태 설정
        if (currentPage == "camera") {
            // 카메라 버튼만 보이고, 바 배경은 카메라 버튼 크기
            navGallery?.visibility = View.GONE
            navHome?.visibility = View.GONE
            navGallery?.translationX = 0f
            navHome?.translationX = 0f
            navBarBackground?.let {
                val params = it.layoutParams
                params.width = it.dpToPx(48)
                it.layoutParams = params
            }
            isExpanded = false
        } else {
            // 갤러리/홈에서는 항상 펼쳐진 상태 (화면의 95%)
            navGallery?.visibility = View.VISIBLE
            navHome?.visibility = View.VISIBLE
            navBarBackground?.let {
                val screenWidth = it.resources.displayMetrics.widthPixels
                val expandedWidth = (screenWidth * 0.95f).toInt()
                val params = it.layoutParams
                params.width = expandedWidth
                it.layoutParams = params
                
                // 버튼 위치 계산: 바 너비의 1/4 지점에 버튼 배치
                val buttonOffset = expandedWidth / 3f
                navGallery?.translationX = -buttonOffset
                navHome?.translationX = buttonOffset
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
                
                // 펼치기/접기 애니메이션
                toggleNavigationBar(navBarBackground, navGallery, navHome)
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
    }
    
    /**
     * 네비게이션 바 펼치기/접기 애니메이션
     * - 하얀색 바가 가운데서 양옆으로 펼쳐지고
     * - 갤러리(왼쪽), 홈(오른쪽) 버튼이 나타남
     */
    private fun toggleNavigationBar(
        navBarBackground: View?,
        navGallery: ImageView?,
        navHome: ImageView?
    ) {
        if (navBarBackground == null) return
        
        val collapsedWidth = navBarBackground.dpToPx(48)
        // 화면 너비의 95%로 펼쳐짐
        val screenWidth = navBarBackground.resources.displayMetrics.widthPixels
        val expandedWidth = (screenWidth * 0.95f).toInt()
        
        // 버튼이 이동할 거리 계산 (바 너비의 1/3 지점)
        val buttonOffset = expandedWidth / 3f
        
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
            
            navHome?.animate()
                ?.alpha(0f)
                ?.scaleX(0.5f)
                ?.scaleY(0.5f)
                ?.translationX(0f)
                ?.setDuration(200)
                ?.withEndAction { navHome.visibility = View.GONE }
                ?.start()
            
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
            
            // 2. 버튼들 가운데서 양끝으로 이동하면서 페이드인
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
                    .translationX(-buttonOffset)
                    .setDuration(300)
                    .setStartDelay(100)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            
            navHome?.apply {
                visibility = View.VISIBLE
                alpha = 0f
                scaleX = 0.5f
                scaleY = 0.5f
                translationX = 0f
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(buttonOffset)
                    .setDuration(300)
                    .setStartDelay(100)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            
            isExpanded = true
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
    }
}

