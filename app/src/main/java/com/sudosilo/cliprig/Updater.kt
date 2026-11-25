package com.sudosilo.cliprig

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    private const val API = "https://api.github.com/repos/sudosilo/2026-11-25-blue-cliprig/releases/latest"

    data class Release(val version: Int, val apkUrl: String, val name: String)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(API).openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 10000; c.readTimeout = 10000
            val body = c.inputStream.bufferedReader().readText()
            val o = JSONObject(body)
            val tag = o.optString("tag_name").removePrefix("v").toIntOrNull() ?: return@runCatching null
            val assets = o.optJSONArray("assets") ?: return@runCatching null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return@runCatching Release(tag, a.optString("browser_download_url"), a.optString("name"))
                }
            }
            null
        }.getOrNull()
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun downloadAndInstall(ctx: Context, r: Release, progress: (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, r.name)
                var url = URL(r.apkUrl)
                var conn: HttpURLConnection
                var hops = 0
                while (true) {
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15000; conn.readTimeout = 30000
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 6) { url = URL(conn.getHeaderField("Location")); hops++; continue }
                    break
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) progress(done.toFloat() / total)
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(ctx, "com.sudosilo.cliprig.files", f)
                withContext(Dispatchers.Main) {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                true
            }.getOrDefault(false)
        }
}
