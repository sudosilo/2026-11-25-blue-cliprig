package com.sudosilo.cliprig

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GameProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",
    var captions: MutableList<String> = mutableListOf(),
    var endText: String = "",
    var hook: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url)
        .put("captions", JSONArray(captions)).put("endText", endText).put("hook", hook)

    companion object {
        fun fromJson(o: JSONObject): GameProfile {
            val caps = mutableListOf<String>()
            val a = o.optJSONArray("captions") ?: JSONArray()
            for (i in 0 until a.length()) caps.add(a.optString(i))
            return GameProfile(
                o.optString("id"), o.optString("name"), o.optString("url"),
                caps, o.optString("endText"), o.optBoolean("hook")
            )
        }
    }
}

data class ClipRecord(
    val id: String,
    val uri: String,
    val gameName: String,
    var caption: String,
    val source: String,
    val startMs: Long,
    val endMs: Long,
    val gameId: String,
    val createdAt: Long,
    val segments: String = "",
    val rapid: Boolean = false,
    val theme: String = "plain"
) {
    fun ranges(): List<LongRange> {
        val parsed = segments.split(";").mapNotNull {
            val a = it.split("-"); if (a.size == 2) (a[0].toLongOrNull() ?: return@mapNotNull null)..(a[1].toLongOrNull() ?: return@mapNotNull null) else null
        }
        return parsed.ifEmpty { listOf(startMs..endMs) }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("uri", uri).put("gameName", gameName).put("caption", caption)
        .put("source", source).put("startMs", startMs).put("endMs", endMs)
        .put("gameId", gameId).put("createdAt", createdAt)
        .put("segments", segments).put("rapid", rapid).put("theme", theme)

    companion object {
        fun encode(r: List<LongRange>) = r.joinToString(";") { "${it.first}-${it.last}" }
        fun fromJson(o: JSONObject) = ClipRecord(
            o.optString("id"), o.optString("uri"), o.optString("gameName"),
            o.optString("caption"), o.optString("source"), o.optLong("startMs"),
            o.optLong("endMs"), o.optString("gameId"), o.optLong("createdAt"),
            o.optString("segments"), o.optBoolean("rapid"), o.optString("theme", "plain")
        )
    }
}

data class GameEvent(val ms: Long, val type: String)

data class Session(
    val id: String,
    val videoPath: String,
    val gameId: String,
    val gameName: String,
    val url: String,
    val createdAt: Long,
    val clipLenSec: Int,
    val taps: List<Long>,
    val marks: List<Long>,
    val events: List<GameEvent>
) {
    fun toJson(): JSONObject {
        val ev = JSONArray()
        events.forEach { ev.put(JSONObject().put("ms", it.ms).put("type", it.type)) }
        return JSONObject()
            .put("id", id).put("videoPath", videoPath).put("gameId", gameId)
            .put("gameName", gameName).put("url", url).put("createdAt", createdAt)
            .put("clipLenSec", clipLenSec)
            .put("taps", JSONArray(taps)).put("marks", JSONArray(marks)).put("events", ev)
    }

    companion object {
        fun fromJson(o: JSONObject): Session {
            fun longs(a: JSONArray?): List<Long> {
                if (a == null) return emptyList()
                return (0 until a.length()).map { a.optLong(it) }
            }
            val evA = o.optJSONArray("events") ?: JSONArray()
            val ev = (0 until evA.length()).map {
                val e = evA.getJSONObject(it); GameEvent(e.optLong("ms"), e.optString("type"))
            }
            return Session(
                o.optString("id"), o.optString("videoPath"), o.optString("gameId"),
                o.optString("gameName"), o.optString("url"), o.optLong("createdAt"),
                o.optInt("clipLenSec", 15),
                longs(o.optJSONArray("taps")), longs(o.optJSONArray("marks")), ev
            )
        }
    }
}

object Store {
    private lateinit var ctx: Context
    private val prefs by lazy { ctx.getSharedPreferences("cliprig_v1", Context.MODE_PRIVATE) }

    fun init(c: Context) { ctx = c.applicationContext }

    val rawDir: File get() = File(ctx.filesDir, "raw").apply { mkdirs() }
    val workDir: File get() = File(ctx.cacheDir, "work").apply { mkdirs() }

    var clipLenSec: Int
        get() = prefs.getInt("clipLenSec", 15)
        set(v) = prefs.edit().putInt("clipLenSec", v).apply()
    var clipCount: Int
        get() = prefs.getInt("clipCount", 3)
        set(v) = prefs.edit().putInt("clipCount", v).apply()
    var musicOn: Boolean
        get() = prefs.getBoolean("musicOn", true)
        set(v) = prefs.edit().putBoolean("musicOn", v).apply()
    var musicFolder: String
        get() = prefs.getString("musicFolder", "") ?: ""
        set(v) = prefs.edit().putString("musicFolder", v).apply()
    var musicVolume: Int
        get() = prefs.getInt("musicVolume", 70)
        set(v) = prefs.edit().putInt("musicVolume", v).apply()
    var gameVolume: Int
        get() = prefs.getInt("gameVolume", 60)
        set(v) = prefs.edit().putInt("gameVolume", v).apply()
    var captionStyle: String
        get() = prefs.getString("captionStyle", "Top") ?: "Top"
        set(v) = prefs.edit().putString("captionStyle", v).apply()
    var framing: String
        get() = prefs.getString("framing", "Fill") ?: "Fill"
        set(v) = prefs.edit().putString("framing", v).apply()
    var titleCards: Boolean
        get() = prefs.getBoolean("titleCards", true)
        set(v) = prefs.edit().putBoolean("titleCards", v).apply()
    var rawKeepDays: Int
        get() = prefs.getInt("rawKeepDays", 7)
        set(v) = prefs.edit().putInt("rawKeepDays", v).apply()
    var theme: String
        get() = prefs.getString("theme", "comic") ?: "comic"
        set(v) = prefs.edit().putString("theme", v).apply()
    var introOn: Boolean
        get() = prefs.getBoolean("introOn", true)
        set(v) = prefs.edit().putBoolean("introOn", v).apply()
    var clipStyle: String
        get() = prefs.getString("clipStyle", "Both") ?: "Both"
        set(v) = prefs.edit().putString("clipStyle", v).apply()
    var lastGameId: String
        get() = prefs.getString("lastGameId", "") ?: ""
        set(v) = prefs.edit().putString("lastGameId", v).apply()
    var lastUrl: String
        get() = prefs.getString("lastUrl", "") ?: ""
        set(v) = prefs.edit().putString("lastUrl", v).apply()
    var defaultCaptions: List<String>
        get() = (prefs.getString("defaultCaptions", null)
            ?: "wait for it\nthis run was unreal\nhow did that work\none more try\nnailed it\nclosest call yet")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("defaultCaptions", v.joinToString("\n")).apply()

    fun games(): MutableList<GameProfile> {
        val a = JSONArray(prefs.getString("games", "[]"))
        return (0 until a.length()).map { GameProfile.fromJson(a.getJSONObject(it)) }.toMutableList()
    }

    fun saveGames(list: List<GameProfile>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        prefs.edit().putString("games", a.toString()).apply()
    }

    fun game(id: String): GameProfile? = games().firstOrNull { it.id == id }

    fun upsertGame(g: GameProfile) {
        val list = games()
        val i = list.indexOfFirst { it.id == g.id }
        if (i >= 0) list[i] = g else list.add(g)
        saveGames(list)
    }

    fun deleteGame(id: String) = saveGames(games().filter { it.id != id })

    fun clips(): MutableList<ClipRecord> {
        val a = JSONArray(prefs.getString("clips", "[]"))
        return (0 until a.length()).map { ClipRecord.fromJson(a.getJSONObject(it)) }.toMutableList()
    }

    @Synchronized
    fun saveClips(list: List<ClipRecord>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        prefs.edit().putString("clips", a.toString()).apply()
    }

    @Synchronized
    fun addClip(c: ClipRecord) { val l = clips(); l.add(0, c); saveClips(l) }

    @Synchronized
    fun replaceClip(c: ClipRecord) {
        val l = clips(); val i = l.indexOfFirst { it.id == c.id }
        if (i >= 0) l[i] = c else l.add(0, c)
        saveClips(l)
    }

    @Synchronized
    fun removeClip(id: String) = saveClips(clips().filter { it.id != id })

    fun doneInbox(): Set<String> = prefs.getStringSet("doneInbox", emptySet()) ?: emptySet()
    fun markInboxDone(uri: String) {
        val s = doneInbox().toMutableSet(); s.add(uri)
        prefs.edit().putStringSet("doneInbox", s).apply()
    }

    fun saveSession(s: Session) {
        File(rawDir, "${s.id}.json").writeText(s.toJson().toString())
    }

    fun loadSession(id: String): Session? {
        val f = File(rawDir, "$id.json")
        if (!f.exists()) return null
        return runCatching { Session.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun cleanupRaw() {
        val cutoff = System.currentTimeMillis() - rawKeepDays * 86_400_000L
        rawDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
