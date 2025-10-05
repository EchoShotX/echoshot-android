// HybridPickerDialogFragment.kt
package com.echoshot.app.fragments

import android.app.Dialog
import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.echoshot.app.LogFormat
import com.echoshot.app.R
import com.echoshot.app.VideoPipeline
import com.echoshot.app.mp4detact.LogOrchestrator
import com.echoshot.app.mp4detact.data.Detection
import com.echoshot.app.mp4detact.RectOverlayView
import com.echoshot.app.mp4detact.pipeline.HybridProcessor
import com.echoshot.app.mp4detact.data.HybridConfig
import com.echoshot.app.mp4detact.models.yolo.YoloTflite
import com.echoshot.app.mp4detact.models.pose.PoseMoveNetAdapter
import com.echoshot.app.mp4detact.models.face.FaceEmbedder
import com.echoshot.app.mp4detact.io.JsonLogger
import com.echoshot.app.mp4detact.engine.GlCtx
import com.echoshot.app.mp4detact.io.HybridLogOrchestrator
import kotlinx.coroutines.*
import org.opencv.core.Rect
import java.util.concurrent.Executors
import org.opencv.core.Rect as CvRect

class HybridPickerDialogFragment : DialogFragment() {

    companion object {
        fun newInstance(originalUri: Uri?, sessionUuid: String) =
            HybridPickerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("original_uri", originalUri?.toString())
                    putString("session_uuid", sessionUuid)
                }
            }
    }

    private lateinit var iv: ImageView
    private lateinit var overlay: RectOverlayView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val root = layoutInflater.inflate(R.layout.dialog_multi_hybrid_picker, null)
        iv = root.findViewById(R.id.ivFrame)
        overlay = root.findViewById(R.id.overlay)
        val startButton: Button = root.findViewById(R.id.startButton)
        val btnSmaller: Button = root.findViewById(R.id.btnSmaller)
        val btnBigger: Button = root.findViewById(R.id.btnBigger)

        val originalUriStr = requireArguments().getString("original_uri")
        val sessionUuid    = requireArguments().getString("session_uuid")!!

        val originalUri = originalUriStr?.let { Uri.parse(it) }

        if (originalUri == null) {
            Toast.makeText(requireContext(), "영상 경로를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return AlertDialog.Builder(requireContext()).create()
        }

        // MakeAutoDetactionFragment와 동일한 방식으로 비디오 선택
        // 1) 파일명에서 original/zoomed 구분
        val fileName = requireContext().contentResolver
            .query(originalUri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst())
                    c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                        .substringBeforeLast('.')
                else null
            } ?: run {
            Toast.makeText(requireContext(), "파일명을 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
            return AlertDialog.Builder(requireContext()).create()
        }

        // 2) 자를 대상: zoomed 우선, 없으면 original
        val zoomedPrefix = fileName.substringBefore("_original_") + "_zoomed_"
        val baseUriToProcess = findLatestMediaUri(requireContext(), zoomedPrefix, "mp4") ?: originalUri
        
        // 첫 프레임 로드 (original에서 로드)
        val bmp = firstFrameBitmap(originalUri)
        if (bmp == null) {
            Toast.makeText(requireContext(), "프레임을 불러올 수 없습니다", Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss()
        } else {
            iv.setImageBitmap(bmp)
            iv.viewTreeObserver.addOnGlobalLayoutListener(object: ViewTreeObserver.OnGlobalLayoutListener{
                override fun onGlobalLayout() {
                    iv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    overlay.ensureDefault() // 중앙 정사각 박스
                }
            })
        }

        // 크기 조절 버튼
        btnSmaller.setOnClickListener { overlay.nudgeScale(0.9f) }
        btnBigger.setOnClickListener  { overlay.nudgeScale(1.1f) }

        startButton.setOnClickListener {
            val rView = overlay.getRectViewSpace()
            val rBmp = mapViewToBitmap(iv, rView) ?: run {
                Toast.makeText(requireContext(), "좌표 변환 실패", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val (srcW, srcH) = videoSize(originalUri) ?: (bmp!!.width to bmp!!.height)
            val scaleX = srcW.toFloat() / bmp!!.width
            val scaleY = srcH.toFloat() / bmp.height
            val rSrc = android.graphics.Rect(
                (rBmp.left * scaleX).toInt(),
                (rBmp.top * scaleY).toInt(),
                (rBmp.right * scaleX).toInt(),
                (rBmp.bottom * scaleY).toInt()
            )
            val initCv = CvRect(rSrc.left, rSrc.top, rSrc.width(), rSrc.height())

            val outUri = LogOrchestrator.createOutputJsonInDownloads(
                requireContext(), "hybrid_${sessionUuid}_${System.currentTimeMillis()/1000}.jsonl"
            ) ?: run {
                Toast.makeText(requireContext(), "출력 경로 생성 실패", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startButton.isEnabled = false; startButton.text = "실행 중…"

            // 하이브리드 프로세서 실행 (탐지는 original에서)
            android.util.Log.d("HybridPicker", "탐지 대상 비디오: ${originalUri}")
            runHybridProcessor(
                ctx = requireContext(),
                videoUri = originalUri,
                initBboxSrc = initCv,
                outJsonUri = outUri,
                onSuccess = {
                    val ctx = requireContext()
                    val detectLogUri = outUri

                    val trackingUri = findLatestMediaUri(ctx, "tracking_log_${sessionUuid}", "json")
                    val tsUri       = findLatestMediaUri(ctx, "tracking_log_${sessionUuid}_frame_ts", "json")

                    if (trackingUri == null || tsUri == null) {
                        Toast.makeText(ctx, "tracking/ts 로그를 찾지 못했습니다.", Toast.LENGTH_LONG).show()
                        startButton.isEnabled = true; startButton.text = "시작"
                        return@runHybridProcessor
                    }

                    startButton.text = "병합 중…"

                    // ❌ viewLifecycleOwner.lifecycleScope (금지)
                    // ✅ Fragment lifecycleScope 사용
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val result = com.echoshot.app.mp4detact.io.HybridLogOrchestrator.processAndMerge(
                                ctx = ctx,
                                sessionUuid = sessionUuid,
                                detectLogUri = detectLogUri,
                                trackingUri = trackingUri,
                                tsUri = tsUri,
                                filesDir = ctx.filesDir,
                                mergeModule = "merge_tracks_pipeline",   // 필요 시 교체
                                mergeFunc   = "merge_offline_logs_from_tracks_min"    // 필요 시 교체
                            )

                            // 디버그: 실제 파일 경로 확인
                            android.util.Log.d("HybridPicker", "mergedLocalFile path: ${result.mergedLocalFile.absolutePath}")
                            android.util.Log.d("HybridPicker", "mergedLocalFile exists: ${result.mergedLocalFile.exists()}")
                            android.util.Log.d("HybridPicker", "mergedLocalFile size: ${result.mergedLocalFile.length()}")
                            
                            // 탐지 대상 vs 크롭 대상 확인 (MakeAutoDetactionFragment와 동일)
                            val detectVideoUri = originalUri    // 탐지는 original에서 수행
                            val cropVideoUri = baseUriToProcess // 크롭은 zoomed에서 수행
                            android.util.Log.d("HybridPicker", "탐지 대상 비디오: ${detectVideoUri}")
                            android.util.Log.d("HybridPicker", "크롭 대상 비디오: ${cropVideoUri}")
                            android.util.Log.d("HybridPicker", "탐지=크롭: ${detectVideoUri == cropVideoUri}")

                            val cropped = com.echoshot.app.VideoPipeline.processSessionFromLog(
                                context = ctx,
                                sessionId = sessionUuid,
                                srcVideoUri = cropVideoUri,
                                fps = 30,
                                paddingFactor = 2.0f,
                                logFile = result.mergedLocalFile,
                                format = com.echoshot.app.LogFormat.MERGED_JSONL
                            )

                            withContext(Dispatchers.Main) {
                                if (!isAdded) return@withContext  // 안전장치
                                if (cropped != null) {
                                    Toast.makeText(ctx, "병합 + 크롭 완료!", Toast.LENGTH_SHORT).show()
                                    dismissAllowingStateLoss()
                                } else {
                                    Toast.makeText(ctx, "크롭 결과가 생성되지 않았습니다.", Toast.LENGTH_LONG).show()
                                    startButton.isEnabled = true; startButton.text = "시작"
                                }
                            }
                        } catch (e: Throwable) {
                            withContext(Dispatchers.Main) {
                                if (!isAdded) return@withContext
                                Toast.makeText(ctx, "후처리 실패: ${e.message}", Toast.LENGTH_LONG).show()
                                startButton.isEnabled = true; startButton.text = "시작"
                            }
                        }
                    }
                },
                onError = { e ->
                    Toast.makeText(requireContext(), "하이브리드 추적 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    dismissAllowingStateLoss()
                }
            )
        }

        return AlertDialog.Builder(requireContext())
            .setView(root)
            .create()
            .apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val widthPx = (300 * resources.displayMetrics.density).toInt()
            setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    // --- 유틸 ---

    private fun firstFrameBitmap(uri: Uri): Bitmap? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(requireContext(), uri)
        r.getFrameAtTime(0) ?: r.frameAtTime
    } catch (_: Throwable) { null }

    private fun videoSize(uri: Uri): Pair<Int, Int>? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(requireContext(), uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
        if (w != null && h != null) w to h else null
    } catch (_: Throwable) { null }

    /** ImageView(fitCenter)상의 뷰좌표 RectF → 비트맵 좌표 RectF */
    private fun mapViewToBitmap(iv: ImageView, rView: RectF): RectF? {
        val d = iv.drawable ?: return null
        val bmW = d.intrinsicWidth.toFloat()
        val bmH = d.intrinsicHeight.toFloat()
        val m = Matrix()
        if (!iv.imageMatrix.invert(m)) return null
        val pts = floatArrayOf(rView.left, rView.top, rView.right, rView.bottom)
        m.mapPoints(pts)
        val left = pts[0].coerceIn(0f, bmW)
        val top = pts[1].coerceIn(0f, bmH)
        val right = pts[2].coerceIn(0f, bmW)
        val bottom = pts[3].coerceIn(0f, bmH)
        return RectF(left, top, right, bottom)
    }

    private fun runHybridProcessor(
        ctx: android.content.Context,
        videoUri: Uri,
        initBboxSrc: CvRect,
        outJsonUri: Uri,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // GL 전용 스레드
        val glDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "hybrid-gl")
        }.asCoroutineDispatcher()

        CoroutineScope(glDispatcher).launch {
            val gl = GlCtx()
            try {
                JsonLogger.open(ctx, outJsonUri).use { logger ->
                    // 모델들 초기화
                    val yolo = YoloTflite(ctx, "yolov8n_int8.tflite")
                    val pose = PoseMoveNetAdapter(ctx)
                    val face = FaceEmbedder(ctx, "MobileFaceNet_fp32.tflite")

                    // 하이브리드 프로세서 생성
                    val processor = HybridProcessor(
                        ctx = ctx,
                        gl = gl,
                        yolo = yolo,
                        poseModel = pose,
                        faceModel = face,
                        cfg = HybridConfig()
                    )

                    // 하이브리드 프로세서 실행
                    processor.runOnePass(
                        uri = videoUri,
                        userInitBox = initBboxSrc,
                        logger = logger
                    )
                }
                GlobalScope.launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                android.util.Log.e("HybridPicker", "runHybridProcessor failed", e)
                GlobalScope.launch(Dispatchers.Main) { onError(e) }
            } finally {
                try { gl.release() } catch (_: Throwable) {}
                glDispatcher.close()
            }
        }
    }

    private fun findLatestMediaUri(
        context: android.content.Context,
        prefix: String,
        extension: String
    ): Uri? {
        val (collection, nameCol) = when (extension.lowercase()) {
            "mp4" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MediaStore.Video.Media.DISPLAY_NAME
            "json", "jsonl" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to MediaStore.MediaColumns.DISPLAY_NAME
                else
                    MediaStore.Files.getContentUri("external") to MediaStore.MediaColumns.DISPLAY_NAME
            else -> return null
        }

        val proj = arrayOf(MediaStore.MediaColumns._ID)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns._ID)
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "$nameCol LIKE ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${prefix}%.${extension}"))
            }
            context.contentResolver.query(collection, proj, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
        } else {
            val sel = "$nameCol LIKE ?"
            val selArgs = arrayOf("${prefix}%.${extension}")
            // 구버전: 최신 추정(_ID DESC). 일부 기기에서 LIMIT 지원 안되므로 코드로 첫 행만 사용.
            val sort = "${MediaStore.MediaColumns._ID} DESC"
            context.contentResolver.query(collection, proj, sel, selArgs, sort)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
        }
    }

}


