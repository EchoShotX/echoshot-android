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
        val title: String,
        val description: String
    )

    private val guidePages = listOf(
        GuidePage(
            R.drawable.guide_sample_1,
            "일반 카메라처럼 줌해서 촬영",
            "화면을 확대·축소하면서 원하는 구도로 동영상을 찍어주세요."
        ),
        GuidePage(
            R.drawable.guide_sample_2,
            "EchoShot 갤러리에서 영상 확인",
            "촬영한 영상과 더 넓은 구도의 영상이 저장된것을 확인하세요."
        ),
        GuidePage(
            R.drawable.guide_sample_3,
            "인물 중심 영상 제작",
            "분석 모드를 고르고 '시작'을 누르면 완벽한 인물 중심으로 영상을 다시 만들어요"
        ),
        GuidePage(
            R.drawable.guide_sample_4,
            "인물만 따라가는 직캠을 감상",
            "분석이 끝나면 인물을 크게, 안정된 구도로 다시 볼 수 있습니다."
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
            "확인"
        } else {
            "다음"
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
                titleView.text = page.title
                descriptionView.text = page.description
            }
        }
    }
}

