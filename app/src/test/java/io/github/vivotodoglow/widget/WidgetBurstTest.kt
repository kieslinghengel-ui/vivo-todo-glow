package io.github.vivotodoglow.widget

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.*
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.widget.*
import androidx.test.core.app.ApplicationProvider
import io.github.vivotodoglow.R
import io.github.vivotodoglow.data.TodoTask
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetBurstTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() { WidgetBurst.clear() }
    @After fun clear() { WidgetBurst.clear() }
    private val task = TodoTask(1, "把今天的心愿点亮", 1)
    private fun removed(task: TodoTask) = task.copy(deleteAfter = System.currentTimeMillis() + 5000, removalKind = "完成")

    @Test fun launcherPlaysFramesLocallyWithoutOpeningActivity() {
        val burst = WidgetBurst.begin(task, 0)
        val player = WidgetBurst.views(context, burst, burst.started).apply(context, FrameLayout(context)) as ViewFlipper
        assertTrue(player.isAutoStart)
        assertEquals(40, player.flipInterval)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            controller.get().setContentView(player)
            assertTrue(player.isFlipping)
            assertEquals(0, player.displayedChild)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
            // Initial window attachment can defer the first tick by one display frame.
            assertTrue(player.displayedChild in 2..3)
            assertNull(Shadows.shadowOf(context as Application).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun animationStaysInOriginalSlotThenNextTaskFillsIt() {
        val burst = WidgetBurst.begin(task, 1)
        val tasks = listOf(TodoTask(2, "第一项", 0), removed(task), TodoTask(3, "第三项", 2))
        val host = AppWidgetHostView(context).apply { setAppWidget(7, null) }
        val root = GlowWidgetProvider.views(context, 7, tasks).apply(context, host)
        host.addView(root)
        val list = root.findViewById<ListView>(R.id.widget_tasks)
        assertEquals(3, list.adapter.count)
        assertTrue(list.adapter.getView(1, null, list) is ViewFlipper)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_undo_section).visibility)
        assertTrue(WidgetBurst.active(tasks, burst.deadline).isEmpty())
        val next = GlowWidgetProvider.views(context, 7, tasks).apply(context, host).findViewById<ListView>(R.id.widget_tasks)
        assertEquals(2, next.adapter.count)
    }
    @Test fun undoImmediatelyCancelsItsAnimation() {
        WidgetBurst.begin(task, 0)
        assertEquals(1, WidgetBurst.active(listOf(removed(task))).size)
        assertTrue(WidgetBurst.active(listOf(task)).isEmpty())
    }
    @Test fun lateRefreshResumesInsteadOfReplayingFirstFrame() {
        val burst = WidgetBurst.begin(task, 0)
        val player = WidgetBurst.views(context, burst, burst.started + 360).apply(context, FrameLayout(context)) as ViewFlipper
        assertEquals(9, player.displayedChild)
    }
    @Test fun concurrentBurstsHaveBoundedBitmapMemoryAndCanBeParcelled() {
        repeat(3) { WidgetBurst.begin(task.copy(id = it + 1L), it) }
        val tasks = (1..3).map { removed(task.copy(id = it.toLong())) }
        val active = WidgetBurst.active(tasks)
        assertEquals(2, active.size)
        assertTrue(active.sumOf { it.frames.sumOf { bitmap -> bitmap.allocationByteCount.toLong() } } < 3_000_000)
        val parcel = Parcel.obtain()
        try {
            val views = GlowWidgetProvider.views(context, 7, tasks)
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
            val host = AppWidgetHostView(context).apply { setAppWidget(7, null) }
            assertNotNull(copy.apply(context, host))
        } finally { parcel.recycle() }
    }
    @Test fun particleFramesDissolveTextAndEndFullyTransparent() {
        val frames = WidgetBurst.renderFrames(task)
        assertEquals(20, frames.size)
        fun nonTransparent(bitmap: Bitmap): Int {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.count { Color.alpha(it) > 0 }
        }
        assertTrue(nonTransparent(frames[0]) > 10_000)
        assertTrue(nonTransparent(frames[8]) > 1_000)
        assertTrue(nonTransparent(frames[17]) < nonTransparent(frames[0]))
        assertEquals(0, nonTransparent(frames.last()))
        assertFalse(frames[0].sameAs(frames[8]))
        System.getenv("GLOW_BURST_FRAMES_DIR")?.let { path ->
            val directory = File(path).apply { mkdirs() }
            frames.forEachIndexed { index, frame ->
                File(directory, "$index.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
