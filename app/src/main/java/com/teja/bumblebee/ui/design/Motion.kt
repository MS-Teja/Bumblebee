package com.teja.bumblebee.ui.design

import android.animation.TimeInterpolator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.teja.bumblebee.data.Prefs

/** Motion presets. Everything animates transform/alpha only, so the weak GPU composites it cheaply. */
object Motion {
    val standard: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val emphasized: TimeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val exit: TimeInterpolator = PathInterpolator(0.3f, 0f, 1f, 1f)

    val reduced: Boolean get() = Prefs.reduceMotion
    fun ms(normal: Long): Long = if (reduced) minOf(normal, 150L) else normal

    /** Springy press feedback: shrink on touch, overshoot back on release. */
    fun pressScale(view: View, down: Boolean, pressed: Float = 0.92f) {
        val target = if (down) pressed else 1f
        listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y).forEach { prop ->
            val key = if (prop == DynamicAnimation.SCALE_X) com.teja.bumblebee.R.id.spring_sx else com.teja.bumblebee.R.id.spring_sy
            val anim = (view.getTag(key) as? SpringAnimation) ?: SpringAnimation(view, prop).apply {
                spring = SpringForce().setDampingRatio(0.55f).setStiffness(900f)
                view.setTag(key, this)
            }
            anim.spring.dampingRatio = if (down) 1f else 0.5f
            anim.spring.stiffness = if (down) 1500f else 700f
            anim.animateToFinalPosition(target)
        }
    }
}
