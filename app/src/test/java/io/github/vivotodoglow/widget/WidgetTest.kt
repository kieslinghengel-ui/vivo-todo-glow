package io.github.vivotodoglow.widget

import android.app.Application
import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.os.Looper
import android.view.View
import android.widget.Chronometer
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.vivotodoglow.R
import io.github.vivotodoglow.data.TodoTask
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun tenTasksFitNarrowTallPanelWithVisibleCompletionButtons() {
        val root = render((1..10).map { TodoTask(it.toLong(), "任务 $it", it.toLong()) })
        val density = context.resources.displayMetrics.density
        val width = (240 * density).toInt()
        val height = (660 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val tasks = root.findViewById<ListView>(R.id.widget_tasks)
        assertEquals(10, tasks.childCount)
        assertTrue("Last row bottom=${tasks.getChildAt(9).bottom}, available=${tasks.height}", tasks.getChildAt(9).bottom <= tasks.height)
        val row = tasks.getChildAt(0)
        assertTrue(row.findViewById<View>(R.id.widget_task_body).width > 0)
        assertTrue(row.findViewById<View>(R.id.widget_task_complete).right <= row.width)
    }
    @Test fun pickerPreviewInflatesWithoutWidgetHostOrCollectionAdapter() {
        val root = android.widget.RemoteViews(context.packageName, R.layout.widget_preview)
            .apply(context, android.widget.FrameLayout(context))
        assertTrue(root is android.widget.LinearLayout)
        assertEquals(3, (root as android.widget.LinearLayout).childCount)
        assertEquals("光点待办", (root.getChildAt(0) as TextView).text.toString())
    }
    private fun render(tasks: List<TodoTask>, hostContext: Context = context): View {
        // Android deliberately ignores setRemoteAdapter unless its parent is a real widget
        // host. A plain FrameLayout would silently skip the collection adapter action.
        val host = AppWidgetHostView(hostContext).apply { setAppWidget(7, null) }
        return GlowWidgetProvider.views(hostContext, 7, tasks).apply(hostContext, host)
            .also { host.addView(it) }
    }

    @Test fun widgetUsesTenOrderedTasksAndLauncherClock() {
        val tasks = (1..11).map { TodoTask(it.toLong(), "任务 $it", it.toLong()) }.reversed()
        val root = render(tasks)
        val list = root.findViewById<ListView>(R.id.widget_tasks)
        assertEquals(10, list.adapter.count)
        val first = list.adapter.getView(0, null, list)
        val last = list.adapter.getView(9, null, list)
        assertEquals("任务 1", first.findViewById<TextView>(R.id.widget_task_title).text.toString())
        assertEquals("任务 10", last.findViewById<TextView>(R.id.widget_task_title).text.toString())
        val date = root.findViewById<TextClock>(R.id.widget_date)
        assertEquals("M月d日\nEEEE", date.format12Hour.toString())
        assertEquals(date.format12Hour, date.format24Hour)
        assertNull("The clock should follow the phone timezone", date.timeZone)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_undo_section).visibility)
    }

    @Test fun completionHidesTaskAndIndependentUndoTimersUseTheirDeadlines() {
        // Native graphics/widget inflation has a one-time startup cost. Establish the
        // framework host before starting the real five-second deadlines under test.
        render(emptyList())
        val now = System.currentTimeMillis()
        val tasks = listOf(
            TodoTask(1, "仍在列表", 1),
            TodoTask(2, "先完成", 2, now + 2_000, "完成"),
            TodoTask(3, "后完成", 3, now + 5_000, "完成"),
            TodoTask(4, "已经过期", 4, now - 1, "删除")
        )
        val root = render(tasks)
        assertEquals(1, root.findViewById<ListView>(R.id.widget_tasks).adapter.count)
        val undos = root.findViewById<ListView>(R.id.widget_undos)
        assertEquals(2, undos.adapter.count)
        val first = undos.adapter.getView(0, null, undos).findViewById<Chronometer>(R.id.widget_undo_timer)
        val second = undos.adapter.getView(1, null, undos).findViewById<Chronometer>(R.id.widget_undo_timer)
        assertTrue(first.isCountDown)
        assertTrue(second.isCountDown)
        assertEquals(3_000.0, (second.base - first.base).toDouble(), 100.0)
        assertTrue(first.base > SystemClock.elapsedRealtime())
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_undo_section).visibility)
    }

    @Test fun emptyWidgetHasAddAndHelpfulEmptyState() {
        val root = render(emptyList())
        assertEquals(0, root.findViewById<ListView>(R.id.widget_tasks).adapter.count)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_empty).visibility)
        assertNotNull(root.findViewById<View>(R.id.widget_add))
    }

    @Test fun taskClickReusesActivityAndPassesOnlyItsOwnTaskId() {
        val root = render(listOf(TodoTask(41, "打开已有面板", 1)))
        root.measure(View.MeasureSpec.makeMeasureSpec(656, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1320, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 656, 1320)
        val list = root.findViewById<ListView>(R.id.widget_tasks)
        list.getChildAt(0).findViewById<View>(R.id.widget_task_body).performClick()
        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(41L, started.getLongExtra("edit", -1))
        assertFalse(started.hasExtra("complete"))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun completionButtonRoutesTheSelectedTaskToTheAnimatedPanel() {
        val root = render(listOf(TodoTask(41, "保留第一项", 1), TodoTask(99, "完成第二项", 2)))
        root.measure(View.MeasureSpec.makeMeasureSpec(656, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1320, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 656, 1320)
        val list = root.findViewById<ListView>(R.id.widget_tasks)
        list.getChildAt(1).findViewById<View>(R.id.widget_task_complete).performClick()
        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(99L, started.getLongExtra("complete", -1))
        assertTrue(WidgetActions.isAuthorized(context, started))
        assertFalse(started.hasExtra("edit"))
    }

    @Test fun exportedLauncherRejectsForgedAutomaticCompletion() {
        assertFalse(WidgetActions.isAuthorized(context, Intent().putExtra("complete", 99L)))
        assertFalse(WidgetActions.isAuthorized(context, Intent().putExtra("complete", 99L).putExtra("widget_action_key", "forged")))
    }

    @Test fun twoHundredPercentFontExpandsRowsWithoutClippingTitleOrMetadata() {
        val large = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        val task = TodoTask(1, "每日读书", 1, isDaily = true, dueDate = LocalDate.now().toEpochDay())
        val root = render(listOf(task), large)
        root.measure(View.MeasureSpec.makeMeasureSpec(656, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1320, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 656, 1320)
        val row = root.findViewById<ListView>(R.id.widget_tasks).getChildAt(0)
        val body = row.findViewById<View>(R.id.widget_task_body)
        val title = row.findViewById<TextView>(R.id.widget_task_title)
        val subtitle = row.findViewById<TextView>(R.id.widget_task_subtitle)
        assertEquals(2f, title.resources.configuration.fontScale, 0.001f)
        assertTrue("Large text should grow beyond the standard 48dp row", row.height > 48 * large.resources.displayMetrics.density)
        assertEquals(View.VISIBLE, subtitle.visibility)
        assertTrue(subtitle.text.contains("每日任务"))
        assertTrue(subtitle.text.contains("今天截止"))
        assertTrue("Row must contain the full task body", row.height >= body.measuredHeight)
        assertTrue("Title and metadata must not overlap", title.bottom <= subtitle.top)
        assertTrue("Metadata must fit inside the padded body", subtitle.bottom + body.paddingBottom <= body.height)
        assertTrue("Title's text layout must fit vertically", title.height >= title.layout.height + title.compoundPaddingTop + title.compoundPaddingBottom)
        assertTrue("Metadata's text layout must fit vertically", subtitle.height >= subtitle.layout.height + subtitle.compoundPaddingTop + subtitle.compoundPaddingBottom)
    }

    /** A real RemoteViews rendering for layout inspection, never a real-device screenshot. */
    @Test fun tenRowsFitAFullHeightDesktopWidgetAtStandardFont() {
        val titles = listOf("晨光里，读二十分钟书", "整理今天的工作笔记", "给家人打个电话", "散步，看看晚上的天空", "准备明天的早餐", "完成项目第一版", "收拾书桌", "记下一个新想法", "检查本周的计划", "早点休息，明天再继续")
        val root = render(titles.mapIndexed { index, title ->
            TodoTask(index + 1L, title, index.toLong(), isDaily = index == 0,
                dueDate = if (index == 1) LocalDate.now().plusDays(1).toEpochDay() else null)
        })
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            // TextClock registers and refreshes itself when attached to a real window.
            // Attach its actual widget host rather than filling a fake preview date.
            controller.get().setContentView(root.parent as AppWidgetHostView)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val date = root.findViewById<TextClock>(R.id.widget_date)
            assertTrue(date.isAttachedToWindow)
            assertTrue("The launcher clock should display a date", date.text.contains("月") && date.text.contains("日"))
            assertTrue("A Chinese phone should display a Chinese weekday", date.text.contains("星期"))
            root.measure(View.MeasureSpec.makeMeasureSpec(656, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1320, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 656, 1320)
            assertEquals(10, root.findViewById<ListView>(R.id.widget_tasks).childCount)
            System.getenv("GLOW_WIDGET_PREVIEW_PATH")?.let { path ->
                val bitmap = Bitmap.createBitmap(720, 1600, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val wallpaper = Paint().apply { shader = LinearGradient(0f, 0f, 720f, 1600f,
                    intArrayOf(0xff233e60.toInt(), 0xff647099.toInt(), 0xff182b4a.toInt()), null, Shader.TileMode.CLAMP) }
                canvas.drawRect(0f, 0f, 720f, 1600f, wallpaper)
                canvas.save(); canvas.translate(32f, 96f); root.draw(canvas); canvas.restore()
                File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
