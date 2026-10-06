package io.github.vivotodoglow

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.vivotodoglow.data.*
import io.github.vivotodoglow.ui.*
import io.github.vivotodoglow.widget.GlowWidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val app get() = application as GlowApp
    private lateinit var list: TaskListView
    private lateinit var undo: UndoListView
    private lateinit var count: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = column().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xff162c43.toInt(), 0xff0b1424.toInt(), 0xff10192f.toInt()))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(18) + bars.left, dp(14) + bars.top, dp(18) + bars.right, dp(10) + bars.bottom)
            insets
        }
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = column()
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("光点待办", 26f).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextClock(this).apply {
            format12Hour = "M月d日\nEEEE"; format24Hour = "M月d日\nEEEE"
            textSize = 12f; setTextColor(GLOW); gravity = Gravity.END
        })
        content.addView(header)
        content.space(8)
        content.addView(label("把琐事交给清单，把心情留给今天。", 12f, MUTED))
        content.space(18)
        val widget = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        widget.addView(action("＋ 添加到桌面") { pinWidget() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        widget.addView(action("使用说明") { showHelp() }, LinearLayout.LayoutParams(dp(88), dp(46)).apply { leftMargin = dp(8) })
        content.addView(widget)
        content.space(20)
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        count = label("今天的清单", 18f).apply { typeface = Typeface.DEFAULT_BOLD }
        heading.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(action("＋ 新任务") { edit(null) }, LinearLayout.LayoutParams(dp(94), dp(48)))
        content.addView(heading)
        content.addView(label("按住 ≡ 拖动排序 · 长按任务编辑 · 左滑完成", 11f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(12))
        })
        undo = UndoListView(this)
        content.addView(undo)
        content.space(4)
        list = TaskListView(this, false) { chooseEdit(it) }
        content.addView(list)
        content.space(20)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.snapshot.collect { tasks ->
                        list.render(tasks)
                        count.text = "今天的清单 · ${TaskPolicy.active(tasks).size}"
                        undo.render(tasks, System.currentTimeMillis())
                    }
                }
                launch {
                    var date = LocalDate.now()
                    while (isActive) {
                        undo.render(app.snapshot.value, System.currentTimeMillis())
                        if (date != LocalDate.now()) { date = LocalDate.now(); list.render(app.snapshot.value) }
                        delay(100)
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    private fun handleIntent(source: Intent) {
        if (source.getBooleanExtra("add", false)) edit(null)
        val editId = source.getLongExtra("edit", -1)
        val completeId = source.getLongExtra("complete", -1)
        source.removeExtra("add"); source.removeExtra("edit"); source.removeExtra("complete")
        if (editId < 0 && completeId < 0) return
        lifecycleScope.launch {
            app.repository.cleanup()
            val tasks = app.repository.tasks.first()
            list.render(tasks)
            val task = TaskPolicy.active(tasks).find { it.id == if (editId >= 0) editId else completeId }
            if (task == null) { Toast.makeText(this@MainActivity, "这项任务已更新", Toast.LENGTH_SHORT).show(); return@launch }
            if (editId >= 0) edit(task)
            else list.doOnPreDraw {
                val row = (0 until list.childCount).map { list.getChildAt(it) }
                    .filterIsInstance<TaskRow>().find { it.task.id == completeId }
                row?.let { scroll.scrollTo(0, list.top + it.top - dp(100)) }
                list.completeTask(completeId)
            }
        }
    }
    private fun pinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, GlowWidgetProvider::class.java), null, null)
        } else showWidgetHelp()
    }
    private fun showWidgetHelp() {
        AlertDialog.Builder(this).setTitle("添加桌面小组件")
            .setMessage("在 vivo 桌面空白处长按，进入“组件”或“原子组件”，找到“光点待办”，将它拖到桌面。可以调整组件大小；列表最多展示前10项，空间不足时上下滚动。不同 OriginOS 版本的入口名称可能不同。")
            .setPositiveButton("知道了", null).show()
    }
    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("让待办留在桌面")
            .setMessage("桌面小组件展示前10项、当天日期和星期。点击任务可编辑，点击完成入口会打开此面板并播放光粒子消散。\n\n在此面板按住 ≡ 自由拖动排序，长按任务选择编辑、每日任务或截止日期。每日任务完成后约0.8秒回到第5位；不足5项时放在末尾，可以再次完成。\n\n完成或删除后有5秒撤销。截止日期是日期标记，不会发送提醒。任务只保存在手机，卸载会清空数据。\n\nvivo 桌面的小组件长按用于移动组件，因此排序和粒子动画在此面板中完成。新版不需要悬浮窗权限。")
            .setPositiveButton("添加到桌面") { _, _ -> pinWidget() }.setNegativeButton("知道了", null).show()
    }
    private fun chooseEdit(task: TodoTask) {
        AlertDialog.Builder(this).setTitle(task.title)
            .setItems(arrayOf("编辑任务", if (task.isDaily) "改为普通任务" else "改为每日任务", "设置截止日期", "删除任务")) { _, index ->
                when (index) {
                    0, 2 -> edit(task, index == 2)
                    1 -> lifecycleScope.launch { saveSafely { app.repository.edit(task.id, task.title, !task.isDaily, task.dueDate) } }
                    3 -> lifecycleScope.launch { saveSafely { app.repository.remove(task.id, "删除") } }
                }
            }.show()
    }
    private suspend fun saveSafely(operation: suspend () -> Unit) {
        try { operation() } catch (_: Exception) { Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show() }
    }
    private fun edit(task: TodoTask?, openDate: Boolean = false) {
        val input = EditText(this).apply {
            setTextColor(Color.WHITE); setText(task?.title ?: ""); hint = "写下一件想完成的事"
            setHintTextColor(MUTED); filters = arrayOf(InputFilter.LengthFilter(200)); maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSelectAllOnFocus(true)
        }
        val daily = Switch(this).apply {
            text = "每日任务"; setTextColor(Color.WHITE); textSize = 15f; isChecked = task?.isDaily ?: false
            minHeight = dp(48)
        }
        var deadline = task?.dueDate
        val dateButton = action("") {}
        fun showDate() { dateButton.text = deadline?.let { "截止日期 · ${LocalDate.ofEpochDay(it)}" } ?: "＋ 添加截止日期" }
        fun chooseDate() {
            val selected = deadline?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
            DatePickerDialog(this, { _, year, month, day ->
                deadline = LocalDate.of(year, month + 1, day).toEpochDay(); showDate()
            }, selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
        }
        dateButton.setOnClickListener { chooseDate() }; showDate()
        val dates = LinearLayout(this)
        dates.addView(dateButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        dates.addView(action("清除") { deadline = null; showDate() }, LinearLayout.LayoutParams(dp(64), dp(48)).apply { leftMargin = dp(6) })
        val box = column().apply {
            setPadding(dp(20), dp(10), dp(20), dp(8)); addView(input); space(12); addView(daily)
            addView(label("完成后消散，再回到清单第5位", 11f, MUTED)); space(12); addView(dates)
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (task == null) "新任务" else "编辑任务")
            .setView(box).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = input.text.toString().trim()
                if (title.isEmpty()) { input.error = "请输入任务内容"; return@setOnClickListener }
                lifecycleScope.launch {
                    try {
                        if (task == null) app.repository.add(title, daily.isChecked, deadline)
                        else app.repository.edit(task.id, title, daily.isChecked, deadline)
                        dialog.dismiss()
                    } catch (_: Exception) { input.error = "保存失败，请重试" }
                }
            }
            if (openDate) chooseDate()
            else if (task == null) {
                input.requestFocus()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
        }
        dialog.show()
    }
}
