package com.shakil.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * On some Google TV models the Home button only opens Google's own launcher.
 * This service catches the Home key first and opens our launcher instead.
 * Later phases reuse it for the custom volume bar.
 *
 * Enable once with ADB:
 *   adb shell settings put secure enabled_accessibility_services com.shakil.tvlauncher/com.shakil.tvlauncher.HomeButtonService
 *   adb shell settings put secure accessibility_enabled 1
 */
class HomeButtonService : AccessibilityService() {

    override fun onServiceConnected() {
        Log.i(TAG, "Service connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Logged so we can see what the TCL remote sends: adb logcat -s HomeKey
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d(TAG, "key down: ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode})")
        }
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false

        if (event.action == KeyEvent.ACTION_UP) {
            Log.i(TAG, "Home pressed: opening launcher")
            openLauncher()
        }
        return true // Stop the system from also handling Home.
    }

    private fun openLauncher() {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open launcher", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used yet.
    }

    override fun onInterrupt() {
        // Nothing to stop.
    }

    companion object {
        private const val TAG = "HomeKey"
    }
}
