package com.sudosilo.cliprig

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class PlayActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        var current: Activity? = null
    }

    private lateinit var web: WebView
    private lateinit var badge: TextView
    private var stopped = false

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "cliprig")
        web.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) SessionLog.tap()
            false
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        val dp = resources.displayMetrics.density
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 28 * dp
                setColor(0xCC0F1A33.toInt())
                setStroke((2 * dp).toInt(), 0xFF1E6BFF.toInt())
            }
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            alpha = 0.85f
        }
        fun circle(label: String, color: Int): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }
        val markBtn = circle("★", 0xFF1E6BFF.toInt())
        val stopBtn = circle("■", 0xFFE53935.toInt())
        badge = TextView(this).apply {
            text = "0"
            setTextColor(0xFFBFD4FF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = (26 * dp).toInt()
        }
        val size = (44 * dp).toInt()
        pill.addView(markBtn, LinearLayout.LayoutParams(size, size))
        pill.addView(badge, LinearLayout.LayoutParams(-2, -2))
        pill.addView(stopBtn, LinearLayout.LayoutParams(size, size))
        val plp = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (40 * dp).toInt(); rightMargin = (12 * dp).toInt()
        }
        root.addView(pill, plp)
        setContentView(root)

        makeDraggable(pill, markBtn) { SessionLog.mark() }
        makeDraggable(pill, stopBtn) { finishRun() }
        makeDraggable(pill, badge) {}

        SessionLog.listener = { runOnUiThread { badge.text = SessionLog.highlightCount().toString() } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finishRun()
            }
        })

        web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: "about:blank")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(pill: View, handle: View, onTap: () -> Unit) {
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = pill.translationX; startY = pill.translationY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || dx * dx + dy * dy > 400) {
                        moved = true
                        pill.translationX = startX + dx; pill.translationY = startY + dy
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
    }

    private fun finishRun() {
        if (stopped) return
        stopped = true
        web.onPause()
        web.loadUrl("about:blank")
        RecordService.instance?.stopRecording() ?: finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) SessionLog.mark()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        SessionLog.listener = null
        if (current === this) current = null
        runCatching { web.destroy() }
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface fun mark(type: String?) { SessionLog.event(type ?: "event") }
        @JavascriptInterface fun mark() { SessionLog.event("event") }
    }
}
