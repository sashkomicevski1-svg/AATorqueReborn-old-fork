package com.aatorque.utils

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View

/**
 * Makes a view blink for an alarm rule that asks for it: its opacity swings between full and
 * low, about once a second. Stopping restores full opacity.
 */
class Blinker(private val target: View) {
    private var animator: ObjectAnimator? = null

    fun set(on: Boolean) {
        if (on == (animator != null)) return
        if (on) {
            animator = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, LOW_ALPHA).apply {
                duration = HALF_PERIOD_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            animator?.cancel()
            animator = null
            target.alpha = 1f
        }
    }

    companion object {
        const val HALF_PERIOD_MS = 450L
        const val LOW_ALPHA = 0.15f
    }
}
