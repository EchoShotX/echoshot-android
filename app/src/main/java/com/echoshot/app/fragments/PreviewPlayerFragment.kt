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

        // MediaController 연결
        val mc = MediaController(requireContext()).apply {
            setAnchorView(binding.videoView)
        }
        binding.videoView.setMediaController(mc)

        // 전달받은 Uri 로 재생
        binding.videoView.setVideoURI(Uri.parse(args.videoUri))
        binding.videoView.requestFocus()
        binding.videoView.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.videoView.stopPlayback()
        _binding = null
    }
}

