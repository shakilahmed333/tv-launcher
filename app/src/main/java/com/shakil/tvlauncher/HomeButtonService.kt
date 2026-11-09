package com.shakil.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Runs in the background (very small) and handles the remote:
 *  - Home: opens our launcher (this TV otherwise only opens Google's).
 *  - Hold Home: opens the control panel.
 *  - Volume keys: changes the volume and shows our own volume bar, in any app.
 * Also brings our launcher back whenever Google's comes to the front.
 *
 * Enable once with ADB:
 *   adb shell settings put secure enabled_accessibility_services com.shakil.tvlauncher/com.shakil.tvlauncher.HomeButtonService
 *   adb shell settings put secure accessibility_enabled 1
 */
class HomeButtonService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var hud: VolumeHud? = null
    private var lastRedirect = 0L

    private var homeHeld = false
    private val homeLongPress = Runnable {
        homeHeld = true
        Log.i(TAG, "Home held: opening control panel")
        openPanel()
    }

    /** Runs when the TV finishes starting up: show our launcher instead of Google's. */
    override fun onServiceConnected() {
        Log.i(TAG, "Service connected: opening launcher. Volume fixed=${Volume.isFixed(this)}")
        hud = VolumeHud(this)
        openLauncher()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Logged so we can see what the TCL remote sends: adb logcat -s HomeKey
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.d(TAG, "key down: ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode})")
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_HOME -> handleHome(event)
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            -> handleVolume(event)
            else -> false
        }
    }

    /** Short press opens the launcher; holding for 0.6 s opens the control panel. */
    private fun handleHome(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            homeHeld = false
            handler.postDelayed(homeLongPress, 600)
        } else if (event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(homeLongPress)
            if (!homeHeld) {
                Log.i(TAG, "Home pressed: opening launcher")
                openLauncher()
            }
        }
        return true // Stop the system from also handling Home.
    }

    private fun handleVolume(event: KeyEvent): Boolean {
        // Some TVs control the speaker volume themselves; then leave their own bar alone.
        if (Volume.isFixed(this)) return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                else -> {
                    if (event.repeatCount > 0) return true // Holding mute: toggle once only.
                    AudioManager.ADJUST_TOGGLE_MUTE
                }
            }
            Volume.adjust(this, direction)
            hud?.show(Volume.percent(this), Volume.isMuted(this))
        }
        return true // Our bar replaces the TV's default volume pop-up.
    }

    private fun openLauncher() {
        // Opened as a real "home" intent (not by class name), so Android marks
        // our launcher as the home app and lets Google's drop to the background.
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(packageName)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        start(intent)
    }

    private fun openPanel() {
        start(Intent(this, ControlPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open ${intent.component ?: intent}", e)
        }
    }

    /**
     * This TV opens Google's launcher by itself (at startup, and sometimes on Back).
     * Whenever it comes to the front, switch straight to ours.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != GOOGLE_LAUNCHER) return

        val now = SystemClock.uptimeMillis()
        if (now - lastRedirect < 1500) return // Avoid bouncing back and forth.
        lastRedirect = now
        Log.i(TAG, "Google launcher came to the front: switching to ours")
        openLauncher()
    }

    override fun onInterrupt() {
        // Nothing to stop.
    }

    override fun onDestroy() {
        handler.removeCallbacks(homeLongPress)
        hud?.destroy()
        hud = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HomeKey"
        private const val GOOGLE_LAUNCHER = "com.google.android.apps.tv.launcherx"
    }
}
