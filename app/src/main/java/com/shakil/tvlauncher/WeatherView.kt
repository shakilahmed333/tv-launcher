package com.shakil.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live weather layer drawn over the wallpaper photo: a colour tint for the
 * weather and time of day, plus light animations (sun glow, drifting clouds,
 * rain, lightning, mist, twinkling stars). Capped at 30 fps and paused when
 * the home screen is hidden, so it costs very little on a 1 GB TV.
 */
class WeatherView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { CLEAR_DAY, CLEAR_NIGHT, PARTLY_DAY, CLOUDY, RAIN, STORM, FOG }

    private var mode = Mode.CLEAR_DAY
    private var night = false
    private var running = false
    private var lastFrame = 0L
    private var nextFlash = 0L
    private var flashStart = 0L

    private val rnd = Random(7)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8CFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private var cloud: Bitmap? = null
    private var sunShader: RadialGradient? = null

    private class Drop(var x: Float, var y: Float, val speed: Float, val len: Float)
    private class Cloud(var x: Float, val y: Float, val scale: Float, val speed: Float, val alpha: Int)
    private class Star(val x: Float, val y: Float, val r: Float, val phase: Float)

    private val drops = ArrayList<Drop>()
    private val clouds = ArrayList<Cloud>()
    private val stars = ArrayList<Star>()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** Weather code (Open-Meteo/WMO) and day or night. */
    fun setWeather(code: Int, isDay: Boolean) {
        night = !isDay
        mode = when (code) {
            0, 1 -> if (isDay) Mode.CLEAR_DAY else Mode.CLEAR_NIGHT
            2 -> if (isDay) Mode.PARTLY_DAY else Mode.CLEAR_NIGHT
            3 -> Mode.CLOUDY
            45, 48 -> Mode.FOG
            in 51..67, in 80..82 -> Mode.RAIN
            in 95..99 -> Mode.STORM
            else -> Mode.CLOUDY
        }
        seed()
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sunShader = null
        seed()
    }

    private fun seed() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drops.clear()
        clouds.clear()
        stars.clear()

        val rainy = mode == Mode.RAIN || mode == Mode.STORM
        if (rainy) {
            repeat(if (mode == Mode.STORM) 110 else 80) {
                drops.add(Drop(rnd.nextFloat() * w, rnd.nextFloat() * h, dp(9f + rnd.nextFloat() * 6f), dp(10f + rnd.nextFloat() * 12f)))
            }
        }
        val cloudCount = when (mode) {
            Mode.PARTLY_DAY -> 3
            Mode.CLOUDY, Mode.RAIN, Mode.STORM -> 5
            Mode.FOG -> 4
            else -> 0
        }
        repeat(cloudCount) { i ->
            clouds.add(
                Cloud(
                    x = rnd.nextFloat() * w,
                    y = h * (0.04f + 0.09f * i + rnd.nextFloat() * 0.05f),
                    scale = 1.2f + rnd.nextFloat() * 1.3f,
                    speed = dp(0.15f + rnd.nextFloat() * 0.25f),
                    alpha = if (mode == Mode.FOG) 70 else 120 + rnd.nextInt(70),
                ),
            )
        }
        if (mode == Mode.CLEAR_NIGHT) {
            repeat(70) {
                stars.add(Star(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.55f, dp(0.6f + rnd.nextFloat() * 1.1f), rnd.nextFloat() * 6.28f))
            }
        }
        nextFlash = SystemClock.uptimeMillis() + 4000
    }

    /** A soft cloud made of overlapping radial blobs, drawn once and reused. */
    private fun cloudBitmap(): Bitmap {
        cloud?.let { return it }
        val w = dp(220f).toInt()
        val h = dp(90f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val blobs = arrayOf(
            floatArrayOf(0.30f, 0.62f, 0.30f),
            floatArrayOf(0.50f, 0.45f, 0.36f),
            floatArrayOf(0.70f, 0.60f, 0.30f),
            floatArrayOf(0.50f, 0.70f, 0.34f),
        )
        for (b in blobs) {
            val cx = b[0] * w
            val cy = b[1] * h
            val r = b[2] * w * 0.75f
            p.shader = RadialGradient(cx, cy, r, intArrayOf(Color.WHITE, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }
        cloud = bmp
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(0L, 100L).toFloat()
        lastFrame = now

        // 1. Colour tint for weather and time of day.
        canvas.drawColor(tintColor())

        // 2. Sun glow, gently breathing.
        if (mode == Mode.CLEAR_DAY || mode == Mode.PARTLY_DAY) {
            val shader = sunShader ?: RadialGradient(
                w * 0.86f, h * 0.10f, dp(330f),
                intArrayOf(0xB3FFF3D6.toInt(), 0x40FFE2A8, 0x00FFE2A8),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            ).also { sunShader = it }
            paint.shader = shader
            paint.alpha = (190 + 50 * sin(now / 4000.0 * 2 * PI)).toInt()
            canvas.drawCircle(w * 0.86f, h * 0.10f, dp(330f), paint)
            paint.shader = null
            paint.alpha = 255
        }

        // 3. Stars.
        if (stars.isNotEmpty()) {
            paint.color = Color.WHITE
            for (s in stars) {
                paint.alpha = (110 + 110 * sin(now / 900.0 + s.phase)).toInt().coerceIn(30, 230)
                canvas.drawCircle(s.x, s.y, s.r, paint)
            }
            paint.alpha = 255
        }

        // 4. Drifting clouds.
        if (clouds.isNotEmpty()) {
            val bmp = cloudBitmap()
            for (c in clouds) {
                c.x += c.speed * dt / 16f
                val cw = bmp.width * c.scale
                if (c.x > w) c.x = -cw
                paint.alpha = c.alpha
                canvas.save()
                canvas.translate(c.x, c.y)
                canvas.scale(c.scale, c.scale)
                canvas.drawBitmap(bmp, 0f, 0f, paint)
                canvas.restore()
            }
            paint.alpha = 255
        }

        // 5. Rain.
        if (drops.isNotEmpty()) {
            rainPaint.strokeWidth = dp(1.2f)
            val slant = 0.22f
            for (d in drops) {
                d.y += d.speed * dt / 16f
                d.x += d.speed * slant * dt / 16f
                if (d.y > h) {
                    d.y = -d.len
                    d.x = rnd.nextFloat() * w
                }
                canvas.drawLine(d.x, d.y, d.x - d.len * slant, d.y - d.len, rainPaint)
            }
        }

        // 6. Lightning flash every 6-12 s during storms.
        if (mode == Mode.STORM) {
            if (now >= nextFlash) {
                flashStart = now
                nextFlash = now + 6000 + rnd.nextLong(6000)
            }
            val t = now - flashStart
            if (t in 0L..350L) {
                val a = ((1f - t / 350f) * 150).toInt()
                canvas.drawColor(Color.argb(a, 255, 255, 255))
            }
        }

        // 7. Mist bands.
        if (mode == Mode.FOG) {
            paint.color = Color.WHITE
            for (i in 0 until 3) {
                val y = h * (0.35f + i * 0.18f) + dp(10f) * sin(now / 3000.0 + i).toFloat()
                paint.alpha = 45
                canvas.drawRect(0f, y, w, y + dp(60f), paint)
            }
            paint.alpha = 255
        }

        if (running && isShown) {
            // About 30 fps is plenty for slow weather motion.
            postOnAnimationDelayed({ invalidate() }, 33)
        }
    }

    private fun tintColor(): Int = when {
        mode == Mode.STORM -> 0x66404B5A
        mode == Mode.RAIN -> 0x4D5B6B7D
        night -> 0x8010182E.toInt()
        mode == Mode.CLOUDY -> 0x33C9D2DC
        mode == Mode.FOG -> 0x4DE4E7EA
        mode == Mode.PARTLY_DAY -> 0x14FFE7C2
        else -> 0x1AFFE2B0
    }
}
