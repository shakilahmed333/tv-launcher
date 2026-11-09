package com.shakil.tvlauncher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.view.View
import android.view.animation.PathInterpolator

/** Shared look-and-feel helpers for the control panel and volume bar. */
object Ui {
    val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    val slide = PathInterpolator(0.2f, 0.9f, 0.2f, 1f)

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** Grows and lifts a focused view. */
    fun animateFocus(ctx: Context, v: View, has: Boolean, scale: Float) {
        val s = if (has) scale else 1f
        v.animate()
            .scaleX(s)
            .scaleY(s)
            .translationZ(if (has) dp(ctx, 8).toFloat() else 0f)
            .setDuration(180)
            .setInterpolator(ease)
            .start()
    }

    /** White outline plus a soft sheen, faded in on focus. */
    fun focusRing(ctx: Context, radius: Float): Drawable {
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x40FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
        ).apply { cornerRadius = radius }
        val ring = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(ctx, 2), ctx.getColor(R.color.text))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), LayerDrawable(arrayOf<Drawable>(sheen, ring)))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            setEnterFadeDuration(180)
            setExitFadeDuration(180)
        }
    }

    fun rounded(color: Int, radius: Float, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }
}

/** TV speaker volume through Android's media stream. */
object Volume {
    fun audio(ctx: Context): AudioManager = ctx.getSystemService(AudioManager::class.java)

    /** True when this TV doesn't let apps change the volume (then we leave the TV's own bar alone). */
    fun isFixed(ctx: Context): Boolean = audio(ctx).isVolumeFixed

    fun adjust(ctx: Context, direction: Int) {
        audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        VolumeBus.changed()
    }

    fun percent(ctx: Context): Int {
        val am = audio(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun level(ctx: Context): Int = audio(ctx).getStreamVolume(AudioManager.STREAM_MUSIC)

    fun isMuted(ctx: Context): Boolean {
        val am = audio(ctx)
        return am.isStreamMute(AudioManager.STREAM_MUSIC) || am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }
}

/** Lets an open control panel refresh its slider when the remote changes the volume. */
object VolumeBus {
    var listener: (() -> Unit)? = null

    fun changed() {
        listener?.invoke()
    }
}
