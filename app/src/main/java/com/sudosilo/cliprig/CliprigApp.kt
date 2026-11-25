package com.sudosilo.cliprig

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class CliprigApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(RecordService.CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        Thread { runCatching { Store.cleanupRaw() } }.start()
        RecordService.onFinished = { session -> onRunFinished(session) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun onRunFinished(session: Session?) {
        PlayActivity.current?.finish()
        if (session != null) Pipeline.fromSession(this, session)
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .putExtra("screen", "library")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}

object SessionLog {
    @Volatile var active = false
    var baseNs = 0L
    var gameId = ""
    var gameName = ""
    var url = ""
    var clipLenSec = 15
    val taps = mutableListOf<Long>()
    val marks = mutableListOf<Long>()
    val events = mutableListOf<GameEvent>()
    var listener: (() -> Unit)? = null

    fun reset(id: String, name: String, u: String, len: Int) {
        synchronized(this) {
            gameId = id; gameName = name; url = u; clipLenSec = len
            taps.clear(); marks.clear(); events.clear()
            baseNs = System.nanoTime()
        }
    }

    private fun now(): Long = (System.nanoTime() - baseNs) / 1_000_000L

    fun tap() { if (active) synchronized(this) { taps.add(now()) } }
    fun mark() { if (active) { synchronized(this) { marks.add(now()) }; listener?.invoke() } }
    fun event(type: String) { if (active) { synchronized(this) { events.add(GameEvent(now(), type)) }; listener?.invoke() } }
    fun highlightCount(): Int = synchronized(this) { marks.size + events.size }
}
