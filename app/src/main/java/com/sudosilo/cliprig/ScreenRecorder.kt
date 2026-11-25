package com.sudosilo.cliprig

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface
import java.io.File

class ScreenRecorder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val out: File
) {
    private val sampleRate = 44100
    private val channels = 2

    private lateinit var videoCodec: MediaCodec
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private lateinit var inputSurface: Surface
    private var display: VirtualDisplay? = null
    private val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var expectAudio = false
    @Volatile private var running = false
    private var sawVideoKey = false
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L

    var firstPtsUs = -1L
        private set

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 30)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoCodec.createInputSurface()

        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                .setAudioPlaybackCaptureConfig(capture)
                .build()
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }

        if (audioRecord != null) {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            expectAudio = true
        }

        running = true
        videoCodec.start()
        audioCodec?.start()

        display = projection.createVirtualDisplay(
            "cliprig", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null
        )

        videoThread = Thread({ drainVideo() }, "cliprig-video").also { it.start() }
        if (audioRecord != null) {
            audioThread = Thread({ runAudio() }, "cliprig-audio").also { it.start() }
        }
    }

    private fun maybeStartMuxer() {
        if (muxerStarted) return
        if (videoTrack < 0) return
        if (expectAudio && audioTrack < 0) return
        muxer.start()
        muxerStarted = true
        runCatching {
            videoCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
        lock.notifyAll()
    }

    private fun write(track: Int, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo, isVideo: Boolean) {
        synchronized(lock) {
            if (!muxerStarted) return
            if (isVideo) {
                if (!sawVideoKey) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
                    sawVideoKey = true
                }
                if (info.presentationTimeUs <= lastVideoPts) return
                lastVideoPts = info.presentationTimeUs
            } else {
                if (!sawVideoKey) return
                if (info.presentationTimeUs <= lastAudioPts) return
                lastAudioPts = info.presentationTimeUs
            }
            if (firstPtsUs < 0 || info.presentationTimeUs < firstPtsUs) firstPtsUs = info.presentationTimeUs
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer.writeSampleData(track, buf, info)
        }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = videoCodec.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    videoTrack = muxer.addTrack(videoCodec.outputFormat)
                    maybeStartMuxer()
                }
                idx >= 0 -> {
                    val buf = videoCodec.getOutputBuffer(idx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !config) write(videoTrack, buf, info, true)
                    videoCodec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun runAudio() {
        val rec = audioRecord ?: return
        val codec = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(4096)
        var framesWritten = 0L
        var startUs = -1L
        rec.startRecording()
        var eosSent = false
        while (true) {
            if (!eosSent) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    if (!running) {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsFor(startUs, framesWritten), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    } else {
                        val n = rec.read(pcm, 0, pcm.size)
                        if (n > 0) {
                            if (startUs < 0) {
                                val bufferedUs = n.toLong() / (2 * channels) * 1_000_000L / sampleRate
                                startUs = System.nanoTime() / 1000 - bufferedUs
                            }
                            val ib = codec.getInputBuffer(inIdx)!!
                            ib.clear(); ib.put(pcm, 0, n)
                            codec.queueInputBuffer(inIdx, 0, n, ptsFor(startUs, framesWritten), 0)
                            framesWritten += n / (2 * channels)
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsFor(startUs, framesWritten), 0)
                        }
                    }
                }
            }
            var outIdx = codec.dequeueOutputBuffer(info, 0)
            while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(lock) {
                        audioTrack = muxer.addTrack(codec.outputFormat)
                        maybeStartMuxer()
                    }
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !config) write(audioTrack, buf, info, false)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        runCatching { rec.stop() }
                        return
                    }
                }
                outIdx = codec.dequeueOutputBuffer(info, 0)
            }
        }
    }

    private fun ptsFor(startUs: Long, frames: Long): Long {
        val s = if (startUs < 0) System.nanoTime() / 1000 else startUs
        return s + frames * 1_000_000L / sampleRate
    }

    fun stop() {
        running = false
        runCatching { display?.release() }
        runCatching { videoCodec.signalEndOfInputStream() }
        runCatching { videoThread?.join(4000) }
        runCatching { audioThread?.join(4000) }
        runCatching { videoCodec.stop(); videoCodec.release() }
        runCatching { audioCodec?.stop(); audioCodec?.release() }
        runCatching { audioRecord?.release() }
        synchronized(lock) {
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
        runCatching { inputSurface.release() }
    }
}
