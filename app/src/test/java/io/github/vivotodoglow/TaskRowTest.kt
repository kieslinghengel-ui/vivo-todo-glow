package io.github.vivotodoglow

import android.app.Application
import android.content.Context
import android.view.*
import android.widget.*
import androidx.test.core.app.ApplicationProvider
import io.github.vivotodoglow.data.TodoTask
import io.github.vivotodoglow.ui.TaskRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
