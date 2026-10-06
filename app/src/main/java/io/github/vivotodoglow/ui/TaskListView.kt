package io.github.vivotodoglow.ui

import android.content.Context
import android.animation.LayoutTransition
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import java.time.LocalDate
import android.widget.*
import io.github.vivotodoglow.GlowApp
import io.github.vivotodoglow.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class TaskListView(context: Context, private val panel: Boolean, private val edit: (TodoTask) -> Unit) : LinearLayout(context) {
    private val app = context.applicationContext as GlowApp
    private var latest = emptyList<TodoTask>()
    private var key = ""
    private var renderedDay = LocalDate.now().toEpochDay()
    private var dragging: TaskRow? = null
    private var dragStartY = 0f
    private var pointerY = 0f
    private var dragFrom = 0
    private var dragTarget = 0
    private var committingDrag = false
    private var savedTransition: LayoutTransition? = null
    private var scrollParent: ScrollView? = null
    private val autoScroll = object : Runnable {
        override fun run() {
            if (dragging == null) return
            scrollParent?.let { scroll ->
                val screen = IntArray(2); scroll.getLocationOnScreen(screen)
                val step = when {
                    pointerY < screen[1] + context.dp(64) -> -context.dp(7)
                    pointerY > screen[1] + scroll.height - context.dp(64) -> context.dp(7)
                    else -> 0
                }
                if (step != 0) {
                    val before = scroll.scrollY; scroll.scrollBy(0, step)
                    dragStartY -= scroll.scrollY - before
                    updateDrag()
                }
            }
            postDelayed(this, 16)
        }
    }
    init {
        orientation = VERTICAL
        layoutTransition = LayoutTransition().apply { setDuration(200) }
    }
    fun render(tasks: List<TodoTask>) {
        latest = tasks
        if (dragging != null || committingDrag) return
        if ((0 until childCount).any { (getChildAt(it) as? TaskRow)?.isDissolving == true }) return
        val today = LocalDate.now().toEpochDay()
        if (today != renderedDay) { renderedDay = today; key = ""; removeAllViews() }
        val visible = if (panel) TaskPolicy.panel(tasks) else TaskPolicy.active(tasks)
        val nextKey = visible.joinToString { "${it.id}:${it.sortOrder}:${it.title}:${it.isDaily}:${it.dueDate}" }
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
            if (row == null || visible.none { sameContent(it, row.task) }) removeViewAt(index)
        }
        visible.forEachIndexed { index, task ->
            val existing = (0 until childCount).map { getChildAt(it) }.filterIsInstance<TaskRow>()
                .find { sameContent(it.task, task) }
            if (existing != null) {
                if (indexOfChild(existing) != index) { removeView(existing); addView(existing, index) }
                return@forEachIndexed
            }
            val row = TaskRow(context, task, { edit(task) }) { view ->
                app.scope.launch {
                    try {
                        if (app.repository.remove(task.id)) view.dissolve { removeView(view); key = ""; render(latest) }
                        else { view.cancelCompletion(); key = ""; render(latest) }
                    } catch (_: Exception) {
                        view.cancelCompletion()
                        Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (!panel) row.onDrag = { view, event -> drag(view, event) }
            addView(row, index)
        }
    }
    fun completeTask(id: Long) {
        rows().find { it.task.id == id }?.requestCompletion()
    }
    private fun sameContent(a: TodoTask, b: TodoTask) =
        a.id == b.id && a.title == b.title && a.isDaily == b.isDaily && a.dueDate == b.dueDate
    private fun rows() = (0 until childCount).map { getChildAt(it) }.filterIsInstance<TaskRow>()
    private fun drag(row: TaskRow, event: MotionEvent): Boolean {
        if (committingDrag || rows().any { it.isDissolving }) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = row; dragStartY = event.rawY; pointerY = event.rawY
                dragFrom = indexOfChild(row); dragTarget = dragFrom
                savedTransition = layoutTransition; layoutTransition = null
                row.setDragging(true); row.translationZ = context.dp(10).toFloat()
                parent.requestDisallowInterceptTouchEvent(true)
                var ancestor = parent
                while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
                scrollParent = ancestor as? ScrollView
                post(autoScroll)
            }
            MotionEvent.ACTION_MOVE -> { pointerY = event.rawY; updateDrag() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != row) return false
                removeCallbacks(autoScroll)
                parent.requestDisallowInterceptTouchEvent(false)
                rows().forEach { it.animate().cancel(); it.translationY = 0f }
                row.setDragging(false); row.translationZ = 0f; dragging = null
                val target = dragTarget
                if (event.actionMasked == MotionEvent.ACTION_UP && target != dragFrom) {
                    removeView(row); addView(row, target); committingDrag = true
                    app.scope.launch {
                        try { app.repository.moveTo(row.task.id, target); latest = app.repository.tasks.first() }
                        catch (_: Exception) { Toast.makeText(context, "排序保存失败，请重试", Toast.LENGTH_SHORT).show() }
                        finally { committingDrag = false; key = ""; render(latest) }
                    }
                } else { key = ""; render(latest) }
                layoutTransition = savedTransition
            }
        }
        return true
    }
    private fun updateDrag() {
        val row = dragging ?: return
        val children = rows()
        row.translationY = (pointerY - dragStartY).coerceIn(-row.top.toFloat(), (height - row.bottom).toFloat())
        val center = row.top + row.translationY + row.height / 2f
        dragTarget = children.indices.minByOrNull { abs(children[it].top + children[it].height / 2f - center) } ?: dragFrom
        val extent = row.height + (row.layoutParams as LayoutParams).bottomMargin
        children.forEachIndexed { index, child ->
            if (child == row) return@forEachIndexed
            val shift = when {
                dragTarget > dragFrom && index in (dragFrom + 1)..dragTarget -> -extent.toFloat()
                dragTarget < dragFrom && index in dragTarget until dragFrom -> extent.toFloat()
                else -> 0f
            }
            child.animate().translationY(shift).setDuration(120).start()
        }
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(autoScroll)
        dragging?.setDragging(false); dragging = null
        super.onDetachedFromWindow()
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
            val explanation = if (task.removalKind == "每日完成") "${seconds}秒内可撤销本次完成" else "${seconds}秒后永久删除"
            row.addView(context.label("${task.removalKind ?: "移除"} · ${task.title}\n$explanation", if (compact) 11f else 13f, MUTED).apply {
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
