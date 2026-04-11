

    package com.echoshot.app

    import android.hardware.camera2.params.DynamicRangeProfiles
    import android.media.AudioFormat
    import android.media.AudioRecord
    import android.media.MediaCodec
    import android.media.MediaCodecInfo
    import android.media.MediaFormat
    import android.media.MediaMuxer
    import android.media.MediaRecorder
    import android.os.Handler
    import android.os.Looper
    import android.os.Message
    import android.util.Log
    import android.view.Surface

    import com.echoshot.app.fragments.VideoCodecFragment

    import java.io.File
    import java.io.IOException
    import java.lang.ref.WeakReference
    import java.nio.ByteBuffer

    /**
    * Encodes video by streaming to disk.
    */
    class EncoderWrapper(private val name: String,
                        width: Int,
                        height: Int,
                        bitRate: Int,
                        frameRate: Int,
                        dynamicRange: Long,
                        orientationHint: Int,
                        outputFile: File,
                        useMediaRecorder: Boolean,
                        videoCodec: Int) {
        companion object {
            val TAG = "EncoderWrapper"
            val VERBOSE = false
            val IFRAME_INTERVAL = 1 // sync one frame every second
            
            // 오디오 설정
            private const val AUDIO_SAMPLE_RATE = 44100
            private const val AUDIO_CHANNEL_COUNT = 1 // 모노
            private const val AUDIO_BIT_RATE = 64000 // 64kbps
            private const val AUDIO_MIME_TYPE = "audio/mp4a-latm" // AAC
            
        }

        private val mWidth = width
        private val mHeight = height
        private val mBitRate = bitRate
        private val mFrameRate = frameRate
        private val mDynamicRange = dynamicRange
        private val mOrientationHint = orientationHint

        private val mUseMediaRecorder = useMediaRecorder
        private val mVideoCodec = videoCodec

        private val mMimeType = VideoCodecFragment.idToType(mVideoCodec)

        private val mOutputFile = outputFile

        // 🔓 외부에서 접근할 수 있도록 공개 getter 제공
        val outputFile: File
            get() = mOutputFile

        private val mEncoderThread: EncoderThread? by lazy {
            if (useMediaRecorder) {
                null
            } else {
                EncoderThread(name, mEncoder!!, outputFile, mOrientationHint, frameEncodedListener, mAudioEncoder, mAudioRecord, mFrameRate, mWidth, mHeight)
            }
        }

        // ✅ var로 변경하여 재시도 시 재할당 가능
        private var mEncoder: MediaCodec? = null

        private val mInputSurface: Surface by lazy {
            if (useMediaRecorder) {
                // Get a persistent Surface from MediaCodec, don't forget to release when done
                val surface = MediaCodec.createPersistentInputSurface()

                // Prepare and release a dummy MediaRecorder with our new surface
                // Required to allocate an appropriately sized buffer before passing the Surface as the
                //  output target to the capture session
                createRecorder(surface).apply {
                    prepare()
                    release()
                }

                surface
            } else {
                mEncoder!!.createInputSurface()
            }
        }

        private var mMediaRecorder: MediaRecorder? = null

        // 오디오 관련 변수
        private var mAudioRecord: AudioRecord? = null
        private var mAudioEncoder: MediaCodec? = null

        // ① 프레임 타임스탬프 전달용 콜백 인터페이스
        fun interface OnFrameEncodedListener {
            fun onFrameEncoded(presentationTimeUs: Long)
        }

        // ② 외부에서 등록한 리스너를 보관할 변수
        private var frameEncodedListener: OnFrameEncodedListener? = null

        // ③ 외부에서 리스너를 등록하기 위한 메서드
        fun setOnFrameEncodedListener(listener: OnFrameEncodedListener) {
            frameEncodedListener = listener
        }

        private fun createRecorder(surface: Surface): MediaRecorder {
            Log.d(TAG, "🎬 [$name] MediaRecorder 설정: 요청 해상도=${mWidth}x${mHeight}, bitRate=$mBitRate, frameRate=$mFrameRate")
            return MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(mOutputFile.absolutePath)
                setVideoEncodingBitRate(mBitRate)
                if (mFrameRate > 0) setVideoFrameRate(mFrameRate)
                setVideoSize(mWidth, mHeight)

                val videoEncoder = when (mVideoCodec) {
                    VideoCodecFragment.VIDEO_CODEC_ID_H264 ->
                            MediaRecorder.VideoEncoder.H264
                    VideoCodecFragment.VIDEO_CODEC_ID_HEVC ->
                            MediaRecorder.VideoEncoder.HEVC
                    VideoCodecFragment.VIDEO_CODEC_ID_AV1 ->
                            MediaRecorder.VideoEncoder.AV1
                    else -> throw IllegalArgumentException("Unknown video codec id")
                }

                setVideoEncoder(videoEncoder)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(16)
                setAudioSamplingRate(44100)
                setInputSurface(surface)
                setOrientationHint(mOrientationHint)
            }
        }

        /**
        * Configures encoder
        */
        init {
            if (useMediaRecorder) {
                mMediaRecorder = createRecorder(mInputSurface)
            } else {
                // ✅ 인코더 먼저 생성 - HEVC 실패 시 H.264로 Fallback
                var actualMimeType = mMimeType
                mEncoder = try {
                    MediaCodec.createEncoderByType(mMimeType)
                } catch (e: Exception) {
                    Log.w(TAG, "🎬 [$name] $mMimeType 인코더 생성 실패, H.264로 Fallback: ${e.message}")
                    actualMimeType = "video/avc"  // H.264
                    try {
                        MediaCodec.createEncoderByType(actualMimeType)
                    } catch (e2: Exception) {
                        Log.e(TAG, "🎬 [$name] H.264 인코더도 생성 실패 - 이 기기는 녹화를 지원하지 않습니다: ${e2.message}")
                        throw RuntimeException("이 기기에서 비디오 녹화를 지원하지 않습니다", e2)
                    }
                }
                
                // ✅ H.264로 Fallback된 경우 HDR 프로파일 비활성화
                val didFallbackToH264 = (actualMimeType != mMimeType)
                
                val codecProfile = if (didFallbackToH264) {
                    -1  // H.264는 HDR 프로파일 미지원
                } else {
                    when (mVideoCodec) {
                        VideoCodecFragment.VIDEO_CODEC_ID_HEVC -> when {
                            dynamicRange == DynamicRangeProfiles.HLG10 ->
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                            dynamicRange == DynamicRangeProfiles.HDR10 ->
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                            dynamicRange == DynamicRangeProfiles.HDR10_PLUS ->
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                            else -> -1
                        }
                        VideoCodecFragment.VIDEO_CODEC_ID_AV1 -> when {
                            dynamicRange == DynamicRangeProfiles.HLG10 ->
                                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                            dynamicRange == DynamicRangeProfiles.HDR10 ->
                                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
                            dynamicRange == DynamicRangeProfiles.HDR10_PLUS ->
                                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                            else -> -1
                        }
                        else -> -1
                    }
                }

                Log.d(TAG, "🎬 [$name] 인코더 설정: 요청 해상도=${width}x${height}, bitRate=$bitRate, frameRate=$frameRate, fallbackToH264=$didFallbackToH264")

                // ✅ 강화된 폴백 전략: 최대 12단계 시도 (해상도 폴백 포함)
                var configured = false
                var currentBitRate = bitRate
                var currentWidth = width
                var currentHeight = height
                var currentMimeType = actualMimeType  // ✅ 이미 Fallback된 mime type 사용
                var currentCodecProfile = codecProfile
                var attempts = 0
                val maxAttempts = 12
                val minBitRate = 2_000_000  // 최소 2Mbps (1080p 기준)
                var useHdrProfile = (codecProfile != -1) && !didFallbackToH264  // ✅ H.264 Fallback 시 HDR 비활성화
                var triedH264 = didFallbackToH264  // ✅ 이미 H.264로 Fallback된 상태
                var resolution = "4K"  // 현재 해상도 단계
                
                while (!configured && attempts < maxAttempts) {
                    try {
                        val format = MediaFormat.createVideoFormat(currentMimeType, currentWidth, currentHeight)
                        
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        format.setInteger(MediaFormat.KEY_BIT_RATE, currentBitRate)
                        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)

                        // HDR 프로파일 설정
                        if (useHdrProfile && currentCodecProfile != -1) {
                            format.setInteger(MediaFormat.KEY_PROFILE, currentCodecProfile)
                            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, getTransferFunction())
                            format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing, true)
                        }

                        if (VERBOSE) Log.d(TAG, "format: " + format)

                        mEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        configured = true
                        Log.d(TAG, "🎬 [$name] 인코더 설정 성공: ${currentWidth}x${currentHeight} ($resolution), codec=$currentMimeType, bitRate=$currentBitRate, hdr=$useHdrProfile")
                        
                    } catch (e: IllegalArgumentException) {
                        attempts++
                        Log.w(TAG, "🎬 [$name] 인코더 설정 실패 (시도 $attempts/$maxAttempts): ${e.message}")
                        
                        if (attempts < maxAttempts) {
                            // 폴백 전략:
                            // 1-2차: 비트레이트 낮춤 (4K 유지)
                            // 3차: HDR 비활성화 (4K 유지)
                            // 4차: 비트레이트 더 낮춤 (4K 유지)
                            // 5차: 1080p로 해상도 낮춤 + H.264
                            // 6-8차: 1080p + 비트레이트 낮춤
                            // 9차: 720p로 해상도 낮춤
                            // 10-12차: 720p + 비트레이트 낮춤
                            when (attempts) {
                                1, 2 -> {
                                    currentBitRate = currentBitRate / 2
                                    Log.d(TAG, "🎬 [$name] 비트레이트 낮춰서 재시도: $currentBitRate")
                                }
                                3 -> {
                                    useHdrProfile = false
                                    currentCodecProfile = -1
                                    currentBitRate = bitRate / 2
                                    Log.d(TAG, "🎬 [$name] HDR 비활성화 후 재시도: bitRate=$currentBitRate")
                                }
                                4 -> {
                                    currentBitRate = currentBitRate / 2
                                    Log.d(TAG, "🎬 [$name] 비트레이트 더 낮춰서 재시도: $currentBitRate")
                                }
                                5 -> {
                                    // 1080p로 해상도 낮춤 (저사양 기기 대응)
                                    resolution = "1080p"
                                    // 가로가 더 긴 경우 (가로 모드)
                                    if (width > height) {
                                        currentWidth = 1920
                                        currentHeight = 1080
                                    } else {
                                        // 세로 모드
                                        currentWidth = 1080
                                        currentHeight = 1920
                                    }
                                    currentBitRate = 10_000_000  // 1080p에 적합한 10Mbps
                                    currentMimeType = "video/avc"  // H.264로 변경 (호환성)
                                    triedH264 = true
                                    Log.d(TAG, "🎬 [$name] 1080p + H.264로 폴백: ${currentWidth}x${currentHeight}, bitRate=$currentBitRate")
                                }
                                6, 7, 8 -> {
                                    currentBitRate = (currentBitRate / 2).coerceAtLeast(minBitRate)
                                    Log.d(TAG, "🎬 [$name] 1080p 비트레이트 낮춤: $currentBitRate")
                                }
                                9 -> {
                                    // 720p로 해상도 더 낮춤 (초저사양 기기 대응)
                                    resolution = "720p"
                                    if (width > height) {
                                        currentWidth = 1280
                                        currentHeight = 720
                                    } else {
                                        currentWidth = 720
                                        currentHeight = 1280
                                    }
                                    currentBitRate = 5_000_000  // 720p에 적합한 5Mbps
                                    Log.d(TAG, "🎬 [$name] 720p로 폴백: ${currentWidth}x${currentHeight}, bitRate=$currentBitRate")
                                }
                                else -> {
                                    currentBitRate = (currentBitRate / 2).coerceAtLeast(1_000_000)
                                    Log.d(TAG, "🎬 [$name] 720p 비트레이트 낮춤: $currentBitRate")
                                }
                            }
                            
                            // 인코더 재생성
                            try {
                                mEncoder?.release()
                            } catch (ignored: Exception) {}
                            mEncoder = MediaCodec.createEncoderByType(currentMimeType)
                        } else {
                            // 모든 시도 실패 시 예외 전파
                            Log.e(TAG, "🎬 [$name] 인코더 설정 최종 실패 (12회 시도) - 이 기기에서 녹화가 지원되지 않을 수 있습니다")
                            throw e
                        }
                    }
                }
                
                // ✅ 오디오 인코더 및 AudioRecord 초기화
                setupAudioEncoder()
            }
        }
        
        /**
        * 오디오 인코더 및 AudioRecord 설정
        */
        private fun setupAudioEncoder() {
            try {
                // 오디오 포맷 생성
                val audioFormat = MediaFormat.createAudioFormat(
                    AUDIO_MIME_TYPE,
                    AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNEL_COUNT
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                
                // 오디오 인코더 생성 및 설정
                mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE)
                mAudioEncoder?.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                
                // AudioRecord 설정
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    channelConfig,
                    audioEncoding
                ) * 2
                
                if (bufferSize <= 0) {
                    Log.e(TAG, "❌ AudioRecord 버퍼 크기 계산 실패")
                    mAudioEncoder = null
                    mAudioRecord = null
                    return
                }
                
                mAudioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE,
                    channelConfig,
                    audioEncoding,
                    bufferSize
                )
                
                if (mAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "❌ AudioRecord 초기화 실패")
                    mAudioRecord?.release()
                    mAudioRecord = null
                    mAudioEncoder?.release()
                    mAudioEncoder = null
                    return
                }
                
                Log.d(TAG, "✅ 오디오 인코더 및 AudioRecord 초기화 완료 (버퍼 크기: $bufferSize)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 오디오 인코더 설정 실패", e)
                mAudioEncoder = null
                mAudioRecord = null
            }
        }

        private fun getTransferFunction() = when (mDynamicRange) {
            DynamicRangeProfiles.HLG10 -> MediaFormat.COLOR_TRANSFER_HLG
            DynamicRangeProfiles.HDR10 -> MediaFormat.COLOR_TRANSFER_ST2084
            DynamicRangeProfiles.HDR10_PLUS -> MediaFormat.COLOR_TRANSFER_ST2084
            else -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        }

        /**
        * Returns the encoder's input surface.
        */
        public fun getInputSurface(): Surface {
            return mInputSurface
        }

        public fun start() {
            if (mUseMediaRecorder) {
                mMediaRecorder!!.apply {
                    prepare()
                    start()
                }
            } else {
                try {
                    mEncoder!!.start()
                } catch (e: android.media.MediaCodec.CodecException) {
                    Log.e(TAG, "❌ [$name] 비디오 인코더 시작 실패 (CodecException): ${e.message}", e)
                    throw RuntimeException("비디오 녹화를 시작할 수 없습니다. 기기가 현재 인코딩을 지원하지 않습니다.", e)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "❌ [$name] 비디오 인코더 시작 실패 (IllegalState): ${e.message}", e)
                    throw RuntimeException("비디오 녹화를 시작할 수 없습니다. 인코더 상태가 올바르지 않습니다.", e)
                }
                
                // ✅ 오디오 인코더 시작
                try {
                    mAudioEncoder?.start()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ [$name] 오디오 인코더 시작 실패 (무시): ${e.message}")
                    // 오디오는 선택적이므로 실패해도 계속 진행
                }

                // Start the encoder thread last.  That way we're sure it can see all of the state
                // we've initialized.
                mEncoderThread!!.start()
                mEncoderThread!!.waitUntilReady()
            }
        }

        /**
        * Shuts down the encoder thread, and releases encoder resources.
        * <p>
        * Does not return until the encoder thread has stopped.
        */
        public fun shutdown(): Boolean {
            if (VERBOSE) Log.d(TAG, "releasing encoder objects")

            if (mUseMediaRecorder) {
                try {
                    mMediaRecorder!!.stop()
                } catch (e: RuntimeException) {
                    // https://developer.android.com/reference/android/media/MediaRecorder.html#stop()
                    // RuntimeException will be thrown if no valid audio/video data has been received
                    // when stop() is called, it usually happens when stop() is called immediately after
                    // start(). In this case the output file is not properly constructed ans should be
                    // deleted.
                    Log.d(TAG, "RuntimeException: stop() is called immediately after start()");
                    //noinspection ResultOfMethodCallIgnored
                    mOutputFile.delete()
                    return false
                }
            } else {
                val handler = mEncoderThread!!.getHandler()
                handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN))
                try {
                    mEncoderThread!!.join()
                } catch (ie: InterruptedException ) {
                    Log.w(TAG, "Encoder thread join() was interrupted", ie)
                }

                mEncoder!!.stop()
                mEncoder!!.release()
                
                // ✅ 오디오 정리
                mAudioRecord?.stop()
                mAudioRecord?.release()
                mAudioEncoder?.stop()
                mAudioEncoder?.release()
            }
            return true
        }

        fun shutdownTogetherWith(other: EncoderWrapper): Boolean {
            if (VERBOSE) Log.d(TAG, "🧯 두 인코더 병렬 셧다운 시작")

            var allSuccess = true

            try {
                // 1. 두 스레드에게 동시에 shutdown 메시지 전달
                mEncoderThread?.getHandler()?.sendMessage(
                    mEncoderThread!!.getHandler().obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN)
                )
                other.mEncoderThread?.getHandler()?.sendMessage(
                    other.mEncoderThread!!.getHandler().obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN)
                )

                // 2. 두 스레드 join (각각의 인코딩 스레드 종료 대기)
                mEncoderThread?.join()
                other.mEncoderThread?.join()

                // 3. MediaCodec stop
                try {
                    mEncoder?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 첫 번째 인코더 stop 중 예외 발생: ${e.message}")
                    allSuccess = false
                }

                try {
                    other.mEncoder?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 두 번째 인코더 stop 중 예외 발생: ${e.message}")
                    allSuccess = false
                }

                // 4. MediaCodec release
                try {
                    mEncoder?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 첫 번째 인코더 release 중 예외 발생: ${e.message}")
                    allSuccess = false
                }

                try {
                    other.mEncoder?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 두 번째 인코더 release 중 예외 발생: ${e.message}")
                    allSuccess = false
                }
                
                // ✅ 오디오 정리 (각 인스턴스의 shutdown()에서 처리되지만, 여기서도 명시적으로 정리)
                try {
                    mAudioRecord?.stop()
                    mAudioRecord?.release()
                    mAudioEncoder?.stop()
                    mAudioEncoder?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 첫 번째 오디오 인코더 정리 중 예외 발생: ${e.message}")
                    allSuccess = false
                }
                
                // other 인스턴스의 오디오는 other.shutdown()에서 처리됨

            } catch (e: Exception) {
                Log.e(TAG, "❌ 인코더 셧다운 중 예외 발생: ${e.message}")
                allSuccess = false
            }

            return allSuccess
        }

        /**
        * Notifies the encoder thread that a new frame is available to the encoder.
        */
        public fun frameAvailable() {
            if (!mUseMediaRecorder) {
                Log.d("RenderHandler", "🟢 EncoderWrapper.frameAvailable() called")
                val handler = mEncoderThread!!.getHandler()
                handler.sendMessage(handler.obtainMessage(
                        EncoderThread.EncoderHandler.MSG_FRAME_AVAILABLE))
            }
        }

        public fun waitForFirstFrame() {
            if (!mUseMediaRecorder) {
                mEncoderThread!!.waitForFirstFrame()
            }
        }

        fun signalEndOfInput() {
            if (mUseMediaRecorder) return
            mEncoder?.signalEndOfInputStream()
            // 출력 큐 비우도록 한 번 더 깨우기
            mEncoderThread?.getHandler()?.sendMessage(
                mEncoderThread!!.getHandler()
                    .obtainMessage(EncoderThread.EncoderHandler.MSG_FRAME_AVAILABLE)
            )
        }

        /**
        * Object that encapsulates the encoder thread.
        * <p>
        * We want to sleep until there's work to do.  We don't actually know when a new frame
        * arrives at the encoder, because the other thread is sending frames directly to the
        * input surface.  We will see data appear at the decoder output, so we can either use
        * an infinite timeout on dequeueOutputBuffer() or wait() on an object and require the
        * calling app wake us.  It's very useful to have all of the buffer management local to
        * this thread -- avoids synchronization -- so we want to do the file muxing in here.
        * So, it's best to sleep on an object and do something appropriate when awakened.
        * <p>
        * This class does not manage the MediaCodec encoder startup/shutdown.  The encoder
        * should be fully started before the thread is created, and not shut down until this
        * thread has been joined.
        */
        private class EncoderThread(private val name: String,
                                    mediaCodec: MediaCodec,
                                    outputFile: File,
                                    orientationHint: Int,
                                    private var listener: OnFrameEncodedListener?,
                                    private val audioEncoder: MediaCodec?,
                                    private val audioRecord: AudioRecord?,
                                    private val frameRate: Int,
                                    private val requestedWidth: Int,
                                    private val requestedHeight: Int
                                ): Thread() {
            val mEncoder = mediaCodec
            var mEncodedFormat: MediaFormat? = null
            val mBufferInfo = MediaCodec.BufferInfo()
            val mMuxer = MediaMuxer(outputFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mOrientationHint = orientationHint
            var mVideoTrack: Int = -1
            
            // 오디오 관련 변수
            var mAudioTrack: Int = -1
            var mAudioFormat: MediaFormat? = null
            val mAudioBufferInfo = MediaCodec.BufferInfo()
            @Volatile
            private var mAudioRecording = false
            private var mAudioThread: Thread? = null
            private var mAudioDrainThread: Thread? = null
            private var mAudioSampleCount: Long = 0 // 오디오 샘플 카운터 (타임스탬프 계산용)
            private val AUDIO_SAMPLE_RATE = 44100L // 샘플레이트
            @Volatile
            private var mFirstVideoTimestamp: Long = -1 // 첫 비디오 프레임 타임스탬프 (오디오 동기화용)
            private var mLastVideoPtsUs: Long = -1L // 마지막 비디오 PTS (단조증가 보정용)
            private var mLastAudioPtsUs: Long = -1L // 마지막 오디오 PTS (단조증가 보정용)
            private val mFrameDurUs: Long = 1_000_000L / frameRate.coerceAtLeast(1) // 프레임 간격 (마이크로초)
            private var mVideoFrameIndex: Long = 0L // 비디오 프레임 인덱스 (CFR 강제용)
            
            // ✅ MediaMuxer 상태 관리 - 모든 트랙이 추가된 후에만 start
            @Volatile
            private var mMuxerStarted = false
            @Volatile
            private var mVideoEOSReceived = false
            @Volatile  
            private var mAudioEOSReceived = false
            
            // 오디오가 필요한지 여부 (audioEncoder가 null이면 오디오 불필요)
            private val mNeedsAudio = audioEncoder != null

            var mHandler: EncoderHandler? = null
            var mFrameNum: Int = 0

            val mLock: Object = Object()

            @Volatile
            var mReady: Boolean = false

            /**
            * Thread entry point.
            * <p>
            * Prepares the Looper, Handler, and signals anybody watching that we're ready to go.
            */
            public override fun run() {
                Looper.prepare()
                mHandler = EncoderHandler(this)    // must create on encoder thread
                Log.d(TAG, "encoder thread ready")
                
                // ✅ 오디오 녹음 및 인코딩 스레드 시작
                if (audioEncoder != null && audioRecord != null) {
                    startAudioRecording()
                }
                
                synchronized (mLock) {
                    mReady = true
                    mLock.notify()    // signal waitUntilReady()
                }

                Looper.loop()

                // ✅ 오디오 정리
                stopAudioRecording()
                
                synchronized (mLock) {
                    mReady = false
                    mHandler = null
                }
                Log.d(TAG, "looper quit")
            }
            
            /**
            * 오디오 녹음 시작
            */
            private fun startAudioRecording() {
                if (audioRecord == null || audioEncoder == null) return
                
                mAudioRecording = true
                mAudioSampleCount = 0 // 샘플 카운터 초기화
                
                // 오디오 입력 스레드 (AudioRecord -> MediaCodec)
                mAudioThread = Thread {
                    val buffer = ByteArray(4096)
                    var pendingData: ByteArray? = null  // 버퍼를 못 얻었을 때 임시 저장
                    var pendingSize = 0
                    
                    try {
                        audioRecord.startRecording()
                        Log.d(TAG, "🎵 AudioRecord 녹음 시작")
                        
                        while (mAudioRecording) {
                            // 이전에 저장해둔 데이터가 있으면 먼저 처리
                            val dataToProcess: ByteArray
                            val sizeToProcess: Int
                            
                            if (pendingData != null) {
                                dataToProcess = pendingData
                                sizeToProcess = pendingSize
                                pendingData = null
                                pendingSize = 0
                            } else {
                                val readSize = audioRecord.read(buffer, 0, buffer.size)
                                if (readSize > 0) {
                                    dataToProcess = buffer
                                    sizeToProcess = readSize
                                } else if (readSize == AudioRecord.ERROR_INVALID_OPERATION) {
                                    Log.e(TAG, "❌ AudioRecord 읽기 오류: ERROR_INVALID_OPERATION")
                                    break
                                } else if (readSize == AudioRecord.ERROR_BAD_VALUE) {
                                    Log.e(TAG, "❌ AudioRecord 읽기 오류: ERROR_BAD_VALUE")
                                    break
                                } else {
                                    continue
                                }
                            }
                            
                            // ✅ 버퍼 획득 시 재시도 로직 (최대 50ms 대기)
                            var inputBufferIndex = -1
                            var retryCount = 0
                            val maxRetries = 5
                            
                            while (inputBufferIndex < 0 && retryCount < maxRetries && mAudioRecording) {
                                inputBufferIndex = audioEncoder.dequeueInputBuffer(10000) // 10ms 타임아웃
                                if (inputBufferIndex < 0) {
                                    retryCount++
                                    if (retryCount < maxRetries) {
                                        Thread.sleep(2) // 짧은 대기 후 재시도
                                    }
                                }
                            }
                            
                            if (inputBufferIndex >= 0) {
                                val inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex)
                                inputBuffer?.clear()
                                inputBuffer?.put(dataToProcess, 0, sizeToProcess)
                                
                                // ✅ 샘플 수 기반 타임스탬프 계산 (16-bit 모노 = 2 bytes per sample)
                                val samplesRead = sizeToProcess / 2
                                val presentationTimeUs = (mAudioSampleCount * 1_000_000L) / AUDIO_SAMPLE_RATE
                                mAudioSampleCount += samplesRead
                                
                                audioEncoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sizeToProcess,
                                    presentationTimeUs,
                                    0
                                )
                            } else {
                                // ✅ 버퍼를 끝내 얻지 못함 - 데이터 임시 저장 (다음 루프에서 재시도)
                                Log.w(TAG, "⚠️ 오디오 입력 버퍼 획득 실패 (재시도 $retryCount 회) - 데이터 임시 저장")
                                pendingData = dataToProcess.copyOf(sizeToProcess)
                                pendingSize = sizeToProcess
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 오디오 녹음 중 예외 발생", e)
                    } finally {
                        try {
                            audioRecord.stop()
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ AudioRecord stop 중 예외", e)
                        }
                        Log.d(TAG, "🎵 AudioRecord 녹음 종료")
                    }
                }
                
                // 오디오 인코더 출력 처리 스레드
                mAudioDrainThread = Thread {
                    drainAudioEncoder()
                }
                
                mAudioThread?.start()
                mAudioDrainThread?.start()
            }
            
            /**
            * 오디오 녹음 중지
            */
            private fun stopAudioRecording() {
                mAudioRecording = false
                
                // 오디오 인코더에 EOS 신호
                try {
                    if (audioEncoder != null) {
                        val inputBufferIndex = audioEncoder.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            audioEncoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 오디오 EOS 신호 전송 중 예외", e)
                }
                
                // 스레드 종료 대기
                try {
                    mAudioThread?.join(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 오디오 스레드 join 중 예외", e)
                }
                
                try {
                    mAudioDrainThread?.join(2000)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 오디오 드레인 스레드 join 중 예외", e)
                }
            }
            
            /**
            * 오디오 인코더 출력 처리
            */
            private fun drainAudioEncoder() {
                if (audioEncoder == null) return
                
                while (!mAudioEOSReceived) {
                    val outputBufferIndex = audioEncoder.dequeueOutputBuffer(mAudioBufferInfo, 10000)
                    
                    when {
                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!mAudioRecording && !mAudioEOSReceived) {
                                // 녹음은 중지되었지만 EOS 아직 안 받음 - 계속 드레인
                                continue
                            } else if (!mAudioRecording) {
                                break
                            }
                        }
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            mAudioFormat = audioEncoder.outputFormat
                            Log.d(TAG, "🎵 오디오 포맷 변경: $mAudioFormat")
                            
                            // ✅ Muxer 상태에 따라 트랙 추가 및 시작
                            synchronized(mMuxer) {
                                if (!mMuxerStarted && mAudioTrack == -1 && mAudioFormat != null) {
                                    mAudioTrack = mMuxer.addTrack(mAudioFormat!!)
                                    Log.d(TAG, "🎵 오디오 트랙 추가됨: track=$mAudioTrack")
                                    
                                    // 비디오 트랙도 이미 있으면 Muxer 시작
                                    tryStartMuxer()
                                }
                            }
                        }
                        outputBufferIndex >= 0 -> {
                            val outputBuffer = audioEncoder.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null && mAudioBufferInfo.size > 0) {
                                outputBuffer.position(mAudioBufferInfo.offset)
                                outputBuffer.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size)
                                
                                // ✅ 오디오 PTS 단조증가 보정
                                var adjustedAudioTimestamp = mAudioBufferInfo.presentationTimeUs
                                if (adjustedAudioTimestamp <= mLastAudioPtsUs) {
                                    adjustedAudioTimestamp = mLastAudioPtsUs + 1000 // 최소 1ms 증가
                                    Log.w(TAG, "⚠️ 오디오 PTS 단조증가 보정: $adjustedAudioTimestamp (last=$mLastAudioPtsUs)")
                                }
                                mLastAudioPtsUs = adjustedAudioTimestamp
                                
                                // 타임스탬프 조정된 BufferInfo 생성
                                val adjustedBufferInfo = MediaCodec.BufferInfo().apply {
                                    set(
                                        mAudioBufferInfo.offset,
                                        mAudioBufferInfo.size,
                                        adjustedAudioTimestamp,
                                        mAudioBufferInfo.flags
                                    )
                                }
                                
                                // ✅ Muxer가 시작되었고 오디오 트랙이 있으면 기록
                                synchronized(mMuxer) {
                                    if (mMuxerStarted && mAudioTrack != -1) {
                                        mMuxer.writeSampleData(mAudioTrack, outputBuffer, adjustedBufferInfo)
                                    }
                                }
                            }
                            audioEncoder.releaseOutputBuffer(outputBufferIndex, false)
                            
                            if ((mAudioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.d(TAG, "🎵 오디오 End of Stream 도달")
                                mAudioEOSReceived = true
                            }
                        }
                    }
                }
                
                Log.d(TAG, "🎵 오디오 인코더 드레인 종료 (EOS 수신완료)")
            }
            
            /**
            * ✅ 비디오/오디오 트랙이 모두 준비되면 Muxer 시작
            * synchronized(mMuxer) 블록 내에서만 호출해야 함
            */
            private fun tryStartMuxer() {
                // 이미 시작되었으면 무시
                if (mMuxerStarted) return
                
                // 비디오 트랙은 필수
                if (mVideoTrack == -1) return
                
                // 오디오가 필요한데 아직 트랙이 없으면 대기
                if (mNeedsAudio && mAudioTrack == -1) {
                    Log.d(TAG, "⏳ Muxer 시작 대기: 오디오 트랙 아직 없음 (video=$mVideoTrack, audio=$mAudioTrack)")
                    return
                }
                
                // 모든 조건 충족 - Muxer 시작
                mMuxer.start()
                mMuxerStarted = true
                Log.d(TAG, "🟢 MediaMuxer 시작됨 (video=$mVideoTrack, audio=$mAudioTrack, needsAudio=$mNeedsAudio)")
            }

            /**
            * Waits until the encoder thread is ready to receive messages.
            * <p>
            * Call from non-encoder thread.
            */
            public fun waitUntilReady() {
                synchronized (mLock) {
                    while (!mReady) {
                        try {
                            mLock.wait()
                        } catch (ie: InterruptedException) { /* not expected */ }
                    }
                }
            }

            /**
            * Waits until the encoder has processed a single frame.
            * <p>
            * Call from non-encoder thread.
            */
            public fun waitForFirstFrame() {
                synchronized (mLock) {
                    while (mFrameNum < 1) {
                        try {
                            mLock.wait()
                        } catch (ie: InterruptedException) {
                            ie.printStackTrace();
                        }
                    }
                }
                Log.d(TAG, "🟢 OriginalEncoder.frameAvailable() called")
                Log.d(TAG, " Waited for first frame");
            }

            /**
            * Returns the Handler used to send messages to the encoder thread.
            */
            public fun getHandler(): EncoderHandler {
                synchronized (mLock) {
                    // Confirm ready state.
                    if (!mReady) {
                        throw RuntimeException("not ready")
                    }
                }
                return mHandler!!
            }

            /**
            * Drains all pending output from the encoder, and adds it to the circular buffer.
            */
            @Synchronized
            fun drainEncoder(): Boolean {
                val TIMEOUT_USEC: Long = 0 // 타임아웃 없음
                var encodedFrame = false

                Log.d(TAG, "🚨 [$name] drainEncoder() 시작")

                while (true) {
                    val encoderStatus: Int = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC)

                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.d(TAG, "⏳ [$name] 출력 버퍼 없음, 잠시 후 재시도")
                        break
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        mEncodedFormat = mEncoder.outputFormat
                        val actualWidth = mEncodedFormat?.getInteger(MediaFormat.KEY_WIDTH) ?: 0
                        val actualHeight = mEncodedFormat?.getInteger(MediaFormat.KEY_HEIGHT) ?: 0
                        Log.w(TAG, "🎬 [$name] 출력 포맷 변경됨: $mEncodedFormat")
                        Log.w(TAG, "⚠️ [$name] 실제 인코딩 해상도: ${actualWidth}x${actualHeight} (요청: ${requestedWidth}x${requestedHeight})")
                        if (actualWidth != requestedWidth || actualHeight != requestedHeight) {
                            Log.e(TAG, "❌ [$name] 해상도 불일치! 인코더가 입력 Surface 크기에 맞춰 해상도를 변경했습니다.")
                        }
                    } else if (encoderStatus < 0) {
                        Log.w(TAG, "⚠️ [$name] 예기치 않은 dequeueOutputBuffer 결과: $encoderStatus (무시됨)")
                    } else {
                        val encodedData: ByteBuffer? = mEncoder.getOutputBuffer(encoderStatus)
                        if (encodedData == null) {
                            throw RuntimeException("❌ [$name] 인코더 출력 버퍼 $encoderStatus 가 null입니다")
                        }

                        Log.d(TAG, "📦 [$name] 버퍼 정보: offset=${mBufferInfo.offset}, size=${mBufferInfo.size}, flags=${mBufferInfo.flags}")

                        if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            Log.d(TAG, "⚙️ [$name] Codec 설정 버퍼(SPS/PPS 등) → 무시됨")
                            mBufferInfo.size = 0
                        }

                        if (mBufferInfo.size != 0) {
                            encodedData.position(mBufferInfo.offset)
                            encodedData.limit(mBufferInfo.offset + mBufferInfo.size)

                            synchronized(mMuxer) {
                                if (mVideoTrack == -1) {
                                    mVideoTrack = mMuxer.addTrack(mEncodedFormat!!)
                                    mMuxer.setOrientationHint(mOrientationHint)
                                    
                                    // ✅ 첫 비디오 프레임 타임스탬프 저장 (오디오 동기화용)
                                    mFirstVideoTimestamp = mBufferInfo.presentationTimeUs
                                    // ✅ CFR 강제를 위해 프레임 인덱스 초기화
                                    mVideoFrameIndex = 0L
                                    Log.d(TAG, "🎬 [$name] 첫 비디오 타임스탬프: $mFirstVideoTimestamp, CFR 모드 시작 (frameRate=$frameRate, frameDur=${mFrameDurUs}us)")
                                    Log.d(TAG, "🎬 [$name] 비디오 트랙 추가됨: track=$mVideoTrack")
                                    
                                    // ✅ 오디오 트랙이 이미 준비되어 있으면 함께 추가 후 Muxer 시작 시도
                                    if (mAudioFormat != null && mAudioTrack == -1) {
                                        mAudioTrack = mMuxer.addTrack(mAudioFormat!!)
                                        Log.d(TAG, "🎵 오디오 트랙 추가됨 (비디오 트랙과 함께): track=$mAudioTrack")
                                    }
                                    
                                    // ✅ 모든 트랙이 준비되었으면 Muxer 시작
                                    tryStartMuxer()
                                }
                            }

                            synchronized(mMuxer) {
                                // ✅ Muxer가 시작되었고 비디오 트랙이 있으면 기록
                                if (mMuxerStarted && mVideoTrack != -1) {
                                    // ✅ CFR 강제: 프레임 인덱스 기반으로 고정 PTS 생성 (raw PTS 무시)
                                    // 이렇게 하면 두 인코더 모두 동일한 프레임 간격을 가짐
                                    var ptsUs = mVideoFrameIndex * mFrameDurUs
                                    
                                    // ✅ 단조증가 안전장치 (동일/역전 방지, 거의 안 걸리지만 안전장치)
                                    if (ptsUs <= mLastVideoPtsUs) {
                                        ptsUs = mLastVideoPtsUs + mFrameDurUs
                                        Log.w(TAG, "⚠️ [$name] PTS 단조증가 보정: $ptsUs (last=$mLastVideoPtsUs)")
                                    }
                                    
                                    // ✅ PTS 로깅 (문제 진단용 - 처음 30프레임 + 마지막 프레임)
                                    if (mFrameNum < 30 || (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        Log.d(TAG, "📊 [$name] PTS: raw=${mBufferInfo.presentationTimeUs}, CFR=$ptsUs (frameIdx=$mVideoFrameIndex), last=$mLastVideoPtsUs")
                                    }
                                    
                                    val fixedBufferInfo = MediaCodec.BufferInfo().apply {
                                        set(
                                            mBufferInfo.offset,
                                            mBufferInfo.size,
                                            ptsUs,
                                            mBufferInfo.flags
                                        )
                                    }
                                    
                                    mMuxer.writeSampleData(mVideoTrack, encodedData, fixedBufferInfo)
                                    
                                    // 프레임 인덱스 및 마지막 PTS 업데이트
                                    mVideoFrameIndex++
                                    mLastVideoPtsUs = ptsUs
                                    
                                    // ✅ 마지막 프레임 PTS 로깅 (두 동영상 길이 비교용)
                                    if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        val durationSec = ptsUs / 1_000_000.0
                                        val expectedFps = mVideoFrameIndex / durationSec
                                        Log.d(TAG, "🏁 [$name] 마지막 프레임: CFR PTS=$ptsUs, duration=${String.format("%.2f", durationSec)}초, frames=$mVideoFrameIndex, fps=${String.format("%.2f", expectedFps)}")
                                    }
                                }
                            }

                            listener?.onFrameEncoded(mBufferInfo.presentationTimeUs)


                            encodedFrame = true
                            Log.d(TAG, "✅ [$name] ${mBufferInfo.size} 바이트 프레임 기록 완료 (timestamp=${mBufferInfo.presentationTimeUs})")
                        } else {
                            Log.d(TAG, "📭 [$name] size 0인 버퍼 → 기록 생략")
                        }

                        mEncoder.releaseOutputBuffer(encoderStatus, false)

                        if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.w(TAG, "⛔ [$name] End of Stream 도달 → 종료")
                            mVideoEOSReceived = true
                            break
                        }
                    }
                }

                if (!encodedFrame) {
                    Log.w(TAG, "❌ [$name] 인코딩된 유효한 프레임이 없음")
                } else {
                    Log.d(TAG, "🏁 [$name] drainEncoder() 종료 — 적어도 한 프레임 이상 인코딩됨")
                }

                return encodedFrame
            }



            /**
            * Drains the encoder output.
            * <p>
            * See notes for {@link EncoderWrapper#frameAvailable()}.
            */
            fun frameAvailable() {
                Log.d("RenderHandler", "🟢 EncoderThread.frameAvailable() called")
                if (VERBOSE) Log.d(TAG, "frameAvailable")
                if (drainEncoder()) {
                    synchronized (mLock) {
                        mFrameNum++
                        mLock.notify()
                    }
                }
            }

            /**
            * Tells the Looper to quit.
            */
            fun shutdown() {
                if (VERBOSE) Log.d(TAG, "shutdown")
                Log.d(TAG, "🛑 [$name] shutdown() 시작")
                
                // ✅ 오디오 녹음 중지
                stopAudioRecording()
                
                // ✅ 비디오 EOS까지 드레인 (마지막 프레임 유실 방지)
                if (!mVideoEOSReceived) {
                    Log.d(TAG, "⏳ [$name] 비디오 EOS 대기 중...")
                    val maxDrainAttempts = 100 // 최대 1초 (10ms * 100)
                    var drainAttempt = 0
                    while (!mVideoEOSReceived && drainAttempt < maxDrainAttempts) {
                        drainEncoder()
                        if (!mVideoEOSReceived) {
                            try {
                                Thread.sleep(10)
                            } catch (e: InterruptedException) {
                                break
                            }
                        }
                        drainAttempt++
                    }
                    if (mVideoEOSReceived) {
                        Log.d(TAG, "✅ [$name] 비디오 EOS 수신 완료")
                    } else {
                        Log.w(TAG, "⚠️ [$name] 비디오 EOS 대기 타임아웃")
                    }
                }
                
                // ✅ 오디오 EOS 대기 (필요한 경우)
                if (mNeedsAudio && !mAudioEOSReceived) {
                    Log.d(TAG, "⏳ [$name] 오디오 EOS 대기 중...")
                    val maxWait = 2000L // 최대 2초
                    val startTime = System.currentTimeMillis()
                    while (!mAudioEOSReceived && (System.currentTimeMillis() - startTime) < maxWait) {
                        try {
                            Thread.sleep(50)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                    if (mAudioEOSReceived) {
                        Log.d(TAG, "✅ [$name] 오디오 EOS 수신 완료")
                    } else {
                        Log.w(TAG, "⚠️ [$name] 오디오 EOS 대기 타임아웃")
                    }
                }
                
                Log.d(TAG, "🏁 [$name] shutdown() 완료: videoEOS=$mVideoEOSReceived, audioEOS=$mAudioEOSReceived, frames=$mFrameNum")
                
                Looper.myLooper()!!.quit()
                
                // ✅ Muxer가 시작되었으면 stop
                synchronized(mMuxer) {
                    if (mMuxerStarted) {
                        try {
                            mMuxer.stop()
                            Log.d(TAG, "✅ [$name] MediaMuxer stop 완료")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ [$name] MediaMuxer stop 실패", e)
                        }
                    } else {
                        Log.w(TAG, "⚠️ [$name] MediaMuxer가 시작되지 않아 stop 생략")
                    }
                }
                mMuxer.release()
            }

            /**
            * Handler for EncoderThread.  Used for messages sent from the UI thread (or whatever
            * is driving the encoder) to the encoder thread.
            * <p>
            * The object is created on the encoder thread.
            */
            public class EncoderHandler(et: EncoderThread): Handler() {
                companion object {
                    val MSG_FRAME_AVAILABLE: Int = 0
                    val MSG_SHUTDOWN: Int = 1
                }

                // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
                // but no real harm in it.
                private val mWeakEncoderThread = WeakReference<EncoderThread>(et)

                // runs on encoder thread
                public override fun handleMessage(msg: Message) {
                    val what: Int = msg.what
                    if (VERBOSE) {
                        Log.v(TAG, "EncoderHandler: what=" + what)
                    }

                    val encoderThread: EncoderThread? = mWeakEncoderThread.get()
                    if (encoderThread == null) {
                        Log.w(TAG, "EncoderHandler.handleMessage: weak ref is null")
                        return
                    }

                    when (what) {
                        MSG_FRAME_AVAILABLE -> encoderThread.frameAvailable()
                        MSG_SHUTDOWN -> encoderThread.shutdown()
                        else -> throw RuntimeException("unknown message " + what)
                    }
                }
            }
        }
    }
