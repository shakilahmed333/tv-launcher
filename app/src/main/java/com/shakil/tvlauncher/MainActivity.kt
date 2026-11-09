package com.shakil.tvlauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private lateinit var usage: Usage
    private lateinit var greeting: TextView
    private lateinit var favRow: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var previewName: TextView

    private val panelBg = GradientDrawable()
    private val glowBg = GradientDrawable()
    private var panelColor = 0
    private var glowColor = 0
    private var panelAnim: ValueAnimator? = null
    private var glowAnim: ValueAnimator? = null

    private var lastKey = ""
    private var textColor = 0
    private var tileColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usage = Usage(this)
        textColor = getColor(R.color.text)
        tileColor = getColor(R.color.tile)
        greeting = findViewById(R.id.greeting)
        favRow = findViewById(R.id.favRow)
        grid = findViewById(R.id.grid)
        previewName = findViewById(R.id.previewName)

        panelColor = tileColor
        panelBg.cornerRadius = dp(16).toFloat()
        panelBg.setColor(panelColor)
        val panel = findViewById<FrameLayout>(R.id.previewPanel)
        panel.background = panelBg
        panel.outlineProvider = ViewOutlineProvider.BACKGROUND
        panel.clipToOutline = true

        glowColor = Palette.withAlpha(getColor(R.color.accent), 0x30)
        glowBg.gradientType = GradientDrawable.RADIAL_GRADIENT
        glowBg.gradientRadius = dp(320).toFloat()
        glowBg.colors = intArrayOf(glowColor, Color.TRANSPARENT)
        findViewById<View>(R.id.glow).background = glowBg

        val settings = findViewById<View>(R.id.settingsBtn)
        settings.foreground = focusRing(dp(17).toFloat())
        settings.setOnFocusChangeListener { v, has -> animateFocus(v, has, 1.12f) }
        settings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onResume() {
        super.onResume()
        updateGreeting()
        io.execute {
            val apps = AppRepo.load(this)
            ui.post { if (!isDestroyed) render(apps) }
        }
    }

    /** Home pressed while we're already open: jump back to the top row. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        findViewById<ScrollView>(R.id.scroll).smoothScrollTo(0, 0)
        favRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back does nothing here.")
    override fun onBackPressed() {
        // Stay on the home screen.
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun updateGreeting() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when {
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        greeting.text = "$part, ${getString(R.string.user_name)}"
    }

    /** Builds the two rows. Skips the work if nothing changed since last time. */
    private fun render(apps: List<AppEntry>) {
        val favs = apps
            .sortedWith(compareByDescending<AppEntry> { usage.count(it.pkg) }.thenBy { it.label.lowercase() })
            .take(5)
        val key = favs.joinToString(",") { it.pkg } + "|" + apps.joinToString(",") { it.pkg }
        if (key == lastKey) return
        lastKey = key

        favRow.removeAllViews()
        favs.forEachIndexed { i, app ->
            val lp = LinearLayout.LayoutParams(dp(160), dp(90))
            if (i < favs.size - 1) lp.marginEnd = dp(16)
            favRow.addView(makeTile(app, big = true), lp)
        }

        grid.removeAllViews()
        apps.forEachIndexed { i, app ->
            val lp = GridLayout.LayoutParams()
            lp.width = dp(96)
            lp.height = dp(54)
            lp.setMargins(0, 0, if (i % 8 != 7) dp(12) else 0, dp(12))
            grid.addView(makeTile(app, big = false), lp)
        }

        favRow.getChildAt(0)?.requestFocus()
    }

    private fun makeTile(app: AppEntry, big: Boolean): View {
        val radius = dp(if (big) 12 else 10).toFloat()
        val tile = FrameLayout(this)
        tile.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (app.banner != null) app.color else tileColor)
        }
        tile.outlineProvider = ViewOutlineProvider.BACKGROUND
        tile.clipToOutline = true
        tile.isFocusable = true
        tile.isClickable = true
        tile.contentDescription = app.label
        tile.foreground = focusRing(radius)

        val banner = copyOf(app.banner)
        if (banner != null) {
            val img = ImageView(this)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setImageDrawable(banner)
            tile.addView(img, FrameLayout.LayoutParams(-1, -1))
        } else {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val iconSize = dp(if (big) 36 else 22)
            val icon = ImageView(this)
            icon.setImageDrawable(copyOf(app.icon))
            col.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            val label = TextView(this)
            label.text = app.label
            label.setTextColor(textColor)
            label.textSize = if (big) 13f else 9f
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.gravity = Gravity.CENTER
            label.setPadding(dp(6), dp(4), dp(6), 0)
            col.addView(label, LinearLayout.LayoutParams(-1, -2))
            tile.addView(col, FrameLayout.LayoutParams(-1, -1))
        }

        tile.setOnClickListener { launch(app) }
        tile.setOnFocusChangeListener { v, has ->
            animateFocus(v, has, if (big) 1.08f else 1.10f)
            if (has) showPreview(app)
        }
        return tile
    }

    /** Focused: grows, lifts and casts a shadow. 180 ms, ease-out. */
    private fun animateFocus(v: View, has: Boolean, scale: Float) {
        val s = if (has) scale else 1f
        v.animate()
            .scaleX(s)
            .scaleY(s)
            .translationZ(if (has) dp(10).toFloat() else 0f)
            .setDuration(180)
            .setInterpolator(ease)
            .start()
    }

    /** White outline plus a soft light sheen, faded in when the tile is focused. */
    private fun focusRing(radius: Float): Drawable {
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x40FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
        ).apply { cornerRadius = radius }
        val ring = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), textColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), LayerDrawable(arrayOf<Drawable>(sheen, ring)))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            setEnterFadeDuration(180)
            setExitFadeDuration(180)
        }
    }

    private fun showPreview(app: AppEntry) {
        previewName.text = app.label

        val panelTarget = Palette.blend(app.color, Color.BLACK, 0.35f)
        panelAnim?.cancel()
        panelAnim = ValueAnimator.ofArgb(panelColor, panelTarget).apply {
            duration = 300
            addUpdateListener {
                panelColor = it.animatedValue as Int
                panelBg.setColor(panelColor)
            }
            start()
        }

        val glowTarget = Palette.withAlpha(app.color, 0x3A)
        glowAnim?.cancel()
        glowAnim = ValueAnimator.ofArgb(glowColor, glowTarget).apply {
            duration = 400
            addUpdateListener {
                glowColor = it.animatedValue as Int
                glowBg.colors = intArrayOf(glowColor, Color.TRANSPARENT)
            }
            start()
        }
    }

    private fun launch(app: AppEntry) {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg)
            ?: pm.getLaunchIntentForPackage(app.pkg)
            ?: return
        usage.bump(app.pkg)
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // App could not be opened; stay on the home screen.
        }
    }

    /** Each tile needs its own drawable instance, even when two tiles show the same app. */
    private fun copyOf(d: Drawable?): Drawable? = d?.constantState?.newDrawable(resources)?.mutate() ?: d

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
