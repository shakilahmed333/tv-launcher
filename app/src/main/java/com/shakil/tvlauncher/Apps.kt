package com.shakil.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable

/** One installed app the launcher can show. */
class AppEntry(
    val pkg: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable?,
    val color: Int,
)

object AppRepo {
    /** All launchable apps, TV apps first, sorted by name. Runs off the main thread. */
    @Suppress("DEPRECATION")
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, AppEntry>()

        fun collect(category: String) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val info = ri.activityInfo ?: continue
                val pkg = info.packageName
                if (pkg == ctx.packageName || found.containsKey(pkg)) continue
                val label = ri.loadLabel(pm).toString()
                val banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm)
                val icon = ri.loadIcon(pm)
                found[pkg] = AppEntry(pkg, label, banner, icon, Palette.average(banner ?: icon))
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER)
        collect(Intent.CATEGORY_LAUNCHER)
        return found.values.sortedBy { it.label.lowercase() }
    }
}

/** Counts how often each app is opened from this launcher, for the "Most used" row. */
class Usage(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)

    fun count(pkg: String): Int = prefs.getInt(pkg, 0)

    fun bump(pkg: String) {
        prefs.edit().putInt(pkg, count(pkg) + 1).apply()
    }
}

object Palette {
    const val FALLBACK = 0xFF3A1A24.toInt()

    /** Average colour of an app's banner or icon, drawn at 12x12 px so it's cheap. */
    fun average(d: Drawable?): Int {
        if (d == null) return FALLBACK
        val size = 12
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val old = d.copyBounds()
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        d.bounds = old

        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (x in 0 until size) {
            for (y in 0 until size) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                r += Color.red(p)
                g += Color.green(p)
                b += Color.blue(p)
                n++
            }
        }
        bmp.recycle()
        if (n == 0) return FALLBACK
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Mixes [a] towards [b] by [t] (0..1). */
    fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    fun withAlpha(c: Int, alpha: Int): Int = (c and 0x00FFFFFF) or (alpha shl 24)
}
