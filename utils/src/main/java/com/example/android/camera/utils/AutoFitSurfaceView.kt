package com.example.android.camera.utils

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import kotlin.math.roundToInt

class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var aspectRatio = 0f

    /**
     * Sets the aspect ratio for this view. The size of the view will be
     * measured based on the ratio calculated from the parameters.
     *
     * @param width  Camera resolution horizontal size
     * @param height Camera resolution vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }

        aspectRatio = width.toFloat() / height.toFloat()
        Log.d(TAG, "[setAspectRatio] → cameraSize=$width x $height | ratio=%.4f".format(aspectRatio))

        holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        Log.d(TAG, "[onMeasure] Called with: parentWidth=$width, parentHeight=$height")

        if (aspectRatio == 0f) {
            Log.w(TAG, "[onMeasure] aspectRatio not set. Using full size.")
            setMeasuredDimension(width, height)
            return
        }

        val viewRatio = width.toFloat() / height
        val actualRatio = if (width > height) aspectRatio else 1f / aspectRatio

        Log.d(TAG, "[onMeasure] viewRatio=${"%.4f".format(viewRatio)}, actualRatio=${"%.4f".format(actualRatio)}")

        // 먼저 원래대로 비율만 맞춰 계산
        val newWidth: Int
        val newHeight: Int

        if (width < height * actualRatio) {
            newHeight = height
            newWidth = (height * actualRatio).roundToInt()
            Log.d(TAG, "[onMeasure] Initially calculated → $newWidth x $newHeight")
        } else {
            newWidth = width
            newHeight = (width / actualRatio).roundToInt()
            Log.d(TAG, "[onMeasure] Initially calculated → $newWidth x $newHeight")
        }

        // 💡 영상이 화면보다 크면 다시 비율대로 스케일링
        val scale = if (newWidth > width) width.toFloat() / newWidth else 1f
        val finalWidth = (newWidth * scale).roundToInt()
        val finalHeight = (newHeight * scale).roundToInt()

        Log.d(TAG, "[onMeasure] Rescaled to fit screen: $finalWidth x $finalHeight (scale=$scale)")
        setMeasuredDimension(finalWidth, finalHeight)
    }

    companion object {
        private const val TAG = "AutoFitSurfaceView"
    }
}
