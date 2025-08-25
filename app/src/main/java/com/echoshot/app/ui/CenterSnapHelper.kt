package com.echoshot.app.ui

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView

class CenterSnapHelper : LinearSnapHelper() {
    override fun findSnapView(layoutManager: RecyclerView.LayoutManager?): View? {
        if (layoutManager !is LinearLayoutManager) return null
        val center = layoutManager.width / 2
        var minDist = Int.MAX_VALUE
        var target: View? = null
        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            val childCenter = (child.left + child.right) / 2
            val dist = kotlin.math.abs(childCenter - center)
            if (dist < minDist) { minDist = dist; target = child }
        }
        return target
    }
}
