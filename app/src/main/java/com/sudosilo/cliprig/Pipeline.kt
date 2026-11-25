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
        if (!Store.musicOn) return null
        return ClipEditor.musicTracks(ctx).randomOrNull()
    }

    fun fromSession(ctx: Context, session: Session) = enqueue(ctx) {
        val uri = Uri.fromFile(File(session.videoPath))
        makeClips(ctx, uri, session.gameId, session.gameName, session.url, session, session.clipLenSec)
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

    fun regenerate(ctx: Context, clip: ClipRecord) = enqueue(ctx) {
        post(Status(true, "Making a new caption version", 0.2f))
        val src = Uri.parse(clip.source)
        if (src.scheme == "file" && !File(src.path ?: "").exists()) {
            post(Status(true, "The raw recording for that clip was cleaned up", 1f))
            return@enqueue
        }
        val g = Store.game(clip.gameId)
        val caption = pickCaption(clip.gameId, clip.caption)
        val out = File(Store.workDir, "2026-11-25-blue-cliprig-v1-work-${clip.id}.mp4")
        ClipEditor.render(
            ctx, EditSpec(src, clip.startMs, clip.endMs, caption, g?.name ?: clip.gameName,
                endText(g?.url ?: "", g?.endText ?: ""), pickMusic(ctx)), out
        )
        val newUri = ClipEditor.publish(ctx, out, clip.gameName) ?: return@enqueue
        runCatching { ctx.contentResolver.delete(Uri.parse(clip.uri), null, null) }
        Store.replaceClip(clip.copy(uri = newUri.toString(), caption = caption))
        withContext(Dispatchers.Main) { onClipAdded?.invoke() }
    }

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
        val windows = Analyzer.pick(dur, audio, visual, session, clipLenSec, Store.clipCount)
        val g = Store.game(gameId)
        val title = g?.name ?: gameName
        windows.forEachIndexed { i, w ->
            post(Status(true, "Cutting clip ${i + 1} of ${windows.size}", 0.5f + 0.5f * i / windows.size))
            val caption = pickCaption(gameId)
            val out = File(Store.workDir, "2026-11-25-blue-cliprig-v1-work-$i.mp4")
            ClipEditor.render(
                ctx, EditSpec(uri, w.startMs, w.endMs, caption, title, endText(url, g?.endText ?: ""), pickMusic(ctx)), out
            )
            val pub = ClipEditor.publish(ctx, out, title.ifBlank { "game" })
            if (pub != null) {
                Store.addClip(
                    ClipRecord(
                        UUID.randomUUID().toString().take(12), pub.toString(), title.ifBlank { "Unsorted" },
                        caption, uri.toString(), w.startMs, w.endMs, gameId, System.currentTimeMillis()
                    )
                )
                withContext(Dispatchers.Main) { onClipAdded?.invoke() }
            }
        }
        post(Status(true, "Done, ${windows.size} clips in the Library", 1f))
    }
}
