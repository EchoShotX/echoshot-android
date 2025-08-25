package com.echoshot.app.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
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
import com.echoshot.app.databinding.FragmentCustomFrontPreviewBinding
import com.echoshot.app.databinding.FragmentPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
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

    /** Orientation */
    private val orientation: Int by lazy {
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
    }

    @Volatile private var recordingStarted = false
    @Volatile private var recordingComplete = false
    private val cvRecordingStarted = ConditionVariable(false)
    private val cvRecordingComplete = ConditionVariable(false)

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
                fragmentBinding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)
                pipeline.setPreviewSize(previewSize)

                fragmentBinding.viewFinder.post {
                    pipeline.createResources(holder.surface)
                    initializeCamera() // ★ front 카메라로만 연다
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

        fragmentBinding.galleryButton.setOnClickListener {
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
    }

    private fun isCurrentlyRecording() = recordingStarted && !recordingComplete

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
        if (recordingStarted) return@launch

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
            session.close()
            session = createCaptureSession(
                camera, recordTargets, cameraHandler, recordingCompleteOnClose = true
            )
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

        recordingStartMillis = System.currentTimeMillis()

        // UI 업데이트
        fragmentBinding.captureButton.post {
            fragmentBinding.captureButton.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_shutter_pressed)
            fragmentBinding.captureTimer?.visibility = View.VISIBLE
            fragmentBinding.captureTimer?.start()
        }
    }

    private fun stopFrontRecording() = lifecycleScope.launch(Dispatchers.IO) {
        if (!recordingStarted) return@launch

        // 최소 한 프레임 보장
        cvRecordingStarted.block()
        encoder.waitForFirstFrame()

        // 세션 정지/종료
        session.stopRepeating()
        session.close()

        // 파이프라인 리스너 정리
        pipeline.clearFrameListener()

        // UI 업데이트
        fragmentBinding.captureButton.post {
            fragmentBinding.captureButton.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_shutter_normal)
            fragmentBinding.captureTimer?.visibility = View.GONE
            fragmentBinding.captureTimer?.stop()
        }

        // 세션 종료 신호 대기
        cvRecordingComplete.block()

        // 회전 잠금 해제
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // 최소 녹화 시간 보장
        val elapsed = System.currentTimeMillis() - recordingStartMillis
        if (elapsed < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
            delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsed)
        }
        delay(CameraActivity.ANIMATION_SLOW_MILLIS)

        // 파이프라인 정리
        pipeline.cleanup()

        // 인코더 종료
        val shutOk = encoder.shutdown()

        // 미디어 스캔 + 뷰어 열기 (수명주기 가드)
        if (shutOk) {
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(outputFile.extension) ?: "video/mp4"

            MediaScannerConnection.scanFile(
                requireContext().applicationContext,
                arrayOf(outputFile.absolutePath),
                arrayOf(mime)
            ) { _, uri ->
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded ||
                        viewLifecycleOwner.lifecycle.currentState <
                        androidx.lifecycle.Lifecycle.State.STARTED) return@post

                    if (uri != null) {
                        val act = activity
                        if (act != null && !act.isFinishing) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime)
                                    addFlags(
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    )
                                })
                            } catch (_: Exception) {
                                Toast.makeText(act, "동영상을 열 앱이 없습니다.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        Toast.makeText(requireContext(),
                            R.string.error_file_not_found, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                if (isAdded)
                    Toast.makeText(requireContext(),
                        R.string.recorder_shutdown_error, Toast.LENGTH_LONG).show()
            }
        }

        // 상태 플래그 마지막에 내리기
        recordingStarted = false
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
                stopFrontRecording()
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
                if (!recordingCompleteOnClose || !isCurrentlyRecording()) return
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
            "Camera2App"
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
    companion object {
        private val TAG = PreviewFragment::class.java.simpleName
        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        // ✅ 후면과 동일하게 DCIM/Camera2App에 저장 + 확장자 버그 수정
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val fileName = "VID_front_${sdf.format(Date())}.$extension"   // ⬅️ ".{$extension}" → ".$extension"

            val publicDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DCIM
                ),
                "Camera2App"
            )
            if (!publicDir.exists()) publicDir.mkdirs()

            val f = File(publicDir, fileName)
            Log.d("FileDebug", "📂 [front] Public video file path: ${f.absolutePath}")
            return f
        }
    }
}
