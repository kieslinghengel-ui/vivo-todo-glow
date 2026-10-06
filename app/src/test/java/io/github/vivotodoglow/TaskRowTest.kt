package io.github.vivotodoglow

import android.app.Application
import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.test.core.app.ApplicationProvider
import io.github.vivotodoglow.data.TodoTask
import io.github.vivotodoglow.ui.TaskRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TaskRowTest {
    @Test fun leftSwipeOnlyRevealsAndButtonCommitsOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var completions = 0
        val row = TaskRow(context, TodoTask(1, "测试待办", 1), {}, { completions++ })
        row.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(104, View.MeasureSpec.EXACTLY))
        row.layout(0, 0, 600, 104)
        val body = row.getChildAt(1)
        val done = row.getChildAt(0) as Button
        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(0, 16, action, x, y, 0)
            body.dispatchTouchEvent(event); event.recycle()
        }
        assertEquals(View.INVISIBLE, done.visibility)
        touch(MotionEvent.ACTION_DOWN, 400f, 40f)
        touch(MotionEvent.ACTION_MOVE, 200f, 40f)
        touch(MotionEvent.ACTION_UP, 200f, 40f)
        assertEquals(0, completions)
        assertEquals(View.VISIBLE, done.visibility)
        done.performClick(); done.performClick()
        assertEquals(1, completions)
    }
    @Test fun verticalMotionDoesNotRevealCompletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val row = TaskRow(context, TodoTask(1, "纵向滚动", 1), {}, { fail("Must not complete") })
        val body = row.getChildAt(1)
        listOf(Triple(MotionEvent.ACTION_DOWN, 200f, 20f), Triple(MotionEvent.ACTION_MOVE, 198f, 100f), Triple(MotionEvent.ACTION_CANCEL, 198f, 100f)).forEach {
            val e = MotionEvent.obtain(0, 16, it.first, it.second, it.third, 0)
            body.dispatchTouchEvent(e); e.recycle()
        }
        assertEquals(0f, body.translationX)
    }
    @Test fun holdingBodyOpensEditorOnceAndReleaseDoesNotClickAgain() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var edits = 0
        val row = TaskRow(activity, TodoTask(1, "长按编辑", 1), { edits++ }, { fail("Must not complete") })
        activity.setContentView(row)
        val body = row.getChildAt(1)
        touch(body, MotionEvent.ACTION_DOWN, 100f, 20f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
        assertEquals(1, edits)
        touch(body, MotionEvent.ACTION_UP, 100f, 20f)
        assertEquals(1, edits)
        controller.pause().stop().destroy()
    }
    @Test fun scrollingCancelsPendingLongPress() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var edits = 0
        val row = TaskRow(activity, TodoTask(1, "滚动时不编辑", 1), { edits++ }, { fail("Must not complete") })
        activity.setContentView(row)
        val body = row.getChildAt(1)
        touch(body, MotionEvent.ACTION_DOWN, 100f, 20f)
        touch(body, MotionEvent.ACTION_MOVE, 100f, 160f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
        touch(body, MotionEvent.ACTION_UP, 100f, 160f)
        assertEquals(0, edits)
        assertEquals(View.INVISIBLE, row.getChildAt(0).visibility)
        controller.pause().stop().destroy()
    }
    @Test fun separateHandlePassesFullDragGestureWithoutEditingOrCompleting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val row = TaskRow(context, TodoTask(1, "自由排序", 1), { fail("Dragging must not edit") }, { fail("Dragging must not complete") })
        val body = row.getChildAt(1) as LinearLayout
        val handle = body.getChildAt(body.childCount - 1)
        val received = mutableListOf<Int>()
        row.onDrag = { actual, event -> assertSame(row, actual); received.add(event.actionMasked); true }
        touch(handle, MotionEvent.ACTION_DOWN, 12f, 16f)
        touch(handle, MotionEvent.ACTION_MOVE, 12f, 100f)
        touch(handle, MotionEvent.ACTION_UP, 12f, 100f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), received)
    }
    @Test fun directCompletionForWidgetStillCommitsOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var completed = 0
        val row = TaskRow(context, TodoTask(1, "小组件完成", 1), {}, { completed++ })
        row.requestCompletion(); row.requestCompletion()
        assertEquals(1, completed)
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 16, action, x, y, 0)
        view.dispatchTouchEvent(event); event.recycle()
    }
}
