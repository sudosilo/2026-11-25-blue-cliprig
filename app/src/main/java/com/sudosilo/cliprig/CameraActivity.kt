package com.sudosilo.cliprig

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.UUID

@OptIn(UnstableApi::class, ExperimentalPersistentRecording::class)
class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var badge: TextView
    private lateinit var status: TextView
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var back = true
    private var stopping = false
    private var sessionId = ""
    private var outFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val dp = resources.displayMetrics.density
        status = TextView(this).apply {
            text = "● REC"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 20 * dp; setColor(0xCCE53935.toInt()) }
            visibility = View.INVISIBLE
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.START; topMargin = (44 * dp).toInt(); leftMargin = (14 * dp).toInt()
        })

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 28 * dp; setColor(0xCC0F1A33.toInt()); setStroke((2 * dp).toInt(), 0xFF1E6BFF.toInt())
            }
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        }
        fun circle(label: String, color: Int) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }
        val size = (44 * dp).toInt()
        val flip = circle("↻", 0xFF5B6478.toInt())
        val mark = circle("★", 0xFF1E6BFF.toInt())
        val stop = circle("■", 0xFFE53935.toInt())
        badge = TextView(this).apply { text = "0"; setTextColor(0xFFBFD4FF.toInt()); textSize = 13f; gravity = Gravity.CENTER; minWidth = (26 * dp).toInt() }
        pill.addView(flip, LinearLayout.LayoutParams(size, size).apply { rightMargin = (6 * dp).toInt() })
        pill.addView(mark, LinearLayout.LayoutParams(size, size))
        pill.addView(badge, LinearLayout.LayoutParams(-2, -2))
        pill.addView(stop, LinearLayout.LayoutParams(size, size))
        root.addView(pill, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END; topMargin = (40 * dp).toInt(); rightMargin = (12 * dp).toInt()
        })
        setContentView(root)

        tapOrDrag(pill, flip) { back = !back; bind() }
        tapOrDrag(pill, mark) { SessionLog.mark() }
        tapOrDrag(pill, stop) { finishRun() }
        tapOrDrag(pill, badge) {}
        SessionLog.listener = { runOnUiThread { badge.text = SessionLog.highlightCount().toString() } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishRun()
        })

        ProcessCameraProvider.getInstance(this).addListener({
            provider = ProcessCameraProvider.getInstance(this).get()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            bind()
            startRecording()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val vc = videoCapture ?: return
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val selector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        runCatching {
            p.unbindAll()
            p.bindToLifecycle(this, selector, preview, vc)
        }.onFailure { Toast.makeText(this, "That camera is not available", Toast.LENGTH_SHORT).show() }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        sessionId = UUID.randomUUID().toString().take(12)
        val f = File(Store.rawDir, "2026-11-25-blue-cliprig-v1-camera-$sessionId.mp4")
        outFile = f
        var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(f).build()).asPersistentRecording()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { e ->
            when (e) {
                is VideoRecordEvent.Start -> {
                    SessionLog.baseNs = System.nanoTime()
                    SessionLog.active = true
                    status.visibility = View.VISIBLE
                }
                is VideoRecordEvent.Finalize -> onFinalized()
            }
        }
    }

    private fun finishRun() {
        if (stopping) return
        stopping = true
        status.text = "Saving"
        val r = recording
        if (r == null) { finish(); return }
        r.stop()
    }

    private fun onFinalized() {
        SessionLog.active = false
        val f = outFile
        if (f != null && f.exists() && f.length() > 0) {
            val session = synchronized(SessionLog) {
                Session(
                    sessionId, f.absolutePath, SessionLog.gameId, SessionLog.gameName, "camera",
                    System.currentTimeMillis(), SessionLog.clipLenSec,
                    emptyList(), SessionLog.marks.toList(), SessionLog.events.toList()
                )
            }
            Store.saveSession(session)
            Pipeline.fromSession(this, session)
        }
        startActivity(Intent(this, MainActivity::class.java).putExtra("screen", "library")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun tapOrDrag(pill: View, handle: View, onTap: () -> Unit) {
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = pill.translationX; startY = pill.translationY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || dx * dx + dy * dy > 400) { moved = true; pill.translationX = startX + dx; pill.translationY = startY + dy }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) SessionLog.mark()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !stopping && !isFinishing) finishRun()
    }

    override fun onDestroy() {
        SessionLog.listener = null
        super.onDestroy()
    }
}
