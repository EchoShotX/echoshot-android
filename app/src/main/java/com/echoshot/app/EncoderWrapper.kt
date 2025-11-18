/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
            EncoderThread(name, mEncoder!!, outputFile, mOrientationHint, frameEncodedListener, mAudioEncoder, mAudioRecord)
        }
    }

    private val mEncoder: MediaCodec? by lazy {
        if (useMediaRecorder) {
            null
        } else {
            MediaCodec.createEncoderByType(mMimeType)
        }
    }

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
            val codecProfile = when (mVideoCodec) {
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

            val format = MediaFormat.createVideoFormat(mMimeType, width, height)

            // Set some properties.  Failing to specify some of these can cause the MediaCodec
            // configure() call to throw an unhelpful exception.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)

            if (codecProfile != -1) {
                format.setInteger(MediaFormat.KEY_PROFILE, codecProfile)
                format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, getTransferFunction())
                format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing, true)
            }

            if (VERBOSE) Log.d(TAG, "format: " + format)

            // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
            // we can use for input and wrap it with a class that handles the EGL work.
            mEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
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
            mEncoder!!.start()
            
            // ✅ 오디오 인코더 시작
            mAudioEncoder?.start()

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
                                private val audioRecord: AudioRecord?
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
                try {
                    audioRecord.startRecording()
                    Log.d(TAG, "🎵 AudioRecord 녹음 시작")
                    
                    while (mAudioRecording) {
                        val readSize = audioRecord.read(buffer, 0, buffer.size)
                        if (readSize > 0) {
                            val inputBufferIndex = audioEncoder.dequeueInputBuffer(10000)
                            if (inputBufferIndex >= 0) {
                                val inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex)
                                inputBuffer?.clear()
                                inputBuffer?.put(buffer, 0, readSize)
                                
                                // ✅ 샘플 수 기반 타임스탬프 계산 (16-bit 모노 = 2 bytes per sample)
                                val samplesRead = readSize / 2
                                val presentationTimeUs = (mAudioSampleCount * 1_000_000L) / AUDIO_SAMPLE_RATE
                                mAudioSampleCount += samplesRead
                                
                                audioEncoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    readSize,
                                    presentationTimeUs,
                                    0
                                )
                            }
                        } else if (readSize == AudioRecord.ERROR_INVALID_OPERATION) {
                            Log.e(TAG, "❌ AudioRecord 읽기 오류: ERROR_INVALID_OPERATION")
                            break
                        } else if (readSize == AudioRecord.ERROR_BAD_VALUE) {
                            Log.e(TAG, "❌ AudioRecord 읽기 오류: ERROR_BAD_VALUE")
                            break
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
            
            var sawEOS = false
            
            while (!sawEOS || mAudioTrack != -1) {
                val outputBufferIndex = audioEncoder.dequeueOutputBuffer(mAudioBufferInfo, 10000)
                
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!mAudioRecording) {
                            break
                        }
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        mAudioFormat = audioEncoder.outputFormat
                        Log.d(TAG, "🎵 오디오 포맷 변경: $mAudioFormat")
                        
                        // 비디오 트랙이 이미 추가되어 있으면 오디오 트랙도 추가
                        synchronized(mMuxer) {
                            if (mVideoTrack != -1 && mAudioTrack == -1 && mAudioFormat != null) {
                                mAudioTrack = mMuxer.addTrack(mAudioFormat!!)
                                Log.d(TAG, "🎵 오디오 트랙 추가됨 (비디오 트랙 이후)")
                            }
                        }
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = audioEncoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && mAudioBufferInfo.size > 0) {
                            outputBuffer.position(mAudioBufferInfo.offset)
                            outputBuffer.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size)
                            
                            // ✅ 오디오 타임스탬프는 이미 0부터 시작하므로 그대로 사용 (비디오와 동일하게 정규화됨)
                            val adjustedAudioTimestamp = mAudioBufferInfo.presentationTimeUs
                            
                            // 타임스탬프 조정된 BufferInfo 생성
                            val adjustedBufferInfo = MediaCodec.BufferInfo().apply {
                                set(
                                    mAudioBufferInfo.offset,
                                    mAudioBufferInfo.size,
                                    adjustedAudioTimestamp,
                                    mAudioBufferInfo.flags
                                )
                            }
                            
                            // 비디오 트랙이 준비되어 있고 오디오 트랙도 추가되었으면 기록
                            synchronized(mMuxer) {
                                if (mVideoTrack != -1 && mAudioTrack != -1) {
                                    mMuxer.writeSampleData(mAudioTrack, outputBuffer, adjustedBufferInfo)
                                }
                            }
                        }
                        audioEncoder.releaseOutputBuffer(outputBufferIndex, false)
                        
                        if ((mAudioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "🎵 오디오 End of Stream 도달")
                            sawEOS = true
                        }
                    }
                }
            }
            
            Log.d(TAG, "🎵 오디오 인코더 드레인 종료")
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
                    Log.d(TAG, "🎬 [$name] 출력 포맷 변경됨: $mEncodedFormat")
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
                                Log.d(TAG, "🎬 [$name] 첫 비디오 타임스탬프: $mFirstVideoTimestamp")
                                
                                // 오디오 트랙이 이미 준비되어 있으면 함께 추가
                                if (mAudioFormat != null && mAudioTrack == -1) {
                                    mAudioTrack = mMuxer.addTrack(mAudioFormat!!)
                                    Log.d(TAG, "🎵 오디오 트랙 추가됨 (비디오 트랙과 함께)")
                                }
                                
                                mMuxer.start()
                                Log.d(TAG, "🟢 [$name] MediaMuxer 시작됨 (비디오 트랙 추가 완료)")
                            }
                        }

                        synchronized(mMuxer) {
                            if (mVideoTrack != -1) {
                                // ✅ 비디오 타임스탬프를 0부터 시작하도록 정규화
                                val normalizedVideoTimestamp = if (mFirstVideoTimestamp >= 0) {
                                    mBufferInfo.presentationTimeUs - mFirstVideoTimestamp
                                } else {
                                    mBufferInfo.presentationTimeUs
                                }
                                
                                val normalizedVideoBufferInfo = MediaCodec.BufferInfo().apply {
                                    set(
                                        mBufferInfo.offset,
                                        mBufferInfo.size,
                                        normalizedVideoTimestamp,
                                        mBufferInfo.flags
                                    )
                                }
                                
                                mMuxer.writeSampleData(mVideoTrack, encodedData, normalizedVideoBufferInfo)
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
            
            // ✅ 오디오 녹음 중지
            stopAudioRecording()
            
            Looper.myLooper()!!.quit()
            mMuxer.stop()
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
