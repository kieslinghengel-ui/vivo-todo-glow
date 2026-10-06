package io.github.vivotodoglow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import io.github.vivotodoglow.data.TodoTask
import io.github.vivotodoglow.ui.TaskListView
import io.github.vivotodoglow.ui.GlowDissolveView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Actual Android Views rendering. Does not replace installation/gesture tests on vivo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi", application = GlowApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewTest {
    @Test fun taskPanelRendersWithDailyAndDeadlineMetadata() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val list = MainActivity::class.java.getDeclaredField("list").apply { isAccessible = true }.get(activity) as TaskListView
        list.layoutTransition = null
        val titles = listOf("晨光里，读二十分钟书", "整理今天的工作笔记", "给家人打个电话", "散步，看看晚上的天空", "准备明天的早餐", "完成项目第一版", "收拾书桌", "记下一个新想法", "检查本周的计划", "早点休息，明天再继续")
        list.render(titles.mapIndexed { index, title -> TodoTask(index + 1L, title, index.toLong(),
            isDaily = index == 0 || index == 4, dueDate = if (index == 1) LocalDate.now().toEpochDay() else null) })
        (MainActivity::class.java.getDeclaredField("count").apply { isAccessible = true }.get(activity) as android.widget.TextView).text = "今天的清单 · 10"
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 720, 1600)
        assertEquals(10, list.childCount)
        assertTrue(list.height > 0)
        System.getenv("GLOW_PREVIEW_PATH")?.let { path ->
            val bitmap = Bitmap.createBitmap(720, 1600, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        System.getenv("GLOW_DISSOLVE_PATH")?.let { path ->
            val row = list.getChildAt(0)
            val body = (row as android.view.ViewGroup).getChildAt(1)
            val source = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
            body.draw(Canvas(source))
            val stride = row.height + 56
            val storyboard = Bitmap.createBitmap(720, stride * 4 + 24, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(storyboard)
            canvas.drawColor(0xff0b1424.toInt())
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff99afc7.toInt(); textSize = 22f }
            listOf(0f, .3f, .6f, .85f).forEachIndexed { index, progress ->
                val top = index * stride + 24f
                canvas.drawText("${(progress * 800).toInt()} ms", 36f, top + 16f, label)
                val dissolve = GlowDissolveView(activity, source.copy(Bitmap.Config.ARGB_8888, false), 1).apply {
                    measure(View.MeasureSpec.makeMeasureSpec(row.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(row.height, View.MeasureSpec.EXACTLY))
                    layout(0, 0, row.width, row.height); this.progress = progress
                }
                canvas.save(); canvas.translate(36f, top + 30f); dissolve.draw(canvas); canvas.restore()
                dissolve.release()
            }
            File(path).outputStream().use { storyboard.compress(Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle(); storyboard.recycle()
        }
        controller.pause().stop().destroy()
    }
}
