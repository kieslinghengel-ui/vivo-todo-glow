package io.github.vivotodoglow

import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import io.github.vivotodoglow.data.TaskDatabase
import io.github.vivotodoglow.data.TaskRepository
import io.github.vivotodoglow.data.TaskPolicy
import io.github.vivotodoglow.ui.TaskListView
import io.github.vivotodoglow.ui.TaskRow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = GlowApp::class)
class TaskListDragTest {
    @Test fun oneDragMovesFirstTaskDirectlyToLastAndPersists() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<GlowApp>()
        // Isolate this gesture's data from Application startup cleanup, whose main-loop
        // continuation cannot execute while this JUnit thread is inside runBlocking.
        app.repository = TaskRepository(Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java)
            .allowMainThreadQueries().build())
        val ids = listOf(app.repository.add("A"), app.repository.add("B"), app.repository.add("C"))
        val list = TaskListView(app, false) {}
        val host = android.widget.FrameLayout(app)
        host.addView(list)
        list.render(app.repository.tasks.first())
        host.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 720, 1000)
        val row = list.getChildAt(0) as TaskRow
        val start = row.top + row.height / 2f
        val end = list.getChildAt(2).bottom - row.height / 2f
        fun event(action: Int, y: Float) = MotionEvent.obtain(0, 0, action, 24f, y, 0)
        event(MotionEvent.ACTION_DOWN, start).let { row.onDrag!!(row, it); it.recycle() }
        event(MotionEvent.ACTION_MOVE, end).let { row.onDrag!!(row, it); it.recycle() }
        event(MotionEvent.ACTION_UP, end).let { row.onDrag!!(row, it); it.recycle() }
        assertEquals(listOf(ids[1], ids[2], ids[0]), (0 until list.childCount).map { (list.getChildAt(it) as TaskRow).task.id })
        val saved = withTimeout(5000) { app.repository.tasks.first { TaskPolicy.active(it).map { t -> t.id } == listOf(ids[1], ids[2], ids[0]) } }
        assertEquals(listOf("B", "C", "A"), TaskPolicy.active(saved).map { it.title })
    }
}
