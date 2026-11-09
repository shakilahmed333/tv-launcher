package com.shakil.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The custom volume bar: a small pill at the top of the screen, shown over any app.
 * Slides in, the fill glides to the new level, and it fades out after 1.8 s.
 */
class VolumeHud(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { hide() }

    private var root: LinearLayout? = null
    private lateinit var icon: ImageView
    private lateinit var fill: View
    private lateinit var number: TextView
    private var shown = false

    private fun dp(v: Int) = Ui.dp(service, v)

    fun show(percent: Int, muted: Boolean) {
        val view = root ?: build()
        val shownPct = if (muted) 0 else percent.coerceIn(0, 100)

        icon.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        number.text = if (muted) "Muted" else shownPct.toString()
        fill.animate().scaleX(shownPct / 100f).setDuration(160).setInterpolator(Ui.ease).start()

        if (!shown) {
            shown = true
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = -dp(16).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Ui.ease).start()
        }
        handler.removeCallbacks(hideLater)
        handler.postDelayed(hideLater, 1800)
    }

    private fun hide() {
        val view = root ?: return
        shown = false
        view.animate().alpha(0f).translationY(-dp(16).toFloat()).setDuration(200).withEndAction {
            if (!shown) view.visibility = View.INVISIBLE
        }.start()
    }

    fun destroy() {
        handler.removeCallbacks(hideLater)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun build(): LinearLayout {
        val pill = LinearLayout(service)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(dp(14), 0, dp(16), 0)
        pill.background = Ui.rounded(
            service.getColor(R.color.panel), dp(20).toFloat(), dp(1), service.getColor(R.color.line),
        )
        pill.elevation = dp(8).toFloat()

        icon = ImageView(service)
        pill.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)))

        val track = FrameLayout(service)
        track.background = Ui.rounded(0x1F000000, dp(3).toFloat())
        fill = View(service)
        fill.background = Ui.rounded(service.getColor(R.color.accent), dp(3).toFloat())
        fill.pivotX = 0f
        fill.scaleX = 0f
        track.addView(fill, FrameLayout.LayoutParams(-1, -1))
        val trackLp = LinearLayout.LayoutParams(dp(170), dp(6))
        trackLp.marginStart = dp(12)
        trackLp.marginEnd = dp(12)
        pill.addView(track, trackLp)

        number = TextView(service)
        number.setTextColor(service.getColor(R.color.text))
        number.textSize = 13f
        number.minWidth = dp(26)
        number.gravity = Gravity.END
        pill.addView(number, LinearLayout.LayoutParams(-2, -2))

        val lp = WindowManager.LayoutParams(
            -2,
            dp(40),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(24)

        pill.visibility = View.INVISIBLE
        wm.addView(pill, lp)
        root = pill
        return pill
    }
}
