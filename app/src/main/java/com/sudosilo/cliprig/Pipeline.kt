package com.sudosilo.cliprig

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@UnstableApi
object Pipeline {
    data class Status(val running: Boolean, val text: String, val progress: Float)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<suspend () -> Unit>()
    private var busy = false

    @Volatile var status = Status(false, "", 0f)
        private set
    var listener: ((Status) -> Unit)? = null
    var onClipAdded: (() -> Unit)? = null

    private fun post(s: Status) {
        status = s
        scope.launch(Dispatchers.Main) { listener?.invoke(s) }
    }

    private fun enqueue(ctx: Context, job: suspend () -> Unit) {
        synchronized(queue) { queue.addLast(job) }
        drain(ctx.applicationContext)
    }

    private fun drain(ctx: Context) {
        synchronized(queue) { if (busy) return; busy = true }
        scope.launch {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cliprig:pipeline")
            wl.acquire(60 * 60 * 1000L)
            try {
                while (true) {
                    val job = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    runCatching { job() }.onFailure {
                        post(Status(true, "Something broke making that clip, moving on", 1f))
                    }
                }
            } finally {
                synchronized(queue) { busy = false }
                runCatching { wl.release() }
                post(Status(false, "", 0f))
            }
        }
    }

    private fun pickCaption(gameId: String, avoid: String = ""): String {
        val g = Store.game(gameId)
        val bank = (g?.captions?.takeIf { it.isNotEmpty() } ?: Store.defaultCaptions).filter { it != avoid }
        return bank.randomOrNull() ?: avoid
    }

    private fun pickMusic(ctx: Context): Uri? {
        when (Store.musicMode) {
            "Off" -> return null
            "Queue" -> {
                val q = Store.musicQueue
                if (q.isNotEmpty()) {
                    val i = Store.queueIndex % q.size
                    Store.queueIndex = i + 1
                    return Uri.parse(q[i])
                }
            }
        }
        return ClipEditor.musicTracks(ctx).randomOrNull()
    }

    fun newMusic(ctx: Context, clip: ClipRecord) = rerender(ctx, clip, clip.theme, false, true)

    fun fromSession(ctx: Context, session: Session) = enqueue(ctx) {
        val uri = Uri.fromFile(File(session.videoPath))
        makeClips(ctx, uri, session.gameId, session.gameName, session.url, session, session.clipLenSec)
        if (session.url == "camera") Store.markInboxDone(uri.toString())
    }

    fun fromUri(ctx: Context, uri: Uri, gameId: String, copyFirst: Boolean) = enqueue(ctx) {
        var src = uri
        if (copyFirst) {
            post(Status(true, "Copying the recording", 0f))
            val f = File(Store.rawDir, "2026-11-25-blue-cliprig-v1-shared-${UUID.randomUUID().toString().take(8)}.mp4")
            ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
            src = Uri.fromFile(f)
        }
        val g = Store.game(gameId)
        makeClips(ctx, src, gameId, g?.name ?: "", g?.url ?: "", null, Store.clipLenSec)
        Store.markInboxDone(uri.toString())
    }

    fun regenerate(ctx: Context, clip: ClipRecord) = rerender(ctx, clip, clip.theme, true)

    fun restyle(ctx: Context, clip: ClipRecord) = rerender(ctx, clip, ThemeBook.pick(Store.theme).id, false)

    private fun rerender(ctx: Context, clip: ClipRecord, themeId: String, newCaption: Boolean, swapMusic: Boolean = false) = enqueue(ctx) {
        post(Status(true, when {
            swapMusic -> "Swapping in the next song"
            newCaption -> "Making a new caption version"
            else -> "Restyling with ${ThemeBook.byId(themeId).label}"
        }, 0.2f))
        val music: Uri? = when {
            swapMusic -> pickMusic(ctx)
            clip.music.isNotBlank() -> Uri.parse(clip.music)
            Store.musicMode == "Off" -> null
            else -> pickMusic(ctx)
        }
        val src = Uri.parse(clip.source)
        if (src.scheme == "file" && !File(src.path ?: "").exists()) {
            post(Status(true, "The raw recording for that clip was cleaned up", 1f))
            return@enqueue
        }
        val g = Store.game(clip.gameId)
        val caption = if (newCaption) pickCaption(clip.gameId, clip.caption) else clip.caption
        val ranges = clip.segs()
        val theme = ThemeBook.byId(themeId)
        val out = File(Store.workDir, "2026-11-25-blue-cliprig-v1-work-${clip.id}.mp4")
        ClipEditor.render(
            ctx, EditSpec(src, ranges, clip.rapid, caption, hypes(theme, ranges.size), g?.name ?: clip.gameName,
                endText(promo(g, g?.url ?: ""), g?.endText ?: ""), music, themeId, Store.introOn,
                tagline(g), popups(g)), out
        )
        val newUri = ClipEditor.publish(ctx, out, clip.gameName) ?: return@enqueue
        runCatching { ctx.contentResolver.delete(Uri.parse(clip.uri), null, null) }
        Store.replaceClip(clip.copy(uri = newUri.toString(), caption = caption, theme = themeId, music = music?.toString() ?: ""))
        withContext(Dispatchers.Main) { onClipAdded?.invoke() }
        post(Status(true, "Done", 1f))
    }

    private fun fastSeg(dur: Long, outMs: Long): Seg {
        val start = minOf(1500L, dur / 20)
        val len = dur - start
        val speed = (len.toFloat() / outMs).coerceIn(1.5f, 16f)
        return Seg(start, minOf(dur, start + (outMs * speed).toLong()), speed)
    }

    private fun hypes(theme: Theme, n: Int): List<String> {
        val bank = theme.hype.shuffled()
        return (0 until n).map { bank[it % bank.size] }
    }

    private fun promo(g: GameProfile?, playUrl: String): String =
        g?.promoUrl?.ifBlank { null } ?: Store.defaultPromo.ifBlank { null } ?: playUrl

    private fun tagline(g: GameProfile?): String = g?.tagline?.ifBlank { null } ?: Store.defaultTagline

    private fun popups(g: GameProfile?): List<String> =
        (g?.popups?.takeIf { it.isNotEmpty() } ?: Store.defaultPopups).shuffled()

    private fun endText(url: String, custom: String): String {
        if (custom.isNotBlank()) return custom
        val clean = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return if (clean.isBlank()) "" else "play it at\n$clean"
    }

    private suspend fun makeClips(
        ctx: Context, uri: Uri, gameId: String, gameName: String, url: String,
        session: Session?, clipLenSec: Int
    ) {
        post(Status(true, "Reading the recording", 0.02f))
        val dur = Analyzer.durationMs(ctx, uri)
        if (dur <= 0) { post(Status(true, "That recording could not be read", 1f)); return }
        val bins = ((dur + Analyzer.BIN_MS - 1) / Analyzer.BIN_MS).toInt()
        post(Status(true, "Listening for loud moments", 0.05f))
        val audio = Analyzer.audioLevels(ctx, uri, bins)
        post(Status(true, "Watching for big moments", 0.15f))
        val visual = Analyzer.visualChange(ctx, uri, bins) { p ->
            post(Status(true, "Watching for big moments", 0.15f + p * 0.35f))
        }
        val g = Store.game(gameId)
        val title = (g?.name ?: gameName).ifBlank { "Game" }
        val style = Store.clipStyle
        val jobs = mutableListOf<Pair<List<Seg>, Boolean>>()
        val sp = Store.speed
        val wantHighlights = style == "Highlights" || style == "Highlights and rapid fire" || style == "Everything"
        val wantRapid = style == "Rapid fire" || style == "Highlights and rapid fire" || style == "Everything"
        val wantFast = style == "Fast forward" || style == "Everything"
        if (wantHighlights) {
            val srcLen = (clipLenSec * 1000L * sp).toLong()
            val picked = Analyzer.pick(dur, audio, visual, session, srcLen, Store.clipCount)
            picked.forEach { jobs.add(listOf(Seg(it.startMs, it.endMs, if (it.endMs - it.startMs < srcLen) 1f else sp)) to false) }
            if (picked.size < Store.clipCount && !wantFast) jobs.add(listOf(fastSeg(dur, clipLenSec * 1000L)) to false)
        }
        if (wantRapid) {
            val cuts = Analyzer.pick(dur, audio, visual, session, 3000L, 5).map { Seg(it.startMs, it.endMs) }.toMutableList()
            if (cuts.size < 5) {
                val fill = mutableListOf<Seg>()
                var k = 0
                while (cuts.size + fill.size < 5) {
                    val base = cuts.getOrNull(k % maxOf(1, cuts.size))
                    val speed = if (k < cuts.size) 2f else 3f + k
                    val want = (3000 * speed).toLong()
                    val center = base?.let { (it.from + it.to) / 2 } ?: dur / 2
                    var s0 = maxOf(0L, center - want / 2)
                    val e0 = minOf(dur, s0 + want)
                    s0 = maxOf(0L, e0 - want)
                    val actual = maxOf(1.1f, (e0 - s0) / 3000f)
                    fill.add(Seg(s0, e0, actual))
                    k++
                }
                cuts.addAll(fill)
            }
            if (cuts.isNotEmpty()) jobs.add(cuts to true)
        }
        if (wantFast) jobs.add(listOf(fastSeg(dur, clipLenSec * 1000L)) to false)
        var made = 0
        jobs.forEachIndexed { i, (ranges, rapid) ->
            post(Status(true, if (rapid) "Cutting the rapid fire montage" else "Cutting clip ${i + 1} of ${jobs.size}", 0.5f + 0.5f * i / jobs.size))
            val theme = ThemeBook.pick(Store.theme)
            val caption = pickCaption(gameId)
            val music = pickMusic(ctx)
            val out = File(Store.workDir, "2026-11-25-blue-cliprig-v1-work-$i.mp4")
            ClipEditor.render(
                ctx, EditSpec(uri, ranges, rapid, caption, hypes(theme, ranges.size), title,
                    endText(promo(g, if (url == "camera") "" else url), g?.endText ?: ""), music, theme.id, Store.introOn,
                    tagline(g), popups(g)), out
            )
            val pub = ClipEditor.publish(ctx, out, title)
            if (pub != null) {
                made++
                Store.addClip(
                    ClipRecord(
                        UUID.randomUUID().toString().take(12), pub.toString(), title,
                        caption, uri.toString(), ranges.first().from, ranges.last().to, gameId,
                        System.currentTimeMillis(), ClipRecord.encode(ranges), rapid, theme.id, music?.toString() ?: ""
                    )
                )
                withContext(Dispatchers.Main) { onClipAdded?.invoke() }
            }
        }
        post(Status(true, "Done, $made clips in the Library", 1f))
    }
}
