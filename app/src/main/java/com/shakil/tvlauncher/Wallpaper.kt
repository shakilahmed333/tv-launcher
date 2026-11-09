package com.shakil.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bing's free "photo of the day" (1920x1080 nature photos), downloaded once a day
 * and kept on disk so the home screen shows it instantly, even offline.
 */
object WallpaperRepo {
    private const val INFO_URL = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US"

    /** Decoded photo kept in memory (about 4 MB) so returning Home never re-decodes it. */
    @Volatile
    var bitmap: Bitmap? = null
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Loads the saved photo from disk. Run off the main thread. */
    fun loadCached(ctx: Context): Bitmap? {
        bitmap?.let { return it }
        val f = file(ctx)
        if (!f.exists()) return null
        bitmap = decode(f)
        return bitmap
    }

    /** Downloads today's photo if we don't have it yet. Returns the new photo, or null if nothing changed. */
    fun refreshIfStale(ctx: Context): Bitmap? {
        val p = prefs(ctx)
        if (p.getString("day", null) == today() && file(ctx).exists()) return null
        return try {
            val info = JSONObject(get(INFO_URL).toString(Charsets.UTF_8))
            val path = info.getJSONArray("images").getJSONObject(0).getString("url")
            val bytes = get("https://www.bing.com$path")
            val tmp = File(ctx.filesDir, "wallpaper.tmp")
            tmp.writeBytes(bytes)
            val decoded = decode(tmp) ?: return null
            tmp.renameTo(file(ctx))
            p.edit().putString("day", today()).apply()
            bitmap = decoded
            decoded
        } catch (e: Exception) {
            null
        }
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** RGB_565 halves the memory of a full-HD photo; nature photos look the same. */
    private fun decode(f: File): Bitmap? {
        val opts = BitmapFactory.Options()
        opts.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }
}
