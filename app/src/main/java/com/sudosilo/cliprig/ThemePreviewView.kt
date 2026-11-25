package com.sudosilo.cliprig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ThemePreviewView(ctx: Context) : View(ctx) {
    var theme: Theme = PlainTheme
        set(v) { field = v; start = System.currentTimeMillis(); invalidate() }
    var title = "Your Game"
    var caption = "this run was unreal"
    var endText = "play it at\nyourgame.site"
    var intro = true
    var rapid = true
    var tagline = ""
    var popups: List<String> = emptyList()

    private var start = System.currentTimeMillis()
    private val layer = Bitmap.createBitmap((W / 2).toInt(), (H / 2).toInt(), Bitmap.Config.ARGB_8888)
    private val layerCanvas = Canvas(layer)
    private val fake = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun totalMs(): Long {
        val i = if (intro) theme.introMs else 0L
        return i + 15000L
    }

    private fun frameAt(t: Long): Frame {
        val i = if (intro) theme.introMs else 0L
        val body = (t - i).coerceAtLeast(0)
        val cut = if (t < i) -1 else (body / 3000).toInt().coerceAtMost(4)
        val cutT = if (t < i) t else body - cut * 3000L
        val hype = if (cut >= 0 && rapid) theme.hype[cut % theme.hype.size] else ""
        val cutVal = if (t < i) -1 else if (rapid) cut else 0
        val cutTime = if (rapid) cutT else (t - i).coerceAtLeast(0)
        val sched = ThemeBook.popupSchedule(popups, i, totalMs(), true)
        val p = sched.firstOrNull { t >= it.startMs && t < it.startMs + it.durMs }
        return Frame(t, totalMs(), i, cutVal, cutTime, rapid, caption, hype, title, endText, true, Store.captionStyle, 1f,
            tagline, p?.text ?: "", if (p != null) t - p.startMs else 0L, p?.durMs ?: 0L)
    }

    private fun drawFakeVideo(c: Canvas, r: RectF, t: Long, cut: Int) {
        if (r.width() < 1 || r.height() < 1) return
        c.save(); c.clipRect(r)
        val hues = listOf(0xFF1E6BFF, 0xFF22C55E, 0xFFF97316, 0xFFA855F7, 0xFFEF4444).map { it.toInt() }
        val base = hues[(maxOf(cut, 0)) % hues.size]
        fake.shader = LinearGradient(r.left, r.top, r.right, r.bottom, base, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(r, fake)
        fake.shader = null
        fake.color = 0x55FFFFFF
        val s = r.width() / 1080f
        for (k in 0 until 6) {
            val y = r.top + ((t / 4 + k * 260) % r.height().toLong()).toFloat()
            c.drawRect(r.left, y, r.right, y + 30 * s, fake)
        }
        fake.color = Color.WHITE
        val px = r.centerX() + cos(t / 300.0).toFloat() * r.width() * 0.3f
        val py = r.centerY() + sin(t / 220.0).toFloat() * r.height() * 0.25f
        c.drawCircle(px, py, 70 * s, fake)
        fake.color = 0xFFFFD400.toInt()
        c.drawRect(px - 200 * s, py + 120 * s, px + 200 * s, py + 150 * s, fake)
        c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (System.currentTimeMillis() - start) % (totalMs() + 800)
        val f = frameAt(t)
        layer.eraseColor(0)
        layerCanvas.save(); layerCanvas.scale(0.5f, 0.5f)
        theme.draw(layerCanvas, f)
        if (f.popup.isNotBlank() && !f.inIntro && !f.inEnd) theme.drawPopup(layerCanvas, f)
        layerCanvas.restore()

        val scale = min(width / (W / 2), height / (H / 2))
        val dx = (width - W / 2 * scale) / 2
        val dy = (height - H / 2 * scale) / 2
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawRect(0f, 0f, W / 2, H / 2, Paint().apply { color = Color.BLACK })
        val vr = theme.videoRect(f)
        drawFakeVideo(canvas, RectF(vr.left / 2, vr.top / 2, vr.right / 2, vr.bottom / 2), t, f.cut)
        canvas.drawBitmap(layer, 0f, 0f, null)
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
