package com.sudosilo.cliprig

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeShotsTest {
    @Test
    fun renderShots() {
        val out = File(System.getProperty("cliprig.out") ?: "build/theme-shots").apply { mkdirs() }
        for (theme in ThemeBook.all) {
            val intro = theme.introMs
            val total = intro + 15000
            val times = listOf(300L, intro / 2 + 200, intro - 250, intro + 80, intro + 3100, intro + 7500, total - 900)
            val sheet = Bitmap.createBitmap((W / 4 * times.size).toInt(), (H / 4).toInt(), Bitmap.Config.ARGB_8888)
            val sc = Canvas(sheet)
            times.forEachIndexed { i, t ->
                val i0 = intro
                val body = (t - i0).coerceAtLeast(0)
                val cut = if (t < i0) -1 else (body / 3000).toInt()
                val cutT = if (t < i0) t else body - cut * 3000
                val f = Frame(t, total, i0, cut, cutT, true, "this run was unreal", theme.hype[cut.coerceAtLeast(0) % theme.hype.size],
                    "Ascend", "play it at\nascend.vercel.app", true, "Top")
                val bmp = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.MAGENTA)
                val vr = theme.videoRect(f)
                c.drawRect(vr, Paint().apply { color = 0xFF33AA55.toInt() })
                val layer = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
                theme.draw(Canvas(layer), f)
                c.drawBitmap(layer, 0f, 0f, null)
                sc.save(); sc.translate(i * W / 4, 0f); sc.scale(0.25f, 0.25f); sc.drawBitmap(bmp, 0f, 0f, null); sc.restore()
            }
            File(out, "${theme.id}.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
