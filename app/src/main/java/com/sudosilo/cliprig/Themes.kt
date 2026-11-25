package com.sudosilo.cliprig

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

const val W = 1080f
const val H = 1920f

data class Frame(
    val t: Long,
    val total: Long,
    val intro: Long,
    val cut: Int,
    val cutT: Long,
    val rapid: Boolean,
    val caption: String,
    val hype: String,
    val title: String,
    val endText: String,
    val cards: Boolean,
    val captionStyle: String,
    val speed: Float = 1f
) {
    val endStart: Long get() = if (cards && endText.isNotBlank()) total - 2200 else Long.MAX_VALUE
    val inEnd: Boolean get() = t >= endStart
    val inIntro: Boolean get() = t < intro
    val showCaption: Boolean get() = captionStyle != "Off" && caption.isNotBlank() && !inIntro && !inEnd
    val burst: Boolean get() = cut >= 0 && hype.isNotBlank() && cutT < 650 && !inIntro && !inEnd
}

object Fx {
    val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    val black: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val plain: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val casual: Typeface = Typeface.create("casual", Typeface.BOLD)
    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    fun clamp01(v: Float) = max(0f, min(1f, v))
    fun prog(t: Long, a: Long, b: Long) = clamp01((t - a).toFloat() / max(1L, b - a))
    fun easeOut(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)
    fun easeInOut(p: Float) = if (p < 0.5f) 4 * p * p * p else 1 - (-2 * p + 2).let { it * it * it } / 2
    fun easeBack(p: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; return 1 + c3 * (p - 1) * (p - 1) * (p - 1) + c1 * (p - 1) * (p - 1) }
    fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
    fun lerpRect(a: RectF, b: RectF, p: Float) = RectF(lerp(a.left, b.left, p), lerp(a.top, b.top, p), lerp(a.right, b.right, p), lerp(a.bottom, b.bottom, p))
    fun scaleRect(r: RectF, sx: Float, sy: Float): RectF {
        val cx = r.centerX(); val cy = r.centerY()
        return RectF(cx - r.width() * sx / 2, cy - r.height() * sy / 2, cx + r.width() * sx / 2, cy + r.height() * sy / 2)
    }

    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface,
             align: Paint.Align = Paint.Align.CENTER, outline: Int = 0, outlineW: Float = 0f, shadow: Int = 0) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = tf; textAlign = align }
        if (outline != 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = outlineW; p.color = outline; p.strokeJoin = Paint.Join.ROUND
            c.drawText(s, x, y, p)
        }
        p.style = Paint.Style.FILL; p.color = color
        if (shadow != 0) p.setShadowLayer(size * 0.25f, 0f, 0f, shadow)
        c.drawText(s, x, y, p)
    }

    fun fitSize(s: String, maxW: Float, start: Float, tf: Typeface): Float {
        val p = Paint().apply { typeface = tf }
        var size = start
        while (size > 24f) { p.textSize = size; if (p.measureText(s) <= maxW) break; size -= 4f }
        return size
    }

    fun layout(s: String, width: Int, size: Float, color: Int, tf: Typeface,
               align: Layout.Alignment = Layout.Alignment.ALIGN_CENTER): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = tf }
        return StaticLayout.Builder.obtain(s, 0, s.length, tp, max(10, width)).setAlignment(align).setLineSpacing(0f, 1.04f).build()
    }

    fun drawLayout(c: Canvas, l: StaticLayout, x: Float, y: Float) { c.save(); c.translate(x, y); l.draw(c); c.restore() }

    fun starburst(c: Canvas, cx: Float, cy: Float, ro: Float, ri: Float, n: Int, rot: Float, fillC: Int, strokeC: Int, sw: Float) {
        val p = Path()
        for (i in 0 until n * 2) {
            val r = if (i % 2 == 0) ro else ri
            val a = rot + i * PI.toFloat() / n
            val x = cx + cos(a) * r; val y = cy + sin(a) * r
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        c.drawPath(p, fill(fillC))
        if (sw > 0) c.drawPath(p, stroke(strokeC, sw))
    }

    fun blob(c: Canvas, cx: Float, cy: Float, r: Float, seed: Int, fillC: Int, strokeC: Int, sw: Float) {
        val rnd = Random(seed)
        val n = 14
        val pts = (0 until n).map { i ->
            val a = i * 2 * PI.toFloat() / n
            val rr = r * (0.75f + rnd.nextFloat() * 0.45f)
            floatArrayOf(cx + cos(a) * rr, cy + sin(a) * rr)
        }
        val p = Path()
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % n]
            val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
            if (i == 0) p.moveTo(mx, my) else p.quadTo(a[0], a[1], mx, my)
        }
        val a0 = pts[0]; val b0 = pts[1]
        p.quadTo(a0[0], a0[1], (a0[0] + b0[0]) / 2, (a0[1] + b0[1]) / 2)
        p.close()
        c.drawPath(p, fill(fillC))
        if (sw > 0) c.drawPath(p, stroke(strokeC, sw))
    }

    fun wobblyRect(c: Canvas, r: RectF, seed: Int, sw: Float, color: Int, amp: Float = 7f) {
        val rnd = Random(seed)
        val p = Path()
        val step = 60f
        fun j() = (rnd.nextFloat() - 0.5f) * amp * 2
        p.moveTo(r.left + j(), r.top + j())
        var x = r.left; while (x < r.right) { x = min(r.right, x + step); p.lineTo(x + j(), r.top + j()) }
        var y = r.top; while (y < r.bottom) { y = min(r.bottom, y + step); p.lineTo(r.right + j(), y + j()) }
        x = r.right; while (x > r.left) { x = max(r.left, x - step); p.lineTo(x + j(), r.bottom + j()) }
        y = r.bottom; while (y > r.top) { y = max(r.top, y - step); p.lineTo(r.left + j(), y + j()) }
        p.close()
        c.drawPath(p, stroke(color, sw))
    }
}

abstract class Theme(val id: String, val label: String, val blurb: String) {
    open val introMs: Long = 0
    abstract val screen: RectF
    open val screenRadius: Float = 0f
    open val hype: List<String> = listOf("WOW", "NICE", "YES", "CLUTCH", "NO WAY")

    private var baseCache: Bitmap? = null
    protected open fun drawBase(c: Canvas) {}
    protected fun base(c: Canvas) {
        val b = baseCache ?: Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888).also {
            drawBase(Canvas(it)); baseCache = it
        }
        c.drawBitmap(b, 0f, 0f, null)
    }

    open fun videoRect(f: Frame): RectF = if (f.t < f.intro) RectF() else RectF(screen)

    protected fun hole(c: Canvas, r: RectF, radius: Float = screenRadius) {
        if (r.width() < 1 || r.height() < 1) return
        if (radius > 0) c.drawRoundRect(r, radius, radius, Fx.clear) else c.drawRect(r, Fx.clear)
    }

    abstract fun draw(c: Canvas, f: Frame)

    fun speedBadge(c: Canvas, f: Frame) {
        val label = "\u25B6\u25B6 " + (if (f.speed % 1f == 0f) "${f.speed.toInt()}x" else "${"%.1f".format(f.speed)}x")
        val vr = videoRect(f).takeIf { it.width() > 10 } ?: screen
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 46f; typeface = Fx.black }
        val w = p.measureText(label) + 44
        val r = RectF(vr.right - w - 24, vr.top + 70, vr.right - 24, vr.top + 140)
        c.drawRoundRect(r, 35f, 35f, Fx.fill(0xE61E6BFF.toInt()))
        Fx.text(c, label, r.centerX(), r.centerY() + 16, 46f, Color.WHITE, Fx.black)
    }

    open fun key(f: Frame): String {
        val animating = f.inIntro || f.burst || (f.inEnd && f.t - f.endStart < 900) || (f.cutT < 400)
        return if (animating) "a${f.t / 33}" else "s${f.cut}|${f.showCaption}|${f.inEnd}"
    }
}

object PlainTheme : Theme("plain", "Clean", "Just the gameplay with a caption, title card and end card") {
    override val screen = RectF(0f, 0f, W, H)
    override fun videoRect(f: Frame) = RectF(screen)

    private fun box(c: Canvas, s: String, size: Float, bg: Int, cy: Float) {
        val l = Fx.layout(s, 900, size, Color.WHITE, Fx.bold)
        var wMax = 0f; for (i in 0 until l.lineCount) wMax = max(wMax, l.getLineWidth(i))
        val pad = size * 0.45f
        val r = RectF(W / 2 - wMax / 2 - pad, cy - l.height / 2f - pad, W / 2 + wMax / 2 + pad, cy + l.height / 2f + pad)
        c.drawRoundRect(r, size * 0.4f, size * 0.4f, Fx.fill(bg))
        Fx.drawLayout(c, l, W / 2 - 450f, cy - l.height / 2f)
    }

    override fun draw(c: Canvas, f: Frame) {
        when {
            f.cards && f.t < 1600 && f.title.isNotBlank() -> box(c, f.title, 92f, 0xF01E6BFF.toInt(), H / 2)
            f.inEnd -> box(c, f.endText, 62f, 0xF00F1A33.toInt(), H / 2)
            f.showCaption -> box(c, f.caption, 64f, 0xF01E6BFF.toInt(), if (f.captionStyle == "Bottom") H * 0.81f else H * 0.19f)
        }
        if (f.rapid && f.cut > 0 && f.cutT < 220) {
            c.drawRect(0f, 0f, W, H, Fx.fill(Color.argb((200 * (1 - f.cutT / 220f)).toInt(), 255, 255, 255)))
        }
        if (f.burst && f.rapid) {
            val p = Fx.easeBack(Fx.prog(f.cutT, 0, 260))
            c.save(); c.scale(p, p, W / 2, H * 0.5f)
            Fx.text(c, f.hype, W / 2, H * 0.52f, 120f, Color.WHITE, Fx.black, outline = 0xFF1E6BFF.toInt(), outlineW = 22f)
            c.restore()
        }
    }
}

object ComicTheme : Theme("comic", "Comic Book", "Halftone pages, ink panels, caption boxes and POW bursts") {
    override val introMs = 2300L
    override val screen = RectF(70f, 170f, 1010f, 170f + 940f * 16 / 9)
    override val hype = listOf("POW!", "BAM!", "ZAP!", "WHAM!", "KA-BOOM!", "THWACK!", "BLAMMO!")
    private val yellow = 0xFFFFD400.toInt()
    private val red = 0xFFE4202A.toInt()
    private val ink = 0xFF111111.toInt()

    override fun drawBase(c: Canvas) {
        c.drawColor(yellow)
        val dot = Fx.fill(0x55E4202A)
        var row = 0
        var y = 0f
        while (y < H + 30) {
            var x = if (row % 2 == 0) 0f else 14f
            while (x < W + 30) { c.drawCircle(x, y, 7f, dot); x += 28f }
            y += 24f; row++
        }
        val sh = RectF(screen).apply { offset(18f, 18f); inset(-10f, -10f) }
        c.drawRect(sh, Fx.fill(ink))
        c.drawRect(RectF(screen).apply { inset(-12f, -12f) }, Fx.fill(Color.WHITE))
        c.drawRect(RectF(screen).apply { inset(-12f, -12f) }, Fx.stroke(ink, 14f))
    }

    override fun videoRect(f: Frame): RectF {
        if (f.t < 1250) return RectF()
        val p = Fx.easeBack(Fx.prog(f.t, 1250, 1800))
        return Fx.scaleRect(screen, p, p)
    }

    private fun captionBox(c: Canvas, s: String, x: Float, y: Float, maxW: Int) {
        val l = Fx.layout(s.uppercase(), maxW - 40, 46f, ink, Fx.black, Layout.Alignment.ALIGN_NORMAL)
        var wMax = 0f; for (i in 0 until l.lineCount) wMax = max(wMax, l.getLineWidth(i))
        val r = RectF(x, y, x + wMax + 44, y + l.height + 36)
        c.drawRect(RectF(r).apply { offset(8f, 8f) }, Fx.fill(ink))
        c.drawRect(r, Fx.fill(0xFFFFF3A0.toInt()))
        c.drawRect(r, Fx.stroke(ink, 7f))
        Fx.drawLayout(c, l, x + 22, y + 18)
    }

    private fun burstWord(c: Canvas, word: String, cx: Float, cy: Float, p: Float, seed: Int) {
        val rot = (seed % 7 - 3) * 0.05f
        c.save(); c.scale(p, p, cx, cy); c.rotate(rot * 57f, cx, cy)
        Fx.starburst(c, cx, cy, 250f, 150f, 14, seed * 0.3f, if (seed % 2 == 0) red else 0xFF1E6BFF.toInt(), ink, 10f)
        Fx.starburst(c, cx, cy, 200f, 125f, 14, seed * 0.3f + 0.2f, yellow, ink, 0f)
        val size = Fx.fitSize(word, 330f, 110f, Fx.black)
        Fx.text(c, word, cx, cy + size * 0.35f, size, red, Fx.black, outline = ink, outlineW = 16f)
        c.restore()
    }

    override fun draw(c: Canvas, f: Frame) {
        base(c)
        if (f.t < 1250) {
            c.drawRect(RectF(screen).apply { inset(-12f, -12f) }, Fx.fill(Color.WHITE))
            c.drawRect(RectF(screen).apply { inset(-12f, -12f) }, Fx.stroke(ink, 14f))
            val pop = Fx.easeBack(Fx.prog(f.t, 0, 450))
            c.save(); c.scale(pop, pop, W / 2, H * 0.45f); c.rotate(-6f, W / 2, H * 0.45f)
            Fx.text(c, "THE AMAZING", W / 2, H * 0.36f, 70f, ink, Fx.black)
            val size = Fx.fitSize(f.title.uppercase(), 860f, 170f, Fx.black)
            Fx.text(c, f.title.uppercase(), W / 2, H * 0.46f, size, red, Fx.black, outline = ink, outlineW = 22f)
            Fx.text(c, "ISSUE ONE", W / 2, H * 0.53f, 54f, 0xFF1E6BFF.toInt(), Fx.black, outline = ink, outlineW = 8f)
            c.restore()
        } else {
            val vr = videoRect(f)
            c.drawRect(RectF(screen).apply { inset(-12f, -12f) }, Fx.fill(yellow))
            if (f.t < 1800) {
                val dot = Fx.fill(0x55E4202A)
                var y = screen.top - 12; var row = 0
                while (y < screen.bottom + 12) { var x = screen.left - 12 + if (row % 2 == 0) 0f else 14f
                    while (x < screen.right + 12) { c.drawCircle(x, y, 7f, dot); x += 28f }; y += 24f; row++ }
            }
            c.drawRect(RectF(vr).apply { inset(-12f, -12f) }, Fx.fill(ink))
            hole(c, vr)
            if (f.t < 2300) burstWord(c, "KA-POW!", W * 0.7f, screen.top + 200, Fx.easeBack(Fx.prog(f.t, 1500, 1800)) * (1 - Fx.prog(f.t, 2100, 2300)), 3)
        }
        if (f.showCaption) captionBox(c, f.caption, 40f, 40f, 900)
        if (f.burst) {
            val seed = f.cut * 3 + 1
            val cx = if (f.cut % 2 == 0) W * 0.68f else W * 0.32f
            val cy = screen.top + 300 + (f.cut % 3) * 380
            burstWord(c, f.hype, cx, cy, Fx.easeBack(Fx.prog(f.cutT, 0, 240)) * (1 - Fx.prog(f.cutT, 520, 650)), seed)
        }
        if (f.inEnd) {
            val p = Fx.easeBack(Fx.prog(f.t, f.endStart, f.endStart + 350))
            c.save(); c.scale(p, p, W / 2, H * 0.72f)
            val l = Fx.layout(f.endText.uppercase(), 760, 56f, ink, Fx.black)
            val r = RectF(W / 2 - 420, H * 0.72f - l.height / 2f - 50, W / 2 + 420, H * 0.72f + l.height / 2f + 50)
            val tail = Path().apply { moveTo(r.left + 160, r.bottom - 10); lineTo(r.left + 90, r.bottom + 110); lineTo(r.left + 280, r.bottom - 10); close() }
            c.drawOval(RectF(r).apply { inset(-30f, -20f) }, Fx.fill(Color.WHITE))
            c.drawPath(tail, Fx.fill(Color.WHITE))
            c.drawOval(RectF(r).apply { inset(-30f, -20f) }, Fx.stroke(ink, 9f))
            c.drawPath(tail, Fx.stroke(ink, 9f))
            c.drawOval(RectF(r).apply { inset(-24f, -14f) }, Fx.fill(Color.WHITE))
            Fx.drawLayout(c, l, W / 2 - 380, H * 0.72f - l.height / 2f)
            c.restore()
            captionBox(c, "TO BE CONTINUED...", 540f, screen.bottom - 130, 520)
        }
        Fx.text(c, f.title.uppercase(), W / 2, H - 28, 40f, ink, Fx.black)
    }
}

object DesktopTheme : Theme("desktop", "Retro Desktop", "Teal desktop, chunky gray windows, pop up dialog boxes everywhere") {
    override val introMs = 2400L
    override val screen = RectF(84f, 134f, 84f + 912f, 134f + 912f * 16 / 9)
    override val hype = listOf(
        "Legendary move detected.", "Are you sure you want to be this good?", "Skill overflow. Click OK to continue.",
        "This program has performed an awesome operation.", "New high score saved to the hard drive.", "Warning: too much fun."
    )
    private val teal = 0xFF008080.toInt()
    private val face = 0xFFC0C0C0.toInt()
    private val dark = 0xFF808080.toInt()
    private val navy = 0xFF000080.toInt()
    private val win = RectF(66f, 66f, 1014f, screen.bottom + 18)
    private val iconY = listOf(170f, 390f, 610f, 830f)
    private val iconX = 150f

    private fun bevel(c: Canvas, r: RectF, raised: Boolean = true) {
        c.drawRect(r, Fx.fill(face))
        val lt = if (raised) Color.WHITE else dark
        val rb = if (raised) Color.BLACK else Color.WHITE
        c.drawRect(r.left, r.top, r.right, r.top + 4, Fx.fill(lt))
        c.drawRect(r.left, r.top, r.left + 4, r.bottom, Fx.fill(lt))
        c.drawRect(r.left, r.bottom - 4, r.right, r.bottom, Fx.fill(rb))
        c.drawRect(r.right - 4, r.top, r.right, r.bottom, Fx.fill(rb))
        if (raised) {
            c.drawRect(r.left + 4, r.bottom - 8, r.right - 4, r.bottom - 4, Fx.fill(dark))
            c.drawRect(r.right - 8, r.top + 4, r.right - 4, r.bottom - 4, Fx.fill(dark))
        }
    }

    private fun titleBar(c: Canvas, r: RectF, s: String, active: Boolean = true) {
        val p = Paint().apply {
            shader = LinearGradient(r.left, 0f, r.right, 0f,
                if (active) navy else dark, if (active) 0xFF1084D0.toInt() else 0xFFB5B5B5.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(r, p)
        val size = r.height() * 0.6f
        Fx.text(c, s, r.left + 14, r.centerY() + size * 0.36f, size, Color.WHITE, Fx.bold, Paint.Align.LEFT)
        val bw = r.height() - 8
        for (i in 0 until 3) {
            val br = RectF(r.right - 6 - (3 - i) * (bw + 4), r.top + 4, r.right - 6 - (3 - i) * (bw + 4) + bw, r.bottom - 4)
            bevel(c, br)
            val g = Fx.stroke(Color.BLACK, 4f)
            when (i) {
                0 -> c.drawLine(br.left + 10, br.bottom - 12, br.right - 10, br.bottom - 12, g)
                1 -> c.drawRect(br.left + 10, br.top + 10, br.right - 10, br.bottom - 10, g)
                2 -> { c.drawLine(br.left + 11, br.top + 11, br.right - 11, br.bottom - 11, g); c.drawLine(br.right - 11, br.top + 11, br.left + 11, br.bottom - 11, g) }
            }
        }
    }

    private fun icon(c: Canvas, cx: Float, cy: Float, label: String, kind: Int, selected: Boolean) {
        val r = RectF(cx - 56, cy - 56, cx + 56, cy + 56)
        when (kind) {
            0 -> { c.drawRect(r.left + 6, r.top + 10, r.right - 6, r.bottom - 26, Fx.fill(face)); c.drawRect(r.left + 18, r.top + 22, r.right - 18, r.bottom - 38, Fx.fill(navy)); c.drawRect(r.left + 20, r.bottom - 22, r.right - 20, r.bottom - 6, Fx.fill(face)) }
            1 -> { c.drawRect(r.left + 24, r.top + 18, r.right - 24, r.bottom - 4, Fx.fill(0xFFE0E0E0.toInt())); c.drawRect(r.left + 24, r.top + 18, r.right - 24, r.bottom - 4, Fx.stroke(Color.BLACK, 3f)); c.drawRect(r.left + 16, r.top + 8, r.right - 16, r.top + 20, Fx.fill(dark)) }
            2 -> { c.drawRoundRect(r.left + 8, r.top + 8, r.right - 8, r.bottom - 8, 14f, 14f, Fx.fill(0xFF1E6BFF.toInt())); Fx.text(c, label.take(1).uppercase(), cx, cy + 22, 64f, Color.WHITE, Fx.black) }
            else -> { c.drawRect(r.left + 6, r.top + 22, r.right - 6, r.bottom - 6, Fx.fill(0xFFF2D063.toInt())); c.drawRect(r.left + 6, r.top + 10, r.left + 54, r.top + 26, Fx.fill(0xFFF2D063.toInt())) }
        }
        if (selected) c.drawRect(r, Fx.fill(0x660000AA))
        val size = 30f
        val tw = Paint().apply { textSize = size; typeface = Fx.plain }.measureText(label)
        if (selected) c.drawRect(cx - tw / 2 - 6, cy + 64, cx + tw / 2 + 6, cy + 104, Fx.fill(navy))
        Fx.text(c, label, cx, cy + 94, size, Color.WHITE, Fx.plain)
    }

    private fun taskbar(c: Canvas) {
        bevel(c, RectF(0f, H - 80, W, H))
        bevel(c, RectF(8f, H - 72, 190f, H - 8))
        val d = Path().apply { moveTo(44f, H - 58); lineTo(62f, H - 40); lineTo(44f, H - 22); lineTo(26f, H - 40); close() }
        c.drawPath(d, Fx.fill(0xFF1E6BFF.toInt()))
        Fx.text(c, "Start", 80f, H - 26, 38f, Color.BLACK, Fx.bold, Paint.Align.LEFT)
        bevel(c, RectF(W - 250, H - 72, W - 8, H - 8), false)
        Fx.text(c, "cliprig", W - 129, H - 28, 32f, Color.BLACK, Fx.plain)
    }

    override fun drawBase(c: Canvas) { c.drawColor(teal); taskbar(c) }

    private fun cursor(c: Canvas, x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y); lineTo(x, y + 64); lineTo(x + 16, y + 50); lineTo(x + 28, y + 76); lineTo(x + 38, y + 72); lineTo(x + 26, y + 46); lineTo(x + 46, y + 46); close() }
        c.drawPath(p, Fx.fill(Color.WHITE)); c.drawPath(p, Fx.stroke(Color.BLACK, 3f))
    }

    private fun dialog(c: Canvas, cx: Float, cy: Float, title: String, msg: String, wide: Float = 760f, twoButtons: Boolean = false) {
        val l = Fx.layout(msg, (wide - 200).toInt(), 38f, Color.BLACK, Fx.plain, Layout.Alignment.ALIGN_NORMAL)
        val h = 60 + 40 + max(l.height.toFloat(), 90f) + 120
        val r = RectF(cx - wide / 2, cy - h / 2, cx + wide / 2, cy + h / 2)
        c.drawRect(RectF(r).apply { offset(10f, 10f) }, Fx.fill(0x66000000))
        bevel(c, r)
        titleBar(c, RectF(r.left + 8, r.top + 8, r.right - 8, r.top + 60), title)
        c.drawCircle(r.left + 80, r.top + 130, 38f, Fx.fill(0xFF1E6BFF.toInt()))
        Fx.text(c, "i", r.left + 80, r.top + 148, 54f, Color.WHITE, Fx.black)
        Fx.drawLayout(c, l, r.left + 150, r.top + 96)
        val bw = 180f
        if (twoButtons) {
            bevel(c, RectF(cx - bw - 20, r.bottom - 88, cx - 20, r.bottom - 24)); Fx.text(c, "OK", cx - bw / 2 - 20, r.bottom - 42, 34f, Color.BLACK, Fx.plain)
            bevel(c, RectF(cx + 20, r.bottom - 88, cx + 20 + bw, r.bottom - 24)); Fx.text(c, "Cancel", cx + 20 + bw / 2, r.bottom - 42, 34f, Color.BLACK, Fx.plain)
        } else {
            bevel(c, RectF(cx - bw / 2, r.bottom - 88, cx + bw / 2, r.bottom - 24)); Fx.text(c, "OK", cx, r.bottom - 42, 34f, Color.BLACK, Fx.plain)
        }
    }

    override fun draw(c: Canvas, f: Frame) {
        base(c)
        val labels = listOf("Computer", "Trash", f.title.ifBlank { "Game" }.take(14), "Clips")
        val gameIcon = RectF(iconX - 56, iconY[2] - 56, iconX + 56, iconY[2] + 56)
        if (f.t < 2100) {
            for (i in 0 until 4) icon(c, iconX, iconY[i], labels[i], i, i == 2 && f.t > 1100)
            if (f.t in 1500..2099) {
                val p = Fx.easeInOut(Fx.prog(f.t, 1500, 2050))
                for (k in 0 until 3) {
                    val r = Fx.lerpRect(gameIcon, win, max(0f, p - k * 0.12f))
                    c.drawRect(r, Fx.stroke(Color.BLACK, 4f))
                }
            }
            if (f.t < 1500) {
                val p = Fx.easeInOut(Fx.prog(f.t, 100, 1000))
                val x = Fx.lerp(820f, iconX + 10, p); val y = Fx.lerp(1500f, iconY[2] - 10, p)
                val click = f.t in 1100..1180 || f.t in 1260..1340
                cursor(c, x, y + if (click) 4f else 0f)
            }
            taskbar(c)
            return
        }
        bevel(c, win)
        titleBar(c, RectF(win.left + 8, win.top + 8, win.right - 8, win.top + 62), "${f.title.ifBlank { "Game" }}  cliprig")
        c.drawRect(RectF(screen).apply { inset(-4f, -4f) }, Fx.fill(Color.BLACK))
        hole(c, videoRect(f))
        taskbar(c)
        bevel(c, RectF(200f, H - 72, 560f, H - 8), false)
        Fx.text(c, f.title.ifBlank { "Game" }.take(16), 220f, H - 28, 30f, Color.BLACK, Fx.bold, Paint.Align.LEFT)
        if (f.showCaption) dialog(c, W / 2, screen.top + 210, "cliprig", f.caption)
        if (f.rapid && f.cut >= 0 && !f.inEnd) {
            val shown = (max(0, f.cut - 2)..f.cut)
            for (k in shown) {
                val msg = hype[k % hype.size]
                val dy = screen.top + 560 + (k % 3) * 300f
                val dx = W / 2 + ((k % 3) - 1) * 70f
                if (k < f.cut || f.cutT > 60) dialog(c, dx, dy, "Notice", msg, 700f)
            }
        }
        if (f.inEnd) dialog(c, W / 2, H / 2, "Play now", f.endText, 820f, true)
    }
}

object TouchPhoneTheme : Theme("touchphone", "Classic Touch Phone", "Glossy icon grid, tap to open the game, notification banners") {
    override val introMs = 2000L
    override val screen = RectF(130f, 230f, 950f, 230f + 820f * 16 / 9)
    override val screenRadius = 6f
    override val hype = listOf("New high score", "Achievement unlocked", "That was sick", "Run it back", "Clip saved", "Bro what")
    private val iconColors = listOf(0xFF34C759, 0xFFFF9500, 0xFFFF2D55, 0xFF5856D6, 0xFF8E8E93, 0xFFFFCC00, 0xFF5AC8FA, 0xFF4CD964,
        0xFF1E6BFF, 0xFFFF3B30, 0xFFAF52DE, 0xFF007AFF, 0xFFFF6B00, 0xFF30B0C7, 0xFF8BC34A, 0xFFE91E63).map { it.toInt() }
    private val names = listOf("Text", "Calendar", "Photos", "Camera", "Weather", "Clock", "Maps", "Notes",
        "GAME", "Music", "Videos", "Mail", "Web", "Phone", "Store", "Settings")

    private fun iconRect(i: Int): RectF {
        val col = i % 4; val row = i / 4
        val size = 150f; val gapX = (screen.width() - 4 * size) / 5
        val x = screen.left + gapX + col * (size + gapX)
        val y = screen.top + 110 + row * 250f
        return RectF(x, y, x + size, y + size)
    }

    override fun drawBase(c: Canvas) {
        c.drawColor(0xFF0B0D12.toInt())
        val body = RectF(60f, 40f, 1020f, 1890f)
        c.drawRoundRect(body, 130f, 130f, Fx.fill(0xFF15171C.toInt()))
        c.drawRoundRect(body, 130f, 130f, Fx.stroke(0xFFAAB2BD.toInt(), 10f))
        c.drawRoundRect(RectF(450f, 130f, 630f, 150f), 10f, 10f, Fx.fill(0xFF333842.toInt()))
        c.drawCircle(540f, 90f, 10f, Fx.fill(0xFF2A2F38.toInt()))
        c.drawCircle(540f, 1790f, 72f, Fx.fill(0xFF0E1014.toInt()))
        c.drawCircle(540f, 1790f, 72f, Fx.stroke(0xFF3A3F48.toInt(), 5f))
        c.drawRoundRect(RectF(515f, 1765f, 565f, 1815f), 10f, 10f, Fx.stroke(0xFF8A919C.toInt(), 4f))
    }

    private fun homeScreen(c: Canvas, f: Frame, pressed: Boolean) {
        c.save(); c.clipRect(screen)
        val p = Paint().apply { shader = LinearGradient(0f, screen.top, 0f, screen.bottom, 0xFF0B2E6B.toInt(), 0xFF1FA2C9.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(screen, p)
        for (i in 0 until 12) {
            val r = iconRect(i)
            val game = names[i] == "GAME"
            c.drawRoundRect(r, 34f, 34f, Fx.fill(if (game) 0xFF1E6BFF.toInt() else iconColors[i]))
            val gloss = Paint().apply { shader = LinearGradient(0f, r.top, 0f, r.centerY(), 0x88FFFFFF.toInt(), 0x22FFFFFF, Shader.TileMode.CLAMP) }
            c.drawRoundRect(RectF(r.left, r.top, r.right, r.centerY() + 6), 34f, 34f, gloss)
            if (game) Fx.text(c, f.title.ifBlank { "G" }.take(1).uppercase(), r.centerX(), r.centerY() + 34, 96f, Color.WHITE, Fx.black)
            else when (i % 4) {
                0 -> c.drawCircle(r.centerX(), r.centerY(), 36f, Fx.fill(0xCCFFFFFF.toInt()))
                1 -> c.drawRect(r.centerX() - 34, r.centerY() - 34, r.centerX() + 34, r.centerY() + 34, Fx.fill(0xCCFFFFFF.toInt()))
                2 -> c.drawRoundRect(RectF(r.centerX() - 40, r.centerY() - 22, r.centerX() + 40, r.centerY() + 22), 12f, 12f, Fx.fill(0xCCFFFFFF.toInt()))
                else -> c.drawCircle(r.centerX(), r.centerY(), 36f, Fx.stroke(0xCCFFFFFF.toInt(), 12f))
            }
            if (game && pressed) c.drawRoundRect(r, 34f, 34f, Fx.fill(0x77000000))
            val label = if (game) f.title.ifBlank { "Game" }.take(10) else names[i]
            Fx.text(c, label, r.centerX(), r.bottom + 40, 30f, Color.WHITE, Fx.bold)
        }
        val dock = RectF(screen.left, screen.bottom - 230, screen.right, screen.bottom)
        c.drawRect(dock, Fx.fill(0x55FFFFFF))
        for (i in 12 until 16) {
            val col = i - 12; val size = 150f; val gapX = (screen.width() - 4 * size) / 5
            val r = RectF(screen.left + gapX + col * (size + gapX), dock.top + 30, screen.left + gapX + col * (size + gapX) + size, dock.top + 180)
            c.drawRoundRect(r, 34f, 34f, Fx.fill(iconColors[i]))
            Fx.text(c, names[i], r.centerX(), dock.bottom - 14, 26f, Color.WHITE, Fx.bold)
        }
        c.restore()
    }

    override fun videoRect(f: Frame): RectF {
        if (f.t < 1200) return RectF()
        return Fx.lerpRect(iconRect(8), screen, Fx.easeOut(Fx.prog(f.t, 1200, 1800)))
    }

    private fun statusBar(c: Canvas) {
        c.drawRect(screen.left, screen.top, screen.right, screen.top + 44, Fx.fill(0xCC000000.toInt()))
        Fx.text(c, "cliprig", screen.left + 16, screen.top + 32, 28f, Color.WHITE, Fx.bold, Paint.Align.LEFT)
        Fx.text(c, "12:00", screen.centerX(), screen.top + 32, 28f, Color.WHITE, Fx.bold)
        c.drawRect(screen.right - 76, screen.top + 12, screen.right - 22, screen.top + 32, Fx.stroke(Color.WHITE, 3f))
        c.drawRect(screen.right - 72, screen.top + 16, screen.right - 34, screen.top + 28, Fx.fill(0xFF4CD964.toInt()))
    }

    private fun banner(c: Canvas, title: String, msg: String, slide: Float) {
        val top = screen.top + 52 - (1 - slide) * 220
        val r = RectF(screen.left + 18, top, screen.right - 18, top + 170)
        c.drawRoundRect(r, 26f, 26f, Fx.fill(0xE6202329.toInt()))
        c.drawRoundRect(RectF(r.left + 22, r.top + 22, r.left + 82, r.top + 82), 14f, 14f, Fx.fill(0xFF1E6BFF.toInt()))
        Fx.text(c, title, r.left + 100, r.top + 62, 34f, Color.WHITE, Fx.bold, Paint.Align.LEFT)
        val l = Fx.layout(msg, (r.width() - 60).toInt(), 36f, 0xFFE8ECF2.toInt(), Fx.plain, Layout.Alignment.ALIGN_NORMAL)
        c.save(); c.clipRect(r); Fx.drawLayout(c, l, r.left + 24, r.top + 96); c.restore()
    }

    override fun draw(c: Canvas, f: Frame) {
        base(c)
        if (f.t < 1800) {
            homeScreen(c, f, f.t in 900..1300)
            if (f.t in 900..1350) {
                val r = iconRect(8)
                val p = Fx.prog(f.t, 900, 1150)
                c.drawCircle(r.centerX() + 20, r.centerY() + 30, 40f + p * 30f, Fx.fill(Color.argb((120 * (1 - Fx.prog(f.t, 1150, 1350))).toInt(), 255, 255, 255)))
            }
            hole(c, videoRect(f), 34f * (1 - Fx.prog(f.t, 1200, 1800)))
            statusBar(c)
            return
        }
        hole(c, videoRect(f))
        statusBar(c)
        if (f.inEnd) {
            val p = Fx.easeOut(Fx.prog(f.t, f.endStart, f.endStart + 400))
            c.drawRect(screen, Fx.fill(Color.argb((170 * p).toInt(), 0, 0, 0)))
            val l = Fx.layout(f.endText, (screen.width() - 80).toInt(), 60f, Color.WHITE, Fx.bold)
            Fx.drawLayout(c, l, screen.left + 40, screen.centerY() - l.height / 2f - 80)
            val bar = RectF(screen.left + 60, screen.bottom - 220, screen.right - 60, screen.bottom - 110)
            c.drawRoundRect(bar, 55f, 55f, Fx.fill(0x99FFFFFF.toInt()))
            val kx = Fx.lerp(bar.left + 10, bar.right - 130, Fx.easeInOut(Fx.prog(f.t, f.endStart + 600, f.endStart + 1600)))
            c.drawRoundRect(RectF(kx, bar.top + 10, kx + 120, bar.bottom - 10), 45f, 45f, Fx.fill(Color.WHITE))
            Fx.text(c, "→", kx + 60, bar.centerY() + 20, 56f, 0xFF1E6BFF.toInt(), Fx.black)
            Fx.text(c, "slide to play", bar.centerX() + 60, bar.centerY() + 14, 40f, Color.WHITE, Fx.bold)
            return
        }
        if (f.burst) banner(c, f.title.ifBlank { "Game" }, f.hype, Fx.easeOut(Fx.prog(f.cutT, 0, 250)))
        else if (f.showCaption) banner(c, "cliprig", f.caption, Fx.easeOut(Fx.prog(f.t, f.intro, f.intro + 300)))
    }
}

object SliderTheme : Theme("slider", "Slider Phone", "Chunky handheld with a keyboard, menu scrolling into the game") {
    override val introMs = 2300L
    override val screen = RectF(190f, 150f, 890f, 150f + 700f * 16 / 9)
    override val screenRadius = 4f
    override val hype = listOf("NEW MSG: that was sick", "NEW MSG: how", "ALERT: skill level rising", "NEW MSG: again!!", "MSG: ur cracked")
    private val lcd = 0xFFA8D5C8.toInt()
    private val lcdInk = 0xFF0F3A33.toInt()

    override fun drawBase(c: Canvas) {
        c.drawColor(0xFF0E1116.toInt())
        val body = RectF(110f, 50f, 970f, 1890f)
        c.drawRoundRect(body, 90f, 90f, Fx.fill(0xFF2B3340.toInt()))
        c.drawRoundRect(body, 90f, 90f, Fx.stroke(0xFF4A5566.toInt(), 8f))
        c.drawRoundRect(RectF(150f, 110f, 930f, screen.bottom + 40), 40f, 40f, Fx.fill(0xFF11151B.toInt()))
        c.drawRect(110f, screen.bottom + 60, 970f, screen.bottom + 66, Fx.fill(0xFF1E6BFF.toInt()))
        c.drawCircle(540f, screen.bottom + 63, 26f, Fx.fill(0xFF1E6BFF.toInt()))
        c.drawCircle(540f, screen.bottom + 63, 16f, Fx.fill(0xFF8FB4FF.toInt()))
        val kb = RectF(150f, screen.bottom + 100, 930f, 1850f)
        c.drawRoundRect(kb, 30f, 30f, Fx.fill(0xFF1A2029.toInt()))
        val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM", " ")
        val kh = (kb.height() - 50) / 4
        rows.forEachIndexed { ri, row ->
            val n = if (row == " ") 1 else row.length
            val kw = if (row == " ") 420f else 68f
            val total = n * kw + (n - 1) * 8
            var x = kb.centerX() - total / 2
            val y = kb.top + 20 + ri * kh
            for (k in 0 until n) {
                val r = RectF(x, y, x + kw, y + kh - 10)
                c.drawRoundRect(r, 12f, 12f, Fx.fill(0xFFD9DEE6.toInt()))
                if (row != " ") Fx.text(c, row[k].toString(), r.centerX(), r.centerY() + 13, 34f, 0xFF1A2029.toInt(), Fx.bold)
                x += kw + 8
            }
        }
    }

    private fun menu(c: Canvas, f: Frame) {
        c.save(); c.clipRect(screen)
        c.drawRect(screen, Fx.fill(lcd))
        c.drawRect(screen.left, screen.top, screen.right, screen.top + 80, Fx.fill(lcdInk))
        val sub = f.t >= 1100
        Fx.text(c, if (sub) "Games" else "Menu", screen.left + 24, screen.top + 56, 40f, lcd, Fx.mono, Paint.Align.LEFT)
        val items = if (sub) listOf("Tile Drop", f.title.ifBlank { "Game" }.take(16), "Snake Pit", "Get more...")
        else listOf("Phone", "Messages", "Web", "Games", "Settings")
        val sel = if (sub) (if (f.t < 1450) 0 else 1) else min(3, (f.t / 230).toInt())
        items.forEachIndexed { i, s ->
            val y = screen.top + 120 + i * 110f
            if (i == sel) {
                val flash = (f.t in 900..1050 && !sub) || (f.t in 1600..1750 && sub)
                c.drawRect(screen.left + 10, y, screen.right - 10, y + 96, Fx.fill(if (flash) 0xFF1E6BFF.toInt() else lcdInk))
            }
            Fx.text(c, s, screen.left + 40, y + 66, 46f, if (i == sel) lcd else lcdInk, Fx.mono, Paint.Align.LEFT)
        }
        if (f.t >= 1800) {
            c.drawRect(screen, Fx.fill(lcd))
            Fx.text(c, "Loading...", screen.centerX(), screen.centerY() - 40, 50f, lcdInk, Fx.mono)
            val p = Fx.prog(f.t, 1800, 2250)
            c.drawRect(screen.left + 80, screen.centerY() + 10, screen.right - 80, screen.centerY() + 60, Fx.stroke(lcdInk, 5f))
            c.drawRect(screen.left + 90, screen.centerY() + 20, Fx.lerp(screen.left + 90, screen.right - 90, p), screen.centerY() + 50, Fx.fill(lcdInk))
        }
        c.restore()
    }

    private fun popup(c: Canvas, msg: String, slide: Float) {
        val h = 190f
        val top = screen.bottom - h - 24 + (1 - slide) * (h + 40)
        val r = RectF(screen.left + 24, top, screen.right - 24, top + h)
        c.save(); c.clipRect(screen)
        c.drawRect(r, Fx.fill(lcd)); c.drawRect(r, Fx.stroke(lcdInk, 6f))
        c.drawRect(r.left + 24, r.top + 30, r.left + 104, r.top + 86, Fx.stroke(lcdInk, 5f))
        c.drawLine(r.left + 24, r.top + 30, r.left + 64, r.top + 62, Fx.stroke(lcdInk, 5f))
        c.drawLine(r.left + 104, r.top + 30, r.left + 64, r.top + 62, Fx.stroke(lcdInk, 5f))
        val l = Fx.layout(msg, (r.width() - 160).toInt(), 36f, lcdInk, Fx.mono, Layout.Alignment.ALIGN_NORMAL)
        Fx.drawLayout(c, l, r.left + 130, r.top + 26)
        c.restore()
    }

    override fun draw(c: Canvas, f: Frame) {
        base(c)
        if (f.t < 2300) { menu(c, f); return }
        hole(c, videoRect(f))
        c.drawRect(screen.left, screen.top, screen.right, screen.top + 50, Fx.fill(0xCC0F3A33.toInt()))
        Fx.text(c, f.title.ifBlank { "Game" }.take(18), screen.left + 16, screen.top + 36, 30f, lcd, Fx.mono, Paint.Align.LEFT)
        if (f.inEnd) { popup(c, "Get it: " + f.endText, Fx.easeOut(Fx.prog(f.t, f.endStart, f.endStart + 300))); return }
        if (f.burst) popup(c, f.hype, Fx.easeOut(Fx.prog(f.cutT, 0, 220)))
        else if (f.showCaption) popup(c, f.caption, Fx.easeOut(Fx.prog(f.t, f.intro, f.intro + 300)))
    }
}

object CartoonTheme : Theme("cartoon", "Saturday Cartoon", "Wobbly ink, loud splats, curtains up, rapid fire sound effects") {
    override val introMs = 2100L
    override val screen = RectF(100f, 190f, 980f, 190f + 880f * 16 / 9)
    override val hype = listOf("WHOOSH!", "KA-BLAM!", "SPLAT!", "YOWZA!", "BOING!", "KERSPLOOSH!", "HOO BOY!")
    private val purple = 0xFF7B2FF7.toInt()
    private val pink = 0xFFFF3EA5.toInt()
    private val lime = 0xFF9BE22D.toInt()
    private val orange = 0xFFFF7A00.toInt()
    private val ink = 0xFF14081F.toInt()
    private val splatColors = listOf(lime, orange, pink, 0xFF22D3EE.toInt(), 0xFFFFE14D.toInt())

    override fun drawBase(c: Canvas) {
        c.drawColor(purple)
        val stripe = Fx.fill(0x33FFFFFF)
        var x = -H
        while (x < W + H) {
            val p = Path().apply { moveTo(x, 0f); lineTo(x + 60, 0f); lineTo(x + 60 + H, H); lineTo(x + H, H); close() }
            c.drawPath(p, stripe); x += 140f
        }
        val rnd = Random(9)
        repeat(40) {
            val cx = rnd.nextFloat() * W; val cy = rnd.nextFloat() * H
            when (it % 3) {
                0 -> c.drawCircle(cx, cy, 10f + rnd.nextFloat() * 14, Fx.fill(splatColors[it % splatColors.size]))
                1 -> { val p = Path(); p.moveTo(cx, cy); for (k in 1..4) p.lineTo(cx + k * 22, cy + if (k % 2 == 0) 0f else 22f); c.drawPath(p, Fx.stroke(lime, 8f)) }
                else -> Fx.starburst(c, cx, cy, 20f, 9f, 5, 0f, 0xFFFFE14D.toInt(), ink, 3f)
            }
        }
    }

    override fun videoRect(f: Frame): RectF = if (f.t < 1300) RectF() else RectF(screen)

    private fun splatWord(c: Canvas, word: String, cx: Float, cy: Float, p: Float, seed: Int) {
        c.save(); c.scale(p, p, cx, cy); c.rotate(((seed % 5) - 2) * 6f, cx, cy)
        Fx.blob(c, cx, cy, 230f, seed, splatColors[seed % splatColors.size], ink, 12f)
        val size = Fx.fitSize(word, 400f, 110f, Fx.casual)
        Fx.text(c, word, cx, cy + size * 0.35f, size, Color.WHITE, Fx.casual, outline = ink, outlineW = 16f)
        c.restore()
    }

    private fun curtains(c: Canvas, open: Float) {
        c.save(); c.clipRect(screen)
        val half = screen.width() / 2
        val shift = half * open
        for (side in 0..1) {
            val l = if (side == 0) screen.left - shift else screen.centerX() + shift
            val r = l + half
            c.drawRect(l, screen.top, r, screen.bottom, Fx.fill(0xFFC81D3A.toInt()))
            var x = l + 30
            while (x < r) { c.drawRect(x, screen.top, x + 22, screen.bottom, Fx.fill(0xFF8E1028.toInt())); x += 80f }
        }
        c.drawRect(screen.left, screen.top, screen.right, screen.top + 90, Fx.fill(0xFFFFC21A.toInt()))
        c.restore()
    }

    override fun draw(c: Canvas, f: Frame) {
        base(c)
        c.drawRect(RectF(screen).apply { inset(-6f, -6f) }, Fx.fill(ink))
        hole(c, videoRect(f))
        if (f.t < 2100) {
            curtains(c, Fx.easeInOut(Fx.prog(f.t, 1300, 2050)))
            if (f.t < 1500) {
                val bob = sin(f.t / 120.0).toFloat() * 14
                val p = Fx.easeBack(Fx.prog(f.t, 0, 450)) * (1 - Fx.prog(f.t, 1300, 1500))
                c.save(); c.scale(p, p, W / 2, H / 2)
                Fx.blob(c, W / 2, H / 2 + bob, 380f, 4, orange, ink, 14f)
                Fx.text(c, "today's episode", W / 2, H / 2 - 120 + bob, 54f, Color.WHITE, Fx.casual, outline = ink, outlineW = 10f)
                val size = Fx.fitSize(f.title, 640f, 140f, Fx.casual)
                Fx.text(c, f.title, W / 2, H / 2 + 60 + bob, size, 0xFFFFE14D.toInt(), Fx.casual, outline = ink, outlineW = 18f)
                c.restore()
            }
        }
        Fx.wobblyRect(c, RectF(screen).apply { inset(-4f, -4f) }, ((f.t / 110) % 3).toInt() + 1, 16f, ink)
        if (f.showCaption) {
            val l = Fx.layout(f.caption, 700, 52f, ink, Fx.casual)
            val cy = screen.bottom - 220
            val r = RectF(W / 2 - 400, cy - l.height / 2f - 40, W / 2 + 400, cy + l.height / 2f + 40)
            val tail = Path().apply { moveTo(r.right - 220, r.top + 10); lineTo(r.right - 120, r.top - 110); lineTo(r.right - 130, r.top + 10); close() }
            c.drawOval(RectF(r).apply { inset(-20f, -10f) }, Fx.fill(Color.WHITE)); c.drawPath(tail, Fx.fill(Color.WHITE))
            c.drawOval(RectF(r).apply { inset(-20f, -10f) }, Fx.stroke(ink, 9f)); c.drawPath(tail, Fx.stroke(ink, 9f))
            c.drawOval(RectF(r).apply { inset(-14f, -4f) }, Fx.fill(Color.WHITE))
            Fx.drawLayout(c, l, W / 2 - 350, cy - l.height / 2f)
        }
        if (f.burst) {
            val seed = f.cut * 5 + 2
            val cx = W / 2 + ((f.cut % 3) - 1) * 220f
            val cy = screen.top + 360 + (f.cut % 2) * 260
            splatWord(c, f.hype, cx, cy, Fx.easeBack(Fx.prog(f.cutT, 0, 220)) * (1 - Fx.prog(f.cutT, 540, 650)), seed)
        }
        if (f.inEnd) {
            val bounce = 1 + sin((f.t - f.endStart) / 140.0).toFloat() * 0.04f
            val p = Fx.easeBack(Fx.prog(f.t, f.endStart, f.endStart + 380)) * bounce
            c.save(); c.scale(p, p, W / 2, H / 2)
            Fx.blob(c, W / 2, H / 2, 420f, 11, lime, ink, 14f)
            Fx.text(c, "catch it at", W / 2, H / 2 - 90, 60f, Color.WHITE, Fx.casual, outline = ink, outlineW = 10f)
            val l = Fx.layout(f.endText, 640, 58f, ink, Fx.casual)
            Fx.drawLayout(c, l, W / 2 - 320, H / 2 - 30)
            c.restore()
        }
        Fx.text(c, f.title, W / 2, 120f, Fx.fitSize(f.title, 900f, 74f, Fx.casual), 0xFFFFE14D.toInt(), Fx.casual, outline = ink, outlineW = 12f)
    }

    override fun key(f: Frame): String = super.key(f) + "|w${(f.t / 110) % 3}"
}

object ArcadeTheme : Theme("arcade", "Arcade Cabinet", "Neon marquee, CRT scanlines, insert coin power on") {
    override val introMs = 1900L
    override val screen = RectF(150f, 320f, 930f, 320f + 780f * 16 / 9)
    override val screenRadius = 24f
    override val hype = listOf("COMBO!", "PERFECT!", "1UP!", "MEGA!", "HI SCORE!", "BONUS!", "CRITICAL!")
    private val cyan = 0xFF00E5FF.toInt()
    private val magenta = 0xFFFF2BD6.toInt()

    override fun drawBase(c: Canvas) {
        c.drawColor(0xFF120A24.toInt())
        for (i in 0 until 6) {
            val y = 300f + i * 260
            c.drawLine(0f, y, 120f, y + 160, Fx.stroke(if (i % 2 == 0) cyan else magenta, 10f))
            c.drawLine(W, y, W - 120, y + 160, Fx.stroke(if (i % 2 == 0) magenta else cyan, 10f))
        }
        c.drawRoundRect(RectF(110f, 280f, 970f, screen.bottom + 40), 50f, 50f, Fx.fill(0xFF22212B.toInt()))
        c.drawRoundRect(RectF(110f, 280f, 970f, screen.bottom + 40), 50f, 50f, Fx.stroke(0xFF3C3A4A.toInt(), 8f))
        val panel = RectF(60f, screen.bottom + 70, 1020f, H - 10)
        c.drawRoundRect(panel, 30f, 30f, Fx.fill(0xFF1A1830.toInt()))
        c.drawRect(panel.left, panel.top, panel.right, panel.top + 8, Fx.fill(0xFF1E6BFF.toInt()))
        c.drawLine(220f, panel.centerY() + 20, 220f, panel.centerY() - 30, Fx.stroke(0xFF888888.toInt(), 14f))
        c.drawCircle(220f, panel.centerY() - 40, 30f, Fx.fill(0xFFE53935.toInt()))
        listOf(0xFF1E6BFF, 0xFFFFD400, 0xFF4CD964).forEachIndexed { i, col ->
            c.drawCircle(600f + i * 130, panel.centerY() + 6, 36f, Fx.fill(col.toInt()))
            c.drawCircle(600f + i * 130, panel.centerY() + 6, 36f, Fx.stroke(Color.BLACK, 4f))
        }
        Fx.text(c, "1P", 380f, panel.centerY() + 22, 52f, cyan, Fx.mono)
    }

    override fun videoRect(f: Frame): RectF {
        if (f.t < 1300) return RectF()
        val p = Fx.easeOut(Fx.prog(f.t, 1300, 1750))
        return Fx.scaleRect(screen, Fx.lerp(0.9f, 1f, p), max(0.004f, p))
    }

    override fun draw(c: Canvas, f: Frame) {
        base(c)
        val marquee = RectF(60f, 40f, 1020f, 250f)
        c.drawRoundRect(marquee, 26f, 26f, Fx.fill(0xFF05030C.toInt()))
        val hueA = ((f.t / 400) % 2 == 0L)
        c.drawRoundRect(marquee, 26f, 26f, Fx.stroke(if (hueA) cyan else magenta, 8f))
        val ms = Fx.fitSize(f.title.uppercase(), 860f, 120f, Fx.mono)
        Fx.text(c, f.title.uppercase(), W / 2, marquee.centerY() + ms * 0.36f, ms, Color.WHITE, Fx.mono, shadow = if (hueA) magenta else cyan)
        c.drawRoundRect(screen, screenRadius, screenRadius, Fx.fill(0xFF050505.toInt()))
        val vr = videoRect(f)
        hole(c, vr, if (f.t < 1750) 0f else screenRadius)
        c.save(); c.clipRect(screen)
        val line = Fx.fill(0x40000000)
        var y = screen.top
        while (y < screen.bottom) { c.drawRect(screen.left, y, screen.right, y + 3, line); y += 7f }
        val vig = Paint().apply { shader = RadialGradient(screen.centerX(), screen.centerY(), screen.height() * 0.62f, 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(screen, vig)
        if (f.t < 1300) {
            if ((f.t / 300) % 2 == 0L) Fx.text(c, "INSERT COIN", W / 2, screen.centerY(), 80f, 0xFFFFD400.toInt(), Fx.mono, shadow = 0xFFFF8800.toInt())
            if (f.t > 800) Fx.text(c, "CREDIT 01", W / 2, screen.centerY() + 140, 54f, cyan, Fx.mono)
        }
        if (f.t in 1300..1450) c.drawRect(screen.left, screen.centerY() - 6, screen.right, screen.centerY() + 6, Fx.fill(Color.WHITE))
        if (f.rapid && f.cut > 0 && f.cutT < 160) {
            val rnd = Random(f.t)
            repeat(260) {
                val x = screen.left + rnd.nextFloat() * screen.width(); val yy = screen.top + rnd.nextFloat() * screen.height()
                val g = rnd.nextInt(255)
                c.drawRect(x, yy, x + 22, yy + 10, Fx.fill(Color.argb(200, g, g, g)))
            }
        }
        if (f.burst) {
            val p = Fx.easeBack(Fx.prog(f.cutT, 0, 200))
            c.save(); c.scale(p, p, W / 2, screen.top + 300)
            Fx.text(c, f.hype, W / 2, screen.top + 330, 110f, 0xFFFFD400.toInt(), Fx.mono, outline = 0xFFE53935.toInt(), outlineW = 14f, shadow = magenta)
            c.restore()
        }
        if (f.showCaption) {
            val l = Fx.layout("> " + f.caption.uppercase(), (screen.width() - 80).toInt(), 44f, 0xFFFFE14D.toInt(), Fx.mono, Layout.Alignment.ALIGN_NORMAL)
            c.drawRect(screen.left, screen.bottom - l.height - 70, screen.right, screen.bottom, Fx.fill(0xAA000000.toInt()))
            Fx.drawLayout(c, l, screen.left + 40, screen.bottom - l.height - 36)
        }
        if (f.inEnd) {
            c.drawRect(screen, Fx.fill(0xCC000000.toInt()))
            if (((f.t - f.endStart) / 300) % 2 == 0L) Fx.text(c, "PLAY NOW", W / 2, screen.centerY() - 160, 120f, magenta, Fx.mono, shadow = cyan)
            val l = Fx.layout(f.endText.uppercase(), (screen.width() - 100).toInt(), 50f, Color.WHITE, Fx.mono)
            Fx.drawLayout(c, l, screen.left + 50, screen.centerY() - 40)
            Fx.text(c, "INSERT COIN", W / 2, screen.bottom - 120, 60f, 0xFFFFD400.toInt(), Fx.mono)
        }
        c.restore()
    }

    override fun key(f: Frame): String = super.key(f) + "|h${(f.t / 400) % 2}"
}

object ThemeBook {
    val all: List<Theme> = listOf(PlainTheme, ComicTheme, DesktopTheme, TouchPhoneTheme, SliderTheme, CartoonTheme, ArcadeTheme)
    fun byId(id: String): Theme = all.firstOrNull { it.id == id } ?: PlainTheme
    fun pick(setting: String): Theme = if (setting == "random") all.filter { it != PlainTheme }.random() else byId(setting)
}
