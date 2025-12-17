package com.echoshot.app.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceProfiles
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.os.ConditionVariable
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.view.Gravity
import android.graphics.Color
import android.graphics.Paint
import android.util.Range
import android.util.Rational
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.echoshot.app.utils.setupBottomNavigationBar
import com.example.android.camera.utils.getPreviewOutputSize
import com.echoshot.app.BuildConfig
import com.echoshot.app.CameraActivity
import com.echoshot.app.EncoderWrapper
import com.echoshot.app.R
import com.echoshot.app.databinding.FragmentCustomFrontPreviewBinding
import com.echoshot.app.databinding.FragmentPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CustomFrontPreviewFragment : Fragment() {

    private class HandlerExecutor(handler: Handler) : Executor {
        private val mHandler = handler
        override fun execute(command: Runnable) {
            if (!mHandler.post(command)) throw RejectedExecutionException("$mHandler is shutting down")
        }
    }

    /** Android ViewBinding */
    private var _fragmentBinding: FragmentCustomFrontPreviewBinding? = null
    private val fragmentBinding get() = _fragmentBinding!!

    // ★ 변경: 전면 전용 → 항상 HardwarePipeline 사용
    private val pipeline: Pipeline by lazy {
        HardwarePipeline(
            args.width, args.height, args.fps, args.filterOn, args.transfer,
            args.dynamicRange, characteristics, encoder, fragmentBinding.viewFinder
        )
    }

    /** AndroidX navigation arguments */
    private val args: CustomFrontPreviewFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // ★ 변경: characteristics는 “전면 ID”가 정해진 뒤에 접근해야 안전하지만,
    //        기존 구조를 크게 안 바꾸려면 lazy에서 args.cameraId 대신 “실제 open 시점의 frontId”를 쓰는 게 베스트.
    //        간단히는 아래처럼 두고, initializeCamera에서 frontId로 open 한 뒤 세션/리퀘스트에서 문제 없이 동작합니다.
    private val characteristics: CameraCharacteristics by lazy {
        // NOTE: 이 값은 args.cameraId 기준. 정확히 front characteristics가 필요하다면
        //       initializeCamera에서 frontId로 다시 꺼내 사용해도 됩니다.
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    //밝기 초점 조정용 프리뷰 사이즈
    private var currentPreviewSize: Size? = null

    /** File where the recording will be saved */
    private val outputFile: File by lazy { createFile(requireContext(), "mp4") }

    /** Encoder & Surface */
    private val encoder: EncoderWrapper by lazy { createEncoder("front") } // ★ 변경: 이름만 front로
    private val encoderSurface: Surface by lazy { encoder.getInputSurface() }

    /** Camera thread/handler */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    /** Session/Device */
    private lateinit var session: CameraCaptureSession
    private lateinit var camera: CameraDevice

    /** Requests */
    private val previewRequest: CaptureRequest? by lazy {
        pipeline.createPreviewRequest(session, args.previewStabilization)
    }
    private val recordRequest: CaptureRequest by lazy {
        pipeline.createRecordRequest(session, args.previewStabilization)
    }

    private var recordingStartMillis: Long = 0L

    // 타이머 업데이트용 Handler & Runnable
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    // 타이머 포맷팅 함수 (시:분:초)
    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    // 타이머 업데이트 시작
    private fun startTimerUpdate() {
        stopTimerUpdate() // 기존 타이머가 있으면 중지
        timerRunnable = object : Runnable {
            override fun run() {
                if (recordingStarted && recordingStartMillis > 0) {
                    val elapsed = System.currentTimeMillis() - recordingStartMillis
                    fragmentBinding.captureTimer?.text = formatTime(elapsed)
                    timerHandler.postDelayed(this, 1000) // 1초마다 업데이트
                }
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    // 타이머 업데이트 중지
    private fun stopTimerUpdate() {
        timerRunnable?.let {
            timerHandler.removeCallbacks(it)
            timerRunnable = null
        }
    }

    /** Orientation */
    private val orientation: Int by lazy {
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
    }

    @Volatile private var recordingStarted = false
    @Volatile private var recordingComplete = false
    private val cvRecordingStarted = ConditionVariable(false)
    private val cvRecordingComplete = ConditionVariable(false)

    // ✅ 중복 호출 방지를 위한 원자 플래그
    private val isRecording = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
    
    // ✅ 버튼 디바운스용
    @Volatile
    private var lastStopClick = 0L
    private val STOP_DEBOUNCE_MS = 600L
    
    // 갤러리 버튼 원래 상태 저장
    private var galleryButtonOriginalDrawable: android.graphics.drawable.Drawable? = null
    private var galleryButtonOriginalClickListener: View.OnClickListener? = null

    // ===== Overlay & EV state =====
    private lateinit var overlayContainer: FrameLayout
    private lateinit var focusRing: FocusRingView
    private lateinit var evBarContainer: LinearLayout
    private lateinit var evSeek: SeekBar
    private var evHideRunnable: Runnable? = null
    private var aeCompRange: Range<Int>? = null
    private var aeCompStep: Rational? = null
    private var currentAeComp: Int = 0
    private var evDragging = false
    private var evStartX = 0f
    private var evStartComp = 0
    private val EV_PIXELS_PER_STEP = 20f  // 감도 2배 증가 (값이 작을수록 감도 높음)
    private fun dp(v: Int) = (resources.displayMetrics.density * v + 0.5f).toInt()

    private class FocusRingView(context: Context) : View(context) {
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _fragmentBinding = FragmentCustomFrontPreviewBinding.inflate(inflater, container, false)

        val window = requireActivity().window
        if (args.dynamicRange != DynamicRangeProfiles.STANDARD) {
            if (window.colorMode != ActivityInfo.COLOR_MODE_HDR) {
                window.colorMode = ActivityInfo.COLOR_MODE_HDR
            }
        }
        return fragmentBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 네비게이션 바 설정
        setupBottomNavigationBar(
            currentPage = "camera",
            onHomeClick = {
                // 전면 카메라에서 홈으로 이동
                val action = CustomFrontPreviewFragmentDirections.actionCustomFrontPreviewFragmentToHomeFragment()
                findNavController().navigate(action)
            },
            onGalleryClick = {
                // 전면 카메라에서 갤러리로 이동
                val action = CustomFrontPreviewFragmentDirections
                    .actionCustomFrontPreviewFragmentToGalleryFragment(
                        args.cameraId,
                        args.width,
                        args.height,
                        args.fps,
                        args.dynamicRange,
                        args.colorSpace,
                        args.previewStabilization,
                        args.useMediaRecorder,
                        args.videoCodec,
                        args.filterOn,
                        args.transfer,
                        args.useHardware,
                        "hardware"
                    ).apply {
                        try {
                            startBasic = false
                        } catch (_: Throwable) {
                            setStartBasic(false)
                        }
                    }
                findNavController().navigate(action)
            },
            isRecording = { isCurrentlyRecording() }
        )

        updateGalleryThumbnail()

        fragmentBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pipeline.destroyWindowSurface()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // 프리뷰 사이즈/뷰비 설정
                val previewSize = getPreviewOutputSize(
                    fragmentBinding.viewFinder.display, characteristics, SurfaceHolder::class.java
                )
                currentPreviewSize = previewSize

                fragmentBinding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)
                pipeline.setPreviewSize(previewSize)

                fragmentBinding.viewFinder.post {
                    pipeline.createResources(holder.surface)
                    initializeCamera() // ★ front 카메라로만 연다
                }

                // ===== Overlay container + focus ring =====
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

                    // ===== EV bar (hidden by default) =====
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
                                val r = aeCompRange ?: return
                                val target = r.lower + p
                                if (target != currentAeComp) applyExposureComp(target)
                                scheduleHideEvBar(2000)
                            }
                            override fun onStartTrackingTouch(sb: SeekBar?) {}
                            override fun onStopTrackingTouch(sb: SeekBar?) { scheduleHideEvBar(2000) }
                        })
                        val trackHeight = dp(2)
                        val thumbWidth = dp(2)
                        val thumbHeight = dp(14)
                        val bg = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            setColor(Color.WHITE)
                            alpha = 140
                            cornerRadius = dp(1).toFloat()
                        }
                        val prog = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            setColor(Color.WHITE)
                            cornerRadius = dp(1).toFloat()
                        }
                        val layer = android.graphics.drawable.LayerDrawable(arrayOf(bg, prog)).apply {
                            setId(0, android.R.id.background)
                            setId(1, android.R.id.progress)
                        }
                        splitTrack = false
                        progressDrawable = layer
                        setPadding(0, dp(6), 0, dp(6))
                        val thumbDrawable = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
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

                    // Tap: show focus ring and EV under finger
                    fragmentBinding.viewFinder.setOnTouchListener { _, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                val (ox, oy) = toOverlayXY(ev)
                                focusRing.showAt(ox, oy)
                                syncEvFromChars()
                                placeAndShowEv(ox, oy)
                                // 탭 지점 기준으로 즉시 EV 재설정
                                setEvByTapAbsolute(ev.x)
                                // 드래그 준비
                                evDragging = true
                                evStartX = ev.x
                                evStartComp = currentAeComp
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (evDragging) {
                                    val deltaPx = ev.x - evStartX
                                    val steps = (deltaPx / EV_PIXELS_PER_STEP).toInt()
                                    applyExposureComp(evStartComp + steps)
                                    scheduleHideEvBar(2000)
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                evDragging = false
                                scheduleHideEvBar(2000)
                            }
                        }
                        true
                    }
                }
                fragmentBinding.switchCameraButton.setOnClickListener {
                    val backId = getBackCameraId()
                    if (backId == null) {
                        Toast.makeText(requireContext(), "후면 카메라를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val action = CustomFrontPreviewFragmentDirections.actionCustomFrontPreviewToCustomPreview(
                        backId,
                        args.width,
                        args.height,
                        args.fps,
                        args.dynamicRange,
                        args.colorSpace,
                        args.previewStabilization,
                        args.useMediaRecorder,
                        args.videoCodec,
                        args.filterOn,
                        args.transfer,
                        args.useHardware,
                        "hardware"   // 혹은 네 정책대로
                    )
                    // ⬇️ optional arg 는 setter 로
                    action.forcePhysicalId = null

                    findNavController().navigate(action)
                }

            }

        })

        // ✅ 모드 스위치: 사진 버튼 → PhotoFrontFragment로 이동
        fragmentBinding.btnModePhoto.setOnClickListener {
            val a = args
            val action = CustomFrontPreviewFragmentDirections
                .actionCustomFrontPreviewFragmentToPhotoFrontFragment(
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
                    a.forcePhysicalId?.let { setForcePhysicalId(it) }
                }
            findNavController().navigate(action)
        }

        // (선택) 동영상 버튼은 현재 화면이 동영상이므로 눌러도 변화 없게 or 토스트만
        fragmentBinding.btnModeVideo.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.already_video_mode), Toast.LENGTH_SHORT).show()
        }

        // 갤러리 버튼 원래 상태 저장
        galleryButtonOriginalDrawable = fragmentBinding.galleryButton.drawable
        galleryButtonOriginalClickListener = View.OnClickListener {
            Toast.makeText(requireContext(), "갤러리로 이동", Toast.LENGTH_SHORT).show()

            val action = CustomFrontPreviewFragmentDirections
                .actionCustomFrontPreviewFragmentToGalleryFragment(
                    args.cameraId,
                    args.width,
                    args.height,
                    args.fps,
                    args.dynamicRange,
                    args.colorSpace,
                    args.previewStabilization,
                    args.useMediaRecorder,
                    args.videoCodec,
                    args.filterOn,
                    args.transfer,
                    args.useHardware,
                    "hardware"   // 전면은 고정으로 hardware 사용
                ).apply {
                    // ▶ 확장 갤러리 탭을 기본 활성화
                    try {
                        startBasic = false
                    } catch (_: Throwable) {
                        setStartBasic(false)   // 생성 코드가 Java 스타일이면 이 메서드가 생깁니다
                    }
                }

            findNavController().navigate(action)
        }
        fragmentBinding.galleryButton.setOnClickListener(galleryButtonOriginalClickListener)
    }

    private fun isCurrentlyRecording() = recordingStarted && !recordingComplete

    // ✅ 세션 닫힘 상태 확인 헬퍼 함수
    private fun isSessionClosed(session: CameraCaptureSession): Boolean {
        return try {
            // device 속성에 접근 시도: 닫혔으면 IllegalStateException 발생
            session.device
            false
        } catch (e: IllegalStateException) {
            true
        } catch (e: Exception) {
            // 다른 예외는 닫힌 것으로 간주
            Log.w(TAG, "⚠️ 세션 상태 확인 중 예외: ${e.message}")
            true
        }
    }

    private fun createEncoder(name: String): EncoderWrapper {
        var width = args.width
        var height = args.height
        var orientationHint = orientation

        // ★ 전면도 하드웨어 파이프라인이므로 회전 처리 동일
        if (orientation == 90 || orientation == 270) {
            width = args.height
            height = args.width
        }
        orientationHint = 0

        return EncoderWrapper(
            name, width, height, RECORDER_VIDEO_BITRATE, args.fps,
            args.dynamicRange, orientationHint, outputFile, args.useMediaRecorder, args.videoCodec
        )
    }

    // ★ 추가: 전면 카메라 ID 찾기
    private fun getFrontCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFrontRecording() = lifecycleScope.launch(Dispatchers.IO) {
        // ✅ 중복 시작 방지
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "⚠️ 녹화가 이미 시작되었습니다. 중복 호출 무시")
            return@launch
        }
        isStopping.set(false)

        if (recordingStarted) {
            isRecording.set(false)
            return@launch
        }

        // 🔁 새 녹화 시작이므로 상태 초기화
        recordingComplete = false

        // 화면 회전 잠금
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        // 파이프라인 & 인코더 시작
        pipeline.actionDown(encoderSurface)
        recordingStarted = true
        encoder.start()
        cvRecordingStarted.open()
        pipeline.startRecording()

        // 프리뷰 전용 리퀘스트를 쓰고 있었다면, 레코드 타깃으로 세션 재구성
        if (previewRequest != null) {
            val recordTargets = pipeline.getRecordTargets()
            
            // ✅ 세션 상태 확인 후 안전하게 닫기
            withContext(Dispatchers.Main) {
                runCatching {
                    if (::session.isInitialized && !isSessionClosed(session)) {
                        session.close()
                    }
                }.onFailure { e ->
                    Log.w(TAG, "⚠️ startFrontRecording에서 session.close() 실패: ${e.message}")
                }
            }
            
            session = createCaptureSession(
                camera, recordTargets, cameraHandler, recordingCompleteOnClose = true
            )
            
            runCatching {
                if (!isSessionClosed(session)) {
                    session.setRepeatingRequest(
                        recordRequest,
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                if (isCurrentlyRecording()) encoder.frameAvailable()
                            }
                        },
                        cameraHandler
                    )
                }
            }.onFailure { e ->
                Log.w(TAG, "⚠️ startFrontRecording에서 setRepeatingRequest() 실패: ${e.message}")
            }
        }

        recordingStartMillis = System.currentTimeMillis()

        // UI 업데이트
        fragmentBinding.captureButton.post {
            fragmentBinding.captureButton.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_shutter_pressed)
            fragmentBinding.captureTimer?.apply {
                visibility = View.VISIBLE
                text = "00:00:00"
            }
            // 타이머 업데이트 시작
            startTimerUpdate()
            
            // 📸 갤러리 버튼을 사진 촬영 버튼으로 변경
            galleryButtonOriginalDrawable = fragmentBinding.galleryButton.drawable
            fragmentBinding.galleryButton.setImageResource(R.drawable.ic_shutter_normal)
            fragmentBinding.galleryButton.setOnClickListener {
                captureStillPicture()
            }
            
            // 🎥 녹화 중에는 화면 전환 버튼 숨기기 (placeholder로 교체)
            fragmentBinding.switchCameraButton.visibility = View.GONE
            requireView().findViewById<View>(R.id.switch_camera_placeholder)?.visibility = View.VISIBLE
        }
    }

    private fun stopFrontRecording() = lifecycleScope.launch(Dispatchers.IO) {
        Log.d(TAG, "🛑 stopFrontRecording() 호출됨")

        // ✅ 재진입 방지: 이미 정지 중이면 무시
        if (!isRecording.get() || !isStopping.compareAndSet(false, true)) {
            Log.w(TAG, "⚠️ stopFrontRecording() 중복 호출 무시 (이미 정지 중이거나 녹화 중이 아님)")
            return@launch
        }

        // ✅ UI에서 중복 클릭 방지
        withContext(Dispatchers.Main) {
            fragmentBinding.captureButton.isEnabled = false
        }

        try {
            if (!recordingStarted) {
                isRecording.set(false)
                isStopping.set(false)
                withContext(Dispatchers.Main) {
                    fragmentBinding.captureButton.isEnabled = true
                }
                return@launch
            }

            // 1) 최소 한 프레임 인코딩은 보장
            cvRecordingStarted.block()
            encoder.waitForFirstFrame()

            // 2) 녹화 완료 플래그 & 파이프라인 정리
            recordingComplete = true
            pipeline.stopRecording()
            pipeline.clearFrameListener()
            cvRecordingComplete.open()   // 더 이상 onClosed에 의존하지 않음

            // 3) 캡처 중단 + 프리뷰 요청으로 전환 (세션은 닫지 않는다!)
            withContext(Dispatchers.Main) {
                // ✅ 세션 유효성 검사 및 예외 무해화
                runCatching {
                    if (::session.isInitialized && !isSessionClosed(session)) {
                        session.stopRepeating()
                    }
                }.onFailure { e ->
                    Log.w(TAG, "⚠️ stopRepeating() 실패 (무해화): ${e.message}")
                }

                try {
                    // 기존 세션을 그대로 사용해서 프리뷰 요청만 다시 건다
                    if (::session.isInitialized && !isSessionClosed(session)) {
                        val previewReq = pipeline.createPreviewRequest(session, args.previewStabilization)
                            ?: camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                val previewTargets = pipeline.getPreviewTargets()
                                previewTargets.forEach { addTarget(it) }

                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                            }.build()

                        runCatching {
                            session.setRepeatingRequest(previewReq, null, cameraHandler)
                        }.onFailure { e ->
                            Log.w(TAG, "⚠️ setRepeatingRequest() 실패: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "전면 프리뷰 재시작 실패", e)
                }

            // 4) UI 복원 (버튼/타이머/갤러리/카메라 스위치)
            fragmentBinding.captureButton.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_shutter_normal)
            // 타이머 업데이트 중지
            stopTimerUpdate()
            fragmentBinding.captureTimer?.visibility = View.GONE

            galleryButtonOriginalDrawable?.let {
                fragmentBinding.galleryButton.setImageDrawable(it)
            } ?: run {
                updateGalleryThumbnail()
            }
            galleryButtonOriginalClickListener?.let {
                fragmentBinding.galleryButton.setOnClickListener(it)
            }

            fragmentBinding.switchCameraButton.visibility = View.VISIBLE
            requireView().findViewById<View>(R.id.switch_camera_placeholder)?.visibility = View.GONE
        }

        // 5) 화면 회전 잠금 해제
        requireActivity().requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // 6) 최소 녹화 시간 보장 + 애니메이션 딜레이
        val elapsed = System.currentTimeMillis() - recordingStartMillis
        if (elapsed < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
            delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsed)
        }
        delay(CameraActivity.ANIMATION_SLOW_MILLIS)

        // 7) 인코더 종료
        val shutOk = encoder.shutdown()

        // 8) MediaScanner 등록 + 썸네일만 갱신 (외부 플레이어는 열지 않음)
        if (shutOk) {
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(outputFile.extension) ?: "video/mp4"

            MediaScannerConnection.scanFile(
                requireContext().applicationContext,
                arrayOf(outputFile.absolutePath),
                arrayOf(mime)
            ) { _, _ ->
                Handler(Looper.getMainLooper()).post {
                    if (isAdded) {
                        updateGalleryThumbnail()
                    }
                }
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        R.string.recorder_shutdown_error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

            // 9) 상태 플래그 정리
            recordingStarted = false
            
            // 10) 프래그먼트 재시작 (프리뷰가 멈춘 문제 해결)
            Handler(Looper.getMainLooper()).post {
                if (isAdded) {
                    // 프래그먼트를 다시 띄우기 위해 popBackStack 후 같은 프래그먼트로 다시 네비게이션
                    findNavController().popBackStack()
                    
                    // 같은 프래그먼트로 다시 네비게이션하여 완전히 재시작
                    val frontId = getFrontCameraId()
                    if (frontId != null) {
                        val action = CustomPreviewFragmentDirections
                            .actionCustomPreviewToCustomFrontPreview(
                                frontId,
                                args.width,
                                args.height,
                                args.fps,
                                args.dynamicRange,
                                args.colorSpace,
                                args.previewStabilization,
                                args.useMediaRecorder,
                                args.videoCodec,
                                args.filterOn,
                                args.transfer,
                                args.useHardware,
                                "hardware"
                            ).apply {
                                setForcePhysicalId(null)
                            }
                        findNavController().navigate(action)
                    }
                }
            }
        } finally {
            // ✅ 항상 플래그 리셋 및 UI 복구
            isRecording.set(false)
            isStopping.set(false)
            withContext(Dispatchers.Main) {
                fragmentBinding.captureButton.isEnabled = true
            }
        }
    }


    /**
     * 전면 카메라만 열어 프리뷰/녹화 세션 구성
     */
    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        val frontId = getFrontCameraId()
        if (frontId == null) {
            Toast.makeText(requireContext(), "전면 카메라를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            navController.popBackStack()
            return@launch
        }

        // ★ 항상 전면 ID로 카메라 오픈
        camera = openCamera(cameraManager, frontId, cameraHandler)

        // 출력 타깃
        val previewTargets = pipeline.getPreviewTargets()

        // 세션 생성
        session = createCaptureSession(
            camera, previewTargets, cameraHandler,
            recordingCompleteOnClose = (pipeline !is SoftwarePipeline)
        )

        // 프리뷰 시작 — 항상 "프리뷰 타깃만" 포함된 요청 사용
        val previewReq = pipeline.createPreviewRequest(session, args.previewStabilization)
            ?: camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                // pipeline.getPreviewTargets() 로 만든 세션의 Surface만 추가
                pipeline.getPreviewTargets().forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }.build()

        session.setRepeatingRequest(previewReq, null, cameraHandler)
        // 프리뷰 시작 직후에 추가
        fragmentBinding.captureButton.setOnClickListener {
            if (!recordingStarted) {
                startFrontRecording()
            } else {
                // ✅ 디바운스: 짧은 시간 내 중복 클릭 방지
                val now = SystemClock.elapsedRealtime()
                if (now - lastStopClick < STOP_DEBOUNCE_MS) {
                    Log.d(TAG, "⏱️ 디바운스: 중복 클릭 무시")
                    return@setOnClickListener
                }
                lastStopClick = now
                
                lifecycleScope.launch {
                    stopFrontRecording()
                }
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cont.resume(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                // 필요하면 화면 정리
                if (isAdded) requireActivity().finish()
                if (cont.isActive) cont.resumeWithException(RuntimeException("Camera disconnected"))
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /** 세션 생성 (동일) */
    private fun setupSessionWithDynamicRangeProfile(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler,
        stateCallback: CameraCaptureSession.StateCallback
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val outputConfigs = targets.map { target ->
                OutputConfiguration(target).apply {
                    setDynamicRangeProfile(args.dynamicRange)
                }
            }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputConfigs, HandlerExecutor(handler), stateCallback
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && args.colorSpace != ColorSpaceProfiles.UNSPECIFIED) {
                sessionConfig.setColorSpace(ColorSpace.Named.values()[args.colorSpace])
            }
            device.createCaptureSession(sessionConfig)
            return true
        } else {
            device.createCaptureSession(targets, stateCallback, handler)
            return false
        }
    }

    // ===== EV helpers =====
    private fun toOverlayXY(ev: MotionEvent): Pair<Float, Float> {
        val loc = IntArray(2)
        overlayContainer.getLocationOnScreen(loc)
        return (ev.rawX - loc[0]) to (ev.rawY - loc[1])
    }

    private fun syncEvFromChars() {
        try {
            aeCompRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            aeCompStep  = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        } catch (_: Throwable) {}
        val r = aeCompRange
        if (r != null) {
            val cur = currentAeComp.coerceIn(r.lower, r.upper)
            evSeek.max = (r.upper - r.lower)
            evSeek.progress = (cur - r.lower).coerceIn(0, evSeek.max)
            evSeek.isEnabled = evSeek.max > 0
        } else {
            evSeek.max = 0
            evSeek.progress = 0
            evSeek.isEnabled = false
        }
    }

    private fun placeAndShowEv(x: Float, y: Float) {
        val lp = evBarContainer.layoutParams as FrameLayout.LayoutParams
        val half = if (evSeek.width > 0) evSeek.width / 2f else dp(120) / 2f
        lp.leftMargin = (x - half).toInt().coerceAtLeast(0)
        lp.topMargin = (y + dp(20)).toInt().coerceAtLeast(0)
        evBarContainer.layoutParams = lp
        evBarContainer.visibility = View.VISIBLE
        evBarContainer.alpha = 1f
        scheduleHideEvBar(1800)
    }

    private fun scheduleHideEvBar(delayMs: Long = 1400L) {
        evHideRunnable?.let { evBarContainer.removeCallbacks(it) }
        evHideRunnable = Runnable {
            evBarContainer.animate().alpha(0f).setDuration(140)
                .withEndAction { evBarContainer.visibility = View.GONE }.start()
        }
        evBarContainer.postDelayed(evHideRunnable!!, delayMs)
    }

    private fun applyExposureComp(target: Int) {
        val r = aeCompRange ?: return
        currentAeComp = target.coerceIn(r.lower, r.upper)
        
        // ✅ 세션 상태 확인 후 안전하게 호출
        if (!::session.isInitialized || isSessionClosed(session)) {
            Log.w(TAG, "⚠️ applyExposureComp: 세션이 닫혔거나 초기화되지 않음")
            return
        }
        
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            pipeline.getPreviewTargets().forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentAeComp)
        }
        
        runCatching {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        }.onFailure { e ->
            Log.w(TAG, "⚠️ applyExposureComp에서 setRepeatingRequest() 실패: ${e.message}")
        }
        
        // 슬라이더와 동기화
        val max = (r.upper - r.lower)
        evSeek.max = max
        evSeek.progress = (currentAeComp - r.lower).coerceIn(0, max)
    }

    private fun setEvByTapAbsolute(xInView: Float) {
        val r = aeCompRange ?: return
        val w = view?.findViewById<View>(R.id.view_finder)?.width?.coerceAtLeast(1) ?: return
        val t = (xInView / w.toFloat()).coerceIn(0f, 1f)
        val target = (r.lower + t * (r.upper - r.lower)).toInt()
        applyExposureComp(target)
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler,
        recordingCompleteOnClose: Boolean
    ): CameraCaptureSession = suspendCoroutine { cont ->
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
            override fun onClosed(session: CameraCaptureSession) {
                Log.d(TAG, "🎬 Session closed callback 진입")
                // ✅ 세션 조작 금지: onClosed에서는 세션에 대한 조작을 하지 않음
                // 플래그만 설정하고 파이프라인 정리
                if (!recordingCompleteOnClose || !isCurrentlyRecording()) {
                    Log.w(TAG, "⚠️ 조건 불충족 - open() 호출 생략됨")
                    return
                }
                Log.d(TAG, "✅ 조건 만족 - cvRecordingComplete.open() 호출")
                recordingComplete = true
                pipeline.stopRecording()
                cvRecordingComplete.open()
            }
        }
        setupSessionWithDynamicRangeProfile(device, targets, handler, stateCallback)
    }

    override fun onStop() {
        super.onStop()
        try { camera.close() } catch (exc: Throwable) { Log.e(TAG, "Error closing camera", exc) }
    }

    override fun onDestroy() {
        super.onDestroy()
        pipeline.clearFrameListener()
        pipeline.cleanup()
        cameraThread.quitSafely()
        encoderSurface.release()
    }

    override fun onDestroyView() {
        // 타이머 업데이트 중지
        stopTimerUpdate()
        _fragmentBinding = null
        super.onDestroyView()
    }

    private fun getBackCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun updateGalleryThumbnail() {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "EchoShot"
        )
        val videoFiles = dir.listFiles { f -> f.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        val latest = videoFiles.firstOrNull() ?: return

        val targetPx = 300
        val thumb = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: File 객체 + Size
                ThumbnailUtils.createVideoThumbnail(
                    latest,
                    Size(targetPx, targetPx),
                    null
                )
            } else {
                // 이하: 경로 + 비디오 전용 MINI_KIND
                ThumbnailUtils.createVideoThumbnail(
                    latest.absolutePath,
                    MediaStore.Video.Thumbnails.MINI_KIND   // ← 여기 수정
                )
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        } ?: return

        fragmentBinding.galleryButton.setImageBitmap(thumb)
    }
    
    // 📸 녹화 중 사진 촬영 함수 (미리보기와 동일한 프레임을 저장: PixelCopy)
    private fun captureStillPicture() {
        try {
            val sv = fragmentBinding.viewFinder
            val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(sv, bmp, { result ->
                if (result != PixelCopy.SUCCESS) {
                    Toast.makeText(requireContext(), "캡처 실패($result)", Toast.LENGTH_SHORT).show()
                    return@request
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.KOREA)
                            .format(System.currentTimeMillis())
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/EchoShot")
                        }

                        val uri = requireContext().contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            contentValues
                        )

                        uri?.let {
                            requireContext().contentResolver.openOutputStream(it)?.use { os ->
                                val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)
                                if (!ok) throw RuntimeException("JPEG 압축 실패")
                            }
                            MediaScannerConnection.scanFile(
                                requireContext(),
                                arrayOf(it.toString()),
                                arrayOf("image/jpeg"),
                                null
                            )
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "사진 저장 실패", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "사진 촬영 오류", e)
            Toast.makeText(requireContext(), "${getString(R.string.photo_capture_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    companion object {
        private val TAG = PreviewFragment::class.java.simpleName
        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        // ✅ 후면과 동일하게 DCIM/EchoShot에 저장 + 확장자 버그 수정
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val fileName = "VID_front_${sdf.format(Date())}.$extension"   // ⬅️ ".{$extension}" → ".$extension"

            val publicDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DCIM
                ),
                "EchoShot"
            )
            if (!publicDir.exists()) publicDir.mkdirs()

            val f = File(publicDir, fileName)
            Log.d("FileDebug", "📂 [front] Public video file path: ${f.absolutePath}")
            return f
        }
    }
}