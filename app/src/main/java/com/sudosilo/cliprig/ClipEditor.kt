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
    val startMs: Long,
    val endMs: Long,
    val caption: String,
    val title: String,
    val endText: String,
    val music: Uri?
)

@UnstableApi
object ClipEditor {
    const val OUT_W = 1080
    const val OUT_H = 1920

    fun musicTracks(ctx: Context): List<Uri> {
        val tree = Store.musicFolder
        if (tree.isEmpty()) return emptyList()
        return runCatching {
            val treeUri = Uri.parse(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val list = mutableListOf<Uri>()
            ctx.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: ""
                    if (mime.startsWith("audio/")) {
                        list.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)))
                    }
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun volume(v: Float): AudioProcessor {
        val p = ChannelMixingAudioProcessor()
        for (inCh in 1..2) p.putChannelMixingMatrix(ChannelMixingMatrix.create(inCh, 2).scaleBy(v))
        return p
    }

    suspend fun render(ctx: Context, spec: EditSpec, out: File) {
        val clipItem = MediaItem.Builder()
            .setUri(spec.source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(spec.startMs)
                    .setEndPositionMs(spec.endMs)
                    .build()
            ).build()

        val layout = if (Store.framing == "Fit") Presentation.LAYOUT_SCALE_TO_FIT
        else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        val overlay = CardOverlay(
            spec.caption, spec.title, spec.endText, spec.endMs - spec.startMs,
            Store.captionStyle, Store.titleCards
        )
        val videoEffects = ImmutableList.of<androidx.media3.common.Effect>(
            Presentation.createForWidthAndHeight(OUT_W, OUT_H, layout),
            OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(overlay))
        )
        val gameVol = if (spec.music != null) Store.gameVolume / 100f else 1f
        val game = EditedMediaItem.Builder(clipItem)
            .setEffects(Effects(ImmutableList.of(volume(gameVol)), videoEffects))
            .build()

        val sequences = mutableListOf(EditedMediaItemSequence(ImmutableList.of(game)))
        if (spec.music != null) {
            val m = EditedMediaItem.Builder(MediaItem.fromUri(spec.music))
                .setRemoveVideo(true)
                .setEffects(Effects(ImmutableList.of(volume(Store.musicVolume / 100f)), ImmutableList.of()))
                .build()
            sequences.add(EditedMediaItemSequence(ImmutableList.of(m), true))
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

@UnstableApi
class CardOverlay(
    private val caption: String,
    private val title: String,
    private val endText: String,
    private val clipMs: Long,
    private val style: String,
    private val cards: Boolean
) : BitmapOverlay() {

    private var baseUs = -1L
    private val empty = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private val captionBmp by lazy { box(caption, 64f, 0xF01E6BFF.toInt(), 940) }
    private val titleBmp by lazy { box(title, 92f, 0xF01E6BFF.toInt(), 960) }
    private val endBmp by lazy { box(endText, 62f, 0xF00F1A33.toInt(), 960) }

    private fun phase(us: Long): Int {
        if (baseUs < 0) baseUs = us
        val rel = (us - baseUs) / 1000
        return when {
            cards && title.isNotBlank() && rel < 1600 -> 1
            cards && endText.isNotBlank() && rel > clipMs - 2200 -> 2
            style != "Off" && caption.isNotBlank() -> 0
            else -> 3
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = when (phase(presentationTimeUs)) {
        0 -> captionBmp
        1 -> titleBmp
        2 -> endBmp
        else -> empty
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val y = when (phase(presentationTimeUs)) {
            0 -> if (style == "Bottom") -0.62f else 0.62f
            else -> 0f
        }
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, y)
            .setOverlayFrameAnchor(0f, 0f)
            .build()
    }

    private fun box(text: String, size: Float, bg: Int, maxW: Int): Bitmap {
        if (text.isBlank()) return empty
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pad = (size * 0.45f).toInt()
        val inner = maxW - pad * 2
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, tp, inner)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .build()
        var widest = 0f
        for (i in 0 until sl.lineCount) widest = maxOf(widest, sl.getLineWidth(i))
        val w = (widest.toInt() + pad * 2).coerceAtMost(maxW)
        val h = sl.height + pad * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), size * 0.4f, size * 0.4f, p)
        c.save()
        c.translate((w - inner) / 2f, pad.toFloat())
        sl.draw(c)
        c.restore()
        return bmp
    }
}
