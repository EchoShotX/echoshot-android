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
            EncoderThread(mEncoder!!, outputFile, mOrientationHint,frameEncodedListener)
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
    private class EncoderThread(mediaCodec: MediaCodec,
                                outputFile: File,
                                orientationHint: Int,
                                private var listener: OnFrameEncodedListener?
                            ): Thread() {
        val mEncoder = mediaCodec
        var mEncodedFormat: MediaFormat? = null
        val mBufferInfo = MediaCodec.BufferInfo()
        val mMuxer = MediaMuxer(outputFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val mOrientationHint = orientationHint
        var mVideoTrack: Int = -1

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
            synchronized (mLock) {
                mReady = true
                mLock.notify()    // signal waitUntilReady()
            }

            Looper.loop()

            synchronized (mLock) {
                mReady = false
                mHandler = null
            }
            Log.d(TAG, "looper quit")
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

                        if (mVideoTrack == -1) {
                            mVideoTrack = mMuxer.addTrack(mEncodedFormat!!)
                            mMuxer.setOrientationHint(mOrientationHint)
                            mMuxer.start()
                            Log.d(TAG, "🟢 [$name] MediaMuxer 시작됨 (트랙 추가 완료)")
                        }

                        mMuxer.writeSampleData(mVideoTrack, encodedData, mBufferInfo)

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
