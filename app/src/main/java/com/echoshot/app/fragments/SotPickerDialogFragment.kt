// SotPickerDialogFragment.kt
package com.echoshot.app.fragments

import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.echoshot.app.R
import com.echoshot.app.mp4detact.LogOrchestrator
import com.echoshot.app.mp4detact.RectOverlayView
import com.echoshot.app.mp4detact.SotLogManager
import kotlinx.coroutines.*
import org.opencv.core.Rect as CvRect

class SotPickerDialogFragment : DialogFragment() {

    companion object {
        fun newInstance(originalUri: Uri?, sessionUuid: String) =
            SotPickerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("original_uri", originalUri?.toString())
                    putString("session_uuid", sessionUuid)
                }
            }
    }

    private lateinit var iv: ImageView
    private lateinit var overlay: RectOverlayView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val root = layoutInflater.inflate(R.layout.dialog_sot_picker, null)
        iv = root.findViewById(R.id.ivFrame)
        overlay = root.findViewById(R.id.overlay)
        val btnCancel: Button = root.findViewById(R.id.btnCancel)
        val btnRun: Button = root.findViewById(R.id.btnRun)
        val btnSmaller: Button = root.findViewById(R.id.btnSmaller)
        val btnBigger: Button = root.findViewById(R.id.btnBigger)

        val originalUriStr = requireArguments().getString("original_uri")
        val zoomUriStr     = requireArguments().getString("zoom_uri")
        val sessionUuid    = requireArguments().getString("session_uuid")!!

        val originalUri = originalUriStr?.let { Uri.parse(it) }
        val zoomUri     = zoomUriStr?.let { Uri.parse(it) }

        // 썸네일/프레임은 원본 우선
        val thumbUri = originalUri ?: zoomUri
        // SOT 실행 대상도 원본 우선
        val videoUriForSot = originalUri ?: zoomUri

        if (thumbUri == null || videoUriForSot == null) {
            Toast.makeText(requireContext(), "영상 경로를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return AlertDialog.Builder(requireContext()).create()
        }

        // 첫 프레임 로드
        val bmp = firstFrameBitmap(thumbUri)
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

        btnCancel.setOnClickListener { dismissAllowingStateLoss() }

        btnRun.setOnClickListener {
            val rView = overlay.getRectViewSpace()
            val rBmp = mapViewToBitmap(iv, rView) ?: run {
                Toast.makeText(requireContext(), "좌표 변환 실패", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val (srcW, srcH) = videoSize(videoUriForSot) ?: (bmp!!.width to bmp!!.height)
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
                requireContext(), "sot_${sessionUuid}_${System.currentTimeMillis()/1000}.jsonl"
            ) ?: run {
                Toast.makeText(requireContext(), "출력 경로 생성 실패", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnRun.isEnabled = false; btnCancel.isEnabled = false; btnRun.text = "실행 중…"

            SotLogManager.makeSotLogFromMp4(
                ctx = requireContext(),
                videoUri = videoUriForSot,
                initBboxSrc = initCv,
                outJsonUri = outUri,
                onSuccess = {
                    Toast.makeText(requireContext(), "SOT 로그 저장 완료", Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                },
                onError = { e ->
                    Toast.makeText(requireContext(), "SOT 실패: ${e.message}", Toast.LENGTH_LONG).show()
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
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
}
