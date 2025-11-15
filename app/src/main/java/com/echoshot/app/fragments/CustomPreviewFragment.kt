package com.echoshot.app.fragments

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceProfiles
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ConditionVariable
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.MimeTypeMap
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.animation.addListener
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.getPreviewOutputSize
import com.echoshot.app.BuildConfig
import com.echoshot.app.CameraActivity
import com.echoshot.app.EncoderWrapper
import com.echoshot.app.R
import com.echoshot.app.autozoom.AutoZoomController
import com.echoshot.app.data.Device
import com.echoshot.app.data.KeyPoint
import com.echoshot.app.databinding.FragmentCustomPreviewBinding
import com.echoshot.app.ml.ModelType
import com.echoshot.app.ml.MoveNet
import com.echoshot.app.ml.MoveNetMultiPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.TensorImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import com.echoshot.app.ml.PoseDetector
import com.echoshot.app.ml.Type
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import kotlin.math.abs
import kotlin.math.exp
import android.graphics.Color
import android.graphics.Paint
import android.util.Range
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.InsetDrawable
import android.media.ImageReader
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaMetadataRetriever
import android.widget.ImageView
import java.nio.ByteBuffer


class CustomPreviewFragment : Fragment() {

    private class HandlerExecutor(handler: Handler) : Executor {
        private val mHandler = handler

        override fun execute(command: Runnable) {
            if (!mHandler.post(command)) {
                throw RejectedExecutionException("" + mHandler + " is shutting down");
            }
        }
    }

    private var zoomLevel: Float = 1.0f

    // 맨 위 어딘가에 추가
    private enum class LensMode { WIDE, TELE }

    // ⚠️ 여기서는 isTeleCurrent() 호출하지 말고 기본값만
    private var lensMode: LensMode = LensMode.WIDE
    private val TELE_BASE = 3f

    private fun displayLabelFor(z: Float): String {
        val shown = if (lensMode == LensMode.TELE) z * TELE_BASE else z
        return String.format("%.2fx", shown)
    }


    //밝기 초점 조정용 프리뷰 사이즈
    private var currentPreviewSize: Size? = null

    //
    // Focus & EV overlay
    private lateinit var focusRing: FocusRingView
    private lateinit var evBarContainer: LinearLayout
    private lateinit var evSeek: SeekBar
    private var evDragging = false
    private var evStartX = 0f
    private var evStartComp = 0
    private val EV_PIXELS_PER_STEP = 20f  // 감도 2배 증가 (값이 작을수록 감도 높음)
    private val LONG_PRESS_MS = 100L
    private var longPressFired = false
    private var longPressRunnable: Runnable? = null
    private var evLocked = false  // 수동 EV 조절 후 자동 변경 방지

    // AE 보정 범위/스텝 상태
    private var aeCompRange: Range<Int>? = null
    private var aeCompStep: Rational? = null
    private var currentAeComp: Int = 0

    private fun dp(v: Int) = (resources.displayMetrics.density * v + 0.5f).toInt()
    // 줌 핀처
    private lateinit var scaleDetector: ScaleGestureDetector
    private var isScaling = false
    private var pinchStartZoom = 1.0f
    private var accumulatedScale = 1.0f
    private val PINCH_POWER = 1.0f // 1.0이면 표준 감도. (0.8~1.2 사이로 미세조정 가능)

    /** Android ViewBinding */
    private var _fragmentBinding: FragmentCustomPreviewBinding? = null

    private val fragmentBinding get() = _fragmentBinding!!

    private val pipeline: Pipeline by lazy {
        when (args.pipelineMode) {
            "hardware" -> CustomHardwarePipeline(
                args.width, args.height, args.fps, args.filterOn, args.transfer,
                args.dynamicRange, characteristics, encoder, originalencoder, fragmentBinding.viewFinder
            )
            "hybrid" -> CustomHardwarePipelineDefault( // Hybrid 모드는 기본 줌 적용 파이프라인이라고 가정
                args.width, args.height, args.fps, args.filterOn, args.transfer,
                args.dynamicRange, characteristics, encoder, originalencoder, fragmentBinding.viewFinder
            )
            "software" -> SoftwarePipeline(
                args.width, args.height, args.fps, args.filterOn,
                args.dynamicRange, characteristics, encoder, fragmentBinding.viewFinder
            )
            else -> throw IllegalArgumentException("❌ 지원하지 않는 pipelineMode: ${args.pipelineMode}")
        }
    }

    /** AndroidX navigation arguments */
    private val args: CustomPreviewFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    // 갤러리 버튼 원래 상태 저장
    private var galleryButtonOriginalDrawable: android.graphics.drawable.Drawable? = null
    private var galleryButtonOriginalClickListener: View.OnClickListener? = null
    private var imageReader: android.media.ImageReader? = null
    private var stillReader: ImageReader? = null

    /** File where the recording will be saved */


    /**
     * Setup a [Surface] for the encoder
     */
    private val encoderSurface: Surface by lazy {
        encoder.getInputSurface()
    }
    private val originalencoderSurface: Surface by lazy { // 추가된 인코더 서페이스
        originalencoder.getInputSurface()
    }

    /** [EncoderWrapper] utility class */

    private val encoder: EncoderWrapper by lazy { createEncoder("zoomed") }
    private val originalencoder: EncoderWrapper by lazy { createEncoder("original") }

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Requests used for preview only in the [CameraCaptureSession] */
    private val previewRequest: CaptureRequest? by lazy {
        pipeline.createPreviewRequest(session, args.previewStabilization)
    }

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val recordRequest: CaptureRequest by lazy {
        pipeline.createRecordRequest(session, args.previewStabilization)
    }

    private var recordingStartMillis: Long = 0L

    /** Orientation of the camera as 0, 90, 180, or 270 degrees */
    private val orientation: Int by lazy {
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
    }

    private val sessionUuid: String = UUID.randomUUID().toString()

    @Volatile
    private var recordingStarted = false

    @Volatile
    private var recordingComplete = false

    /** Condition variable for blocking until the recording completes */
    private val cvRecordingStarted = ConditionVariable(false)
    private val cvRecordingComplete = ConditionVariable(false)


    // ① PoseDetector 인스턴스
    private lateinit var poseDetector: PoseDetector

    // ② PixelCopy 루프용 핸들러 & Runnable
    private lateinit var pixelHandler: Handler
    private lateinit var pixelRunnable: Runnable

    // AutoZoomController
    private val autoZoom = AutoZoomController()

    // 클래스 멤버로 추가 안전한 종료를 위함
    private lateinit var pixelThread: HandlerThread

    // 후처리를 위한 로깅정보 변수
    private lateinit var logWriter: BufferedWriter
    private lateinit var logFile: File
    private var logEntryCount = 0
    private val LOG_FLUSH_INTERVAL = 50
    private var frameIndex = 0L
    private val frameTimestamps = mutableListOf<Long>()

    /** SurfaceView bitmap → 모델 추론용 PixelCopy 루프 함수 */
    private fun startPixelCopyLoop() {
        // 1) 백그라운드 스레드
        pixelThread = HandlerThread("PixelCopyThread").apply { start() }
        pixelHandler = Handler(pixelThread.looper)

        // 2) Canvas 크기와 동일한 Bitmap
        val sv = fragmentBinding.viewFinder
        val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)

        // 3) 매 100ms마다 PixelCopy 요청
        pixelRunnable = object : Runnable {
            override fun run() {
                PixelCopy.request(sv, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        runPoseEstimation(bmp)
                    } else {
                        Log.e("PixelCopy", "error code $result")
                    }
                    pixelHandler.postDelayed(this, 30)
                }, pixelHandler)
            }
        }

        // 4) 첫 실행
        pixelHandler.postDelayed(pixelRunnable, 500)
    }


    // 프래그먼트 멤버 변수로 추가
    private var zoomTarget: Float? = null
    private var zoomStart: Float = 1f
    private var zoomStartTs: Long = 0L
    private val zoomDuration = 16L  // 밀리초 단위, 원하는 부드러움에 맞춰 조절

    private var zoomAnimator: ValueAnimator? = null
    private val zoomAnimDuration = 300L  // 목표 변경 시 300ms 동안 부드럽게 이동

    private fun runPoseEstimation(bitmap: Bitmap) {
        // 0) 뷰가 내려간 상태면 즉시 반환
        if (!isAdded || _fragmentBinding == null || viewLifecycleOwner.lifecycle.currentState < Lifecycle.State.STARTED) {
            return
        }
        // 1) 추론
        val rawPeople = poseDetector.estimatePoses(bitmap)

        // 2) Single‑Pose 모델에는 boundingBox가 없으니 keyPoints로 박스 계산
        val peopleWithBox = rawPeople.map { person ->
            val xs = person.keyPoints.map { it.coordinate.x }
            val ys = person.keyPoints.map { it.coordinate.y }
            val left   = xs.minOrNull() ?: 0f
            val right  = xs.maxOrNull() ?: 0f
            val top    = ys.minOrNull() ?: 0f
            val bottom = ys.maxOrNull() ?: 0f
            person.boundingBox = RectF(left, top, right, bottom)
            person
        }

        // 2) 첫 번째 사람 정보가 있으면 채우고, 없으면 null/제로 배열로 채우기
        val (bboxArr, kpArr) = if (peopleWithBox.isNotEmpty()) {
            val p = peopleWithBox[0]
            val rect = p.boundingBox!!
            // bbox를 Float 리스트로
            val b = listOf(rect.left, rect.top, rect.right, rect.bottom)
            // keypoints를 Float 3-튜플 리스트로
            val k = p.keyPoints.map { kp -> listOf(kp.coordinate.x, kp.coordinate.y, kp.score) }
            Pair(b, k)
        } else {
            // 검출 없으면 bbox=null, keypoints는 17개 [0,0,0]
            val emptyK = List(17) { listOf(0f, 0f, 0f) }
            Pair(null, emptyK)
        }

        // 3) **무조건** 로그 남기기
        if (recordingStarted && this::logWriter.isInitialized) {
            logTrackingFrame(
                frameIndex++,
                System.nanoTime(),
                zoomLevel,
                bboxArr,    // null 가능
                kpArr       // 항상 17개
            )
        }

        // 4)Auto‑Zoom 타깃 계산
        if (peopleWithBox.isNotEmpty() && autoZoom.isActive) {
            val person = peopleWithBox[0]
            val box    = person.boundingBox!!

            // 1) confidence 0.5 이상인 포인트만 세기
            val validKeyPointCount = person.keyPoints.count { it.score >= 0.3f }

            autoZoom.update(
                currentZoom    = zoomLevel,
                box            = box,
                viewW          = fragmentBinding.viewFinder.width,
                viewH          = fragmentBinding.viewFinder.height,
                keyPointCount  = validKeyPointCount  // ‘실제 검출된’ 개수
            )?.let { newTargetZoom ->
                // 목표가 바뀌었을 때만 애니메이터 실행
                if (zoomAnimator == null || zoomAnimator?.isRunning == false) {
                    startSmoothZoom(zoomLevel, newTargetZoom)
                } else {
                    // 이미 애니메이션 중이면, 끝나고 새로 시작하게
                    zoomAnimator?.addListener(onEnd = {
                        startSmoothZoom(zoomLevel, newTargetZoom)
                    })
                }
            }
        }

        requireActivity().runOnUiThread {
            _fragmentBinding?.let { binding ->
                // 1) 오버레이 갱신
                fragmentBinding.poseOverlayView.apply {
                    people = peopleWithBox
                    invalidate()
                }

                // Linear Interpolation 으로 Zoom 보간
                zoomTarget?.let { tZ ->
                    val now = SystemClock.uptimeMillis()
                    val elapsed = (now - zoomStartTs).coerceAtLeast(0L)
                    val fraction = (elapsed.toFloat() / zoomDuration.toFloat()).coerceIn(0f, 1f)

                    // 선형 보간: start → target
                    val rawZoom = zoomStart + (tZ - zoomStart) * fraction
                    // 1.0f 이하로 내려가지 않도록 클램프
                    zoomLevel = rawZoom.coerceIn(1.0f, 10.0f)

                    // 목표에 도달했으면 깔끔하게 스냅 & 리셋
                    if (fraction >= 1f) {
                        zoomLevel = tZ.coerceIn(1.0f, 10.0f)    // ⬅️ 완료 시에도 동일하게
                        zoomTarget = null
                        zoomStartTs = 0L
                    }

                    // 파이프라인과 UI 반영
                    setZoomLevel(zoomLevel)
                    fragmentBinding.zoomLevelText.text = String.format("%.2fx", zoomLevel)
                    fragmentBinding.zoomSlider.progress =
                        ((zoomLevel - 1f) / 9f * fragmentBinding.zoomSlider.max).toInt()
                }
            }
        }
    }

    // ② 메인 스레드에서만 호출되도록 보장
    private fun startSmoothZoom(from: Float, to: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { startSmoothZoom(from, to) }
            return
        }

        zoomAnimator?.cancel()

        val fromClamped = from.coerceIn(1.0f, 10.0f)
        val toClamped = to.coerceIn(1.0f, 10.0f)

        zoomAnimator = ValueAnimator.ofFloat(fromClamped, toClamped).apply {
            duration = zoomAnimDuration
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val z = (anim.animatedValue as Float).coerceIn(1.0f, 10.0f) // ⬅️ 중간 값도 클램프
                zoomLevel = z
                setZoomLevel(z)
                fragmentBinding.zoomLevelText.text = String.format("%.2fx", z)
                fragmentBinding.zoomSlider.progress =
                    ((z - 1f) / 9f * fragmentBinding.zoomSlider.max).toInt()
            }
            addListener(onEnd = { zoomAnimator = null }, onCancel = { zoomAnimator = null })
            start()
        }
    }

    private fun disableAutoZoom() {
        if (autoZoom.isActive) {
            autoZoom.toggle() // 전환 방식이면 toggle 사용
            // 만약 deactivate()가 있다면 그걸 쓰세요: autoZoom.deactivate()
            fragmentBinding.autoZoomButton.setColorFilter(
                ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
            )
            Toast.makeText(requireContext(), "Auto-Zoom 해제", Toast.LENGTH_SHORT).show()
        }
        // 진행 중이던 자동 보간/애니메이션 정리
        zoomTarget = null
        zoomStartTs = 0L
        zoomAnimator?.cancel()
        zoomAnimator = null
    }

    // 3) 헬퍼 메소드로 실제 파이프라인에 줌 적용
    private fun setZoomLevel(zoom: Float) {
        // 전역 안전망: 1.0f ~ 10.0f로 강제
        val z = zoom.coerceIn(1.0f, 10.0f)

        // 내부 상태도 동일 값으로 동기화
        zoomLevel = z

        when (pipeline) {
            is CustomHardwarePipeline ->
                (pipeline as CustomHardwarePipeline).setZoomLevel(z)
            is CustomHardwarePipelineDefault ->
                (pipeline as CustomHardwarePipelineDefault).setZoomLevel(z)
        }
    }

    // 프레임별 로깅 헬퍼

    private fun logTrackingFrame(
        frameIdx: Long,
        timestamp: Long,
        zoom: Float,
        bbox: List<Float>?,
        keypoints: List<List<Float>>
    ) {
        if (!this@CustomPreviewFragment::logWriter.isInitialized) return

        val screenW = fragmentBinding.viewFinder.width
        val screenH = fragmentBinding.viewFinder.height

        val obj = JSONObject().apply {
            put("frame", frameIdx)
            put("timestamp", timestamp)
            put("screenWidth", screenW)
            put("screenHeight", screenH)
            put("zoom", zoom)
            put("bbox", bbox)             // null 이면 자동으로 JSONObject.NULL 처리
            put("keypoints", keypoints)
        }

        synchronized(this) {
            logWriter.append(obj.toString())
            logWriter.newLine()
            if (++logEntryCount % LOG_FLUSH_INTERVAL == 0) {
                logWriter.flush()
            }
        }
    }
    // EV 노출값 조절 (수치 표시는 제거)
    private fun updateEvText() { }

    private var hideEvRunnable: Runnable? = null
    private fun scheduleHideEvBar(delayMs: Long = 1400L) {
        hideEvRunnable?.let { evBarContainer.removeCallbacks(it) }
        hideEvRunnable = Runnable {
            evBarContainer.animate().alpha(0f).setDuration(140)
                .withEndAction { evBarContainer.visibility = View.GONE }.start()
        }
        evBarContainer.postDelayed(hideEvRunnable!!, delayMs)
    }

    private fun showEvBarAt(x: Float, y: Float) {
        val parentW = fragmentBinding.overlayContainer.width
        val parentH = fragmentBinding.overlayContainer.height
        evBarContainer.measure(
            View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(parentH, View.MeasureSpec.AT_MOST)
        )
        val bw = evBarContainer.measuredWidth



        val bh = evBarContainer.measuredHeight
        val left = (x - bw/2f).coerceIn(0f, (parentW - bw).toFloat())
        val top  = (y + dp(20)).coerceIn(0f, (parentH - bh).toFloat())

        (evBarContainer.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left.toInt(); topMargin = top.toInt()
        }
        evBarContainer.requestLayout()
        evBarContainer.visibility = View.VISIBLE
        evBarContainer.animate().alpha(1f).setDuration(120).start()
        scheduleHideEvBar()
    }

    private fun applyExposureComp(evSteps: Int) {
        val range = aeCompRange ?: return
        val clamped = evSteps.coerceIn(range.lower, range.upper)
        currentAeComp = clamped

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            pipeline.getPreviewTargets().forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped)
            // (필요 시 AF/AWB 모드도 유지)
        }
        session.setRepeatingRequest(builder.build(), null, cameraHandler)

        // 시크바 동기화
        aeCompRange?.let { evSeek.progress = clamped - it.lower }
    }

    private fun setEvByTapAbsolute(xInView: Float) {
        val range = aeCompRange ?: return
        val w = fragmentBinding.viewFinder.width.coerceAtLeast(1)
        val t = (xInView / w.toFloat()) // 0.0~1.0
        val steps = (range.lower + t * (range.upper - range.lower)).toInt()
        applyExposureComp(steps)
        updateEvText()
        // 시크바/EV바 HUD 위치도 갱신
        evSeek.progress = steps - range.lower
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentBinding = FragmentCustomPreviewBinding.inflate(inflater, container, false)

        val window = requireActivity().getWindow()
        if (args.dynamicRange != DynamicRangeProfiles.STANDARD) {
            if (window.getColorMode() != ActivityInfo.COLOR_MODE_HDR) {
                window.setColorMode(ActivityInfo.COLOR_MODE_HDR)
            }
        }

        return fragmentBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateGalleryThumbnail()

        // 뷰 세팅 직후, PixelCopy 등록 전에
        poseDetector = MoveNetMultiPose.create(
            requireContext(),
            Device.CPU,       // CPU / GPU / NNAPI
            Type.Dynamic      // Dynamic 모델(256×256) 또는 Type.Fixed
        )


        // ✅ 줌 슬라이더 리스너 등록
        fragmentBinding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) disableAutoZoom()

                // 내부 로직용 줌(그대로 1.0x ~ 10.0x)
                zoomLevel = 1.0f + (progress / 1000f) * 9.0f

                // 파이프라인엔 내부 줌 그대로
                setZoomLevel(zoomLevel)

                // UI엔 렌즈에 맞춰 표기 (Tele면 ×3)
                val label = displayLabelFor(zoomLevel)
                fragmentBinding.zoomLevelText.text = label
                showZoomHUD(label)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // ✅ 모드 스위치: 사진 버튼 → PhotoFragment로 이동
        view.findViewById<View>(R.id.btn_mode_photo)?.setOnClickListener {
            val a = args  // Safe Args로 받은 기존 동영상 설정
            val action = CustomPreviewFragmentDirections
                .actionCustomPreviewFragmentToPhotoFragment(
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
            findNavController().navigate(action)
        }

        // (선택) 동영상 버튼은 현재 화면이 동영상이므로 눌러도 변화 없게 or 토스트만
        view.findViewById<View>(R.id.btn_mode_video)?.setOnClickListener {
            Toast.makeText(requireContext(), "이미 동영상 모드입니다", Toast.LENGTH_SHORT).show()
        }

        scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    disableAutoZoom()
                    isScaling = true
                    pinchStartZoom = zoomLevel
                    accumulatedScale = 1.0f

                    showZoomHUD(String.format("%.2fx", zoomLevel))
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    // 누적 스케일 = 직전 대비 비율을 계속 곱함
                    val step = Math.pow(detector.scaleFactor.toDouble(), PINCH_POWER.toDouble()).toFloat()
                    accumulatedScale *= step

                    val newZoom = (pinchStartZoom * accumulatedScale).coerceIn(1.0f, 10.0f)
                    setZoomLevel(newZoom)
                    fragmentBinding.zoomLevelText.text = String.format("%.2fx", newZoom)
                    fragmentBinding.zoomSlider.progress =
                        (((newZoom - 1f) / 9f) * fragmentBinding.zoomSlider.max).toInt()

                    showZoomHUD(String.format("%.2fx", zoomLevel))

                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isScaling = false
                }
            }
        )


        fragmentBinding.viewFinder.setOnTouchListener { v, event ->
            // 핀치(줌) 먼저 처리
            scaleDetector.onTouchEvent(event)

            fun toOverlayXY(ev: MotionEvent): Pair<Float, Float> {
                val loc = IntArray(2)
                fragmentBinding.overlayContainer.getLocationOnScreen(loc)
                return (ev.rawX - loc[0]) to (ev.rawY - loc[1])
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isScaling && event.pointerCount == 1) {
                        // 이전에 수동 EV 조절을 했다면, 다음 터치에서 즉시 자동으로 EV와 초점 설정
                        if (evLocked) {
                            evLocked = false
                            val (ox, oy) = toOverlayXY(event)
                            focusRing.showAt(ox, oy)
                            showEvBarAt(ox, oy)

                            // 터치 위치에 맞게 EV 자동 설정
                            setEvByTapAbsolute(event.x)

                            // 초점 설정
                            val x = event.x / fragmentBinding.viewFinder.width
                            val y = event.y / fragmentBinding.viewFinder.height
                            triggerFocusAtPoint(x, y)

                            scheduleHideEvBar(2000)
                            return@setOnTouchListener true
                        }

                        // 롱 프레스 초기화
                        longPressFired = false
                        longPressRunnable?.let { v.removeCallbacks(it) }

                        longPressRunnable = Runnable {
                            longPressFired = true
                            val (ox, oy) = toOverlayXY(event)
                            focusRing.showAt(ox, oy)
                            showEvBarAt(ox, oy)

                            // ✅ 롱 프레스 시, 화면 가로 위치를 EV 보정 범위에 "절대 맵핑"해서 즉시 적용
                            setEvByTapAbsolute(event.x)

                            // 이후 드래그를 위한 초기화(이전 드래그와 무관하게 새 기준으로 시작)
                            evDragging = true
                            evStartX = event.x
                            evStartComp = currentAeComp

                            scheduleHideEvBar(2000)
                        }.also { v.postDelayed(it, LONG_PRESS_MS) }
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 멀티터치 감지 시 롱 프레스 취소
                    longPressRunnable?.let { v.removeCallbacks(it) }
                    longPressRunnable = null
                    longPressFired = false
                }

                MotionEvent.ACTION_MOVE -> {
                    // 멀티터치 감지 시 롱 프레스 취소
                    if (event.pointerCount > 1) {
                        longPressRunnable?.let { v.removeCallbacks(it) }
                        longPressRunnable = null
                        longPressFired = false
                    }

                    if (!isScaling && evDragging && longPressFired) {
                        // 기존과 동일: 가로 드래그로 상대 변경(미세 조절)
                        val deltaPx = event.x - evStartX
                        val steps = (deltaPx / EV_PIXELS_PER_STEP).toInt()
                        applyExposureComp(evStartComp + steps)
                        updateEvText()
                        scheduleHideEvBar(2000)
                        // 수동 EV 조절 플래그 설정
                        evLocked = true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 롱 프레스 취소
                    longPressRunnable?.let { v.removeCallbacks(it) }
                    longPressRunnable = null

                    evDragging = false
                    scheduleHideEvBar()

                    if (!isScaling && event.pointerCount == 1 && longPressFired) {
                        val x = event.x / fragmentBinding.viewFinder.width
                        val y = event.y / fragmentBinding.viewFinder.height
                        triggerFocusAtPoint(x, y)

                        if (!isCurrentlyRecording() && !autoZoom.isActive) showLensHUD()
                    }

                    longPressFired = false
                }
            }
            true
        }



        //녹화 시작 종료 버튼
        fragmentBinding.captureButton.setOnClickListener {
            Toast.makeText(requireContext(), "버튼 눌림", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "버튼 눌림")

            if (!recordingStarted) {
                startRecording()
                Toast.makeText(requireContext(), "녹화 시작", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "녹화 시작")
            } else {
                stopRecording()
                Toast.makeText(requireContext(), "녹화 중지 시도", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "녹화 중지 시도")
            }
        }

        // ✅ SurfaceView 초기화
        fragmentBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pipeline.destroyWindowSurface()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // 권장 사이즈로 선택
                val previewSize = getPreviewOutputSize(
                    fragmentBinding.viewFinder.display,
                    characteristics,
                    SurfaceHolder::class.java
                )

                // 뷰 비율/버퍼 고정 (버퍼를 먼저 고정해 두면 크롭 이슈가 줄어듦)
                holder.setFixedSize(previewSize.width, previewSize.height)
                fragmentBinding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)

                currentPreviewSize = previewSize

                pipeline.setPreviewSize(previewSize)

                // 📸 정지화상용 ImageReader 미리 생성 (세션 outputs에 포함시키기 위함)
                if (stillReader == null) {
                    stillReader = ImageReader.newInstance(
                        previewSize.width,
                        previewSize.height,
                        ImageFormat.JPEG,
                        2
                    )
                }

                fragmentBinding.viewFinder.post {
                    pipeline.createResources(holder.surface)
                    initializeCamera()
                    startPixelCopyLoop()
                }
            }

        })



        val whiteColor = ContextCompat.getColor(requireContext(), android.R.color.white)
        val defaultColor = ContextCompat.getColor(requireContext(), android.R.color.darker_gray)

        when (args.pipelineMode) {
            "hybrid" -> {
                fragmentBinding.iconZoom.setColorFilter(whiteColor)
                fragmentBinding.iconNozoom.setColorFilter(defaultColor)

                // 🔁 hybrid 상태에서 zoom 아이콘 누르면 hardware로 전환
                fragmentBinding.iconNozoom.setOnClickListener {
                    Toast.makeText(requireContext(), "🔁 반배줌  모드로 전환", Toast.LENGTH_SHORT).show()
                    reloadWithNewPipeline(args.cameraId, "hardware")
                }
            }
            "hardware" -> {
                fragmentBinding.iconZoom.setColorFilter(defaultColor)
                fragmentBinding.iconNozoom.setColorFilter(whiteColor)

                // 🔁 hardware 상태에서 nozoom 아이콘 누르면 hybrid로 전환
                fragmentBinding.iconZoom.setOnClickListener {
                    Toast.makeText(requireContext(), "🔁 1배 촬영 모드로 전환", Toast.LENGTH_SHORT).show()
                    reloadWithNewPipeline(args.cameraId, "hybrid")
                }
            }
            else -> {
                fragmentBinding.iconZoom.setColorFilter(defaultColor)
                fragmentBinding.iconNozoom.setColorFilter(defaultColor)

                // 안전하게 클릭 리스너 제거
                fragmentBinding.iconZoom.setOnClickListener(null)
                fragmentBinding.iconNozoom.setOnClickListener(null)
            }
        }

        //오버레이 UI 생성 코드--------------------------
        // 0) 카메라가 지원하는 EV 범위/스텝 읽기
        aeCompRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        aeCompStep  = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

        // 1) 포커스 링
        focusRing = FocusRingView(requireContext()).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        fragmentBinding.overlayContainer.addView(focusRing)

        // 2) EV 바 (텍스트 + 시크바)
        evBarContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            background = null
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0f; visibility = View.GONE
        }
        evSeek = SeekBar(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(73), dp(14)).apply { leftMargin = dp(10) }
            max = aeCompRange?.let { it.upper - it.lower } ?: 0
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val r = aeCompRange ?: return
                    val target = r.lower + p
                    if (target != currentAeComp) { applyExposureComp(target) }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { scheduleHideEvBar() }
            })
            // 스타일: 얇은 흰 트랙 + 짧은 흰 썸
            val trackHeight = dp(2)
            val thumbWidth = dp(2)
            val thumbHeight = dp(14)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                alpha = 160
                cornerRadius = dp(1).toFloat()
            }
            val prog = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dp(1).toFloat()
            }
            val layer = LayerDrawable(arrayOf(InsetDrawable(bg, 0), InsetDrawable(prog, 0))).apply {
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

        // 컨테이너에 붙이기
        fragmentBinding.overlayContainer.addView(
            evBarContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // 숫자 수치 제거
        //오버레이 UI 생성 코드 끝--------------------------

        // 갤러리 버튼 원래 상태 저장
        galleryButtonOriginalDrawable = fragmentBinding.galleryButton.drawable
        galleryButtonOriginalClickListener = View.OnClickListener {
            Toast.makeText(requireContext(), "갤러리로 이동", Toast.LENGTH_SHORT).show()

            val action = CustomPreviewFragmentDirections
                .actionCustomPreviewFragmentToGalleryFragment(
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
                    args.pipelineMode
                ).apply {
                    startBasic = false   // ✅ 확장 갤러리 탭을 기본 활성화
                }

            findNavController().navigate(action)
        }
        fragmentBinding.galleryButton.setOnClickListener(galleryButtonOriginalClickListener)
        //오토줌과 전면 렌즈 변경 버튼 표시
        updateTopRightButton()
        //진입시 렌즈 선택 버튼 표시
        showLensHUD()
        // 렌즈 스위칭 로직 바인딩
        bindLensButtons()
        //줌 슬라이터 값 변경
        lensMode = if (isTeleCurrent()) LensMode.TELE else LensMode.WIDE
        fragmentBinding.zoomLevelText.text = displayLabelFor(zoomLevel)
    }


    private fun applyZoomRatio(builder: CaptureRequest.Builder, ratio: Float) {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val z = ratio.coerceIn(1f, 10f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
        } else {
            // SCALER_CROP_REGION으로 1x=풀프레임, z>1이면 센터크롭
            val cx = active.centerX()
            val cy = active.centerY()
            val w = (active.width() / z).toInt()
            val h = (active.height() / z).toInt()
            val left = (cx - w / 2).coerceAtLeast(0)
            val top = (cy - h / 2).coerceAtLeast(0)
            val right = (left + w).coerceAtMost(active.right)
            val bottom = (top + h).coerceAtMost(active.bottom)
            builder.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(left, top, right, bottom))
        }
    }

    // 물리 카메라 강제 라우팅 시 사용: per-physical 키로 줌/크롭 적용
    private fun applyZoomRatio(builder: CaptureRequest.Builder, ratio: Float, forcePhysicalId: String?) {
        val z = ratio.coerceIn(1f, 10f)
        if (forcePhysicalId.isNullOrEmpty()) {
            applyZoomRatio(builder, z)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: per-physical zoom ratio
            builder.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, z, forcePhysicalId)
        } else {
            // 하위: per-physical SCALER_CROP_REGION
            val physChars = cameraManager.getCameraCharacteristics(forcePhysicalId)
            val active = physChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cx = active.centerX()
            val cy = active.centerY()
            val w = (active.width() / z).toInt()
            val h = (active.height() / z).toInt()
            val left = (cx - w / 2).coerceAtLeast(0)
            val top = (cy - h / 2).coerceAtLeast(0)
            val right = (left + w).coerceAtMost(active.right)
            val bottom = (top + h).coerceAtMost(active.bottom)
            val rect = android.graphics.Rect(left, top, right, bottom)
            builder.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, rect, forcePhysicalId)
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerFocusAtPoint(xNormView: Float, yNormView: Float) {
        val sensorActive = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val previewSize = currentPreviewSize
        if (previewSize == null) {
            Log.w(TAG, "previewSize not ready; fallback to view size mapping only")
            return
        }

        val content = computeContentRect(
            viewW = fragmentBinding.viewFinder.width,
            viewH = fragmentBinding.viewFinder.height,
            bufW = previewSize.width,
            bufH = previewSize.height
        )

        val xInContent = (xNormView * fragmentBinding.viewFinder.width  - content.left) / content.width()
        val yInContent = (yNormView * fragmentBinding.viewFinder.height - content.top ) / content.height()
        val xClamped = xInContent.coerceIn(0f, 1f)
        val yClamped = yInContent.coerceIn(0f, 1f)

        val visibleOnSensor: android.graphics.Rect =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                computeCropByZoomRatio(sensorActive, zoomLevel)
            } else {
                computeCenterCropByUiZoom(sensorActive, zoomLevel)
            }

        var sx = (visibleOnSensor.left + xClamped * visibleOnSensor.width()).toInt()
        var sy = (visibleOnSensor.top  + yClamped * visibleOnSensor.height()).toInt()

        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            sx = visibleOnSensor.left + visibleOnSensor.right - sx
        }

        val boxSize = (minOf(visibleOnSensor.width(), visibleOnSensor.height()) * 0.12f)
            .toInt().coerceAtLeast(80)
        val left = (sx - boxSize/2).coerceIn(visibleOnSensor.left, visibleOnSensor.right - boxSize)
        val top  = (sy - boxSize/2).coerceIn(visibleOnSensor.top,  visibleOnSensor.bottom - boxSize)
        val rect = android.graphics.Rect(left, top, left + boxSize, top + boxSize)
        val metering = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(pipeline.getPreviewTargets().first())
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
            // 필요 시 AWB도 동일하게
            // set(CaptureRequest.CONTROL_AWB_REGIONS, arrayOf(metering))
            // 물리카메라 강제 사용 중이면 per-physical key도 고려(주석 참조)
        }

        session.stopRepeating()
        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                cameraHandler.postDelayed({
                    val preview = previewRequest ?: request
                    session.setRepeatingRequest(preview, null, cameraHandler)
                }, 50)
            }
        }, cameraHandler)
    }

    /** 뷰 안에서 프리뷰 버퍼가 차지하는 '콘텐츠 사각형'(레터/필러 박스 보정용)을 구한다 */
    private fun computeContentRect(viewW: Int, viewH: Int, bufW: Int, bufH: Int): android.graphics.RectF {
        val viewAR = viewW / viewH.toFloat()
        val bufAR  = bufW  / bufH.toFloat()
        return if (bufAR > viewAR) {
            // 좌우가 맞고 위아래가 여백
            val contentW = viewW.toFloat()
            val contentH = contentW / bufAR
            val top = (viewH - contentH) / 2f
            android.graphics.RectF(0f, top, contentW, top + contentH)
        } else {
            // 위아래가 맞고 좌우가 여백
            val contentH = viewH.toFloat()
            val contentW = contentH * bufAR
            val left = (viewW - contentW) / 2f
            android.graphics.RectF(left, 0f, left + contentW, contentH)
        }
    }

    /** GL 기반 내부 줌(zoomLevel)에 맞춰, 센서 active array에서 보이는 영역을 센터 크롭으로 계산 */
    private fun computeCenterCropByUiZoom(active: android.graphics.Rect, zoom: Float): android.graphics.Rect {
        val z = zoom.coerceIn(1f, 10f)
        val newW = (active.width()  / z).toInt()
        val newH = (active.height() / z).toInt()
        val cx = active.centerX()
        val cy = active.centerY()
        return android.graphics.Rect(
            (cx - newW/2).coerceAtLeast(active.left),
            (cy - newH/2).coerceAtLeast(active.top),
            (cx + newW/2).coerceAtMost(active.right),
            (cy + newH/2).coerceAtMost(active.bottom)
        )
    }

    /** (선택) CONTROL_ZOOM_RATIO를 실제로 쓰는 경우의 가시 영역 근사 */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun computeCropByZoomRatio(active: android.graphics.Rect, zoom: Float): android.graphics.Rect {
        // ZOOM_RATIO = 1/scale 과 동치 → 센터 크롭
        return computeCenterCropByUiZoom(active, zoom)
    }



    private fun isCurrentlyRecording(): Boolean {
        return recordingStarted && !recordingComplete
    }

    private fun createEncoder(name: String): EncoderWrapper {
        var width = args.width
        var height = args.height
        var orientationHint = orientation

        if (args.useHardware) {
            if (orientation == 90 || orientation == 270) {
                width = args.height
                height = args.width
            }
            orientationHint = 0
        }

        // 🔑 세션 UUID + 인코더 종류(zoomed/original) + 타임스탬프를 파일명에 포함
        val tag = "${sessionUuid}_${name}" // 세션 UUID와 인코더 이름 합치기
        val file = createFile(requireContext(), "mp4", tag)

        Log.d("EncoderDebug", "🎬 Encoder [$name] will write to: ${file.absolutePath}")

        return EncoderWrapper(
            name,
            width,
            height,
            RECORDER_VIDEO_BITRATE,
            args.fps,
            args.dynamicRange,
            orientationHint,
            file,
            args.useMediaRecorder,
            args.videoCodec
        )
    }


    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        val previewTargets = pipeline.getPreviewTargets()
        val sessionTargets = buildList {
            addAll(previewTargets)
            stillReader?.surface?.let { add(it) }
        }
        val forcePhysicalId = args.forcePhysicalId
        session = createCaptureSession(
            device = camera,
            targets = sessionTargets,
            handler = cameraHandler,
            recordingCompleteOnClose = (pipeline !is SoftwarePipeline),
            forcePhysicalCameraId = forcePhysicalId
        )

        // ★ UI/상태를 명시적으로 1x로
        zoomLevel = 1.0f
        fragmentBinding.zoomSlider.progress = 0
        fragmentBinding.zoomLevelText.text = "1.00x"

        // ★ 파이프라인 요청 대신, 우리가 만든 “1x 고정” 부트스트랩 요청으로 시작
        val bootstrap = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            previewTargets.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // 필요 시 미리보기 안정화 옵션도 여기서 넣기 (기기별로 둘 중 하나)
            // set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)

            // ★ 1x FOV을 “명시”
            applyZoomRatio(this, 1.0f)
        }.build()

        session.setRepeatingRequest(bootstrap, null, cameraHandler)

        // ★ 그 다음 파이프라인에도 1x로 맞추도록 동기화
        setZoomLevel(1.0f)  // 내부 파이프라인(쉐이더/크롭 등)도 1x로
    }


    private fun startRecording() = lifecycleScope.launch(Dispatchers.IO) {


        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        pipeline.actionDown(encoderSurface,originalencoderSurface)

        // — 여기에 프레임 타임스탬프 초기화
        frameTimestamps.clear()

        // ① 프레임 인코딩 타임스탬프 리스너 등록
        encoder.setOnFrameEncodedListener { ts ->
            frameTimestamps.add(ts)
        }

        recordingStarted = true
        encoder.start()
        originalencoder.start()
        cvRecordingStarted.open()
        pipeline.startRecording()



        // 6. UI 업데이트
        recordingStartMillis = System.currentTimeMillis()
        Log.d(TAG, "Recording started")

        withContext(Dispatchers.Main) {
            // 🔽 기존 UI 업데이트
            fragmentBinding.captureButton.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_shutter_pressed)
            fragmentBinding.captureTimer?.visibility = View.VISIBLE
            fragmentBinding.captureTimer?.start()

            // 🔥 상단 우측 버튼을 '오토줌 토글' 모드로 전환
            updateTopRightButton()

            // 📸 갤러리 버튼을 사진 촬영 버튼으로 변경
            galleryButtonOriginalDrawable = fragmentBinding.galleryButton.drawable
            fragmentBinding.galleryButton.setImageResource(R.drawable.ic_shutter_normal)
            fragmentBinding.galleryButton.setOnClickListener {
                captureStillPicture()
            }
            
            // 🎥 녹화 중에는 렌즈 선택 버튼과 사진 모드 버튼 숨기기
            fragmentBinding.lensSelector.visibility = View.GONE
            requireView().findViewById<View>(R.id.btn_mode_photo)?.visibility = View.GONE
        }

        // MediaStore에 JSONL 파일 등록
        val videoPrefix = encoder.outputFile.nameWithoutExtension
        val logUri = createLogUri(requireContext(), "tracking_log_${sessionUuid}")
        if (logUri != null) {
            requireContext().contentResolver.openOutputStream(logUri)?.let { os ->
                logWriter = BufferedWriter(OutputStreamWriter(os))
                logEntryCount = 0
                frameIndex = 0L
            } ?: Log.e(TAG, "로그 스트림 생성 실패")
        } else {
            Log.e(TAG, "MediaStore에 로그 파일 등록 실패")
        }
    }

    private fun stopRecording() = lifecycleScope.launch(Dispatchers.IO) {
        Log.d("RenderHandler", "🛑 stopRecording() 호출됨")

        pixelHandler.removeCallbacks(pixelRunnable)
        pixelThread.quitSafely()

        // 1. 녹화 시작 플래그 대기 및 첫 프레임 처리 보장
        cvRecordingStarted.block()
        encoder.waitForFirstFrame()
        originalencoder.waitForFirstFrame()

        // 2. 세션 중지 및 종료
        withContext(Dispatchers.Main) {
            session.stopRepeating()
            session.close()
        }

        // 3. 프레임 리스너 제거
        pipeline.clearFrameListener()

        // 4. UI 업데이트
        fragmentBinding.captureButton.post {
            fragmentBinding.captureButton.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_shutter_normal)
            fragmentBinding.captureTimer?.visibility = View.GONE
            fragmentBinding.captureTimer?.stop()
            fragmentBinding.captureButton.setOnTouchListener(null)
        }

        // 5. 세션 종료 대기
        cvRecordingComplete.block()

        // 6. 화면 회전 복원
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // 7. 최소 녹화 시간 보장
        val elapsed = System.currentTimeMillis() - recordingStartMillis
        Log.d(TAG, "🕒 최소 녹화 시간 보장 시작 (elapsed=${elapsed})")
        if (elapsed < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
            delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsed)
        }
        Log.d(TAG, "🕒 최소 녹화 시간 보장 완료")

        delay(CameraActivity.ANIMATION_SLOW_MILLIS)

        // 8. 파이프라인 정리
        Log.d(TAG, "🧹 pipeline.cleanup() 호출 직전")
        pipeline.cleanup()
        Log.d(TAG, "✅ pipeline.cleanup() 호출 완료")


        // 9. 인코더 shutdown (동기적으로 안전하게 수행)
        originalencoder.shutdown()
        encoder.shutdown()

        // 🔟 shutdown 이후 MediaScanner에 등록 (갤러리 표시용)
        val outputFiles = listOf(encoder.outputFile, originalencoder.outputFile)
        MediaScannerConnection.scanFile(
            requireContext(),
            outputFiles.map { it.absolutePath }.toTypedArray(),
            null,
            null
        )
        Log.d(TAG, "✅ 모든 비디오 파일이 MediaScanner에 등록되었습니다.")

        // — 로그 플러시 & 스트림 닫기
        synchronized(this@CustomPreviewFragment) {
            if (this@CustomPreviewFragment::logWriter.isInitialized) {
                logWriter.flush()
                logWriter.close()
                Log.d(TAG, "📄 Tracking log saved to Downloads/Camera2App")
            }
        }

        // 프레임 타임스탬프 저장 (Downloads/Camera2App에 MediaStore로 등록)
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val jsonArray = gson.toJson(frameTimestamps)
        val tsPrefix = "tracking_log_${sessionUuid}_frame_ts"
        val tsUri = createLogUri(requireContext(), tsPrefix)
        if (tsUri != null) {
            requireContext().contentResolver.openOutputStream(tsUri)?.use { os ->
                os.write(jsonArray.toByteArray())
            } ?: Log.e(TAG, "타임스탬프 스트림 획득 실패")
            Log.d(TAG, "📄 Frame timestamps saved: $tsUri")
        } else {
            Log.e(TAG, "MediaStore에 타임스탬프 파일 등록 실패")
        }


        // 10. 상태 플래그 업데이트 + 버튼 복구는 메인에서
        withContext(Dispatchers.Main) {
            recordingStarted = false
            // 🔥 상단 우측 버튼을 '전면 전환' 모드로 복귀
            updateTopRightButton()

            // 📸 갤러리 버튼을 원래대로 복원
            galleryButtonOriginalDrawable?.let {
                fragmentBinding.galleryButton.setImageDrawable(it)
            } ?: run {
                updateGalleryThumbnail()  // 원래 drawable이 없으면 썸네일 다시 설정
            }
            galleryButtonOriginalClickListener?.let {
                fragmentBinding.galleryButton.setOnClickListener(it)
            }
            
            // 🎥 녹화 종료 시 렌즈 선택 버튼과 사진 모드 버튼 다시 보이기
            fragmentBinding.lensSelector.visibility = View.VISIBLE
            requireView().findViewById<View>(R.id.btn_mode_photo)?.visibility = View.VISIBLE
        }

        // 11. UI 화면 복귀는 무조건 메인스레드에서 안전하게 실행
        Handler(Looper.getMainLooper()).post {
            navController.popBackStack()
        }


        if (this@CustomPreviewFragment::logFile.isInitialized) {
            Log.d(TAG, "📄 Tracking log saved: ${logFile.absolutePath}")
        }

    }

    //전면카메라로 가는 네비게이션
    // 전면 카메라 ID
    private fun getFrontCameraId(): String? {
        val cm = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
    }

    // 전면 프리뷰로 전환
    private fun navigateToFrontPreview() {
        val frontId = getFrontCameraId()
        if (frontId == null) {
            Toast.makeText(requireContext(), "전면 카메라를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // ⚠️ 네비게이션 그래프에 이 액션 추가해야 함:
        // <action android:id="@+id/action_customPreview_to_customFrontPreview" .../>
        val action = CustomPreviewFragmentDirections
            .actionCustomPreviewToCustomFrontPreview(
                frontId,                          // 가능하면 전면 ID를 전달 (특히 characteristics 정확도↑)
                args.width,
                args.height,
                args.fps,
                args.dynamicRange,
                args.colorSpace,
                args.previewStabilization,
                args.useMediaRecorder,
                args.videoCodec,
                false,                            // 전면은 기본 카메라 역할 → 필터/트래킹 off 권장
                args.transfer,
                true,                           // 전면은 하드웨어 파이프라인 고정 사용할 거라면 true
                "hardware"                      // pipelineMode 추가
            ).apply {
                // forcePhysicalId는 nullable이므로 setter로 설정
                setForcePhysicalId(null)
            }

        findNavController().navigate(action)
    }

    private fun updateTopRightButton() {
        val btn = fragmentBinding.autoZoomButton

        if (!isCurrentlyRecording()) {
            // 🎛️ 녹화 중 아님 → 전면 전환 버튼
            btn.setImageResource(R.drawable.ic_camera_switch) // 전면 전환 아이콘
            btn.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.white))
            btn.setOnClickListener { navigateToFrontPreview() }
        } else {
            // 🎥 녹화 중 → 오토줌 토글
            btn.setImageResource(R.drawable.auto_zoom_btn) // 기존 오토줌 아이콘
            btn.setColorFilter(
                ContextCompat.getColor(
                    requireContext(),
                    if (autoZoom.isActive) android.R.color.holo_red_light else android.R.color.darker_gray
                )
            )
            btn.setOnClickListener {
                autoZoom.toggle()
                btn.setColorFilter(
                    ContextCompat.getColor(
                        requireContext(),
                        if (autoZoom.isActive) android.R.color.holo_red_light else android.R.color.darker_gray
                    )
                )
                Toast.makeText(
                    requireContext(),
                    if (autoZoom.isActive) "Auto-Zoom 시작" else "Auto-Zoom 해제",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ================== HUD 표시 ==================
    private fun showZoomHUD(text: String) {
        fragmentBinding.zoomLevelText.text = text
        fragmentBinding.zoomLevelText.visibility = View.VISIBLE
        fragmentBinding.lensSelector.visibility = View.GONE
    }

    private fun showLensHUD() {
        fragmentBinding.zoomLevelText.visibility = View.GONE
        fragmentBinding.lensSelector.visibility = View.VISIBLE
    }

    // ================== 카메라 ID 탐색 ==================
    private fun getBackWidePhysicalId(): String? {
        val cm = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val logicalId = args.cameraId
        val logicalChars = cm.getCameraCharacteristics(logicalId)

        val caps = logicalChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return null
        }

        val physicalIds = logicalChars.physicalCameraIds
        if (physicalIds.isEmpty()) return null

        var best: Pair<String, Float>? = null // (id, focalLength)

        for (pid in physicalIds) {
            val ch = cm.getCameraCharacteristics(pid)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: continue
            val minF = focals.minOrNull() ?: continue
            if (minF < 5f) continue // 초광각 필터

            if (best == null || minF < best.second) {
                best = pid to minF
            }
        }

        best?.first?.let {
            Log.d("Camera", "논리($logicalId) → 물리 광각 ID: $it, minF=${best!!.second}")
        }
        return best?.first
    }

    private fun getBackTelePhysicalId(): String? {
        val cm = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val logicalId = args.cameraId
        val logicalChars = cm.getCameraCharacteristics(logicalId)

        val caps = logicalChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return null
        }

        val physicalIds = logicalChars.physicalCameraIds
        if (physicalIds.isEmpty()) return null

        var best: Pair<String, Float>? = null // (id, focalLength)

        for (pid in physicalIds) {
            val ch = cm.getCameraCharacteristics(pid)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: continue
            val maxF = focals.maxOrNull() ?: continue

            if (best == null || maxF > best.second) {
                best = pid to maxF
            }
        }

        best?.first?.let {
            Log.d("Camera", "논리($logicalId) → 물리 망원 ID: $it, maxF=${best!!.second}")
        }
        return best?.first
    }

    private fun isTeleCurrent(): Boolean {
        val teleId = getBackTelePhysicalId()
        return teleId != null && args.forcePhysicalId == teleId
    }

    private fun updateLensSelectorUI(isTele: Boolean) {
        val w = fragmentBinding.btnWide
        val t = fragmentBinding.btnTele
        if (isTele) {
            w.background = ContextCompat.getDrawable(requireContext(), R.drawable.lens_chip_inactive)
            t.background = ContextCompat.getDrawable(requireContext(), R.drawable.lens_chip_active)
            w.setTextColor(Color.WHITE)
            t.setTextColor(Color.BLACK)
        } else {
            w.background = ContextCompat.getDrawable(requireContext(), R.drawable.lens_chip_active)
            t.background = ContextCompat.getDrawable(requireContext(), R.drawable.lens_chip_inactive)
            w.setTextColor(Color.BLACK)
            t.setTextColor(Color.WHITE)
        }
    }

    // ================== 버튼 바인딩 ==================
    private fun bindLensButtons() {
        updateLensSelectorUI(isTeleCurrent())
        showLensHUD()

        // 광각 버튼
        fragmentBinding.btnWide.setOnClickListener {
            val widePhysicalId = getBackWidePhysicalId()
            if (widePhysicalId == null) {
                Toast.makeText(requireContext(), "광각 물리ID를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (args.forcePhysicalId != widePhysicalId) {
                reloadWithNewPipeline(args.cameraId, args.pipelineMode, widePhysicalId)
            } else {
                lensMode = LensMode.WIDE
                startSmoothZoom(zoomLevel, 1.0f)
                updateLensSelectorUI(false)
                showLensHUD()
            }
        }

        // 망원 버튼
        fragmentBinding.btnTele.setOnClickListener {
            val telePhysicalId = getBackTelePhysicalId()
            if (telePhysicalId == null) {
                lensMode = LensMode.TELE
                startSmoothZoom(zoomLevel, 3.0f)
                updateLensSelectorUI(true)
                showLensHUD()
                Toast.makeText(requireContext(), "망원 ID 없음 → 3x로 전환", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (args.forcePhysicalId != telePhysicalId) {
                reloadWithNewPipeline(args.cameraId, args.pipelineMode, telePhysicalId)
            } else {
                lensMode = LensMode.TELE
                startSmoothZoom(zoomLevel, 3.0f)
                updateLensSelectorUI(true)
                showLensHUD()
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
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
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

    /**
     * Creates a [CameraCaptureSession] with the dynamic range profile set.
     */
    private fun setupSessionWithDynamicRangeProfile(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler,
        stateCallback: CameraCaptureSession.StateCallback,
        forcePhysicalCameraId: String? // ✅ 물리카메라 강제 ID
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // P(28)+ : SessionConfiguration / OutputConfiguration 사용
            val outputConfigs = targets.map { surface ->
                OutputConfiguration(surface).apply {
                    // T(33)+ : HDR 프로파일 지정
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setDynamicRangeProfile(args.dynamicRange)
                    }
                    // 물리 카메라 라우팅 (null이면 미설정)
                    if (!forcePhysicalCameraId.isNullOrEmpty()) {
                        setPhysicalCameraId(forcePhysicalCameraId)
                    }
                }
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                HandlerExecutor(handler),
                stateCallback
            )

            // UDC(34)+ : ColorSpace 지정
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                args.colorSpace != ColorSpaceProfiles.UNSPECIFIED) {
                sessionConfig.setColorSpace(ColorSpace.Named.values()[args.colorSpace])
            }

            device.createCaptureSession(sessionConfig)
            true
        } else {
            // P 미만: 물리 카메라 강제 불가 → 레거시 경로
            device.createCaptureSession(targets, stateCallback, handler)
            false
        }
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler,
        recordingCompleteOnClose: Boolean,
        forcePhysicalCameraId: String? = null // ← 추가 (기본값 null)
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

        // ✅ 여기서 물리 카메라 강제 id 전달
        setupSessionWithDynamicRangeProfile(device, targets, handler, stateCallback, forcePhysicalCameraId)
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }


    override fun onDestroy() {
        super.onDestroy()

        pipeline.clearFrameListener()
        pipeline.cleanup()
        cameraThread.quitSafely()
        encoderSurface.release()
        originalencoderSurface.release()

    }

    override fun onDestroyView() {

        _fragmentBinding = null
        super.onDestroyView()

        // 1) PixelCopy 루프 중단
        if (::pixelHandler.isInitialized && ::pixelRunnable.isInitialized) {
            pixelHandler.removeCallbacks(pixelRunnable)
        }
        if (::pixelThread.isInitialized) {
            pixelThread.quitSafely()
        }
        // 2) PoseDetector 해제
        if (::poseDetector.isInitialized) {
            poseDetector.close()
        }
        // 3) zoomAnimator 취소
        zoomAnimator?.cancel()
        zoomAnimator = null

        // 4) 카메라 파이프라인 프레임 리스너 중단
        pipeline.clearFrameListener()

    }

    companion object {
        private val TAG = PreviewFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L


        private fun createFile(context: Context, extension: String, tag: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val uniqueSuffix = System.nanoTime() % 100000
            val fileName = "VID_${tag}_${sdf.format(Date())}_$uniqueSuffix.$extension"

            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera2App")
            if (!publicDir.exists()) publicDir.mkdirs()

            val file = File(publicDir, fileName)
            Log.d("FileDebug", "📂 [$tag] Public video file path: ${file.absolutePath}")
            return file
        }
    }

    private fun createLogUri(context: Context, prefix: String): Uri? {
        val resolver = context.contentResolver
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Files.getContentUri("external")

        val values = ContentValues().apply {
            // ★ 확장자(.jsonl) 없이 이름만 지정
            put(MediaStore.MediaColumns.DISPLAY_NAME, prefix)
            // jsonl 이 아닌 일반 json MIME 타입으로
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Camera2App"
                )
            }
        }
        return resolver.insert(collection, values)
    }

    private fun updateGalleryThumbnail() {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera2App"
        )

        Log.d("ThumbDebug", "dir path = ${dir.absolutePath}, exists=${dir.exists()}")

        val videoFiles = dir.listFiles { f -> f.extension.equals("mp4", true) }
            ?: run {
                Log.d("ThumbDebug", "listFiles() == null")
                return
            }

        Log.d("ThumbDebug", "mp4 count = ${videoFiles.size}")

        val latest = videoFiles
            .sortedByDescending { it.lastModified() }
            .firstOrNull()
            ?: run {
                Log.d("ThumbDebug", "no latest mp4 found (size=${videoFiles.size})")
                return
            }

        Log.d("ThumbDebug", "latest file = ${latest.absolutePath}, lastModified=${latest.lastModified()}")

        // 🔥 ThumbnailUtils 대신 MediaMetadataRetriever로 직접 썸네일 만들기
        val thumb = try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(latest.absolutePath)
            // 0초 근처 키프레임 하나 가져오기
            val bmp = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            bmp
        } catch (e: Exception) {
            Log.e("ThumbDebug", "MediaMetadataRetriever 썸네일 생성 실패", e)
            null
        }

        if (thumb == null) {
            Log.d("ThumbDebug", "썸네일이 null이라 갤러리 버튼 업데이트 스킵")
            return
        }

        Log.d("ThumbDebug", "썸네일 생성 성공 → 버튼에 적용")

        fragmentBinding.galleryButton.post {
            fragmentBinding.galleryButton.scaleType = ImageView.ScaleType.CENTER_CROP
            fragmentBinding.galleryButton.setImageBitmap(thumb)
        }
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
                            Toast.makeText(requireContext(), "사진 저장 완료!", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), "사진 촬영 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    class FocusRingView(context: Context) : View(context) {
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
        override fun onDraw(c: Canvas) { c.drawCircle(cx, cy, radius, p2); c.drawCircle(cx, cy, radius, p1) }
    }

    fun reloadWithNewPipeline(
        newCameraId: String,
        mode: String,
        forcePhysicalId: String? = null
    ) {
        val action = CustomPreviewFragmentDirections.actionSelfReloadWithMode(
            newCameraId,
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
            mode
        ).apply {
            // 선택 인자는 setter로 주입해야 함
            // (생성된 메서드가 setForcePhysicalId 또는 프로퍼티 할당일 수 있음)
            forcePhysicalId?.let {
                try { setForcePhysicalId(it) } catch (_: Throwable) { /* 일부 버전은 프로퍼티 형태 */ }
            }
        }

        findNavController().navigate(action)
    }


}