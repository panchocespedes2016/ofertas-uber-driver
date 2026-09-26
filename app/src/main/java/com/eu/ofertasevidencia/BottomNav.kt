package com.eu.ofertasevidencia

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.widget.TextView

/** Barra inferior compartida: Inicio / Capturas / Config. */
object BottomNav {
    const val HOME = 0
    const val CAPTURES = 1
    const val CONFIG = 2

    fun bind(activity: Activity, selected: Int) {
        val tabs = listOf(
            Triple(R.id.nav_home, R.id.nav_home_label, MainActivity::class.java),
            Triple(R.id.nav_captures, R.id.nav_captures_label, CapturesActivity::class.java),
            Triple(R.id.nav_config, R.id.nav_config_label, ConfigActivity::class.java)
        )
        for ((tabId, labelId, target) in tabs) {
            val tab = activity.findViewById<android.view.View>(tabId) ?: continue
            val label = activity.findViewById<TextView>(labelId)
            val isSelected = when (target) {
                MainActivity::class.java -> selected == HOME
                CapturesActivity::class.java -> selected == CAPTURES
                else -> selected == CONFIG
            }
            label?.apply {
                setTextColor(
                    if (isSelected) activity.getColor(R.color.accent)
                    else activity.getColor(R.color.ink_soft)
                )
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            }
            tab.setOnClickListener {
                if (!isSelected) {
                    activity.startActivity(Intent(activity, target))
                    activity.finish()
                }
            }
        }
    }
}
