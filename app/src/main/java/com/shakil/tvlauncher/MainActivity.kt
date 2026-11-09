package com.shakil.tvlauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
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
import android.view.ViewGroup
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
    private val net = Executors.newSingleThreadExecutor() // Weather; never delays the app list.
    private val ui = Handler(Looper.getMainLooper())
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private lateinit var usage: Usage
    private lateinit var layoutPrefs: LayoutPrefs
    private lateinit var scroll: ScrollView
    private var apps: List<AppEntry> = emptyList()
    private var overlay: View? = null
    private var returnFocus: View? = null
    private lateinit var greeting: TextView
    private lateinit var favRow: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var previewName: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherLabel: TextView
    private lateinit var weatherChips: LinearLayout
    private var weatherAt = 0L
    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, WeatherRepo.REFRESH_MS)
        }
    }

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
        layoutPrefs = LayoutPrefs(this)
        scroll = findViewById(R.id.scroll)
        textColor = getColor(R.color.text)
        tileColor = getColor(R.color.tile)
        greeting = findViewById(R.id.greeting)
        favRow = findViewById(R.id.favRow)
        grid = findViewById(R.id.grid)
        previewName = findViewById(R.id.previewName)
        weatherIcon = findViewById(R.id.weatherIcon)
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherLabel = findViewById(R.id.weatherLabel)
        weatherChips = findViewById(R.id.weatherChips)

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
        ui.removeCallbacks(weatherTick)
        ui.post(weatherTick)
        io.execute {
            val apps = AppRepo.load(this)
            ui.post { if (!isDestroyed) render(apps) }
        }
    }

    override fun onPause() {
        ui.removeCallbacks(weatherTick)
        super.onPause()
    }

    /** Shows the cached weather at once, then fetches fresh data if it's over 30 minutes old. */
    private fun refreshWeather() {
        net.execute {
            val cached = WeatherRepo.cached(this)
            if (cached != null) ui.post { if (!isDestroyed) showWeather(cached) }
            val stale = cached == null || System.currentTimeMillis() - cached.fetchedAt > WeatherRepo.REFRESH_MS
            if (stale) {
                val fresh = WeatherRepo.fetch(this)
                if (fresh != null) ui.post { if (!isDestroyed) showWeather(fresh) }
            }
        }
    }

    private fun showWeather(w: Weather) {
        if (w.fetchedAt == weatherAt) return
        weatherAt = w.fetchedAt
        weatherIcon.setImageResource(w.iconRes)
        weatherTemp.text = "${w.temp}°"
        weatherLabel.text = "${w.label} · ${getString(R.string.city_name)}"

        weatherChips.removeAllViews()
        w.upcoming.forEachIndexed { i, (time, temp) ->
            val chip = TextView(this)
            chip.text = "$time  $temp°"
            chip.textSize = 10f
            chip.setTextColor(getColor(R.color.muted))
            chip.setPadding(dp(8), dp(4), dp(8), dp(4))
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(getColor(R.color.surface))
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            if (i > 0) lp.marginStart = dp(6)
            weatherChips.addView(chip, lp)
        }
    }

    /** Home pressed while we're already open: jump back to the top row. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closeOverlay()
        scroll.smoothScrollTo(0, 0)
        favRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back does nothing here.")
    override fun onBackPressed() {
        // Close a menu if one is open; otherwise stay on the home screen.
        if (overlay != null) closeOverlay()
    }

    override fun onDestroy() {
        io.shutdownNow()
        net.shutdownNow()
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
    private fun render(all: List<AppEntry>) {
        apps = all
        val hidden = layoutPrefs.hidden()
        val pinned = layoutPrefs.pinned()
        val visible = all.filter { it.pkg !in hidden }

        // Pinned apps first (in the order they were pinned), then the most opened.
        val pinnedApps = pinned.mapNotNull { pkg -> visible.find { it.pkg == pkg } }
        val rest = visible
            .filter { it.pkg !in pinned }
            .sortedWith(compareByDescending<AppEntry> { usage.count(it.pkg) }.thenBy { it.label.lowercase() })
        val favs = (pinnedApps + rest).take(5)

        val key = favs.joinToString(",") { it.pkg } + "|" + visible.joinToString(",") { it.pkg } +
            "|" + pinned.joinToString(",") + "|" + hidden.sorted().joinToString(",")
        if (key == lastKey) return
        lastKey = key

        favRow.removeAllViews()
        favs.forEachIndexed { i, app ->
            val lp = LinearLayout.LayoutParams(dp(160), dp(90))
            if (i < favs.size - 1) lp.marginEnd = dp(16)
            favRow.addView(makeTile(app, big = true, isPinned = app.pkg in pinned), lp)
        }

        grid.removeAllViews()
        visible.forEach { app -> grid.addView(makeTile(app, big = false, isPinned = app.pkg in pinned), gridParams()) }
        if (hidden.isNotEmpty()) grid.addView(makeHiddenTile(hidden.size), gridParams())

        if (overlay == null) favRow.getChildAt(0)?.requestFocus()
    }

    private fun gridParams(): GridLayout.LayoutParams {
        val i = grid.childCount
        val lp = GridLayout.LayoutParams()
        lp.width = dp(96)
        lp.height = dp(54)
        lp.setMargins(0, 0, if (i % 8 != 7) dp(12) else 0, dp(12))
        return lp
    }

    /** Forces a rebuild after pinning or hiding. */
    private fun rerender() {
        lastKey = ""
        render(apps)
    }

    private fun makeTile(app: AppEntry, big: Boolean, isPinned: Boolean): View {
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
        tile.contentDescription = if (isPinned) "${app.label}, pinned" else app.label
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

        if (isPinned && big) {
            val dot = View(this)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.accent))
                setStroke(dp(2), getColor(R.color.bg))
            }
            val lp = FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.END)
            lp.setMargins(0, dp(8), dp(8), 0)
            tile.addView(dot, lp)
        }

        tile.setOnClickListener { launch(app) }
        tile.setOnLongClickListener {
            showAppMenu(app, isPinned, tile)
            true
        }
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

    /** Last tile of the grid when apps are hidden: opens the list to bring them back. */
    private fun makeHiddenTile(count: Int): View {
        val radius = dp(10).toFloat()
        val tile = TextView(this)
        tile.text = "Hidden ($count)"
        tile.textSize = 10f
        tile.gravity = Gravity.CENTER
        tile.setTextColor(getColor(R.color.muted))
        tile.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), getColor(R.color.line))
        }
        tile.isFocusable = true
        tile.isClickable = true
        tile.foreground = focusRing(radius)
        tile.setOnFocusChangeListener { v, has -> animateFocus(v, has, 1.10f) }
        tile.setOnClickListener { showHiddenMenu(tile) }
        return tile
    }

    /** Hold OK on a tile: pin / unpin, or hide. */
    private fun showAppMenu(app: AppEntry, isPinned: Boolean, from: View) {
        val pinLabel = if (isPinned) "Unpin from Most used" else "Pin to Most used"
        showOverlay(
            app.label,
            listOf(
                pinLabel to {
                    layoutPrefs.togglePin(app.pkg)
                    rerender()
                },
                "Hide app" to {
                    layoutPrefs.hide(app.pkg)
                    rerender()
                },
                "Cancel" to {},
            ),
            from,
        )
    }

    private fun showHiddenMenu(from: View) {
        val hidden = layoutPrefs.hidden()
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        apps.filter { it.pkg in hidden }.forEach { app ->
            buttons.add(
                "Show ${app.label}" to {
                    layoutPrefs.unhide(app.pkg)
                    rerender()
                },
            )
        }
        buttons.add("Close" to {})
        showOverlay("Hidden apps", buttons, from)
    }

    /** A small centred menu that fades and scales in over a dimmed screen. */
    private fun showOverlay(title: String, buttons: List<Pair<String, () -> Unit>>, from: View?) {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        returnFocus = from

        val dim = FrameLayout(this)
        dim.setBackgroundColor(0x99000000.toInt())
        dim.isClickable = true
        dim.alpha = 0f

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(22), dp(20), dp(22), dp(20))
        card.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(getColor(R.color.panel))
            setStroke(dp(1), getColor(R.color.line))
        }

        val heading = TextView(this)
        heading.text = title
        heading.textSize = 18f
        heading.setTextColor(textColor)
        heading.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        heading.maxLines = 1
        heading.ellipsize = TextUtils.TruncateAt.END
        card.addView(heading, LinearLayout.LayoutParams(-1, -2))

        var first: View? = null
        for ((label, action) in buttons) {
            val b = menuButton(label)
            b.setOnClickListener {
                closeOverlay()
                action()
            }
            val lp = LinearLayout.LayoutParams(-1, dp(44))
            lp.topMargin = dp(8)
            card.addView(b, lp)
            if (first == null) first = b
        }

        dim.addView(card, FrameLayout.LayoutParams(dp(320), -2, Gravity.CENTER))
        findViewById<ViewGroup>(android.R.id.content).addView(dim, ViewGroup.LayoutParams(-1, -1))
        overlay = dim

        // Keep the remote's focus inside the menu.
        scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        first?.requestFocus()

        card.scaleX = 0.94f
        card.scaleY = 0.94f
        dim.animate().alpha(1f).setDuration(180).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(ease).start()
    }

    private fun closeOverlay() {
        val o = overlay ?: return
        overlay = null
        scroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        o.animate().alpha(0f).setDuration(140).withEndAction {
            (o.parent as? ViewGroup)?.removeView(o)
        }.start()
        val back = returnFocus
        returnFocus = null
        if (back != null && back.isAttachedToWindow) back.requestFocus() else favRow.getChildAt(0)?.requestFocus()
    }

    private fun menuButton(text: String): TextView {
        val radius = dp(12).toFloat()
        val b = TextView(this)
        b.text = text
        b.textSize = 14f
        b.gravity = Gravity.CENTER_VERTICAL
        b.setPadding(dp(16), 0, dp(16), 0)
        b.isFocusable = true
        b.isClickable = true
        b.background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(getColor(R.color.accent))
                },
            )
            addState(
                intArrayOf(),
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(getColor(R.color.surface))
                },
            )
            setEnterFadeDuration(150)
            setExitFadeDuration(150)
        }
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(getColor(R.color.on_accent), textColor),
            ),
        )
        return b
    }

    /** Each tile needs its own drawable instance, even when two tiles show the same app. */
    private fun copyOf(d: Drawable?): Drawable? = d?.constantState?.newDrawable(resources)?.mutate() ?: d

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
