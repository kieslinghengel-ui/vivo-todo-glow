package io.github.vivotodoglow.ui

import android.content.Context
import android.animation.LayoutTransition
import android.widget.*
import io.github.vivotodoglow.GlowApp
import io.github.vivotodoglow.data.*
import kotlinx.coroutines.launch

class TaskListView(context: Context, private val panel: Boolean, private val edit: (TodoTask) -> Unit) : LinearLayout(context) {
    private val app = context.applicationContext as GlowApp
    private var latest = emptyList<TodoTask>()
    private var key = ""
    init {
        orientation = VERTICAL
        layoutTransition = LayoutTransition().apply { setDuration(200) }
    }
    fun render(tasks: List<TodoTask>) {
        latest = tasks
        if ((0 until childCount).any { (getChildAt(it) as? TaskRow)?.isDissolving == true }) return
        val visible = if (panel) TaskPolicy.panel(tasks) else TaskPolicy.active(tasks)
        val nextKey = visible.joinToString { "${it.id}:${it.sortOrder}:${it.title}" }
        if (key == nextKey && childCount > 0) return
        key = nextKey
        if (visible.isEmpty()) {
            removeAllViews()
            addView(context.label("今天的空间，留给重要的事。\n点击 ＋ 添加一个待办", 14f, MUTED).apply {
                setPadding(context.dp(16), context.dp(24), context.dp(16), context.dp(24))
                gravity = android.view.Gravity.CENTER; setLineSpacing(context.dp(6).toFloat(), 1f)
            })
            return
        }
        for (index in childCount - 1 downTo 0) {
            val row = getChildAt(index) as? TaskRow
            if (row == null || visible.none { it.id == row.task.id && it.title == row.task.title }) removeViewAt(index)
        }
        visible.forEachIndexed { index, task ->
            val existing = (0 until childCount).map { getChildAt(it) }.filterIsInstance<TaskRow>()
                .find { it.task.id == task.id && it.task.title == task.title }
            if (existing != null) {
                if (indexOfChild(existing) != index) { removeView(existing); addView(existing, index) }
                return@forEachIndexed
            }
            val row = TaskRow(context, task, { edit(task) }) { view ->
                app.scope.launch {
                    try {
                        if (app.repository.remove(task.id)) view.dissolve { key = ""; render(latest) }
                        else { view.cancelCompletion(); key = ""; render(latest) }
                    } catch (_: Exception) {
                        view.cancelCompletion()
                        Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(row, index)
        }
    }
}

class UndoListView(context: Context, private val compact: Boolean = false) : LinearLayout(context) {
    private val app = context.applicationContext as GlowApp
    private var signature = ""
    init { orientation = VERTICAL; visibility = GONE }
    fun render(tasks: List<TodoTask>, now: Long) {
        val pending = TaskPolicy.pending(tasks, now)
        val key = pending.joinToString { "${it.id}:${TaskPolicy.secondsLeft(it, now)}" }
        if (key == signature) return
        signature = key
        removeAllViews()
        visibility = if (pending.isEmpty()) GONE else VISIBLE
        pending.forEach { task ->
            val row = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val seconds = TaskPolicy.secondsLeft(task, now)
            row.addView(context.label("${task.removalKind ?: "移除"} · ${task.title}\n${seconds}秒后永久删除", if (compact) 11f else 13f, MUTED).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(context.dp(8), context.dp(4), context.dp(6), context.dp(4))
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(context.action("撤销") {
                app.scope.launch {
                    try {
                        if (!app.repository.undo(task.id)) Toast.makeText(context, "撤销时间已过", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show() }
                }
            }, LayoutParams(context.dp(64), context.dp(48)))
            addView(row)
        }
    }
}
