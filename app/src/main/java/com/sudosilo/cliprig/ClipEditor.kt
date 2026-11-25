package com.sudosilo.cliprig

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.effect.MatrixTransformation
import android.graphics.Matrix
import androidx.media3.effect.OverlayEffect
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class EditSpec(
    val source: Uri,
    val segments: List<Seg>,
    val rapid: Boolean,
    val caption: String,
    val hypes: List<String>,
    val title: String,
    val endText: String,
    val music: Uri?,
    val themeId: String,
    val intro: Boolean,
    val tagline: String = "",
    val popups: List<String> = emptyList()
)

@UnstableApi
object ClipEditor {
    const val OUT_W = 1080
    const val OUT_H = 1920

    fun musicTracks(ctx: Context): List<Uri> = musicList(ctx).map { it.first }

    fun musicList(ctx: Context): List<Pair<Uri, String>> {
        val tree = Store.musicFolder
        if (tree.isEmpty()) return emptyList()
        return runCatching {
            val treeUri = Uri.parse(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val list = mutableListOf<Pair<Uri, String>>()
            ctx.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: ""
                    if (mime.startsWith("audio/")) {
                        list.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)) to
                            (c.getString(2) ?: "track").substringBeforeLast('.'))
                    }
                }
            }
            list.sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun volume(v: Float): AudioProcessor {
        val p = ChannelMixingAudioProcessor()
        for (inCh in 1..2) p.putChannelMixingMatrix(ChannelMixingMatrix.create(inCh, 2).scaleBy(v))
        return p
    }

    private fun clipItem(src: Uri, from: Long, to: Long): MediaItem = MediaItem.Builder()
        .setUri(src)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(from).setEndPositionMs(to).build()
        ).build()

    fun plan(spec: EditSpec, theme: Theme, sourceDurMs: Long): List<Pair<Seg, Int>> {
        val intro = if (spec.intro) theme.introMs else 0L
        val out = mutableListOf<Pair<Seg, Int>>()
        if (!spec.rapid) {
            val seg = spec.segments.first()
            val lead = (intro * seg.speed).toLong()
            var s = seg.from - lead
            var e = seg.to
            if (s < 0) { e = minOf(sourceDurMs, e - s); s = 0 }
            out.add(Seg(s, e, seg.speed) to 0)
        } else {
            if (intro > 0) {
                val first = spec.segments.first()
                val s = maxOf(0L, first.from - intro)
                val e = if (first.from - s >= intro) first.from else minOf(sourceDurMs, s + intro)
                out.add(Seg(s, e, 1f) to -1)
            }
            spec.segments.forEachIndexed { i, r -> out.add(r to i) }
        }
        return out
    }

    private class ConstSpeed(private val v: Float) : androidx.media3.common.audio.SpeedProvider {
        override fun getSpeed(timeUs: Long) = v
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = androidx.media3.common.C.TIME_UNSET
    }

    suspend fun render(ctx: Context, spec: EditSpec, out: File) {
        val theme = ThemeBook.byId(spec.themeId)
        val dur = Analyzer.durationMs(ctx, spec.source).takeIf { it > 0 } ?: Long.MAX_VALUE
        val parts = plan(spec, theme, dur)
        val total = parts.sumOf { it.first.outMs }
        val intro = if (spec.intro) theme.introMs else 0L
        val layout = if (Store.framing == "Fit") Presentation.LAYOUT_SCALE_TO_FIT
        else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        val gameVol = if (spec.music != null) Store.gameVolume / 100f else 1f

        val schedule = ThemeBook.popupSchedule(spec.popups, intro, total, Store.titleCards && spec.endText.isNotBlank())
        var offset = 0L
        val items = parts.map { (seg, cut) ->
            val info = ItemInfo(
                theme, offset, cut, spec.caption,
                if (cut >= 0 && spec.rapid) spec.hypes.getOrElse(cut) { "" } else "",
                total, intro, spec.rapid, spec.title, spec.endText, Store.titleCards, Store.captionStyle, seg.speed,
                spec.tagline, schedule
            )
            offset += seg.outMs
            val clock = ItemClock()
            val effects = mutableListOf<androidx.media3.common.Effect>()
            val audio = mutableListOf<AudioProcessor>()
            if (seg.speed != 1f) {
                effects.add(SpeedChangeEffect(seg.speed))
                audio.add(SpeedChangingAudioProcessor(ConstSpeed(seg.speed)))
            }
            effects.add(FrameDropEffect.createDefaultFrameDropEffect(30f))
            effects.add(Presentation.createForWidthAndHeight(OUT_W, OUT_H, layout))
            effects.add(ThemeMatrix(info, clock))
            effects.add(OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(ThemeOverlay(info, clock))))
            audio.add(volume(if (seg.speed >= 3f) 0f else gameVol))
            EditedMediaItem.Builder(clipItem(spec.source, seg.from, seg.to))
                .setEffects(Effects(ImmutableList.copyOf(audio), ImmutableList.copyOf(effects)))
                .build()
        }

        val sequences = mutableListOf(EditedMediaItemSequence.Builder(items).build())
        if (spec.music != null) {
            val musicItem = MediaItem.Builder().setUri(spec.music)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(Store.musicStartSec * 1000L).build()
                ).build()
            val m = EditedMediaItem.Builder(musicItem)
                .setRemoveVideo(true)
                .setEffects(Effects(ImmutableList.of(volume(Store.musicVolume / 100f)), ImmutableList.of()))
                .build()
            sequences.add(EditedMediaItemSequence.Builder(m).setIsLooping(true).build())
        }
        val composition = Composition.Builder(sequences).build()

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val t = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onError(
                            composition: Composition, exportResult: ExportResult, exportException: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation { runCatching { t.cancel() } }
                t.start(composition, out.absolutePath)
            }
        }
    }

    fun publish(ctx: Context, file: File, gameName: String): Uri? {
        val safe = gameName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "game" }
        val name = "2026-11-25-blue-cliprig-v1-$safe-${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/cliprig")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        cr.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        file.delete()
        return uri
    }
}

class ItemClock {
    private var base = -1L
    @Synchronized fun rel(us: Long): Long { if (base < 0 || us < base) base = us; return (us - base) / 1000 }
}

data class ItemInfo(
    val theme: Theme,
    val offsetMs: Long,
    val cut: Int,
    val caption: String,
    val hype: String,
    val totalMs: Long,
    val introMs: Long,
    val rapid: Boolean,
    val title: String,
    val endText: String,
    val cards: Boolean,
    val captionStyle: String,
    val speed: Float = 1f,
    val tagline: String = "",
    val popups: List<Popup> = emptyList()
) {
    fun frame(relMs: Long): Frame {
        val t = offsetMs + relMs
        val p = popups.firstOrNull { t >= it.startMs && t < it.startMs + it.durMs }
        return Frame(
            t, totalMs, introMs, cut, relMs, rapid, caption, hype, title, endText, cards, captionStyle, speed,
            tagline, p?.text ?: "", if (p != null) t - p.startMs else 0L, p?.durMs ?: 0L
        )
    }
}

@UnstableApi
class ThemeMatrix(private val info: ItemInfo, private val clock: ItemClock) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val r = info.theme.videoRect(info.frame(clock.rel(presentationTimeUs)))
        val m = Matrix()
        val sx = maxOf(0.002f, r.width() / W)
        val sy = maxOf(0.002f, r.height() / H)
        m.setScale(sx, sy)
        val cx = if (r.width() < 1) W / 2 else r.centerX()
        val cy = if (r.height() < 1) H / 2 else r.centerY()
        m.postTranslate(cx / W * 2 - 1, 1 - cy / H * 2)
        return m
    }
}

@UnstableApi
class ThemeOverlay(private val info: ItemInfo, private val clock: ItemClock) : BitmapOverlay() {
    private val bmp = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    private var lastKey = ""

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val f = info.frame(clock.rel(presentationTimeUs))
        val key = info.theme.key(f)
        if (key != lastKey) {
            lastKey = key
            bmp.eraseColor(0)
            info.theme.draw(canvas, f)
            if (f.speed > 1.01f && !f.inIntro && !f.inEnd) info.theme.speedBadge(canvas, f)
            if (f.popup.isNotBlank() && !f.inIntro && !f.inEnd) info.theme.drawPopup(canvas, f)
        }
        return bmp
    }
}
