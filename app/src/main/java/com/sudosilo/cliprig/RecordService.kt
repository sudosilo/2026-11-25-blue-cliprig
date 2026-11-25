package com.sudosilo.cliprig

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.util.UUID

class RecordService : Service() {

    companion object {
        const val CHANNEL = "cliprig_recording"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.sudosilo.cliprig.STOP"

        @Volatile var instance: RecordService? = null
        var onFinished: ((Session?) -> Unit)? = null

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, RecordService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }
    }

    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var sessionId = ""
    private var outFile: File? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopRecording(); return START_NOT_STICKY }
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("cliprig is recording")
            .setContentText("Tap stop when the run is over")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setColor(0xFF1E6BFF.toInt())
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(7, n)

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) { stopSelf(); return START_NOT_STICKY }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        }, Handler(Looper.getMainLooper()))

        val (w, h, dpi) = captureSize()
        sessionId = UUID.randomUUID().toString().take(12)
        val f = File(Store.rawDir, "2026-11-25-blue-cliprig-v1-raw-$sessionId.mp4")
        outFile = f
        SessionLog.baseNs = System.nanoTime()
        val r = ScreenRecorder(proj, w, h, dpi, f)
        recorder = r
        runCatching { r.start() }.onFailure {
            recorder = null
            stopRecording()
            return START_NOT_STICKY
        }
        SessionLog.active = true
        instance = this
        return START_NOT_STICKY
    }

    private fun captureSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val dm: DisplayMetrics = resources.displayMetrics
        var w: Int
        var h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val real = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(real)
            w = real.widthPixels; h = real.heightPixels
        }
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
        var scale = if (minOf(w, h) > 1080) 1080.0 / minOf(w, h) else 1.0
        while (true) {
            val sw = ((w * scale).toInt() / 16) * 16
            val sh = ((h * scale).toInt() / 16) * 16
            val ok = caps.isEmpty() || caps.any { it.isSizeSupported(sw, sh) }
            if (ok || scale < 0.3) { w = sw; h = sh; break }
            scale *= 0.85
        }
        return Triple(w, h, dm.densityDpi)
    }

    fun stopRecording() {
        if (stopping) return
        stopping = true
        SessionLog.active = false
        val r = recorder
        Thread {
            r?.stop()
            runCatching { projection?.stop() }
            val f = outFile
            var session: Session? = null
            if (r != null && f != null && f.exists() && f.length() > 0) {
                val offsetMs = if (r.firstPtsUs > 0) r.firstPtsUs / 1000 - SessionLog.baseNs / 1_000_000L else 0L
                fun adj(v: Long) = maxOf(0L, v - offsetMs)
                synchronized(SessionLog) {
                    session = Session(
                        sessionId, f.absolutePath, SessionLog.gameId, SessionLog.gameName,
                        SessionLog.url, System.currentTimeMillis(), SessionLog.clipLenSec,
                        SessionLog.taps.map(::adj), SessionLog.marks.map(::adj),
                        SessionLog.events.map { GameEvent(adj(it.ms), it.type) }
                    )
                }
                session?.let { Store.saveSession(it) }
            }
            Handler(Looper.getMainLooper()).post {
                instance = null
                onFinished?.invoke(session)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
