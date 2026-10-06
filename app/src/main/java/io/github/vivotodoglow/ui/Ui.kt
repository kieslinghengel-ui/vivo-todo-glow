package io.github.vivotodoglow.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import io.github.vivotodoglow.data.TodoTask
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val INK = Color.rgb(9, 15, 28)
val CARD = Color.rgb(20, 33, 51)
val GLOW = Color.rgb(151, 239, 255)
val MUTED = Color.rgb(153, 175, 199)
fun Context.dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()
fun Context.dp(value: Int) = dp(value.toFloat())
fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    stroke?.let { setStroke(1, it) }
}

/** A restrained glass-like card; the bright edge belongs to the card, not a shadow bitmap. */
fun Context.glowCard(radius: Int = 15, highlighted: Boolean = false) = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(Color.argb(244, 29, 48, 69), Color.argb(240, 15, 25, 43))
).apply {
    cornerRadius = dp(radius).toFloat()
    setStroke(dp(1), if (highlighted) Color.argb(155, 150, 238, 255) else Color.argb(42, 151, 239, 255))
}

fun taskSubtitle(task: TodoTask, today: LocalDate = LocalDate.now()): String = buildList {
    if (task.isDaily) add("每日任务")
    task.dueDate?.let { epochDay ->
        val due = LocalDate.ofEpochDay(epochDay)
        add(when {
            due.isBefore(today) -> "已逾期 · ${due.format(DateTimeFormatter.ofPattern("M月d日"))}"
            due == today -> "今天截止"
            due == today.plusDays(1) -> "明天截止"
            else -> "${due.format(DateTimeFormatter.ofPattern("M月d日"))}截止"
        })
    }
}.joinToString("  ·  ")

fun Context.label(text: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color)
    includeFontPadding = false
}
fun Context.action(text: String, click: () -> Unit) = Button(this).apply {
    this.text = text; textSize = 13f; isAllCaps = false; setTextColor(GLOW)
    minWidth = 0; minimumWidth = 0; minHeight = dp(48); minimumHeight = dp(48)
    setPadding(dp(10), 0, dp(10), 0)
    background = glowCard(12)
    setOnClickListener { click() }
}
fun LinearLayout.space(height: Int) { addView(View(context), LinearLayout.LayoutParams(1, context.dp(height))) }
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
