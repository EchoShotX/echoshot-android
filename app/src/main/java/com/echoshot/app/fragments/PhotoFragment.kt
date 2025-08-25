package com.echoshot.app.ui

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
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.echoshot.app.R
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.echoshot.app.ui.CenterSnapHelper
import com.echoshot.app.ui.ZoomRulerAdapter
import androidx.navigation.fragment.navArgs
import java.io.File
import java.io.IOException

class PhotoFragment : Fragment() {
    private val navArgs by navArgs<PhotoFragmentArgs>()
    private lateinit var viewFinder: PreviewView
    private lateinit var imageCapture: ImageCapture
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private var zoomAdapter: ZoomRulerAdapter? = null
    private var zoomRuler: RecyclerView? = null

    private lateinit var cameraExecutor: ExecutorService

    private var initDone = false   // 초기 배율/룰러 세팅 끝나기 전까지 스크롤 무시
    private var appliedInitialZoom = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_photo_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateGalleryThumbnail()

        viewFinder = view.findViewById(R.id.view_finder)

        // 버튼
        val captureButton: View = view.findViewById(R.id.capture_button)
        val switchCameraButton: View = view.findViewById(R.id.switch_camera_button)

        captureButton.setOnClickListener { takePhoto() }
        switchCameraButton.setOnClickListener {
            cameraSelector =
                if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
            startCamera()
        }

        // ───── 줌 룰러 UI (어댑터는 "처음엔" 붙이지 않음)
        zoomRuler = view.findViewById(R.id.zoom_ruler)
        val zoomHud = view.findViewById<TextView?>(R.id.zoom_level_text)

        val lm = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        zoomRuler!!.layoutManager = lm

        val snapHelper = CenterSnapHelper()
        snapHelper.attachToRecyclerView(zoomRuler)

        val itemWidthDp = 12
        zoomRuler!!.addItemDecoration(EdgeCenterSpacingDecoration(itemWidthDp))

        // 실시간 업데이트용 간단 throttle
        var lastUpdateMs = 0L
        fun maybeUpdateZoom(z: Float) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateMs >= 16) {
                camera?.cameraControl?.setZoomRatio(z)
                zoomHud?.text = String.format(Locale.KOREA, "%.1fx", z)
                lastUpdateMs = now
            }
        }

        view.findViewById<ImageButton>(R.id.gallery_button)?.setOnClickListener {
            openGallery()
        }

        zoomRuler!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (!initDone) return
                val adapter = zoomAdapter ?: return
                val layout = rv.layoutManager as? LinearLayoutManager ?: return

                val centerX = rv.width / 2
                var closestChild: View? = null
                var minDist = Int.MAX_VALUE
                for (i in 0 until layout.childCount) {
                    val child = layout.getChildAt(i) ?: continue
                    val childCenter = (child.left + child.right) / 2
                    val dist = kotlin.math.abs(childCenter - centerX)
                    if (dist < minDist) { minDist = dist; closestChild = child }
                }
                val child = closestChild ?: return
                val pos = rv.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) return

                val childCenter = (child.left + child.right) / 2f
                val itemWidth = child.width.toFloat().coerceAtLeast(1f)
                val offsetInItem = (centerX - childCenter) / itemWidth
                val posF = pos - offsetInItem

                val z = adapter.positionToZoom(posF)
                maybeUpdateZoom(z)
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                super.onScrollStateChanged(rv, newState)
                if (!initDone) return
                val adapter = zoomAdapter ?: return
                val layout = rv.layoutManager as? LinearLayoutManager ?: return
                val snapView = snapHelper.findSnapView(layout) ?: return
                val pos = rv.getChildAdapterPosition(snapView)
                if (pos != RecyclerView.NO_POSITION) {
                    val z = adapter.positionToZoom(pos)
                    camera?.cameraControl?.setZoomRatio(z)
                    zoomHud?.text = String.format(Locale.KOREA, "%.1fx", z)
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

                // 바인딩 직후 즉시 1.0 강제 (기기 min이 0.6이어도 바로 덮어씀)
                camera!!.cameraControl.setZoomRatio(1.0f)
                zoomHud?.text = "1.0x"

                // zoomState 첫 emit에서 실제 min/max 확인 후 어댑터 부착 및 최종 보정
                appliedInitialZoom = false
                initDone = false
                camera!!.cameraInfo.zoomState.observe(viewLifecycleOwner) { state ->
                    if (!appliedInitialZoom && state != null) {
                        val minZ = state.minZoomRatio
                        val maxZ = state.maxZoomRatio

                        // 1.0을 기기 범위에 맞게 한 번 더 보정
                        val target = 1.0f.coerceIn(minZ, maxZ)
                        if (target != 1.0f) {
                            camera!!.cameraControl.setZoomRatio(target)
                        }
                        zoomHud?.text = String.format(Locale.KOREA, "%.1fx", target)

                        // 실제 범위로 어댑터 생성/부착 (초기엔 어댑터 없었음)
                        val newAdapter = ZoomRulerAdapter(
                            minZoom = minZ,   // 0.6 고정 하한 없음
                            midZoom = 1.0f,
                            maxZoom = maxZ,
                            ticksPerLogUnit = 20,
                            itemWidthDp = 12,
                            minLeftTicks = 0,
                            minRightTicks = 0
                        )
                        zoomAdapter = newAdapter
                        zoomRuler?.adapter = newAdapter

                        // 1.0 위치로 이동
                        val pos = newAdapter.zoomToPosition(1.0f)
                        zoomRuler?.post { zoomRuler?.scrollToPosition(pos) }

                        appliedInitialZoom = true
                        initDone = true
                    }
                }

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "카메라 실행 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun updateGalleryThumbnail() {
        val btn = view?.findViewById<ImageButton>(R.id.gallery_button) ?: return

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
        } ?: return

        btn.setImageBitmap(thumb)
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
                    Toast.makeText(requireContext(), "사진 저장 완료!", Toast.LENGTH_SHORT).show()
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
