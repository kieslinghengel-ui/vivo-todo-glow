package io.github.vivotodoglow.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

val INK = Color.rgb(11, 20, 32)
val CARD = Color.rgb(23, 38, 52)
val GLOW = Color.rgb(145, 236, 244)
val MUTED = Color.rgb(155, 177, 195)
fun Context.dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()
fun Context.dp(value: Int) = dp(value.toFloat())
fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    stroke?.let { setStroke(1, it) }
}
fun Context.label(text: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color)
}
fun Context.action(text: String, click: () -> Unit) = Button(this).apply {
    this.text = text; textSize = 13f; isAllCaps = false; setTextColor(GLOW)
    minWidth = 0; minimumWidth = 0; minHeight = dp(48); minimumHeight = dp(48)
    setPadding(dp(10), 0, dp(10), 0)
    background = rounded(CARD, dp(12).toFloat())
    setOnClickListener { click() }
}
fun LinearLayout.space(height: Int) { addView(View(context), LinearLayout.LayoutParams(1, context.dp(height))) }
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
