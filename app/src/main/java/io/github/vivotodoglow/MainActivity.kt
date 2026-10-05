package io.github.vivotodoglow

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import io.github.vivotodoglow.data.*
import io.github.vivotodoglow.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private val app get() = application as GlowApp
    private lateinit var list: TaskListView
    private lateinit var undo: UndoListView
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var opacityText: TextView
    private lateinit var seek: SeekBar
    private var settingProgress = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = column().apply { setBackgroundColor(INK); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(12) + bars.bottom)
            insets
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = column()
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        content.addView(label("光点待办", 30f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
        content.addView(label("让重要的事，停留在桌面。", 14f, MUTED))
        content.space(20)
        val controls = LinearLayout(this)
        controls.addView(action("开启悬浮待办") { startOverlay() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(action("关闭") { stopOverlay() }, LinearLayout.LayoutParams(dp(72), dp(48)).apply { leftMargin = dp(8) })
        content.addView(controls)
        content.space(12)
        status = label("", 12f, MUTED)
        content.addView(status)
        val permissions = LinearLayout(this)
        permissions.addView(action("悬浮窗权限") { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        permissions.addView(action("桌面自动收起") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        content.addView(permissions)
        content.space(8)
        content.addView(action("通知权限与 vivo 后台设置") { showHelp() })
        content.space(18)
        opacityText = label("面板不透明度 · 88%", 12f, MUTED)
        content.addView(opacityText)
        seek = SeekBar(this).apply { max = 65; progress = 53 }
        content.addView(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                opacityText.text = "面板不透明度 · ${value + 35}%"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                if (!settingProgress) lifecycleScope.launch { app.settings.opacity(seek.progress + 35) }
            }
        })
        content.space(18)
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        count = label("我的待办", 19f)
        heading.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(action("＋ 添加") { edit(null) }, LinearLayout.LayoutParams(dp(90), dp(48)))
        content.addView(heading)
        content.space(10)
        content.addView(label("左滑显示完成按钮 · 点击编辑和排序\n桌面只展示前10项，完成后自动补入下一项", 12f, MUTED))
        content.space(12)
        undo = UndoListView(this)
        content.addView(undo)
        content.space(8)
        list = TaskListView(this, false) { edit(it) }
        content.addView(list)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.snapshot.collect { tasks ->
                        list.render(tasks)
                        count.text = "我的待办 · ${TaskPolicy.active(tasks).size}"
                        undo.render(tasks, System.currentTimeMillis())
                    }
                }
                launch { while (isActive) { undo.render(app.snapshot.value, System.currentTimeMillis()); delay(100) } }
                launch {
                    app.settings.settings.collect {
                        settingProgress = true; seek.progress = it.opacity - 35; settingProgress = false
                    }
                }
            }
        }
        if (intent.getBooleanExtra("add", false)) edit(null)
        intent.getLongExtra("edit", -1).takeIf { it >= 0 }?.let { id ->
            lifecycleScope.launch {
                val tasks = app.repository.tasks.first()
                tasks.find { it.id == id && it.deleteAfter == null }?.let { edit(it) }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = "悬浮窗：${if (Settings.canDrawOverlays(this)) "已允许" else "未允许"}  ·  自动收起：${if (DesktopDetector.hasPermission(this)) "已允许" else "未允许"}\n撤销窗口为5秒，超时永久删除。"
        }
    }
    private fun openSettings(action: String, packageUri: Boolean = false) {
        try { startActivity(Intent(action).apply { if (packageUri) data = Uri.parse("package:$packageName") }) }
        catch (_: Exception) { Toast.makeText(this, "请在系统设置中手动开启对应权限", Toast.LENGTH_LONG).show() }
    }
    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true); return }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        try { startForegroundService(Intent(this, OverlayService::class.java)) }
        catch (_: Exception) { Toast.makeText(this, "无法开启，请检查后台运行与悬浮窗权限", Toast.LENGTH_LONG).show() }
    }
    private fun stopOverlay() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        stopService(serviceIntent)
    }
    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("vivo Y36m 设置")
            .setMessage("1. 允许显示在其他应用上层。\n2. 开启使用情况访问，以便识别桌面；只用于本地判断，不上传数据。\n3. 允许通知，通知栏可暂停、恢复或关闭面板。\n4. 若面板被系统清理，请在设置中搜索“后台耗电管理”，允许光点待办后台运行；也可在最近任务中锁定应用。不同 OriginOS 版本入口可能不同。\n\n未允许使用情况访问时，可以手动展开/收起。锁屏不显示任务。重启手机后请打开应用并重新开启。\n\n所有任务仅保存在本机；完成和删除只有5秒撤销时间。卸载会清除任务。")
            .setPositiveButton("开启通知权限") { _, _ ->
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }.setNegativeButton("知道了", null).show()
    }
    private fun edit(task: TodoTask?) {
        val input = EditText(this).apply {
            setTextColor(Color.WHITE); setText(task?.title ?: ""); hint = "输入待办，最多200字"
            setHintTextColor(MUTED); filters = arrayOf(InputFilter.LengthFilter(200)); maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSelectAllOnFocus(true)
        }
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)); addView(input) }
        if (task != null) {
            val sort = LinearLayout(this)
            sort.addView(action("↑ 上移") { lifecycleScope.launch { app.repository.move(task.id, -1) } }, LinearLayout.LayoutParams(0, dp(48), 1f))
            sort.addView(action("↓ 下移") { lifecycleScope.launch { app.repository.move(task.id, 1) } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
            box.addView(sort)
        }
        val builder = AlertDialog.Builder(this).setTitle(if (task == null) "添加待办" else "编辑待办")
            .setView(box).setPositiveButton("保存", null).setNegativeButton("取消", null)
        if (task != null) builder.setNeutralButton("删除") { _, _ -> lifecycleScope.launch {
            try { app.repository.remove(task.id, "删除") } catch (_: Exception) { Toast.makeText(this@MainActivity, "保存失败", Toast.LENGTH_SHORT).show() }
        } }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = input.text.toString().trim()
                if (title.isEmpty()) { input.error = "请输入待办内容"; return@setOnClickListener }
                lifecycleScope.launch {
                    try {
                        if (task == null) app.repository.add(title) else app.repository.rename(task.id, title)
                        dialog.dismiss()
                    } catch (_: Exception) { input.error = "保存失败，请重试" }
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }
}
