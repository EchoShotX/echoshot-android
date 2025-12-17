package com.echoshot.app.fragments

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.echoshot.app.R

class GuideDialogFragment : DialogFragment() {

    companion object {
        fun newInstance() = GuideDialogFragment()
    }

    private data class GuidePage(
        val imageResId: Int,
        val titleResId: Int,
        val descriptionResId: Int
    )

    private val guidePages = listOf(
        GuidePage(
            R.drawable.guide_sample_1,
            R.string.guide_title_1,
            R.string.guide_desc_1
        ),
        GuidePage(
            R.drawable.guide_sample_2,
            R.string.guide_title_2,
            R.string.guide_desc_2
        ),
        GuidePage(
            R.drawable.guide_sample_3,
            R.string.guide_title_3,
            R.string.guide_desc_3
        ),
        GuidePage(
            R.drawable.guide_sample_4,
            R.string.guide_title_4,
            R.string.guide_desc_4
        )
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val root = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_guide, null)
        val viewPager = root.findViewById<ViewPager2>(R.id.viewPager)
        val btnClose = root.findViewById<ImageView>(R.id.btn_close)
        val btnNext = root.findViewById<Button>(R.id.btn_next)
        val indicatorContainer = root.findViewById<LinearLayout>(R.id.indicator_container)

        // ViewPager 어댑터 설정
        viewPager.adapter = GuidePagerAdapter(guidePages)

        // 점 인디케이터 생성
        createIndicators(indicatorContainer, guidePages.size)

        // 현재 페이지 추적
        var currentPage = 0
        updateButtonText(btnNext, currentPage, guidePages.size)
        updateIndicators(indicatorContainer, currentPage)

        // ViewPager 페이지 변경 리스너
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPage = position
                updateButtonText(btnNext, currentPage, guidePages.size)
                updateIndicators(indicatorContainer, currentPage)
            }
        })

        // 닫기 버튼 클릭
        btnClose.setOnClickListener {
            dismiss()
        }

        // 다음/확인 버튼 클릭
        btnNext.setOnClickListener {
            if (currentPage < guidePages.size - 1) {
                viewPager.currentItem = currentPage + 1
            } else {
                dismiss()
            }
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
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun createIndicators(container: LinearLayout, count: Int) {
        container.removeAllViews()
        val dotSize = (8 * resources.displayMetrics.density).toInt() // 8dp
        val dotMargin = (4 * resources.displayMetrics.density).toInt() // 4dp
        
        for (i in 0 until count) {
            val dot = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = dotMargin
                }
                setImageResource(R.drawable.indicator_dot_inactive)
            }
            container.addView(dot)
        }
    }

    private fun updateIndicators(container: LinearLayout, currentPage: Int) {
        for (i in 0 until container.childCount) {
            val dot = container.getChildAt(i) as ImageView
            if (i == currentPage) {
                dot.setImageResource(R.drawable.indicator_dot_active)
            } else {
                dot.setImageResource(R.drawable.indicator_dot_inactive)
            }
        }
    }

    private fun updateButtonText(button: Button, currentPage: Int, totalPages: Int) {
        button.text = if (currentPage == totalPages - 1) {
            getString(R.string.confirm)
        } else {
            getString(R.string.next)
        }
    }

    private class GuidePagerAdapter(private val pages: List<GuidePage>) :
        RecyclerView.Adapter<GuidePagerAdapter.GuideViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuideViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_guide_page, parent, false)
            return GuideViewHolder(view)
        }

        override fun onBindViewHolder(holder: GuideViewHolder, position: Int) {
            holder.bind(pages[position])
        }

        override fun getItemCount() = pages.size

        class GuideViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView = itemView.findViewById<ImageView>(R.id.guide_image)
            private val titleView = itemView.findViewById<TextView>(R.id.guide_title)
            private val descriptionView = itemView.findViewById<TextView>(R.id.guide_description)

            fun bind(page: GuidePage) {
                imageView.setImageResource(page.imageResId)
                titleView.text = itemView.context.getString(page.titleResId)
                descriptionView.text = itemView.context.getString(page.descriptionResId)
            }
        }
    }
}

