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
import android.widget.ImageButton
import android.widget.SeekBar
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
import com.echoshot.app.utils.setupBottomNavigationBar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.navigation.fragment.navArgs
import java.io.File
import java.io.IOException

class PhotoFrontFragment : Fragment() {
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
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA  // 전면 카메라 전용

    private lateinit var cameraExecutor: ExecutorService

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
        return inflater.inflate(R.layout.fragment_front_photo_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 네비게이션 바 설정
        setupBottomNavigationBar(
            currentPage = "camera",
            onHomeClick = {
                // 전면 사진 모드에서 홈으로 이동
                val action = PhotoFrontFragmentDirections.actionPhotoFrontFragmentToHomeFragment()
                findNavController().navigate(action)
            },
            onGalleryClick = {
                // 전면 사진 모드에서 갤러리로 이동
                openGallery()
            },
            onProfileClick = {
                // 전면 사진 모드에서 프로필로 이동
                val action = PhotoFrontFragmentDirections.actionPhotoFrontFragmentToProfileFragment()
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

        // Touch to show EV bar on long-press + 터치 링 표시 (전면 카메라는 줌 없음)
        viewFinder.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (ev.pointerCount == 1) {
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

                    if (evDragging && longPressFired) {
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
        captureButton.setOnClickListener { takePhoto() }
        
        // 카메라 전환 버튼: 후면 카메라 프래그먼트로 이동
        view.findViewById<View>(R.id.switch_camera_button)?.setOnClickListener {
            val a = navArgs
            val action = PhotoFrontFragmentDirections.actionPhotoFrontFragmentToPhotoFragment(
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

        view.findViewById<ImageButton>(R.id.gallery_button)?.setOnClickListener {
            openGallery()
        }

        // 사진 → 동영상 버튼 (전면 동영상으로 이동)
        view.findViewById<View>(R.id.btn_mode_video)?.setOnClickListener {
            val a = navArgs
            val action = PhotoFrontFragmentDirections
                .actionPhotoFrontFragmentToCustomFrontPreviewFragment(
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

                // 전면 카메라는 줌 미지원이므로 줌 로직 제거
                // EV 슬라이더만 동기화
                syncEvSliderFromCamera()

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
