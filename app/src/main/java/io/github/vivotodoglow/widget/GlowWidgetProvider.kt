package io.github.vivotodoglow.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.TypedValue
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import io.github.vivotodoglow.GlowApp
import io.github.vivotodoglow.MainActivity
import io.github.vivotodoglow.R
import io.github.vivotodoglow.data.TaskPolicy
import io.github.vivotodoglow.data.TodoTask
import io.github.vivotodoglow.data.UNDO_WINDOW_MS
import io.github.vivotodoglow.ui.taskSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Real launcher-hosted widget. Rich gestures and the Canvas animation live in MainActivity. */
class GlowWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAsync(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        updateAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_UNDO -> updateAsync(context, undoId = intent.getLongExtra(EXTRA_TASK_ID, 0))
            ACTION_EXPIRE -> updateAsync(context, deadline = intent.getLongExtra(EXTRA_DEADLINE, 0))
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED -> updateAsync(context)
            else -> super.onReceive(context, intent)
        }
    }

    private fun updateAsync(context: Context, undoId: Long? = null, deadline: Long? = null) {
        val result = goAsync()
        val app = context.applicationContext as GlowApp
        app.scope.launch(Dispatchers.IO) {
            try {
                // A short pending broadcast keeps this process alive until the latest 5-second
                // action expires, including when the activity is immediately backgrounded.
                withTimeout(8_000) {
                    deadline?.let { delay((it - System.currentTimeMillis()).coerceIn(0, UNDO_WINDOW_MS)) }
                    if (undoId != null && undoId != 0L) app.repository.undo(undoId)
                    app.repository.cleanup()
                    refresh(context, app.repository.tasks.first())
                }
            } catch (error: Exception) {
                // A failed database/update operation must still release the broadcast token.
                Log.w("GlowWidget", "Unable to update the desktop widget", error)
            } finally {
                deadline?.let { scheduledExpiries.remove(it) }
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_UNDO = "io.github.vivotodoglow.widget.UNDO"
        private const val ACTION_EXPIRE = "io.github.vivotodoglow.widget.EXPIRE"
        const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_DEADLINE = "deadline"
        private val scheduledExpiries = ConcurrentHashMap.newKeySet<Long>()

        fun refresh(context: Context, tasks: List<TodoTask>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GlowWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { manager.updateAppWidget(it, views(context, it, tasks)) }
            val now = System.currentTimeMillis()
            (tasks.mapNotNull { it.deleteAfter } + tasks.mapNotNull { it.hiddenUntil })
                .filter { it > now }.distinct().forEach { deadline ->
                    if (scheduledExpiries.add(deadline)) {
                        context.sendBroadcast(Intent(context, GlowWidgetProvider::class.java)
                            .setAction(ACTION_EXPIRE).putExtra(EXTRA_DEADLINE, deadline))
                    }
                }
        }

        internal fun views(context: Context, widgetId: Int, tasks: List<TodoTask>): RemoteViews {
            val now = System.currentTimeMillis()
            val panel = TaskPolicy.panel(tasks)
            val pending = TaskPolicy.pending(tasks, now)
            val packageName = context.packageName
            val remote = RemoteViews(packageName, R.layout.widget_glow)
            remote.setTextViewText(R.id.widget_count, "${TaskPolicy.active(tasks).size} 件待办")
            remote.setOnClickPendingIntent(R.id.widget_add, activity(context, widgetId * 10 + 1, Intent(context, MainActivity::class.java).putExtra("add", true)))
            remote.setOnClickPendingIntent(R.id.widget_manage, activity(context, widgetId * 10 + 2, Intent(context, MainActivity::class.java)))
            remote.setOnClickPendingIntent(R.id.widget_heading, activity(context, widgetId * 10 + 3, Intent(context, MainActivity::class.java)))

            // Collection entries merge only their own extras into this explicit activity intent.
            val template = PendingIntent.getActivity(context, widgetId * 10 + 4,
                Intent(context, MainActivity::class.java).setAction("widget.tasks.$widgetId")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            remote.setPendingIntentTemplate(R.id.widget_tasks, template)
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            panel.forEach { task ->
                val item = RemoteViews(packageName, R.layout.widget_task)
                item.setTextViewText(R.id.widget_task_title, task.title)
                val subtitle = taskSubtitle(task)
                item.setTextViewText(R.id.widget_task_subtitle, subtitle)
                item.setViewVisibility(R.id.widget_task_subtitle, if (subtitle.isEmpty()) View.GONE else View.VISIBLE)
                item.setContentDescription(R.id.widget_task_complete, "完成：${task.title}")
                item.setOnClickFillInIntent(R.id.widget_task_body, Intent().putExtra("edit", task.id))
                item.setOnClickFillInIntent(R.id.widget_task_complete, Intent().putExtra("complete", task.id))
                items.addItem(task.id, item)
            }
            remote.setRemoteAdapter(R.id.widget_tasks, items.build())
            remote.setEmptyView(R.id.widget_tasks, R.id.widget_empty)

            remote.setViewVisibility(R.id.widget_undo_section, if (pending.isEmpty()) View.GONE else View.VISIBLE)
            remote.setTextViewText(R.id.widget_undo_heading, "${pending.size} 项操作可撤销 · 5 秒内")
            val undoTemplate = PendingIntent.getBroadcast(context, widgetId * 10 + 5,
                Intent(context, GlowWidgetProvider::class.java).setAction(ACTION_UNDO),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            remote.setPendingIntentTemplate(R.id.widget_undos, undoTemplate)
            val undos = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            pending.forEach { task ->
                val item = RemoteViews(packageName, R.layout.widget_undo)
                item.setTextViewText(R.id.widget_undo_title, "${task.removalKind ?: "操作"} · ${task.title}")
                val remaining = ((task.deleteAfter ?: now) - now).coerceAtLeast(0)
                item.setChronometerCountDown(R.id.widget_undo_timer, true)
                item.setChronometer(R.id.widget_undo_timer, SystemClock.elapsedRealtime() + remaining, "%s", remaining > 0)
                item.setContentDescription(R.id.widget_undo_button, "撤销：${task.title}")
                item.setOnClickFillInIntent(R.id.widget_undo_button, Intent().putExtra(EXTRA_TASK_ID, task.id))
                undos.addItem(task.id, item)
            }
            remote.setRemoteAdapter(R.id.widget_undos, undos.build())
            remote.setViewLayoutHeight(R.id.widget_undos, minOf(pending.size, 2).coerceAtLeast(1) * 40f, TypedValue.COMPLEX_UNIT_DIP)
            return remote
        }

        private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(context, code,
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
