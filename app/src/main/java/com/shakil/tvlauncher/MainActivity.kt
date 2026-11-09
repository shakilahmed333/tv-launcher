package com.shakil.tvlauncher

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
    private val net = Executors.newSingleThreadExecutor() // Weather + wallpaper; never delays the app list.
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var usage: Usage
    private lateinit var layoutPrefs: LayoutPrefs
    private lateinit var scroll: ScrollView
    private lateinit var greeting: TextView
    private lateinit var favRow: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var focusLabel: TextView
    private lateinit var wallpaper: ImageView
    private lateinit var weatherFx: WeatherView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherLabel: TextView
    private lateinit var weatherChips: LinearLayout
    private lateinit var netDot: View
    private lateinit var netIcon: ImageView
    private lateinit var netText: TextView

    private var apps: List<AppEntry> = emptyList()
    private var overlay: View? = null
    private var returnFocus: View? = null
    private var lastKey = ""
    private var weatherAt = 0L
    private var shownWallpaper: Bitmap? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, WeatherRepo.REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usage = Usage(this)
        layoutPrefs = LayoutPrefs(this)
        scroll = findViewById(R.id.scroll)
        greeting = findViewById(R.id.greeting)
        favRow = findViewById(R.id.favRow)
        grid = findViewById(R.id.grid)
        focusLabel = findViewById(R.id.focusLabel)
        wallpaper = findViewById(R.id.wallpaper)
        weatherFx = findViewById(R.id.weatherFx)
        weatherIcon = findViewById(R.id.weatherIcon)
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherLabel = findViewById(R.id.weatherLabel)
        weatherChips = findViewById(R.id.weatherChips)
        netDot = findViewById(R.id.netDot)
        netIcon = findViewById(R.id.netIcon)
        netText = findViewById(R.id.netText)

        // Shades that keep text readable on any photo.
        findViewById<View>(R.id.topShade).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x59000000, 0x00000000),
        )
        findViewById<View>(R.id.bottomWash).background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2F7F4F1.toInt(), 0xB3F7F4F1.toInt(), 0x00F7F4F1),
        )
        wallpaper.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF8FB3D9.toInt(), 0xFFE9D8C4.toInt()),
        )

        for (id in intArrayOf(R.id.panelBtn, R.id.settingsBtn)) {
            val b = findViewById<View>(id)
            b.foreground = Ui.focusRing(this, dp(17).toFloat())
            b.setOnFocusChangeListener { v, has -> Ui.animateFocus(this, v, has, 1.12f) }
        }
        findViewById<View>(R.id.panelBtn).setOnClickListener {
            startActivity(Intent(this, ControlPanelActivity::class.java))
        }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        // Fast start: show whatever is already in memory right away.
        WallpaperRepo.bitmap?.let { setWallpaper(it, fade = false) }
        WeatherRepo.cachedInMemory?.let { showWeather(it) }
        AppCache.apps?.let { render(it) }
    }

    override fun onResume() {
        super.onResume()
        updateGreeting()
        weatherFx.start()
        watchNetwork()
        ui.removeCallbacks(weatherTick)
        ui.post(weatherTick)

        io.execute {
            val list = AppRepo.load(this)
            AppCache.apps = list
            ui.post { if (!isDestroyed) render(list) }
        }
        net.execute {
            WallpaperRepo.loadCached(this)?.let { bmp -> ui.post { if (!isDestroyed) setWallpaper(bmp, fade = true) } }
            WallpaperRepo.refreshIfStale(this)?.let { bmp -> ui.post { if (!isDestroyed) setWallpaper(bmp, fade = true) } }
        }
    }

    override fun onPause() {
        ui.removeCallbacks(weatherTick)
        weatherFx.stop()
        unwatchNetwork()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        net.shutdownNow()
        super.onDestroy()
    }

    /** Home pressed while we're already open: jump back to the top row. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closeOverlay()
        scroll.smoothScrollTo(0, 0)
        favRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back only closes menus here.")
    override fun onBackPressed() {
        if (overlay != null) closeOverlay()
    }

    // ---------------------------------------------------------------- wallpaper

    /** Cross-fades to a new photo. */
    private fun setWallpaper(bmp: Bitmap, fade: Boolean) {
        if (bmp === shownWallpaper) return
        shownWallpaper = bmp
        wallpaper.setImageBitmap(bmp)
        if (fade) {
            wallpaper.alpha = 0f
            wallpaper.animate().alpha(1f).setDuration(700).start()
        }
    }

    // ---------------------------------------------------------------- greeting

    private fun updateGreeting() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when {
            h < 5 -> "Good night"
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        greeting.text = "$part, ${getString(R.string.user_name)}"
    }

    // ---------------------------------------------------------------- Wi-Fi status

    /** Live network status: green = online, amber = connected but no internet, red = offline. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        showNetwork(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                ui.post { showNetwork(caps) }
            }

            override fun onLost(network: Network) {
                ui.post { showNetwork(null) }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            netCallback = null
        }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // Already unregistered.
        }
    }

    private fun showNetwork(caps: NetworkCapabilities?) {
        val (color, text) = when {
            caps == null -> getColor(R.color.status_off) to getString(R.string.offline)
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                getColor(R.color.status_warn) to getString(R.string.no_internet)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                getColor(R.color.status_ok) to getString(R.string.ethernet)
            else -> getColor(R.color.status_ok) to getString(R.string.wifi)
        }
        netDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        netIcon.imageTintList = ColorStateList.valueOf(if (caps == null) getColor(R.color.muted) else getColor(R.color.text))
        netText.text = text
        findViewById<View>(R.id.netPill).contentDescription = "Network: $text"
    }

    // ---------------------------------------------------------------- weather

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
        weatherFx.setWeather(w.code, w.isDay)
        weatherIcon.setImageResource(w.iconRes)
        weatherTemp.text = "${w.temp}°"
        weatherLabel.text = w.label

        weatherChips.removeAllViews()
        w.upcoming.forEachIndexed { i, (time, temp) ->
            val chip = TextView(this)
            chip.text = "$time  $temp°"
            chip.textSize = 11f
            chip.setTextColor(getColor(R.color.muted))
            chip.setPadding(dp(9), dp(4), dp(9), dp(4))
            chip.background = Ui.rounded(getColor(R.color.surface), dp(9).toFloat())
            val lp = LinearLayout.LayoutParams(-2, -2)
            if (i > 0) lp.marginStart = dp(6)
            weatherChips.addView(chip, lp)
        }
    }

    // ---------------------------------------------------------------- app rows

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
        val radius = dp(if (big) 14 else 11).toFloat()
        val tile = FrameLayout(this)
        tile.background = Ui.rounded(if (app.banner != null) app.color else getColor(R.color.tile), radius)
        tile.outlineProvider = ViewOutlineProvider.BACKGROUND
        tile.clipToOutline = true
        tile.elevation = dp(3).toFloat()
        tile.isFocusable = true
        tile.isClickable = true
        tile.contentDescription = if (isPinned) "${app.label}, pinned" else app.label
        tile.foreground = Ui.focusRing(this, radius)

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
            label.setTextColor(getColor(R.color.text))
            label.textSize = if (big) 13f else 9f
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.gravity = Gravity.CENTER
            label.setPadding(dp(6), dp(4), dp(6), 0)
            col.addView(label, LinearLayout.LayoutParams(-1, -2))
            tile.addView(col, FrameLayout.LayoutParams(-1, -1))
        }

        // A band of light that sweeps across the tile when it gets focus.
        val shine = View(this)
        shine.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF),
        )
        shine.rotation = 18f
        shine.alpha = 0f
        val shineW = dp(if (big) 70 else 44)
        tile.addView(shine, FrameLayout.LayoutParams(shineW, dp(if (big) 160 else 100), Gravity.CENTER_VERTICAL))

        if (isPinned && big) {
            val dot = View(this)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.accent))
                setStroke(dp(2), Color.WHITE)
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
            Ui.animateFocus(this, v, has, if (big) 1.10f else 1.12f)
            v.elevation = dp(if (has) 14 else 3).toFloat()
            if (has) {
                sweep(shine, v.width.toFloat())
                if (big) showFocusLabel(app.label, v) else hideFocusLabel()
            }
        }
        return tile
    }

    private fun sweep(shine: View, tileWidth: Float) {
        shine.animate().cancel()
        shine.translationX = -shine.width.toFloat() * 1.5f
        shine.alpha = 1f
        shine.animate()
            .translationX(tileWidth + shine.width)
            .setDuration(650)
            .setStartDelay(80)
            .setInterpolator(Ui.ease)
            .withEndAction { shine.alpha = 0f }
            .start()
    }

    /** The focused app's name glides under its tile. */
    private fun showFocusLabel(label: String, tile: View) {
        focusLabel.text = label
        focusLabel.post {
            val target = tile.left + tile.width / 2f - focusLabel.width / 2f
            if (focusLabel.alpha < 0.5f) {
                focusLabel.translationX = target
                focusLabel.translationY = -dp(6).toFloat()
            }
            focusLabel.animate().alpha(1f).translationX(target).translationY(0f)
                .setDuration(220).setInterpolator(Ui.ease).start()
        }
    }

    private fun hideFocusLabel() {
        focusLabel.animate().alpha(0f).setDuration(150).start()
    }

    /** Last tile of the grid when apps are hidden: opens the list to bring them back. */
    private fun makeHiddenTile(count: Int): View {
        val radius = dp(11).toFloat()
        val tile = TextView(this)
        tile.text = "Hidden ($count)"
        tile.textSize = 10f
        tile.gravity = Gravity.CENTER
        tile.setTextColor(getColor(R.color.muted))
        tile.background = Ui.rounded(0x80FFFFFF.toInt(), radius, dp(1), getColor(R.color.line))
        tile.isFocusable = true
        tile.isClickable = true
        tile.foreground = Ui.focusRing(this, radius)
        tile.setOnFocusChangeListener { v, has ->
            Ui.animateFocus(this, v, has, 1.12f)
            if (has) hideFocusLabel()
        }
        tile.setOnClickListener { showHiddenMenu(tile) }
        return tile
    }

    // ---------------------------------------------------------------- hold-OK menu

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
        dim.setBackgroundColor(0x66000000)
        dim.isClickable = true
        dim.alpha = 0f

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(22), dp(20), dp(22), dp(20))
        card.background = Ui.rounded(getColor(R.color.panel), dp(22).toFloat(), dp(1), getColor(R.color.line))
        card.elevation = dp(20).toFloat()

        val heading = TextView(this)
        heading.text = title
        heading.textSize = 18f
        heading.setTextColor(getColor(R.color.text))
        heading.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.translationY = dp(12).toFloat()
        dim.animate().alpha(1f).setDuration(180).start()
        card.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(260).setInterpolator(Ui.slide).start()
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
        b.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(getColor(R.color.accent), radius))
            addState(intArrayOf(), Ui.rounded(getColor(R.color.surface), radius))
            setEnterFadeDuration(150)
            setExitFadeDuration(150)
        }
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(getColor(R.color.on_accent), getColor(R.color.text)),
            ),
        )
        return b
    }

    // ---------------------------------------------------------------- launching

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

    private fun dp(v: Int): Int = Ui.dp(this, v)
}
