package io.github.vivotodoglow

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import io.github.vivotodoglow.data.*
import io.github.vivotodoglow.ui.*
import kotlinx.coroutines.*

class OverlayService : Service() {
    private val app get() = application as GlowApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var counter: TextView
    private lateinit var list: TaskListView
    private lateinit var undo: UndoListView
    private lateinit var detector: DesktopDetector
    private var attached = false
    private var expanded = true
    private var paused = false
    private var positionLoaded = false
    private var lastPackage: String? = "__initial__"
    private var lastPermission = false
    private var opacity = 88
    private var dragging = false
    private var panelX = 16
    private var panelY = 80
    private var unlockedLastTick = false
    private val panelWidth get() = (resources.displayMetrics.widthPixels * .91f).toInt()
    private val safeHeight get() = resources.displayMetrics.heightPixels - dp(88)
    private val undoHeight get() = minOf(dp(108), (safeHeight * .24f).toInt())
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) { detach(); detector.reset(); unlockedLastTick = false }
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        detector = DesktopDetector(this)
        wm = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("overlay", "悬浮待办运行状态", NotificationManager.IMPORTANCE_LOW)
        )
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        createViews()
        scope.launch {
            app.settings.settings.collect { setting ->
                opacity = setting.opacity
                panel.background = rounded(Color.argb(opacity * 255 / 100, 11, 20, 32), dp(22).toFloat(), Color.rgb(57, 85, 101))
                if (!positionLoaded) {
                    panelX = setting.x; panelY = setting.y; positionLoaded = true
                    updateMode()
                }
            }
        }
        scope.launch {
            app.snapshot.collect { tasks ->
                list.render(tasks)
                val active = TaskPolicy.active(tasks).size
                counter.text = "待办 ${minOf(active, PANEL_LIMIT)} / $active"
                renderUndo()
            }
        }
        scope.launch {
            while (isActive) {
                renderUndo()
                if (!Settings.canDrawOverlays(this@OverlayService)) { detach(); stopSelf(); break }
                val power = getSystemService(PowerManager::class.java)
                val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
                if (!power.isInteractive || locked || paused) {
                    detach()
                    if (locked || !power.isInteractive) { detector.reset(); unlockedLastTick = false }
                } else {
                    val permitted = DesktopDetector.hasPermission(this@OverlayService)
                    val current = if (permitted) detector.foregroundPackage() else null
                    if (permitted && (current != lastPackage || !lastPermission || !unlockedLastTick)) {
                        expanded = detector.isHome(current); updateMode()
                    } else if (!permitted && lastPermission) {
                        expanded = false; updateMode()
                    }
                    lastPackage = current; lastPermission = permitted; unlockedLastTick = true
                    attach()
                }
                delay(1000)
            }
        }
        scope.launch { while (isActive) { renderUndo(); delay(100) } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close") { detach(); stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == "pause") paused = true
        if (intent?.action == "resume" || intent?.action == null) paused = false
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(71, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(71, notification)
        if (paused) detach()
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun service(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, OverlayService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(io.github.vivotodoglow.R.drawable.ic_glow)
            .setContentTitle(if (paused) "光点待办已暂停" else "光点待办运行中")
            .setContentText("桌面展开 · 离开桌面收起 · 5秒撤销")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, if (paused) "恢复" else "暂停", service(if (paused) "resume" else "pause", 1))
            .addAction(0, "关闭", service("close", 2)).build()
    }
    private fun createViews() {
        params = WindowManager.LayoutParams(panelWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = panelX; y = panelY }
        root = FrameLayout(this)
        panel = column().apply {
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = rounded(Color.argb(224, 11, 20, 32), dp(22).toFloat(), Color.rgb(57, 85, 101))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        counter = label("待办", 15f, GLOW).apply {
            setPadding(dp(4), 0, 0, 0); contentDescription = "拖动调整面板位置"
        }
        header.addView(counter, LinearLayout.LayoutParams(0, dp(48), 1f))
        dragHandle(counter, false)
        header.addView(action("＋") { openApp(add = true) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(action("收起") { expanded = false; updateMode() }, LinearLayout.LayoutParams(dp(62), dp(48)).apply { leftMargin = dp(4) })
        panel.addView(header)
        panel.space(8)
        list = TaskListView(this, true) { openApp(edit = it.id) }
        val taskScroll = CappedScrollView(this) {
            // Reserve undo space only when needed, so all 10 rows fit at standard font size.
            (safeHeight - dp(132) - if (TaskPolicy.pending(app.snapshot.value, System.currentTimeMillis()).isNotEmpty()) undoHeight else 0).coerceAtLeast(dp(40))
        }
        taskScroll.addView(list)
        panel.addView(taskScroll, LinearLayout.LayoutParams(-1, -2))
        undo = UndoListView(this, true)
        val undoScroll = CappedScrollView(this) { undoHeight }
        undoScroll.addView(undo)
        panel.addView(undoScroll)
        panel.addView(action("管理全部待办 ↗") { openApp() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        root.addView(panel, FrameLayout.LayoutParams(-1, -2))
        bubble = label("✓", 23f, GLOW).apply {
            gravity = Gravity.CENTER; background = rounded(Color.argb(240, 16, 36, 48), dp(28).toFloat(), GLOW)
            contentDescription = "展开光点待办"; setOnClickListener { expanded = true; updateMode() }
        }
        dragHandle(bubble, true)
        root.addView(bubble, FrameLayout.LayoutParams(dp(56), dp(56)))
        updateMode()
    }
    private fun renderUndo() {
        if (!::undo.isInitialized) return
        val tasks = app.snapshot.value
        val pending = TaskPolicy.pending(tasks, System.currentTimeMillis())
        undo.render(tasks, System.currentTimeMillis())
        if (::bubble.isInitialized) {
            bubble.text = if (pending.isEmpty()) "✓" else "↶ ${pending.size}"
            bubble.contentDescription = if (pending.isEmpty()) "展开光点待办" else "${pending.size}项可撤销，点击展开，撤销窗口5秒"
        }
    }
    private fun updateMode() {
        if (!::params.isInitialized) return
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        bubble.visibility = if (expanded) View.GONE else View.VISIBLE
        params.width = if (expanded) panelWidth else dp(56)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.x = panelX.coerceIn(dp(6), (resources.displayMetrics.widthPixels - params.width - dp(6)).coerceAtLeast(dp(6)))
        params.y = panelY.coerceIn(dp(8), (safeHeight - if (expanded) dp(140) else dp(56)).coerceAtLeast(dp(8)))
        if (attached) runCatching { wm.updateViewLayout(root, params) }
        root.post { clampPosition() }
    }
    private fun clampPosition() {
        if (!attached || dragging) return
        val maximumY = (safeHeight - root.height).coerceAtLeast(dp(8))
        val newY = params.y.coerceIn(dp(8), maximumY)
        if (params.y != newY) { params.y = newY; runCatching { wm.updateViewLayout(root, params) } }
    }
    private fun dragHandle(view: View, click: Boolean) {
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) dragging = true
                    if (dragging) {
                        params.x = (originX + dx.toInt()).coerceIn(dp(6), (resources.displayMetrics.widthPixels - params.width - dp(6)).coerceAtLeast(dp(6)))
                        params.y = (originY + dy.toInt()).coerceIn(dp(8), (safeHeight - root.height).coerceAtLeast(dp(8)))
                        if (attached) runCatching { wm.updateViewLayout(root, params) }
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        panelX = params.x; panelY = params.y
                        scope.launch { app.settings.position(panelX, panelY) }
                    } else if (click) target.performClick()
                    dragging = false; true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
    }
    private fun attach() {
        if (!attached && Settings.canDrawOverlays(this)) {
            try { wm.addView(root, params); attached = true; root.post { clampPosition() } }
            catch (_: Exception) { stopSelf() }
        }
    }
    private fun detach() { if (attached) { runCatching { wm.removeView(root) }; attached = false } }
    private fun openApp(add: Boolean = false, edit: Long = -1) {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("add", add).putExtra("edit", edit))
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasAttached = attached
        detach(); createViews()
        panel.background = rounded(Color.argb(opacity * 255 / 100, 11, 20, 32), dp(22).toFloat(), Color.rgb(57, 85, 101))
        list.render(app.snapshot.value); renderUndo()
        if (wasAttached) attach()
    }
    override fun onDestroy() {
        detach(); scope.cancel(); unregisterReceiver(screenReceiver); stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

private class CappedScrollView(context: Context, private val limit: () -> Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit(), MeasureSpec.AT_MOST))
    }
}
