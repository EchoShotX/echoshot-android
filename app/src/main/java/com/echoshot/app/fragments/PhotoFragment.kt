package com.echoshot.app.fragments

import android.util.Rational
import androidx.camera.core.ViewPort
import androidx.camera.core.UseCaseGroup
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.Gravity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.echoshot.app.R
import com.echoshot.app.utils.DeploymentModeManager
import com.echoshot.app.utils.setupBottomNavigationBar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.echoshot.app.ui.ZoomRulerAdapter
import androidx.navigation.fragment.navArgs
import java.io.File
import java.io.IOException

class PhotoFragment : Fragment() {
    // 터치 링 뷰
    private class FocusRingView(context: android.content.Context) : View(context) {
        var cx = 0f; var cy = 0f; var radius = 70f
        private val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE
        }
        private val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.BLACK; alpha = 80
        }
        fun showAt(x: Float, y: Float) {
            cx = x; cy = y; alpha = 1f; scaleX = 1.2f; scaleY = 1.2f
            visibility = View.VISIBLE
            animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            removeCallbacks(hideRun); postDelayed(hideRun, 1200)
            invalidate()
        }
        private val hideRun = Runnable {
            animate().alpha(0f).setDuration(160).withEndAction { visibility = GONE }.start()
        }
        override fun onDraw(c: android.graphics.Canvas) { c.drawCircle(cx, cy, radius, p2); c.drawCircle(cx, cy, radius, p1) }
    }
    private val navArgs by navArgs<PhotoFragmentArgs>()
    private lateinit var viewFinder: PreviewView
    private lateinit var imageCapture: ImageCapture
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraMinZoom = 1.0f
    private var cameraMaxZoom = 10.0f

    private var zoomAdapter: ZoomRulerAdapter? = null
    private var zoomRuler: RecyclerView? = null

    private lateinit var cameraExecutor: ExecutorService

    private var initDone = false   // 초기 배율/룰러 세팅 끝나기 전까지 스크롤 무시
    private var appliedInitialZoom = false
    // Pinch-to-zoom
    private lateinit var scaleDetector: ScaleGestureDetector
    private var isScaling = false
    private var pinchStartZoom = 1.0f
    private var accumulatedScale = 1.0f
    private val PINCH_POWER = 1.0f

    // EV overlay
    private lateinit var overlayContainer: FrameLayout
    private lateinit var focusRing: FocusRingView
    private lateinit var evBarContainer: LinearLayout
    private lateinit var evSeek: SeekBar
    private var evHideRunnable: Runnable? = null
    private val LONG_PRESS_MS = 200L
    private var longPressRunnable: Runnable? = null
    private var longPressFired = false
    private var evDragging = false
    private var evStartX = 0f
    private var evStartComp = 0
    private val EV_PIXELS_PER_STEP = 20f  // 감도 2배 증가 (값이 작을수록 감도 높음)
    private var evLocked = false  // 수동 EV 조절 후 자동 변경 방지
    private fun dp(v: Int) = (resources.displayMetrics.density * v + 0.5f).toInt()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_photo_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 네비게이션 바 설정
        setupBottomNavigationBar(
            currentPage = "camera",
            onHomeClick = {
                // 사진 모드에서 홈으로 이동
                val action = PhotoFragmentDirections.actionPhotoFragmentToHomeFragment()
                findNavController().navigate(action)
            },
            onGalleryClick = {
                // 사진 모드에서 갤러리로 이동
                openGallery()
            },
            onArchiveClick = {
                val action = PhotoFragmentDirections.actionPhotoFragmentToFancamEditFragment()
                findNavController().navigate(action)
            },
            onProfileClick = {
                // 배포모드일 때는 프로필로 이동하지 않음
                if (DeploymentModeManager.isDeploymentMode()) {
                    return@setupBottomNavigationBar
                }
                // 사진 모드에서 프로필로 이동
                val action = PhotoFragmentDirections.actionPhotoFragmentToProfileFragment()
                findNavController().navigate(action)
            },
            isRecording = { false } // 사진 모드는 녹화 기능 없음
        )

        updateGalleryThumbnail()

        viewFinder = view.findViewById(R.id.view_finder)

        // Overlay container and EV bar (hidden by default)
        (view as? ViewGroup)?.let { root ->
            overlayContainer = FrameLayout(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isClickable = false
            }
            root.addView(overlayContainer)

            focusRing = FocusRingView(requireContext()).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            overlayContainer.addView(focusRing)

            evBarContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 0)
                background = null
                gravity = Gravity.CENTER_VERTICAL
                alpha = 0f; visibility = View.GONE
            }
            evSeek = SeekBar(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(73), dp(14)).apply { leftMargin = dp(10) }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val es = camera?.cameraInfo?.exposureState ?: return
                        val target = es.exposureCompensationRange.lower + p
                        if (es.exposureCompensationRange.contains(target)) {
                            camera?.cameraControl?.setExposureCompensationIndex(target)
                        }
                        scheduleHideEvBar(2000)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { scheduleHideEvBar(2000) }
                })
                val trackHeight = dp(2)
                val thumbWidth = dp(2)
                val thumbHeight = dp(14)
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    alpha = 140
                    cornerRadius = dp(1).toFloat()
                }
                val prog = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    cornerRadius = dp(1).toFloat()
                }
                val layer = LayerDrawable(arrayOf(bg, prog)).apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                }
                splitTrack = false
                progressDrawable = layer
                setPadding(0, dp(6), 0, dp(6))
                val thumbDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    setSize(thumbWidth, thumbHeight)
                }
                thumb = thumbDrawable
                minHeight = trackHeight + dp(12)
            }
            evBarContainer.addView(evSeek)
            overlayContainer.addView(
                evBarContainer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.TOP or Gravity.START }
            )
        }

        // Pinch zoom detector
        scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                pinchStartZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
                accumulatedScale = 1.0f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val step = Math.pow(detector.scaleFactor.toDouble(), PINCH_POWER.toDouble()).toFloat()
                accumulatedScale *= step
                val state = camera?.cameraInfo?.zoomState?.value
                val minZ = state?.minZoomRatio ?: 1.0f
                val maxZ = state?.maxZoomRatio ?: 10.0f
                val newZoom = (pinchStartZoom * accumulatedScale).coerceIn(minZ, maxZ)
                camera?.cameraControl?.setZoomRatio(newZoom)

                // 줌 HUD 업데이트
                view?.findViewById<TextView>(R.id.zoom_level_text)?.text = String.format(java.util.Locale.KOREA, "%.1fx", newZoom)

                // 하단 줌 룰러도 동기화 (초기화 후에만)
                if (initDone) {
                    val adapter = zoomAdapter
                    val rv = zoomRuler
                    if (adapter != null && rv != null) {
                        val pos = adapter.zoomToPosition(newZoom)
                        val lm = rv.layoutManager as? LinearLayoutManager
                        if (lm != null) {
                            val rvWidth = rv.width
                            val itemWidth = (resources.displayMetrics.density * 15).toInt()  // 15dp
                            val offset = (rvWidth / 2) - (itemWidth / 2)
                            lm.scrollToPositionWithOffset(pos, offset)
                        }
                    }
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

        // Touch to show EV bar on long-press + 터치 링 표시
        viewFinder.setOnTouchListener { v, ev ->
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isScaling && ev.pointerCount == 1) {
                        // 이전에 수동 EV 조절을 했다면, 다음 터치에서 즉시 자동으로 EV 설정
                        if (evLocked) {
                            evLocked = false
                            val loc = IntArray(2)
                            overlayContainer.getLocationOnScreen(loc)
                            val ox = ev.rawX - loc[0]
                            val oy = ev.rawY - loc[1]
                            focusRing.showAt(ox, oy)
                            showEvBarAt(ev.x, ev.y)
                            
                            // 터치 위치에 맞게 EV 자동 설정
                            val es = camera?.cameraInfo?.exposureState ?: return@setOnTouchListener true
                            val range = es.exposureCompensationRange
                            val t = (ev.x / viewFinder.width.toFloat()).coerceIn(0f, 1f)
                            val target = (range.lower + t * (range.upper - range.lower)).toInt()
                            camera?.cameraControl?.setExposureCompensationIndex(target.coerceIn(range.lower, range.upper))
                            syncEvSliderFromCamera()
                            scheduleHideEvBar(2000)
                            return@setOnTouchListener true
                        }
                        
                        longPressFired = false
                        longPressRunnable?.let { v.removeCallbacks(it) }
                        longPressRunnable = Runnable {
                            longPressFired = true
                            val loc = IntArray(2)
                            overlayContainer.getLocationOnScreen(loc)
                            val ox = ev.rawX - loc[0]
                            val oy = ev.rawY - loc[1]
                            focusRing.showAt(ox, oy)
                            showEvBarAt(ev.x, ev.y)
                            
                            // EV 드래그 초기화
                            val es = camera?.cameraInfo?.exposureState ?: return@Runnable
                            evStartComp = es.exposureCompensationIndex
                            evStartX = ev.x
                            evDragging = true
                            
                            // 탭 위치 기준으로 EV 즉시 설정
                            val range = es.exposureCompensationRange
                            val t = (ev.x / viewFinder.width.toFloat()).coerceIn(0f, 1f)
                            val target = (range.lower + t * (range.upper - range.lower)).toInt()
                            camera?.cameraControl?.setExposureCompensationIndex(target.coerceIn(range.lower, range.upper))
                            syncEvSliderFromCamera()
                            scheduleHideEvBar(2000)
                        }.also { v.postDelayed(it, LONG_PRESS_MS) }
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    longPressRunnable?.let { v.removeCallbacks(it) }
                    longPressRunnable = null
                    longPressFired = false
                    evDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount > 1) {
                        longPressRunnable?.let { v.removeCallbacks(it) }
                        longPressRunnable = null
                        longPressFired = false
                        evDragging = false
                    }
                    
                    if (!isScaling && evDragging && longPressFired) {
                        val es = camera?.cameraInfo?.exposureState ?: return@setOnTouchListener true
                        val deltaPx = ev.x - evStartX
                        val steps = (deltaPx / EV_PIXELS_PER_STEP).toInt()
                        val range = es.exposureCompensationRange
                        val target = (evStartComp + steps).coerceIn(range.lower, range.upper)
                        camera?.cameraControl?.setExposureCompensationIndex(target)
                        syncEvSliderFromCamera()
                        scheduleHideEvBar(2000)
                        // 수동 EV 조절 플래그 설정
                        evLocked = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { v.removeCallbacks(it) }
                    longPressRunnable = null
                    evDragging = false
                    scheduleHideEvBar()
                }
            }
            true
        }

        // 버튼
        val captureButton: View = view.findViewById(R.id.capture_button)
        val switchCameraButton: View = view.findViewById(R.id.switch_camera_button)

        captureButton.setOnClickListener {
            com.echoshot.app.AnalyticsTracker.log(requireContext(), "capture_button_click", "media_type" to "photo", "camera_facing" to "back")
            takePhoto()
        }
        switchCameraButton.setOnClickListener {
            // 전면 카메라 프래그먼트로 이동
            val a = navArgs
            val action = PhotoFragmentDirections.actionPhotoFragmentToPhotoFrontFragment(
                a.cameraId,
                a.width,
                a.height,
                a.fps,
                a.dynamicRange,
                a.colorSpace,
                a.previewStabilization,
                a.useMediaRecorder,
                a.videoCodec,
                a.filterOn,
                a.transfer,
                a.useHardware,
                a.pipelineMode
            ).apply {
                // forcePhysicalId는 nullable이므로 setter로 설정
                a.forcePhysicalId?.let { setForcePhysicalId(it) }
            }
            findNavController().navigate(action)
        }

        // ───── 줌 룰러 UI (어댑터는 "처음엔" 붙이지 않음)
        zoomRuler = view.findViewById(R.id.zoom_ruler)
        val zoomHud = view.findViewById<TextView?>(R.id.zoom_level_text)

        val lm = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        zoomRuler!!.layoutManager = lm

        // CenterSnapHelper 제거 - 연속적인 줌 업데이트를 위해 자유로운 스크롤 허용
        // EdgeCenterSpacingDecoration 제거 - 패딩으로 통일하여 중복 계산 방지

        val itemWidthDp = 15  // 눈금 간격 줄임 (20 -> 15)

        // 실시간 업데이트 - throttle 제거하여 최대한 부드럽게
        var lastZoomText = ""
        fun maybeUpdateZoom(z: Float) {
            // 카메라 범위로 클램프 (minZ >= 1.0인 경우 보호)
            val clampedZ = z.coerceIn(cameraMinZoom, cameraMaxZoom)
            // 줌 값은 항상 즉시 업데이트
            camera?.cameraControl?.setZoomRatio(clampedZ)
            
            // 텍스트는 소수점 첫째자리만 표시하되, 변경될 때만 업데이트하여 부드럽게
            val newText = String.format(Locale.KOREA, "%.1fx", clampedZ)
            if (newText != lastZoomText) {
                zoomHud?.text = newText
                lastZoomText = newText
            }
        }

        view.findViewById<ImageButton>(R.id.gallery_button)?.setOnClickListener {
            com.echoshot.app.AnalyticsTracker.log(requireContext(), "gallery_open", "source_screen" to "photo_back")
            openGallery()
        }

        // 마지막으로 계산된 줌 값을 저장 (연속적인 줌 업데이트용)
        var lastCalculatedZoom = 1.0f
        
        zoomRuler!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (!initDone) return
                val adapter = zoomAdapter ?: return
                val layout = rv.layoutManager as? LinearLayoutManager ?: return

                val itemWidth = (resources.displayMetrics.density * 15).toFloat() // 15dp
                
                // 패딩이 적용된 상태에서 스크롤 0은 중앙 아이템 인덱스 0을 의미합니다.
                val scrollOffset = rv.computeHorizontalScrollOffset().toFloat()
                val posF = scrollOffset / itemWidth

                // positionToZoom 내부에서 minZoom에 따라 0.6x인지 1.0x인지 알아서 계산함

                val z = adapter.positionToZoom(posF).coerceIn(cameraMinZoom, cameraMaxZoom)
                lastCalculatedZoom = z
                maybeUpdateZoom(z)
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                super.onScrollStateChanged(rv, newState)
                if (!initDone) return
                
                // 스크롤이 멈췄을 때 마지막으로 계산된 연속적인 줌 값 사용
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    camera?.cameraControl?.setZoomRatio(lastCalculatedZoom)
                    zoomHud?.text = String.format(Locale.KOREA, "%.1fx", lastCalculatedZoom)
                }
            }
        })

        // 사진 → 동영상 버튼
        view.findViewById<View>(R.id.btn_mode_video)?.setOnClickListener {
            val a = navArgs
            val back = PhotoFragmentDirections
                .actionPhotoFragmentToCustomPreviewFragment(
                    a.cameraId,
                    a.width,
                    a.height,
                    a.fps,
                    a.dynamicRange,
                    a.colorSpace,
                    a.previewStabilization,
                    a.useMediaRecorder,
                    a.videoCodec,
                    a.filterOn,
                    a.transfer,
                    a.useHardware,
                    a.pipelineMode
                )
            findNavController().navigate(back)
        }

        // 권한 체크 후 카메라 시작
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        // onViewCreated 끝부분 어딘가(미리 잡아도 됨)
        viewFinder.scaleType = PreviewView.ScaleType.FIT_START

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onResume() {
        super.onResume()
        // 앱을 나갔다가 돌아올 때 줌을 1.0으로 리셋하고 슬라이더도 1.0 위치로 이동
        resetZoomToDefault()
    }

    /**
     * 줌을 1.0으로 리셋하고 슬라이더도 1.0 위치(centerIndex)로 이동
     * 정밀한 중앙 정렬을 위해 2단계 미세 조정 로직 포함
     */
    private fun resetZoomToDefault() {
        val rv = zoomRuler ?: return
        val adapter = zoomAdapter ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val isMinZoomOne = cameraMinZoom >= 1.0f

        if (isMinZoomOne) {
            // 패딩 덕분에 0번이 정중앙
            lm.scrollToPositionWithOffset(0, 0)
        } else {
            // 0.6x 환경에서 1.0x(centerIndex)를 중앙으로
            lm.scrollToPositionWithOffset(adapter.centerIndex, 0)
        }

        val targetZoom = if (isMinZoomOne) cameraMinZoom else 1.0f
        camera?.cameraControl?.setZoomRatio(targetZoom)
        view?.findViewById<android.widget.TextView>(R.id.zoom_level_text)?.text = 
            String.format(Locale.KOREA, "%.1fx", targetZoom)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val rotation = viewFinder.display.rotation
            val aspect = AspectRatio.RATIO_16_9

            // Preview: 반드시 바인딩 전에 Provider 등록
            val preview = Preview.Builder()
                .setTargetAspectRatio(aspect)
                .setTargetRotation(rotation)
                .build()
            preview.setSurfaceProvider(viewFinder.surfaceProvider)

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(aspect)
                .setTargetRotation(rotation)
                .build()


            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, cameraSelector, useCaseGroup)

                val zoomHud = view?.findViewById<TextView>(R.id.zoom_level_text)

                // zoomState observe로 줌 범위 및 상태 관리
                appliedInitialZoom = false
                initDone = false
                
                // 카메라 바인딩 직후 즉시 초기 줌 설정 시도 (zoomState가 아직 없으므로 일단 1.0으로 시도)
                // 실제 값은 zoomState observe에서 설정됨
                camera!!.cameraControl.setZoomRatio(1.0f)
                zoomHud?.text = "1.0x"
                
                camera!!.cameraInfo.zoomState.observe(viewLifecycleOwner) { state ->
                    if (state != null && !appliedInitialZoom) {
                        val minZ = state.minZoomRatio
                        val maxZ = state.maxZoomRatio
                        
                        // 카메라 줌 범위 저장 (스크롤 리스너에서 사용)
                        cameraMinZoom = minZ
                        cameraMaxZoom = maxZ
                        
                        // 어댑터 생성 및 설정
                        val itemWidthDp = 15  // 눈금 간격 줄임
                        // minZ가 1.0 이상이면 왼쪽 눈금을 생성하지 않음 (스크롤 불가능하게)
                        val minLeftTicks = if (minZ >= 1.0f) 0 else 5
                        // minZ >= 1.0인 경우 midZoom을 minZ로 설정하여 정확한 중앙 정렬 보장
                        val midZoom = if (minZ >= 1.0f) minZ else 1.0f
                        // targetZoom도 midZoom에 맞춤 (minZ >= 1.0인 경우 minZ로 설정)
                        val targetZoom = midZoom.coerceIn(minZ, maxZ)
                        val newAdapter = ZoomRulerAdapter(
                            minZoom = minZ,
                            midZoom = midZoom,
                            maxZoom = maxZ,
                            ticksPerLogUnit = 25,  // 눈금 수 줄여서 드래그 민감도 높임
                            itemWidthDp = itemWidthDp,
                            minLeftTicks = minLeftTicks,      // 최소 왼쪽 눈금 수 (minZ >= 1.0이면 0)
                            minRightTicks = 5      // 최소 오른쪽 눈금 수
                        )
                        zoomAdapter = newAdapter
                        zoomRuler?.adapter = newAdapter

                        val isMinZoomOne = minZ >= 1.0f // 1.0x부터 시작하는 렌즈 여부
                        
                        zoomRuler?.post {
                            val rv = zoomRuler ?: return@post
                            val lm = rv.layoutManager as? LinearLayoutManager ?: return@post
                            
                            // 중요: ruler.width가 0보다 큰지 확인 (측정 완료 확인)
                            if (rv.width <= 0) {
                                // 줌은 먼저 설정
                                camera!!.cameraControl.setZoomRatio(targetZoom)
                                zoomHud?.text = String.format(Locale.KOREA, "%.1fx", targetZoom)
                                return@post
                            }
                            
                            val itemWidth = (resources.displayMetrics.density * itemWidthDp).toInt()

                            // [핵심] 0.6x든 1.0x든 상관없이 중앙 패딩 설정
                            val halfPadding = (rv.width / 2) - (itemWidth / 2)
                            rv.setPadding(halfPadding, 0, halfPadding, 0)
                            rv.clipToPadding = false

                            if (isMinZoomOne) {
                                // 1.0x가 최소면 0번(1.0x)으로 이동
                                lm.scrollToPositionWithOffset(0, 0)
                            } else {
                                // 0.6x가 최소면 centerIndex(1.0x)로 이동
                                lm.scrollToPositionWithOffset(newAdapter.centerIndex, 0)
                            }
                            
                            // 줌도 확실히 설정
                            camera!!.cameraControl.setZoomRatio(targetZoom)
                            zoomHud?.text = String.format(Locale.KOREA, "%.1fx", targetZoom)
                        }

                        appliedInitialZoom = true
                        initDone = true
                        syncEvSliderFromCamera()
                    } else if (state != null && appliedInitialZoom) {
                        // 초기화 후에는 줌 값만 업데이트 (UI 동기화)
                        val currentZoom = state.zoomRatio
                        zoomHud?.text = String.format(Locale.KOREA, "%.1fx", currentZoom)
                    }
                }

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "카메라 실행 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun scheduleHideEvBar(delayMs: Long = 1400L) {
        evHideRunnable?.let { evBarContainer.removeCallbacks(it) }
        evHideRunnable = Runnable {
            evBarContainer.animate().alpha(0f).setDuration(140)
                .withEndAction { evBarContainer.visibility = View.GONE }.start()
        }
        evBarContainer.postDelayed(evHideRunnable!!, delayMs)
    }

    private fun showEvBarAt(x: Float, y: Float) {
        syncEvSliderFromCamera()
        val lp = evBarContainer.layoutParams as FrameLayout.LayoutParams
        val half = if (evSeek.width > 0) evSeek.width / 2f else dp(73) / 2f
        lp.leftMargin = (x - half).toInt().coerceAtLeast(0)
        lp.topMargin = (y + dp(20)).toInt().coerceAtLeast(0)
        evBarContainer.layoutParams = lp
        evBarContainer.visibility = View.VISIBLE
        evBarContainer.alpha = 1f
        scheduleHideEvBar(2000)
    }

    private fun syncEvSliderFromCamera() {
        val es = camera?.cameraInfo?.exposureState ?: return
        val range = es.exposureCompensationRange
        val idx = es.exposureCompensationIndex
        val maxSteps = range.upper - range.lower
        evSeek.max = maxSteps
        evSeek.progress = (idx - range.lower).coerceIn(0, maxSteps)
        evSeek.isEnabled = maxSteps > 0
    }

    private fun updateGalleryThumbnail() {
        val btn = view?.findViewById<ImageButton>(R.id.gallery_button) ?: return

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "EchoShot"
        )
        val videoFiles = dir.listFiles { f -> f.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: run {
                btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
                btn.setImageResource(R.drawable.ic_photo_gallery)
                return
            }

        val latest = videoFiles.firstOrNull() ?: run {
            btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
            btn.setImageResource(R.drawable.ic_photo_gallery)
            return
        }

        val targetPx = 300
        val thumb = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: File + Size
                ThumbnailUtils.createVideoThumbnail(
                    latest,
                    Size(targetPx, targetPx),
                    null
                )
            } else {
                // 이하: 경로 + 비디오 전용 MINI_KIND
                ThumbnailUtils.createVideoThumbnail(
                    latest.absolutePath,
                    MediaStore.Video.Thumbnails.MINI_KIND
                )
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }

        if (thumb == null) {
            btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
            btn.setImageResource(R.drawable.ic_photo_gallery)
        } else {
            btn.scaleType = ImageView.ScaleType.CENTER_CROP
            btn.setImageBitmap(thumb)
        }
    }

    private fun openGallery() {
        val a = navArgs
        val action = PhotoFragmentDirections.actionPhotoFragmentToGalleryFragment(
            a.cameraId,
            a.width,
            a.height,
            a.fps,
            a.dynamicRange,
            a.colorSpace,
            a.previewStabilization,
            a.useMediaRecorder,
            a.videoCodec,
            a.filterOn,
            a.transfer,
            a.useHardware,
            a.pipelineMode
        ).apply {
            // 갤러리 쪽에 확장 탭 등 옵션이 있다면 여기서 세팅
            // startBasic = false
        }
        findNavController().navigate(action)
    }


    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/EchoShot")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            requireContext().contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(requireContext(), "저장 실패: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    com.echoshot.app.AnalyticsTracker.log(requireContext(), "capture_complete", "media_type" to "photo", "camera_facing" to "back")
                    Toast.makeText(requireContext(), getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            requireContext(), it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
