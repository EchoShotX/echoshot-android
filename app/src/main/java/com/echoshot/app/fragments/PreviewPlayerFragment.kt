package com.echoshot.app.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bumptech.glide.Glide
import com.echoshot.app.databinding.FragmentPreviewPlayerBinding

class PreviewPlayerFragment : Fragment() {

    private var _binding: FragmentPreviewPlayerBinding? = null
    private val binding get() = _binding!!
    private val args: PreviewPlayerFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreviewPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ▶︎ 뒤로가기
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        val uri = Uri.parse(args.videoUri)
        
        // MIME 타입 확인하여 이미지/동영상 구분
        val mimeType = requireContext().contentResolver.getType(uri)
        val isImage = mimeType?.startsWith("image/") == true

        if (isImage) {
            // 이미지인 경우 ImageView로 표시
            binding.videoView.visibility = View.GONE
            binding.imageView.visibility = View.VISIBLE
            Glide.with(this)
                .load(uri)
                .into(binding.imageView)
        } else {
            // 동영상인 경우 VideoView로 재생
            binding.videoView.visibility = View.VISIBLE
            binding.imageView.visibility = View.GONE
            
            // MediaController 연결
            val mc = MediaController(requireContext()).apply {
                setAnchorView(binding.videoView)
            }
            binding.videoView.setMediaController(mc)

            // 전달받은 Uri 로 재생
            binding.videoView.setVideoURI(uri)
            binding.videoView.requestFocus()
            binding.videoView.start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.videoView.stopPlayback()
        _binding = null
    }
}

