package io.github.vivotodoglow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.vivotodoglow.data.TodoTask
import io.github.vivotodoglow.ui.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native Android canvas rendering for layout QA; not a substitute for a device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-xhdpi", application = GlowApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewTest {
    @Test fun tenRowsRenderWithinY36mSizedPortrait() {
        val app = ApplicationProvider.getApplicationContext<GlowApp>()
        val controller = Robolectric.buildService(OverlayService::class.java).create()
        val service = controller.get()
        fun field(name: String) = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
        val root = field("root") as FrameLayout
        val list = field("list") as TaskListView
        val titles = listOf("每天读书20分钟", "整理今天的工作笔记", "给家人打个电话", "散步，看看晚上的天空", "准备明天的早餐", "完成项目第一版", "收拾书桌", "记下一个新想法", "检查本周的计划", "早点休息，明天再继续", "这项不应在面板显示")
        list.render(titles.mapIndexed { i, title -> TodoTask(i.toLong() + 1, title, i.toLong()) })
        (field("counter") as TextView).text = "待办 10 / 11"
        root.measure(View.MeasureSpec.makeMeasureSpec(655, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1424, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, 655, root.measuredHeight)
        assertEquals(10, list.childCount)
        assertTrue("Ten rows should fit at standard font", list.height == (0 until list.childCount).sumOf {
            list.getChildAt(it).height + (list.getChildAt(it).layoutParams as android.widget.LinearLayout.LayoutParams).bottomMargin
        })
        assertTrue(root.height <= 1424)
        System.getenv("GLOW_PREVIEW_PATH")?.let { path ->
            val bitmap = Bitmap.createBitmap(720, 1600, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val wallpaper = Paint().apply {
                shader = LinearGradient(0f, 0f, 720f, 1600f, intArrayOf(0xff17394e.toInt(), 0xff496681.toInt(), 0xff152333.toInt()), null, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, 720f, 1600f, wallpaper)
            canvas.save(); canvas.translate(32f, 48f); root.draw(canvas); canvas.restore()
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        controller.destroy()
    }
}
