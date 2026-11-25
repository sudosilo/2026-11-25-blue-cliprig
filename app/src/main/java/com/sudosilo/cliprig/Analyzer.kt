package com.sudosilo.cliprig

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ClipWindow(val startMs: Long, val endMs: Long, val score: Float)

object Analyzer {
    const val BIN_MS = 500L

    fun durationMs(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { runCatching { r.release() } }
    }

    fun audioLevels(ctx: Context, uri: Uri, bins: Int): FloatArray {
        val sums = DoubleArray(bins)
        val counts = LongArray(bins)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return FloatArray(bins)
            ex.selectTrack(track)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, null, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            while (true) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else if (o >= 0) {
                    val b = dec.getOutputBuffer(o)
                    if (b != null && info.size > 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val sb = b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = sb.remaining() / max(1, channels)
                        var idx = 0
                        for (f in 0 until frames) {
                            var acc = 0.0
                            for (c in 0 until channels) { val s = sb.get(idx++) / 32768.0; acc += s * s }
                            val tMs = info.presentationTimeUs / 1000 + f * 1000L / max(1, rate)
                            val bin = (tMs / BIN_MS).toInt()
                            if (bin in 0 until bins) { sums[bin] += acc / channels; counts[bin]++ }
                        }
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            runCatching { dec.stop(); dec.release() }
        } catch (e: Exception) {
        } finally { runCatching { ex.release() } }
        return FloatArray(bins) { if (counts[it] > 0) sqrt(sums[it] / counts[it]).toFloat() else 0f }
    }

    fun visualChange(ctx: Context, uri: Uri, bins: Int, progress: (Float) -> Unit): FloatArray {
        val out = FloatArray(bins)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            var prev: IntArray? = null
            for (b in 0 until bins) {
                val t = (b * BIN_MS + BIN_MS / 2) * 1000
                val bmp: Bitmap? = runCatching {
                    r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, 36, 64)
                }.getOrNull()
                if (bmp != null) {
                    val w = bmp.width; val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    val gray = IntArray(px.size) {
                        val c = px[it]
                        ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10
                    }
                    val p = prev
                    if (p != null && p.size == gray.size) {
                        var d = 0L
                        for (i in gray.indices) d += abs(gray[i] - p[i])
                        out[b] = d.toFloat() / gray.size
                    }
                    prev = gray
                }
                if (b % 8 == 0) progress(b.toFloat() / bins)
            }
        } catch (e: Exception) {
        } finally { runCatching { r.release() } }
        return out
    }

    private fun normalize(a: FloatArray): FloatArray {
        if (a.isEmpty()) return a
        val mean = a.average().toFloat()
        var v = 0.0
        a.forEach { v += (it - mean) * (it - mean) }
        val sd = sqrt(v / a.size).toFloat()
        if (sd < 1e-6f) return FloatArray(a.size)
        return FloatArray(a.size) { max(0f, (a[it] - mean) / sd) }
    }

    fun pick(
        durationMs: Long,
        audio: FloatArray,
        visual: FloatArray,
        session: Session?,
        clipLenSec: Int,
        count: Int
    ): List<ClipWindow> {
        val clipMs = clipLenSec * 1000L
        if (durationMs <= clipMs + 1000) return listOf(ClipWindow(0, durationMs, 1f))
        val bins = audio.size
        val taps = FloatArray(bins)
        val bonus = FloatArray(bins)
        session?.taps?.forEach { val b = (it / BIN_MS).toInt(); if (b in 0 until bins) taps[b] += 1f }
        fun spread(ms: Long, w: Float) {
            val b = (ms / BIN_MS).toInt()
            for (k in -4..1) { val i = b + k; if (i in 0 until bins) bonus[i] += w * (if (k == 0) 1f else 0.6f) }
        }
        session?.events?.forEach { spread(it.ms, 3f) }
        session?.marks?.forEach { spread(it, 6f) }

        val na = normalize(audio); val nv = normalize(visual); val nt = normalize(taps)
        val raw = FloatArray(bins) { na[it] + nv[it] + 0.7f * nt[it] + bonus[it] }
        val score = FloatArray(bins) { i ->
            var s = 0f; var n = 0
            for (k in -2..2) { val j = i + k; if (j in 0 until bins) { s += raw[j]; n++ } }
            s / n
        }
        val skipStart = min(3000L, durationMs / 10)
        val lead = (clipMs * 0.65).toLong()
        val used = mutableListOf<LongRange>()
        val order = score.indices.sortedByDescending { score[it] }
        val result = mutableListOf<ClipWindow>()
        for (b in order) {
            if (result.size >= count) break
            val peak = b * BIN_MS + BIN_MS / 2
            if (peak < skipStart) continue
            var s = peak - lead
            var e = s + clipMs
            if (s < 0) { s = 0; e = clipMs }
            if (e > durationMs) { e = durationMs; s = max(0, e - clipMs) }
            val r = s..e
            if (used.any { it.first < r.last && r.first < it.last }) continue
            used.add(r)
            result.add(ClipWindow(s, e, score[b]))
        }
        return result.sortedBy { it.startMs }
    }
}
