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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
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
import com.echoshot.app.utils.DeploymentModeManager
import com.echoshot.app.utils.setupBottomNavigationBar
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
import com.echoshot.app.ui.VideoZoomRulerAdapter
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
import java.util.concurrent.atomic.AtomicBoolean
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
import android.media.MediaRecorder
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
    
    // 줌 룰러 관련 변수 (동영상 촬영용 별도 UI)
    private var zoomAdapter: VideoZoomRulerAdapter? = null
    private var zoomRuler: RecyclerView? = null
    private val cameraMinZoom = 1.0f
    private val cameraMaxZoom = 10.0f
    
    // ✅ 사용자가 직접 터치로 스크롤 중인지 구분 (오토줌이 스크롤할 때는 false)
    private var isUserTouchingZoomRuler = false

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

    // ✅ 포즈 오버레이 표시/숨김 제어 (기본값: false = 숨김)
    private var showPoseOverlay = false

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

    private val _pipelineLazy = lazy {
        val pipelineMode = args.pipelineMode
        Log.i(TAG, "Creating preview pipeline: mode=$pipelineMode (arg=${args.pipelineMode})")
        when (pipelineMode) {
            "hardware" -> CustomHardwarePipeline(
                args.width, args.height, args.fps, args.filterOn, args.transfer,
                args.dynamicRange, characteristics, encoder, originalencoder, fragmentBinding.viewFinder,
                args.forcePhysicalId  // ✅ 물리 카메라 ID 전달
            )
            "default", "hybrid" -> CustomHardwarePipelineDefault( // Hybrid 모드는 기본 줌 적용 파이프라인이라고 가정
                args.width, args.height, args.fps, args.filterOn, args.transfer,
                args.dynamicRange, characteristics, encoder, originalencoder, fragmentBinding.viewFinder,
                args.forcePhysicalId  // ✅ 물리 카메라 ID 전달
            )
            "bottom" -> CustomHardwarePipelineBottomCrop(
                args.width, args.height, args.fps, args.filterOn, args.transfer,
                args.dynamicRange, characteristics, encoder, originalencoder, fragmentBinding.viewFinder,
                args.forcePhysicalId
            )
            "software" -> SoftwarePipeline(
                args.width, args.height, args.fps, args.filterOn,
                args.dynamicRange, characteristics, encoder, fragmentBinding.viewFinder
            )
            else -> throw IllegalArgumentException("❌ 지원하지 않는 pipelineMode: ${args.pipelineMode}")
        }.also { Log.i(TAG, "Preview pipeline created: ${it::class.java.simpleName}") }
    }
    private val pipeline: Pipeline get() = _pipelineLazy.value

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

    // ✅ 중복 호출 방지를 위한 원자 플래그
    private val isRecording = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
    
    // ✅ 백그라운드 정리 작업 관리
    private var cleanupJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var isPausedForBackground = false
    
    // ✅ 버튼 디바운스용
    @Volatile
    private var lastStopClick = 0L
    private val STOP_DEBOUNCE_MS = 600L

    /** Condition variable for blocking until the recording completes */
    private val cvRecordingStarted = ConditionVariable(false)
    private val cvRecordingComplete = ConditionVariable(false)


    // ① PoseDetector 인스턴스 (TFLite 로드 실패 시 null)
    private var poseDetector: PoseDetector? = null

    // ② PixelCopy 루프용 핸들러 & Runnable
    private lateinit var pixelHandler: Handler
    private lateinit var pixelRunnable: Runnable

    // AutoZoomController
    private val autoZoom = AutoZoomController()

    // 클래스 멤버로 추가 안전한 종료를 위함
    private lateinit var pixelThread: HandlerThread

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

    // 후처리를 위한 로깅정보 변수
    private lateinit var logWriter: BufferedWriter
    private lateinit var logFile: File
    private var logEntryCount = 0
    private val LOG_FLUSH_INTERVAL = 50
    private var frameIndex = 0L
    private val frameTimestamps = mutableListOf<Long>()

    /** SurfaceView bitmap → 모델 추론용 PixelCopy 루프 함수 */
    private fun startPixelCopyLoop() {
        // 중복 실행 방지: 이미 실행 중이면 무시
        if (::pixelThread.isInitialized && pixelThread.isAlive) {
            Log.w(TAG, "PixelCopy 루프가 이미 실행 중입니다")
            return
        }
        
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
        
        // ✅ PoseDetector가 null이면 추론 스킵하지만 로그는 기록 (TFLite 로드 실패한 저사양 기기)
        val detector = poseDetector
        
        // 1) 추론 (detector가 null이면 스킵)
        val peopleWithBox = if (detector != null) {
            val rawPeople = detector.estimatePoses(bitmap)
            
            // Single‑Pose 모델에는 boundingBox가 없으니 keyPoints로 박스 계산
            // 로깅/오버레이용: 전체 키포인트로 박스 계산
            rawPeople.map { person ->
                val xs = person.keyPoints.map { it.coordinate.x }
                val ys = person.keyPoints.map { it.coordinate.y }
                val left   = xs.minOrNull() ?: 0f
                val right  = xs.maxOrNull() ?: 0f
                val top    = ys.minOrNull() ?: 0f
                val bottom = ys.maxOrNull() ?: 0f
                person.boundingBox = RectF(left, top, right, bottom)
                person
            }
        } else {
            // ✅ 트래킹 비활성화: 빈 리스트
            emptyList()
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
            // ✅ 검출 없거나 트래킹 비활성화: bbox=null, keypoints는 17개 [0,0,0]
            val emptyK = List(17) { listOf(0f, 0f, 0f) }
            Pair(null, emptyK)
        }

        // 3) **무조건** 로그 남기기 (트래킹 비활성화되어도 줌/프레임 데이터는 기록)
        if (recordingStarted && this::logWriter.isInitialized) {
            logTrackingFrame(
                frameIndex++,
                System.nanoTime(),
                zoomLevel,
                bboxArr,    // null (트래킹 비활성화 또는 검출 없음)
                kpArr       // 항상 17개 (트래킹 비활성화 시 모두 [0,0,0])
            )
        }

        // 4)Auto‑Zoom 타깃 계산 (상체만 사용) - 트래킹 비활성화 시 스킵
        if (peopleWithBox.isNotEmpty() && autoZoom.isActive) {
            val person = peopleWithBox[0]
            
            // 상체 키포인트 인덱스: 머리(0-4), 어깨(5-6), 엉덩이(11-12)
            val torsoKeyPointIndices = listOf(0, 1, 2, 3, 4, 5, 6, 11, 12)
            val torsoKeyPoints = person.keyPoints.filterIndexed { index, _ -> 
                index in torsoKeyPointIndices && person.keyPoints[index].score >= 0.3f
            }
            
            // 상체 키포인트로만 바운딩박스 계산
            val torsoBox = if (torsoKeyPoints.isNotEmpty()) {
                val torsoXs = torsoKeyPoints.map { it.coordinate.x }
                val torsoYs = torsoKeyPoints.map { it.coordinate.y }
                val left   = torsoXs.minOrNull() ?: 0f
                val right  = torsoXs.maxOrNull() ?: 0f
                val top    = torsoYs.minOrNull() ?: 0f
                val bottom = torsoYs.maxOrNull() ?: 0f
                RectF(left, top, right, bottom)
            } else {
                // 상체 키포인트가 없으면 전체 박스 사용 (fallback)
                person.boundingBox!!
            }

            // 1) confidence 0.3 이상인 포인트만 세기 (전체 키포인트 기준)
            val validKeyPointCount = person.keyPoints.count { it.score >= 0.3f }

            autoZoom.update(
                currentZoom    = zoomLevel,
                box            = torsoBox,  // 상체만으로 계산한 박스 사용
                viewW          = fragmentBinding.viewFinder.width,
                viewH          = fragmentBinding.viewFinder.height,
                keyPointCount  = validKeyPointCount  // '실제 검출된' 개수
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
                // 1) 오버레이 갱신 (표시 상태일 때만)
                if (showPoseOverlay) {
                    fragmentBinding.poseOverlayView.apply {
                        people = peopleWithBox
                        invalidate()
                    }
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
                    scrollZoomRulerTo(zoomLevel)
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
                scrollZoomRulerTo(z)
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
            is CustomHardwarePipelineBottomCrop ->
                (pipeline as CustomHardwarePipelineBottomCrop).setZoomLevel(z)
        }
    }
    
    /** 줌 룰러 초기화 (PhotoFragment와 동일한 방식) */
    private fun setupZoomRuler() {
        zoomRuler = fragmentBinding.zoomRuler
        val rv = zoomRuler ?: return
        
        val lm = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rv.layoutManager = lm
        
        // ✅ 스냅 헬퍼 제거 - 자유로운 스크롤
        
        // 1.0x ~ 10.0x 범위의 어댑터 생성 (동영상 촬영용)
        val itemWidthDp = 10
        val adapter = VideoZoomRulerAdapter(
            minZoom = cameraMinZoom,
            midZoom = cameraMinZoom, // 1.0x가 시작점
            maxZoom = cameraMaxZoom,
            ticksPerLogUnit = 10,    // ✅ 동영상용: 더 적은 눈금 (20 → 10)
            itemWidthDp = itemWidthDp,
            minLeftTicks = 0,
            minRightTicks = 0
        )
        zoomAdapter = adapter
        rv.adapter = adapter
        
        val itemWidthPx = (resources.displayMetrics.density * itemWidthDp).toInt()
        
        // ✅ PhotoFragment 방식: 패딩 = (화면절반) - (아이템절반)
        rv.post {
            if (rv.width <= 0) return@post
            
            val halfPadding = (rv.width / 2) - (itemWidthPx / 2)
            rv.setPadding(halfPadding, 0, halfPadding, 0)
            rv.clipToPadding = false
            
            // 패딩 설정 후 초기 위치로 스크롤 (0번 = 1.0x)
            lm.scrollToPositionWithOffset(0, 0)
        }
        
        // ✅ PhotoFragment 방식: 텍스트만 변경 최소화, 줌은 항상 즉시 업데이트
        var lastZoomText = "1.00x"
        var lastCalculatedZoom = 1.0f
        
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val adapter = zoomAdapter ?: return
                
                // ✅ PhotoFragment 방식: computeHorizontalScrollOffset 사용
                val scrollOffset = recyclerView.computeHorizontalScrollOffset().toFloat()
                
                // ✅ 위치를 범위 내로 제한 (오버스크롤 방지)
                val maxPos = (adapter.total - 1).coerceAtLeast(0)
                val posF = (scrollOffset / itemWidthPx).coerceIn(0f, maxPos.toFloat())
                
                val z = adapter.positionToZoom(posF).coerceIn(cameraMinZoom, cameraMaxZoom)
                lastCalculatedZoom = z
                
                // ✅ 사용자가 직접 터치해서 스크롤할 때만 오토줌 끄기
                if (isUserTouchingZoomRuler) {
                    disableAutoZoom()
                }
                zoomLevel = z
                setZoomLevel(z)
                
                // ✅ 텍스트는 변경될 때만 업데이트 (부드럽게)
                val newText = displayLabelFor(z)
                if (newText != lastZoomText) {
                    showZoomHUD(newText)  // ✅ W/T 숨기고 줌 레벨 표시
                    lastZoomText = newText
                }
            }
            
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        // ✅ 사용자가 터치해서 드래그 시작
                        isUserTouchingZoomRuler = true
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        // ✅ 스크롤 멈추면 최종 줌 확정
                        isUserTouchingZoomRuler = false
                        zoomLevel = lastCalculatedZoom
                        setZoomLevel(lastCalculatedZoom)
                    }
                    RecyclerView.SCROLL_STATE_SETTLING -> {
                        // 관성 스크롤 중 - 사용자 터치 상태 유지
                    }
                }
            }
        })
    }
    
    /** 줌 룰러를 특정 줌 값으로 스크롤 */
    private fun scrollZoomRulerTo(zoom: Float) {
        val rv = zoomRuler ?: return
        val adapter = zoomAdapter ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        
        val pos = adapter.zoomToPosition(zoom)
        lm.scrollToPositionWithOffset(pos, 0)
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

        // ✅ 카메라/세션 초기화 여부 체크
        if (!::camera.isInitialized) {
            Log.w(TAG, "⚠️ applyExposureComp: camera가 아직 초기화되지 않음")
            aeCompRange?.let { evSeek.progress = clamped - it.lower }
            return
        }
        if (!::session.isInitialized || isSessionClosed(session)) {
            Log.w(TAG, "⚠️ applyExposureComp: 세션이 닫혀있어 노출 보정 적용 불가")
            aeCompRange?.let { evSeek.progress = clamped - it.lower }
            return
        }

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                pipeline.getPreviewTargets().forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped)
                // (필요 시 AF/AWB 모드도 유지)
                // ✅ 망원 렌즈 비율 유지를 위해 16:9 크롭 적용
                apply16x9Crop(this, args.forcePhysicalId)
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ applyExposureComp 실패: ${e.message}")
        }

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

        // 네비게이션 바 설정
        setupBottomNavigationBar(
            currentPage = "camera",
            onHomeClick = {
                // 카메라에서 홈으로 이동
                val action = CustomPreviewFragmentDirections.actionCustomPreviewFragmentToHomeFragment()
                findNavController().navigate(action)
            },
            onGalleryClick = {
                // 카메라에서 갤러리로 이동
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
                        startBasic = false   // 확장 갤러리 탭을 기본 활성화
                    }
                findNavController().navigate(action)
            },
            onArchiveClick = {
                val action = CustomPreviewFragmentDirections.actionCustomPreviewFragmentToFancamEditFragment()
                findNavController().navigate(action)
            },
            onProfileClick = {
                // 배포모드일 때는 프로필로 이동하지 않음
                if (DeploymentModeManager.isDeploymentMode()) {
                    return@setupBottomNavigationBar
                }
                // 카메라에서 프로필로 이동
                val action = CustomPreviewFragmentDirections.actionCustomPreviewFragmentToProfileFragment()
                findNavController().navigate(action)
            },
            isRecording = { isCurrentlyRecording() }
        )

        updateGalleryThumbnail()

        // 뷰 세팅 직후, PixelCopy 등록 전에
        poseDetector = MoveNetMultiPose.create(
            requireContext(),
            Device.CPU,       // CPU / GPU / NNAPI
            Type.Dynamic      // Dynamic 모델(256×256) 또는 Type.Fixed
        )


        // ✅ 줌 룰러 설정 (사진촬영과 동일한 눈금자 스타일)
        setupZoomRuler()
        setupPipelineModeSwitch()

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

        // (선택) 동영상 버튼은 현재 화면이 동영상이므로 눌러도 변화 없게
        view.findViewById<View>(R.id.btn_mode_video)?.setOnClickListener {
            // 이미 동영상 모드
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
                    
                    // 줌 룰러 위치 업데이트
                    scrollZoomRulerTo(newZoom)

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

                        // 망원 카메라를 찾을 수 있을 때만 렌즈 버튼 표시
                        if (!isCurrentlyRecording() && !autoZoom.isActive && getBackTelePhysicalId() != null) {
                            showLensHUD()
                        }
                    }

                    longPressFired = false
                }
            }
            true
        }



        //녹화 시작 종료 버튼
        fragmentBinding.captureButton.setOnClickListener {
            Log.d(TAG, "버튼 눌림")

            if (!recordingStarted) {
                startRecording()
                Log.d(TAG, "녹화 시작")
            } else {
                // ✅ 디바운스: 짧은 시간 내 중복 클릭 방지
                val now = SystemClock.elapsedRealtime()
                if (now - lastStopClick < STOP_DEBOUNCE_MS) {
                    Log.d(TAG, "⏱️ 디바운스: 중복 클릭 무시")
                    return@setOnClickListener
                }
                lastStopClick = now
                
                lifecycleScope.launch {
                    stopRecording()
                }
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
                // ✅ fragmentBinding이 null이면 초기화 중단
                val binding = _fragmentBinding ?: run {
                    Log.w(TAG, "⚠️ surfaceCreated(): fragmentBinding이 null입니다. 초기화 중단")
                    return
                }
                
                // ✅ 1) 물리 카메라별로 16:9 프리뷰 사이즈 선택
                val previewSize = if (args.forcePhysicalId != null) {
                    pickPreviewSize16x9For(args.forcePhysicalId!!, SurfaceHolder::class.java)
                } else {
                    getPreviewOutputSize(
                        binding.viewFinder.display,
                        characteristics,
                        SurfaceHolder::class.java
                    )
                }

                // 뷰 비율/버퍼 고정 (버퍼를 먼저 고정해 두면 크롭 이슈가 줄어듦)
                holder.setFixedSize(previewSize.width, previewSize.height)
                binding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)

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

                binding.viewFinder.post {
                    // ✅ 이전 정리 작업이 진행 중이면 완료될 때까지 대기
                    if (cleanupJob?.isActive == true) {
                        Log.d(TAG, "⏳ SurfaceHolder: 이전 정리 작업 완료 대기 중...")
                        lifecycleScope.launch(Dispatchers.Main) {
                            cleanupJob?.join()
                            Log.d(TAG, "✅ SurfaceHolder: 이전 정리 작업 완료, 카메라 초기화 시작")
                            
                            // ✅ Surface 유효성 재확인 (정리 작업 완료 후 Surface가 유효한지 확인)
                            if (!holder.surface.isValid) {
                                Log.e(TAG, "❌ Surface가 유효하지 않습니다. 초기화 중단")
                                return@launch
                            }
                            
                            initPipelineWithRetry(holder.surface)
                        }
                    } else {
                        // ✅ Surface 유효성 확인
                        if (!holder.surface.isValid) {
                            Log.e(TAG, "❌ Surface가 유효하지 않습니다. 초기화 중단")
                            return@post
                        }
                        
                        initPipelineWithRetry(holder.surface)
                    }
                    // ✅ PixelCopy 루프는 녹화 시작 시에만 실행 (발열 방지)
                }
            }

        })



        // ✅ 파이프라인 모드 스위칭 기능 - 당장 사용하지 않으므로 주석처리
        /*
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
                    reloadWithNewPipeline(args.cameraId, "default")
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
        */

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
        
        // 망원 카메라 사용 가능 여부 확인
        val telePhysicalId = getBackTelePhysicalId()
        if (telePhysicalId != null) {
            // 망원 카메라를 찾을 수 있으면 렌즈 전환 버튼 표시
            //진입시 렌즈 선택 버튼 표시
            showLensHUD()
            // 렌즈 스위칭 로직 바인딩
            bindLensButtons()
        } else {
            // 망원 카메라를 찾을 수 없으면 렌즈 전환 버튼 숨기기
            fragmentBinding.lensSelector.visibility = View.GONE
        }
        
        //줌 슬라이터 값 변경
        lensMode = if (isTeleCurrent()) LensMode.TELE else LensMode.WIDE
        fragmentBinding.zoomLevelText.text = displayLabelFor(zoomLevel)

        // ✅ 포즈 오버레이 기본값: 숨김
        fragmentBinding.poseOverlayView.visibility = View.GONE

        // ✅ 설정 버튼 클릭 시 포즈 오버레이 토글
        fragmentBinding.iconSetting.setOnClickListener {
            showPoseOverlay = !showPoseOverlay
            fragmentBinding.poseOverlayView.visibility = if (showPoseOverlay) View.VISIBLE else View.GONE
        }
    }


    // ✅ 1) 물리 카메라별로 16:9 프리뷰 사이즈 선택
    private fun pickPreviewSize16x9For(physicalId: String, klass: Class<*>): Size {
        val ch = cameraManager.getCameraCharacteristics(physicalId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(klass) ?: return Size(1920, 1080)

        fun ar(s: Size) = s.width.toFloat() / s.height.toFloat()
        val targetAR = 16f / 9f
        
        // 16:9 중 가장 큰 것 우선 (없으면 전체 중 16:9에 가장 가까운 것)
        return sizes
            .filter { kotlin.math.abs(ar(it) - targetAR) < 0.02f }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minBy { kotlin.math.abs(ar(it) - targetAR) }
    }

    // ✅ 2) 센서 Active Array를 16:9로 센터-크롭
    private fun cropActiveToAspect(active: android.graphics.Rect, targetAR: Float): android.graphics.Rect {
        val curAR = active.width().toFloat() / active.height().toFloat()
        val (w, h) = if (curAR > targetAR) {
            // 현재가 더 넓음 → 높이 기준으로 너비 조정
            val h = active.height()
            val w = (h * targetAR).toInt()
            w to h
        } else {
            // 현재가 더 좁음 → 너비 기준으로 높이 조정
            val w = active.width()
            val h = (w / targetAR).toInt()
            w to h
        }
        val cx = active.centerX()
        val cy = active.centerY()
        val left = (cx - w / 2).coerceAtLeast(active.left)
        val top = (cy - h / 2).coerceAtLeast(active.top)
        val right = (left + w).coerceAtMost(active.right)
        val bottom = (top + h).coerceAtMost(active.bottom)
        return android.graphics.Rect(left, top, right, bottom)
    }

    // ✅ 센서에서 16:9로 센터-크롭 강제 적용
    private fun apply16x9Crop(builder: CaptureRequest.Builder, forcePhysicalId: String?) {
        val targetAR = 16f / 9f
        if (forcePhysicalId.isNullOrEmpty()) {
            val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cropRect = cropActiveToAspect(active, targetAR)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        } else {
            // 물리 카메라 ID가 있으면 해당 물리 카메라의 센서 크기 사용
            val physChars = cameraManager.getCameraCharacteristics(forcePhysicalId)
            val active = physChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cropRect = cropActiveToAspect(active, targetAR)
            // 세션 생성 시 setPhysicalCameraId로 라우팅했으므로 일반 set 사용
            // 논리 카메라를 사용할 때는 setPhysicalCameraKey가 세션에 등록되지 않을 수 있음
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        }
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
        // ✅ 카메라/세션 초기화 여부 체크
        if (!::camera.isInitialized) {
            Log.w(TAG, "⚠️ triggerFocusAtPoint: camera가 아직 초기화되지 않음")
            return
        }
        if (!::session.isInitialized || isSessionClosed(session)) {
            Log.w(TAG, "⚠️ triggerFocusAtPoint: 세션이 아직 초기화되지 않았거나 닫혀있음")
            return
        }

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
            // ✅ 망원 렌즈 비율 유지를 위해 16:9 크롭 적용
            apply16x9Crop(this, args.forcePhysicalId)
        }

        // ✅ 세션 상태 확인 후 안전하게 호출
        if (isSessionClosed(session)) {
            Log.w(TAG, "⚠️ triggerFocusAtPoint: 세션이 이미 닫혔습니다")
            return
        }
        
        runCatching {
            session.stopRepeating()
        }.onFailure { e ->
            Log.w(TAG, "⚠️ triggerFocusAtPoint에서 stopRepeating() 실패: ${e.message}")
            return
        }
        
        runCatching {
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    cameraHandler.postDelayed({
                        val preview = previewRequest ?: request
                        runCatching {
                            if (!isSessionClosed(session)) {
                                session.setRepeatingRequest(preview, null, cameraHandler)
                            }
                        }.onFailure { e ->
                            Log.w(TAG, "⚠️ setRepeatingRequest() 실패: ${e.message}")
                        }
                    }, 50)
                }
            }, cameraHandler)
        }.onFailure { e ->
            Log.w(TAG, "⚠️ triggerFocusAtPoint에서 capture() 실패: ${e.message}")
        }
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

    private fun setupPipelineModeSwitch() {
        val isBottomMode = args.pipelineMode == "bottom"

        fragmentBinding.iconZoom.visibility = View.GONE
        fragmentBinding.iconNozoom.text = if (isBottomMode) "B" else "C"
        fragmentBinding.iconNozoom.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))

        fragmentBinding.zoomModeOverlay.visibility = View.GONE
        fragmentBinding.zoomModeOverlay.setOnClickListener {
            fragmentBinding.zoomModeOverlay.visibility = View.GONE
        }
        fragmentBinding.zoomModePanel.setOnClickListener {
            // Keep taps inside the panel from closing the overlay.
        }

        fun switchTo(mode: String) {
            val currentMode = args.pipelineMode
            val alreadySelected = currentMode == mode || (mode == "default" && currentMode == "hybrid")
            if (alreadySelected) {
                fragmentBinding.zoomModeOverlay.visibility = View.GONE
                return
            }

            if (isCurrentlyRecording()) {
                Toast.makeText(requireContext(), "Cannot switch pipeline while recording", Toast.LENGTH_SHORT).show()
                return
            }

            Log.i(TAG, "Switching preview pipeline: from=$currentMode to=$mode")
            reloadWithNewPipeline(args.cameraId, mode, args.forcePhysicalId)
        }

        fun styleOption(view: View, enabled: Boolean, selected: Boolean) {
            view.isEnabled = enabled
            view.alpha = if (enabled) 1.0f else 0.42f
            view.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(if (selected) 230 else 190, 24, 24, 24))
                setStroke(
                    dp(if (selected) 3 else 1),
                    if (selected) Color.YELLOW else Color.argb(120, 255, 255, 255)
                )
            }
        }

        styleOption(fragmentBinding.zoomModeDefault, enabled = false, selected = false)
        styleOption(fragmentBinding.zoomModeCenter, enabled = true, selected = !isBottomMode)
        styleOption(fragmentBinding.zoomModeBottom, enabled = true, selected = isBottomMode)
        styleOption(fragmentBinding.zoomModeTop, enabled = false, selected = false)

        fragmentBinding.iconNozoom.setOnClickListener {
            fragmentBinding.zoomModeOverlay.visibility =
                if (fragmentBinding.zoomModeOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        fragmentBinding.zoomModeDefault.setOnClickListener(null)
        fragmentBinding.zoomModeCenter.setOnClickListener { switchTo("default") }
        fragmentBinding.zoomModeBottom.setOnClickListener { switchTo("bottom") }
        fragmentBinding.zoomModeTop.setOnClickListener(null)
    }

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

        Log.d("EncoderDebug", "🔍 [createEncoder] args: width=${args.width}, height=${args.height}, orientation=$orientation")

        if (args.useHardware) {
            if (orientation == 90 || orientation == 270) {
                width = args.height
                height = args.width
            }
            orientationHint = 0
        }

        // ✅ 해상도를 4K (3840x2160)로 강제 설정
        // orientation이 90/270이면 가로/세로를 바꿔서 2160x3840이 되어야 하지만,
        // 일반적으로는 3840x2160 (가로 x 세로)로 저장하는 것이 표준
        val targetWidth = 3840
        val targetHeight = 2160
        
        // orientation이 90/270이고 세로 모드 촬영이면 2160x3840으로 저장
        val finalWidth = if ((orientation == 90 || orientation == 270) && args.useHardware) {
            targetHeight  // 2160
        } else {
            targetWidth   // 3840
        }
        val finalHeight = if ((orientation == 90 || orientation == 270) && args.useHardware) {
            targetWidth   // 3840
        } else {
            targetHeight  // 2160
        }

        Log.d("EncoderDebug", "📐 [createEncoder] 최종 해상도: width=$finalWidth, height=$finalHeight (4K 강제 설정)")

        // 🔑 세션 UUID + 인코더 종류(zoomed/original) + 타임스탬프를 파일명에 포함
        val tag = "${sessionUuid}_${name}" // 세션 UUID와 인코더 이름 합치기
        val file = createFile(requireContext(), "mp4", tag)

        Log.d("EncoderDebug", "🎬 Encoder [$name] will write to: ${file.absolutePath}")

        return EncoderWrapper(
            name,
            finalWidth,
            finalHeight,
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
        // ✅ fragmentBinding이 null이면 초기화 중단 (뷰가 아직 생성되지 않음)
        val binding = _fragmentBinding ?: run {
            Log.w(TAG, "⚠️ initializeCamera(): fragmentBinding이 null입니다. 초기화 중단")
            return@launch
        }
        
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

        // ★ UI/상태를 명시적으로 1x로 (뷰가 유효할 때만)
        zoomLevel = 1.0f
        scrollZoomRulerTo(1.0f)
        binding.zoomLevelText.text = "1.00x"

        // ✅ 카메라가 닫힌 상태인지 확인 (race condition 방지)
        if (!::camera.isInitialized) {
            Log.w(TAG, "⚠️ initializeCamera(): camera가 초기화되지 않음. 중단")
            return@launch
        }

        // ★ 파이프라인 요청 대신, 우리가 만든 "1x 고정" 부트스트랩 요청으로 시작
        val bootstrap = try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                previewTargets.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // 필요 시 미리보기 안정화 옵션도 여기서 넣기 (기기별로 둘 중 하나)
                // set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)

                // ✅ 2) 센서에서 16:9로 센터-크롭 강제
                apply16x9Crop(this, args.forcePhysicalId)
            }.build()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "⚠️ initializeCamera(): CameraDevice가 이미 닫힘 - ${e.message}")
            return@launch
        }

        // ✅ 세션이 닫힌 상태인지 확인
        if (isSessionClosed(session)) {
            Log.w(TAG, "⚠️ initializeCamera(): 세션이 닫힘. setRepeatingRequest 스킵")
            return@launch
        }

        try {
            session.setRepeatingRequest(bootstrap, null, cameraHandler)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "⚠️ initializeCamera(): setRepeatingRequest 실패 - ${e.message}")
            return@launch
        }

        // ★ 그 다음 파이프라인에도 1x로 맞추도록 동기화
        setZoomLevel(1.0f)  // 내부 파이프라인(쉐이더/크롭 등)도 1x로
    }


    private fun startRecording() = lifecycleScope.launch(Dispatchers.IO) {
        // ✅ 중복 시작 방지
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "⚠️ 녹화가 이미 시작되었습니다. 중복 호출 무시")
            return@launch
        }
        isStopping.set(false)

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
        
        // ✅ 동영상 촬영 시작 시에만 포즈 추론 시작 (발열 방지)
        withContext(Dispatchers.Main) {
            startPixelCopyLoop()
        }



        // 6. UI 업데이트
        recordingStartMillis = System.currentTimeMillis()
        Log.d(TAG, "Recording started")

        withContext(Dispatchers.Main) {
            // 🔽 네비게이션 바가 펼쳐진 상태면 접기
            requireView().let { rootView ->
                com.echoshot.app.utils.NavigationBarUtils.collapseNavigationBar(rootView)
            }
            
            // 🔽 기존 UI 업데이트
            fragmentBinding.captureButton.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_shutter_pressed)
            fragmentBinding.captureTimer?.apply {
                visibility = View.VISIBLE
                text = "00:00:00"
            }
            // 타이머 업데이트 시작
            startTimerUpdate()

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

        // ✅ 재진입 방지: 이미 정지 중이면 무시
        if (!isRecording.get() || !isStopping.compareAndSet(false, true)) {
            Log.w(TAG, "⚠️ stopRecording() 중복 호출 무시 (이미 정지 중이거나 녹화 중이 아님)")
            return@launch
        }

        // ✅ UI에서 중복 클릭 방지
        withContext(Dispatchers.Main) {
            fragmentBinding.captureButton.isEnabled = false
        }

        try {
            // ✅ lateinit 변수 초기화 여부 체크
            if (::pixelHandler.isInitialized && ::pixelRunnable.isInitialized) {
                pixelHandler.removeCallbacks(pixelRunnable)
            }
            if (::pixelThread.isInitialized) {
                pixelThread.quitSafely()
            }

            // 1. 녹화 시작 플래그 대기 및 첫 프레임 처리 보장
            cvRecordingStarted.block()
            encoder.waitForFirstFrame()
            originalencoder.waitForFirstFrame()

            // 2. 세션 중지 및 종료 (안전하게)
            withContext(Dispatchers.Main) {
                // ✅ 세션 유효성 검사 및 예외 무해화
                runCatching {
                    if (::session.isInitialized && !isSessionClosed(session)) {
                        session.stopRepeating()
                    }
                }.onFailure { e ->
                    Log.w(TAG, "⚠️ stopRepeating() 실패 (무해화): ${e.message}")
                }

                runCatching {
                    if (::session.isInitialized && !isSessionClosed(session)) {
                        session.abortCaptures()
                    }
                }.onFailure { e ->
                    Log.w(TAG, "⚠️ abortCaptures() 실패 (무해화): ${e.message}")
                }

                // ✅ 세션 닫기 (한 번만)
                runCatching {
                    if (::session.isInitialized && !isSessionClosed(session)) {
                        session.close()
                    }
                }.onFailure { e ->
                    Log.w(TAG, "⚠️ session.close() 실패 (무해화): ${e.message}")
                }
            }

            // 3. 프레임 리스너 제거
            pipeline.clearFrameListener()

            // 4. UI 업데이트 (뷰가 유효할 때만)
            _fragmentBinding?.let { binding ->
                binding.captureButton.post {
                    _fragmentBinding?.let { b ->
                        b.captureButton.background =
                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_shutter_normal)
                        // 타이머 업데이트 중지
                        stopTimerUpdate()
                        b.captureTimer?.visibility = View.GONE
                        b.captureButton.setOnTouchListener(null)
                    }
                }
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
                    Log.d(TAG, "📄 Tracking log saved to Downloads/EchoShotLogs")
                }
            }

            // 프레임 타임스탬프 저장 (Downloads/EchoShotLogs에 MediaStore로 등록)
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


            // 10. 상태 플래그 업데이트 + 버튼 복구는 메인에서 (뷰가 유효할 때만)
            withContext(Dispatchers.Main) {
                recordingStarted = false
                _fragmentBinding?.let { binding ->
                    // 🔥 상단 우측 버튼을 '전면 전환' 모드로 복귀
                    updateTopRightButton()

                    // 📸 갤러리 버튼을 원래대로 복원
                    galleryButtonOriginalDrawable?.let {
                        binding.galleryButton.setImageDrawable(it)
                    } ?: run {
                        updateGalleryThumbnail()  // 원래 drawable이 없으면 썸네일 다시 설정
                    }
                    galleryButtonOriginalClickListener?.let {
                        binding.galleryButton.setOnClickListener(it)
                    }
                    
                    // 🎥 녹화 종료 시 렌즈 선택 버튼과 사진 모드 버튼 다시 보이기
                    binding.lensSelector.visibility = View.VISIBLE
                    try {
                        requireView().findViewById<View>(R.id.btn_mode_photo)?.visibility = View.VISIBLE
                    } catch (e: Exception) {
                        Log.w(TAG, "뷰 접근 실패 (무시): ${e.message}")
                    }
                } ?: Log.w(TAG, "⚠️ fragmentBinding이 null입니다. UI 업데이트 스킵")
            }
        } finally {
            // ✅ 항상 플래그 리셋 및 UI 복구 (뷰가 유효할 때만)
            isRecording.set(false)
            isStopping.set(false)
            withContext(Dispatchers.Main) {
                _fragmentBinding?.let { binding ->
                    binding.captureButton.isEnabled = true
                }
            }
        }

        // 11. 촬영 종료 후 카메라 프리뷰로 복귀
        // ✅ 백그라운드로 나갔다가 돌아온 경우 카메라 재초기화 스킵
        if (!isPausedForBackground) {
            withContext(Dispatchers.Main) {
                if (isAdded && _fragmentBinding != null) {
                    try {
                        // ✅ 프래그먼트 자체를 새로 로드 (Surface 재생성 포함)
                        val action = CustomPreviewFragmentDirections.actionSelfReloadWithMode(
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
                            setForcePhysicalId(args.forcePhysicalId)
                        }
                        findNavController().navigate(action)
                        Log.d(TAG, "✅ 촬영 종료 후 카메라 프래그먼트 재로드")
                    } catch (e: Exception) {
                        Log.w(TAG, "카메라 재로드 실패: ${e.message}")
                    }
                }
            }
        } else {
            Log.d(TAG, "📌 백그라운드 정리 모드: 카메라 재초기화 스킵")
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
            Log.w(TAG, "전면 카메라를 찾을 수 없습니다.")
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
        _fragmentBinding?.let { binding ->
            val btn = binding.autoZoomButton

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
                }
            }
        } ?: Log.w(TAG, "⚠️ updateTopRightButton: fragmentBinding이 null입니다")
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
            // ✅ LauncherFragment처럼 와이드로 전환할 때는 논리 카메라 사용 (forcePhysicalId = null)
            // 망원에서 와이드로 돌아올 때는 논리 카메라로 전환하여 안정적인 상태 보장
            if (args.forcePhysicalId != null) {
                // 현재 물리 카메라를 사용 중이면 논리 카메라로 전환
                reloadWithNewPipeline(args.cameraId, args.pipelineMode, null)
            } else {
                // 이미 논리 카메라를 사용 중이면 줌만 조정
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
        try {
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
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ 카메라 권한이 없습니다: ${e.message}", e)
            // 메인 스레드에서 다이얼로그 표시 후 이전 화면으로
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                showCameraPermissionDialog()
            }
            // 예외를 던지지 않고 coroutine을 중단 상태로 유지 (다이얼로그에서 처리)
            // cont.resumeWithException() 호출하지 않음
        } catch (e: Exception) {
            Log.e(TAG, "❌ 카메라 열기 실패: ${e.message}", e)
            if (cont.isActive) {
                cont.resumeWithException(RuntimeException("카메라를 열 수 없습니다: ${e.message}", e))
            }
        }
    }
    
    /**
     * 카메라 권한이 없을 때 사용자에게 안내 다이얼로그 표시
     */
    private fun showCameraPermissionDialog() {
        if (!isAdded || context == null) {
            // Fragment가 detached된 경우 안전하게 처리
            return
        }
        
        try {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.permission_camera_required_title)
                .setMessage(R.string.permission_camera_required_message)
                .setPositiveButton(R.string.permission_go_to_settings) { _, _ ->
                    // 앱 설정 화면으로 이동 후 이전 화면으로
                    try {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", requireContext().packageName, null)
                        )
                        startActivity(intent)
                        // 설정에서 돌아오면 다시 시도하도록 이전 화면으로
                        findNavController().popBackStack()
                    } catch (e: Exception) {
                        Log.e(TAG, "설정 화면 이동 실패: ${e.message}")
                        requireActivity().finish()
                    }
                }
                .setNegativeButton(R.string.permission_cancel) { _, _ ->
                    // 이전 화면으로 돌아가기
                    try {
                        findNavController().popBackStack()
                    } catch (e: Exception) {
                        requireActivity().finish()
                    }
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "권한 다이얼로그 표시 실패: ${e.message}")
            // 다이얼로그 표시 실패 시 이전 화면으로
            try {
                findNavController().popBackStack()
            } catch (e2: Exception) {
                requireActivity().finish()
            }
        }
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
                        val drProfiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                        val supportedProfiles = drProfiles?.supportedProfiles ?: emptySet()
                        
                        Log.i(TAG, "🔍 Surface Profile Check: Target=${args.dynamicRange}, Supported=$supportedProfiles")
                        
                        // 요청된 프로필이 실제로 지원되는 경우에만 설정, 아니면 STANDARD로 폴백
                        if (supportedProfiles.contains(args.dynamicRange)) {
                            setDynamicRangeProfile(args.dynamicRange)
                        } else {
                            Log.w(TAG, "⚠️ HDR Profile ${args.dynamicRange} is not supported by this camera. Falling back to STANDARD.")
                            setDynamicRangeProfile(DynamicRangeProfiles.STANDARD)
                        }
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
        // ✅ Surface 유효성 사전 검사
        val invalidSurfaces = targets.filter { !it.isValid }
        if (invalidSurfaces.isNotEmpty()) {
            val errorMsg = "❌ 일부 Surface가 유효하지 않습니다 (abandoned/invalid). 세션 생성을 중단합니다."
            Log.e(TAG, errorMsg)
            cont.resumeWithException(RuntimeException(errorMsg))
            return@suspendCoroutine
        }
        
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

        // ✅ Surface가 유효한 경우에만 세션 생성 시도
        try {
            setupSessionWithDynamicRangeProfile(device, targets, handler, stateCallback, forcePhysicalCameraId)
        } catch (e: IllegalArgumentException) {
            // ✅ Surface abandoned 예외 처리
            Log.e(TAG, "❌ Surface abandoned catch in createCaptureSession: ${e.message}", e)
            cont.resumeWithException(RuntimeException("Surface is already abandoned/invalid: ${e.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "❌ 예상치 못한 예외 in createCaptureSession: ${e.message}", e)
            cont.resumeWithException(e)
        }
    }

    override fun onPause() {
        super.onPause()
        // ✅ 촬영 중이면 자동으로 녹화 중지 및 저장
        if (isCurrentlyRecording()) {
            Log.d(TAG, "🛑 onPause() 감지: 녹화 중 자동 중지 시작")
            isPausedForBackground = true  // ✅ 백그라운드 플래그 설정
            cleanupJob = lifecycleScope.launch(Dispatchers.IO) {
                stopRecording()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // ✅ 백그라운드에서 돌아왔고 정리 작업이 있었다면 프래그먼트 재시작
        if (isPausedForBackground) {
            Log.d(TAG, "⏳ onResume(): 백그라운드 정리 후 재진입 감지, 프래그먼트 재시작 필요")
            lifecycleScope.launch(Dispatchers.Main) {
                // 정리 작업 완료 대기
                cleanupJob?.join()
                Log.d(TAG, "✅ onResume(): 정리 작업 완료, 프래그먼트 재시작")
                cleanupJob = null
                isPausedForBackground = false
                
                // 🔄 프래그먼트를 완전히 재시작하여 새 인코더/Surface 생성
                if (isAdded) {
                    try {
                        // 현재 프래그먼트를 pop하고 같은 설정으로 다시 네비게이션
                        val action = CustomPreviewFragmentDirections
                            .actionSelfReloadWithMode(
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
                                setForcePhysicalId(args.forcePhysicalId)
                            }
                        findNavController().navigate(action)
                    } catch (e: Exception) {
                        Log.e(TAG, "프래그먼트 재시작 실패: ${e.message}", e)
                    }
                }
            }
        }
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

        try {
            // pipeline이 이미 초기화된 경우에만 정리
            if (_pipelineLazy.isInitialized()) {
                pipeline.clearFrameListener()
                pipeline.cleanup()
            }
            cameraThread.quitSafely()
            
            // ✅ Surface release 시 안전하게 처리 (by lazy는 isInitialized 사용 불가)
            try {
                if (encoderSurface.isValid) {
                    encoderSurface.release()
                    Log.d(TAG, "✅ encoderSurface release 완료")
                } else {
                    Log.d(TAG, "⚠️ encoderSurface가 이미 해제되었거나 유효하지 않음")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ encoderSurface release 중 예외 (무시)", e)
            }
            
            try {
                if (originalencoderSurface.isValid) {
                    originalencoderSurface.release()
                    Log.d(TAG, "✅ originalencoderSurface release 완료")
                } else {
                    Log.d(TAG, "⚠️ originalencoderSurface가 이미 해제되었거나 유효하지 않음")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ originalencoderSurface release 중 예외 (무시)", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDestroy cleanup", e)
        }
    }

    override fun onDestroyView() {
        // 1) PixelCopy 루프 중단
        if (::pixelHandler.isInitialized && ::pixelRunnable.isInitialized) {
            pixelHandler.removeCallbacks(pixelRunnable)
        }
        if (::pixelThread.isInitialized) {
            pixelThread.quitSafely()
        }
        // 2) PoseDetector 해제
        poseDetector?.close()
        poseDetector = null
        // 3) zoomAnimator 취소
        zoomAnimator?.cancel()
        zoomAnimator = null

        // 4) 타이머 업데이트 중지
        stopTimerUpdate()

        // 5) 카메라 파이프라인 프레임 리스너 중단 (초기화된 경우에만)
        if (_pipelineLazy.isInitialized()) {
            pipeline.clearFrameListener()
        }

        // binding을 마지막에 null로 설정
        _fragmentBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = PreviewFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L


        private fun createFile(context: Context, extension: String, tag: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val uniqueSuffix = System.nanoTime() % 100000
            val fileName = "VID_${tag}_${sdf.format(Date())}_$uniqueSuffix.$extension"

            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "EchoShot")
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
                    "${Environment.DIRECTORY_DOWNLOADS}/EchoShotLogs"
                )
            }
        }
        return resolver.insert(collection, values)
    }

    private fun updateGalleryThumbnail() {
        val binding = _fragmentBinding ?: run {
            Log.w(TAG, "⚠️ updateGalleryThumbnail: fragmentBinding이 null입니다")
            return
        }
        
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "EchoShot"
        )

        Log.d("ThumbDebug", "dir path = ${dir.absolutePath}, exists=${dir.exists()}")

        val videoFiles = dir.listFiles { f -> f.extension.equals("mp4", true) }
            ?: run {
                Log.d("ThumbDebug", "listFiles() == null")
                binding.galleryButton.post {
                    _fragmentBinding?.galleryButton?.let { btn ->
                        btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        btn.setImageResource(R.drawable.ic_photo_gallery)
                    }
                }
                return
            }

        Log.d("ThumbDebug", "mp4 count = ${videoFiles.size}")

        val latest = videoFiles
            .sortedByDescending { it.lastModified() }
            .firstOrNull()
            ?: run {
                Log.d("ThumbDebug", "no latest mp4 found (size=${videoFiles.size})")
                binding.galleryButton.post {
                    _fragmentBinding?.galleryButton?.let { btn ->
                        btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        btn.setImageResource(R.drawable.ic_photo_gallery)
                    }
                }
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

        binding.galleryButton.post {
            _fragmentBinding?.galleryButton?.let { btn ->
                if (thumb == null) {
                    Log.d("ThumbDebug", "썸네일이 null이라 기본 아이콘 표시")
                    btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    btn.setImageResource(R.drawable.ic_photo_gallery)
                } else {
                    Log.d("ThumbDebug", "썸네일 생성 성공 → 버튼에 적용")
                    btn.scaleType = ImageView.ScaleType.CENTER_CROP
                    btn.setImageBitmap(thumb)
                }
            }
        }
    }

    // 📸 녹화 중 사진 촬영 함수 (미리보기와 동일한 프레임을 저장: PixelCopy)
    private fun captureStillPicture() {
        try {
            val sv = fragmentBinding.viewFinder
            val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(sv, bmp, { result ->
                if (result != PixelCopy.SUCCESS) {
                    Log.e(TAG, "캡처 실패($result)")
                    return@request
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // ✅ PhotoFragment와 동일한 파일명 형식 사용
                        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.KOREA)
                            .format(System.currentTimeMillis())
                        
                        // ✅ PhotoFragment와 동일한 방식: MediaStore에 직접 저장
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/EchoShot")  // PhotoFragment와 동일 (슬래시 없음)
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
                            Log.d(TAG, "✅ 사진 저장 완료: $name")
                        } ?: throw RuntimeException("MediaStore insert 실패")
                    } catch (e: Exception) {
                        Log.e(TAG, "사진 저장 실패", e)
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "사진 촬영 오류", e)
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

    /**
     * LauncherFragment의 기본 설정값을 가져오는 헬퍼 함수
     * 렌즈 변경 시 안정적인 파라미터 전달을 위해 사용
     */
    private fun getLauncherDefaults(): LauncherDefaults {
        val context = requireContext()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val selectedCameraId = cameraManager.cameraIdList.first()
        val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)

        val targetClass = MediaRecorder::class.java
        val configMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return LauncherDefaults()

        val allSizes = configMap.getOutputSizes(targetClass)
        val selectedSize = allSizes.firstOrNull { it.width == 3840 && it.height == 2160 }
            ?: Size(4080, 3060)
        val secondsPerFrame = configMap.getOutputMinFrameDuration(targetClass, selectedSize) / 1_000_000_000.0
        val selectedFps = if (secondsPerFrame > 0) (1.0 / secondsPerFrame).toInt() else 30

        val dynamicRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) == true) {
                val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                profiles?.getSupportedProfiles()?.firstOrNull() ?: DynamicRangeProfiles.STANDARD
            } else {
                DynamicRangeProfiles.STANDARD
            }
        } else {
            DynamicRangeProfiles.STANDARD
        }

        val colorSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
            profiles?.getSupportedColorSpacesForDynamicRange(android.graphics.ImageFormat.UNKNOWN, dynamicRange)?.firstOrNull()?.ordinal ?: ColorSpaceProfiles.UNSPECIFIED
        } else {
            ColorSpaceProfiles.UNSPECIFIED
        }

        val stabilizationModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val supportsPreviewStabilization = stabilizationModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true

        return LauncherDefaults(
            cameraId = selectedCameraId,
            width = selectedSize.width,
            height = selectedSize.height,
            fps = selectedFps,
            dynamicRange = dynamicRange,
            colorSpace = colorSpace,
            previewStabilization = supportsPreviewStabilization,
            useMediaRecorder = false,
            videoCodec = 0,
            filterOn = false,
            transfer = 0,
            useHardware = true,
            pipelineMode = "default"
        )
    }

    /**
     * LauncherFragment의 기본 설정값을 담는 데이터 클래스
     */
    private data class LauncherDefaults(
        val cameraId: String = "",
        val width: Int = 3840,
        val height: Int = 2160,
        val fps: Int = 30,
        val dynamicRange: Long = DynamicRangeProfiles.STANDARD,
        val colorSpace: Int = ColorSpaceProfiles.UNSPECIFIED,
        val previewStabilization: Boolean = false,
        val useMediaRecorder: Boolean = false,
        val videoCodec: Int = 0,
        val filterOn: Boolean = false,
        val transfer: Int = 0,
        val useHardware: Boolean = true,
        val pipelineMode: String = "default"
    )

    fun reloadWithNewPipeline(
        newCameraId: String,
        mode: String,
        forcePhysicalId: String? = null
    ) {
        // ✅ LauncherFragment의 기본값을 가져와서 사용 (안정적인 파라미터 전달)
        val defaults = getLauncherDefaults()
        
        // ✅ LauncherFragment처럼 논리 카메라를 사용할 때는 기본값을 그대로 사용
        // 물리 카메라를 사용할 때만 현재 args를 참조
        val useDefaults = forcePhysicalId == null
        
        val action = CustomPreviewFragmentDirections.actionSelfReloadWithMode(
            newCameraId,
            if (useDefaults) defaults.width else (args.width.takeIf { it > 0 } ?: defaults.width),
            if (useDefaults) defaults.height else (args.height.takeIf { it > 0 } ?: defaults.height),
            if (useDefaults) defaults.fps else (args.fps.takeIf { it > 0 } ?: defaults.fps),
            if (useDefaults) defaults.dynamicRange else (args.dynamicRange.takeIf { it != 0L } ?: defaults.dynamicRange),
            if (useDefaults) defaults.colorSpace else (args.colorSpace.takeIf { it != ColorSpaceProfiles.UNSPECIFIED } ?: defaults.colorSpace),
            if (useDefaults) defaults.previewStabilization else args.previewStabilization,
            if (useDefaults) defaults.useMediaRecorder else args.useMediaRecorder,
            if (useDefaults) defaults.videoCodec else args.videoCodec,
            if (useDefaults) defaults.filterOn else args.filterOn,
            if (useDefaults) defaults.transfer else args.transfer,
            if (useDefaults) defaults.useHardware else args.useHardware,
            mode.takeIf { it.isNotEmpty() } ?: defaults.pipelineMode
        ).apply {
            // 선택 인자는 setter로 주입해야 함
            // forcePhysicalId가 null이면 명시적으로 설정하지 않음 (논리 카메라 사용)
            forcePhysicalId?.let {
                try { setForcePhysicalId(it) } catch (_: Throwable) { /* 일부 버전은 프로퍼티 형태 */ }
            }
        }

        findNavController().navigate(action)
    }
    
    /**
     * 파이프라인 초기화 - 최대 3회 재시도 후 실패하면 크래시
     * Surface 타이밍 이슈 대응 (저사양 기기에서 Surface 준비가 늦을 수 있음)
     */
    private fun initPipelineWithRetry(surface: Surface) {
        val maxRetries = 3
        var lastException: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                pipeline.createResources(surface)
                initializeCamera()
                Log.d(TAG, "✅ pipeline.createResources() 성공 (시도 $attempt/$maxRetries)")
                return  // 성공 시 즉시 리턴
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "⚠️ pipeline.createResources() 실패 (시도 $attempt/$maxRetries): ${e.message}")
                
                if (attempt < maxRetries) {
                    // Surface가 아직 준비 안됐을 수 있으므로 잠시 대기 후 재시도
                    try {
                        Thread.sleep(100L * attempt)  // 100ms, 200ms, 300ms 점진적 대기
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        
        // 모든 재시도 실패 → 크래시 (Play Console에 찍힘)
        Log.e(TAG, "❌ pipeline.createResources() 최종 실패 ($maxRetries 회 시도) - 크래시 발생")
        throw lastException ?: RuntimeException("Pipeline 초기화 실패")
    }


}
