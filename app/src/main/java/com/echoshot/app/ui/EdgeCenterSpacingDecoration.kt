package com.echoshot.app.ui

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 첫/마지막 아이템이 화면 중앙에 올 수 있도록
 * 좌/우 가장자리에 (parent.width - itemWidthPx)/2 만큼 여백을 부여.
 */
class EdgeCenterSpacingDecoration(
    private val itemWidthDp: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return

        val itemCount = parent.adapter?.itemCount ?: return
        if (itemCount <= 0) return

        // dp → px
        val density = parent.resources.displayMetrics.density
        val itemWidthPx = (density * itemWidthDp).toInt().coerceAtLeast(1)

        // 부모 폭이 아직 0인 초기 레이아웃 타이밍이 있을 수 있음 → 음수 피하기
        val parentWidth = parent.width.coerceAtLeast(0)
        val sidePadding = (((parentWidth - itemWidthPx) / 2f) * 0.88f).toInt().coerceAtLeast(0)

        when (pos) {
            0 -> outRect.left = sidePadding
            itemCount - 1 -> outRect.right = sidePadding
            else -> { /* no-op */ }
        }
    }
}
