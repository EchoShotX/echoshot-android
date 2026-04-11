package com.echoshot.app.utils

/**
 * 배포모드 설정을 관리하는 유틸리티 클래스
 * 배포모드를 활성화하려면 아래 DEPLOYMENT_MODE를 true로 변경하세요.
 */
object DeploymentModeManager {
    /**
     * 배포모드 활성화 여부
     * true: 프로필 페이지 숨김
     * false: 프로필 페이지 표시
     */
    private const val DEPLOYMENT_MODE = false
    
    /**
     * 배포모드 상태를 반환합니다.
     */
    fun isDeploymentMode(): Boolean = DEPLOYMENT_MODE
}

