package com.shakil.tvlauncher

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * Slide-in control panel: volume, network, remotes, picture & sound, apps, all settings.
 * Opened by holding Home on the remote, or from the home screen's top bar.
 *
 * Tiles open the TV's own setting pages: Android doesn't let normal apps switch
 * Wi-Fi or Bluetooth on and off directly.
 */
class ControlPanelActivity : Activity() {

    private lateinit var dim: View
    private lateinit var panel: View
    private lateinit var volFill: View
    private lateinit var volValue: TextView
    private var closing = false

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control_panel)

        dim = findViewById(R.id.dim)
        panel = findViewById(R.id.panel)
        panel.background = Ui.rounded(getColor(R.color.panel), dp(20).toFloat(), dp(1), getColor(R.color.line))
        panel.elevation = dp(16).toFloat()

        setUpVolume()
        setUpTile(R.id.tileNetwork, R.drawable.ic_wifi, getString(R.string.network), networkStatus()) {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        setUpTile(R.id.tileRemotes, R.drawable.ic_bluetooth, getString(R.string.remotes), getString(R.string.remotes_sub)) {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        setUpTile(R.id.tileDisplay, R.drawable.ic_display, getString(R.string.picture_sound), getString(R.string.picture_sound_sub)) {
            Intent(Settings.ACTION_DISPLAY_SETTINGS)
        }
        setUpTile(R.id.tileApps, R.drawable.ic_apps, getString(R.string.apps), getString(R.string.apps_sub)) {
            Intent(Settings.ACTION_APPLICATION_SETTINGS)
        }

        val all = findViewById<View>(R.id.allSettings)
        all.background = Ui.rounded(getColor(R.color.surface), dp(20).toFloat())
        all.foreground = Ui.focusRing(this, dp(20).toFloat())
        all.setOnFocusChangeListener { v, has -> Ui.animateFocus(this, v, has, 1.04f) }
        all.setOnClickListener { openSetting(Intent(Settings.ACTION_SETTINGS)) }

        // Slide in from the right while the screen behind dims.
        dim.alpha = 0f
        panel.translationX = dp(340).toFloat()
        dim.animate().alpha(1f).setDuration(240).start()
        panel.animate().translationX(0f).setDuration(360).setInterpolator(Ui.slide).start()
    }

    override fun onResume() {
        super.onResume()
        VolumeBus.listener = { runOnUiThread { updateVolume(animate = true) } }
        updateVolume(animate = false)
    }

    override fun onPause() {
        VolumeBus.listener = null
        super.onPause()
    }

    private fun setUpVolume() {
        volFill = findViewById(R.id.volFill)
        volValue = findViewById(R.id.volumeValue)
        findViewById<View>(R.id.volTrack).background = Ui.rounded(0x1F000000, dp(4).toFloat())
        volFill.background = Ui.rounded(getColor(R.color.accent), dp(4).toFloat())
        volFill.pivotX = 0f

        val down = findViewById<View>(R.id.volDown)
        val up = findViewById<View>(R.id.volUp)
        for (b in listOf(down, up)) {
            b.background = Ui.rounded(getColor(R.color.surface), dp(15).toFloat())
            b.foreground = Ui.focusRing(this, dp(15).toFloat())
            b.setOnFocusChangeListener { v, has -> Ui.animateFocus(this, v, has, 1.15f) }
        }
        down.setOnClickListener { Volume.adjust(this, AudioManager.ADJUST_LOWER) }
        up.setOnClickListener { Volume.adjust(this, AudioManager.ADJUST_RAISE) }

        if (Volume.isFixed(this)) {
            findViewById<View>(R.id.volumeControls).visibility = View.GONE
            findViewById<View>(R.id.volumeFixedNote).visibility = View.VISIBLE
        } else {
            up.requestFocus()
        }
    }

    private fun updateVolume(animate: Boolean) {
        if (Volume.isFixed(this)) {
            volValue.text = ""
            return
        }
        val muted = Volume.isMuted(this)
        val pct = if (muted) 0 else Volume.percent(this)
        volValue.text = if (muted) getString(R.string.muted) else Volume.level(this).toString()
        if (animate) {
            volFill.animate().scaleX(pct / 100f).setDuration(160).setInterpolator(Ui.ease).start()
        } else {
            volFill.scaleX = pct / 100f
        }
    }

    private fun setUpTile(id: Int, icon: Int, title: String, sub: String, intent: () -> Intent) {
        val tile = findViewById<View>(id)
        tile.background = Ui.rounded(getColor(R.color.surface), dp(14).toFloat())
        tile.foreground = Ui.focusRing(this, dp(14).toFloat())
        tile.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
        tile.findViewById<TextView>(R.id.tileTitle).text = title
        tile.findViewById<TextView>(R.id.tileSub).text = sub
        tile.contentDescription = "$title, $sub"
        tile.setOnFocusChangeListener { v, has -> Ui.animateFocus(this, v, has, 1.06f) }
        tile.setOnClickListener { openSetting(intent()) }
    }

    private fun networkStatus(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return getString(R.string.offline)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.wifi_connected)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> getString(R.string.ethernet_connected)
            else -> getString(R.string.connected)
        }
    }

    private fun openSetting(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** Back: slide out, then close. */
    @Deprecated("Back closes the panel with an animation.")
    override fun onBackPressed() {
        if (closing) return
        closing = true
        dim.animate().alpha(0f).setDuration(220).start()
        panel.animate().translationX(dp(340).toFloat()).setDuration(240).setInterpolator(Ui.ease)
            .withEndAction {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }.start()
    }
}
