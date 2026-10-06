package io.github.vivotodoglow.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.*
import io.github.vivotodoglow.data.TodoTask
import kotlin.math.*
import kotlin.random.Random

class TaskRow(context: Context, val task: TodoTask, onEdit: () -> Unit, onComplete: (TaskRow) -> Unit) : FrameLayout(context) {
    private val body = LinearLayout(context)
    private val done: Button = context.action("完成") {
        if (!isDissolving) { cancelPendingLongPress(); isDissolving = true; done.isEnabled = false; onComplete(this) }
    }
    /** The list handles reordering; a dedicated handle keeps dragging separate from swiping. */
    var onDrag: ((TaskRow, MotionEvent) -> Boolean)? = null
    var isDissolving = false
        private set
    private var startX = 0f
    private var startY = 0f
    private var initial = 0f
    private var horizontal = false
    private var moved = false
    private var gestureActive = false
    private var longPressed = false
    private var completionAnimator: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val reveal = context.dp(76).toFloat()
    private val longPress = Runnable {
        if (gestureActive && !moved && !isDissolving) {
            longPressed = true
            body.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            body.performLongClick()
        }
    }
    init {
        val subtitle = taskSubtitle(task)
        val baseline = if (subtitle.isEmpty()) 54 else 64
        val scaledMinimum = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, if (subtitle.isEmpty()) 38f else 50f, context.resources.displayMetrics
        ).roundToInt()
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, max(context.dp(baseline), scaledMinimum)).apply {
            bottomMargin = context.dp(6)
        }
        background = context.glowCard()
        clipChildren = false
        addView(done, LayoutParams(context.dp(76), LayoutParams.MATCH_PARENT, Gravity.END))
        done.visibility = INVISIBLE
        done.contentDescription = "完成：${task.title}"
        body.gravity = Gravity.CENTER_VERTICAL
        body.setPadding(context.dp(12), context.dp(7), context.dp(3), context.dp(7))
        body.background = context.glowCard()
        body.addView(context.label(if (task.isDaily) "↻" else "◌", 21f, GLOW).apply {
            gravity = Gravity.CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(27), LayoutParams.WRAP_CONTENT))
        val text = context.column().apply { gravity = Gravity.CENTER_VERTICAL }
        text.addView(context.label(task.title, 14f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (subtitle.isNotEmpty()) text.addView(context.label(subtitle, 10.5f,
            if (task.dueDate != null && task.dueDate < java.time.LocalDate.now().toEpochDay()) Color.rgb(255, 183, 162) else MUTED
        ).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, context.dp(4), 0, 0) })
        body.addView(text, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val drag = context.label("≡", 23f, MUTED).apply {
            gravity = Gravity.CENTER
            contentDescription = "拖动调整${task.title}的顺序"
            isClickable = true
            tooltipText = "按住拖动排序"
        }
        body.addView(drag, LinearLayout.LayoutParams(context.dp(42), LayoutParams.MATCH_PARENT))
        drag.setOnTouchListener { _, event ->
            cancelPendingLongPress()
            val listener = onDrag
            if (isDissolving || listener == null) return@setOnTouchListener false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            val handled = listener(this, event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            handled
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.contentDescription = "${task.title}，$subtitle，长按编辑，向左滑动显示完成按钮"
        body.setOnClickListener { if (!isDissolving) onEdit() }
        body.setOnLongClickListener { if (!isDissolving) onEdit(); true }
        body.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, "显示完成按钮"))
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_DISMISS && !isDissolving) { slide(-reveal); return true }
                return super.performAccessibilityAction(host, action, arguments)
            }
        }
        // The swipe listener consumes touches, so platform onLongClick alone never gets a timer.
        body.setOnTouchListener { view, event ->
            if (isDissolving) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    body.animate().cancel()
                    startX = event.rawX; startY = event.rawY; initial = body.translationX
                    horizontal = false; moved = false; longPressed = false; gestureActive = true
                    body.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (hypot(dx, dy) > slop) { moved = true; cancelPendingLongPress() }
                    if (!longPressed && !horizontal && abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) {
                        horizontal = true; body.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) {
                        done.visibility = VISIBLE
                        body.translationX = (initial + dx).coerceIn(-reveal, 0f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelPendingLongPress(); gestureActive = false
                    body.parent.requestDisallowInterceptTouchEvent(false)
                    if (horizontal) slide(if (body.translationX < -reveal * .4f) -reveal else 0f)
                    else if (!moved && !longPressed) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingLongPress(); gestureActive = false
                    body.parent.requestDisallowInterceptTouchEvent(false)
                    slide(if (body.translationX < -reveal * .4f) -reveal else 0f); true
                }
                else -> false
            }
        }
    }
    private fun cancelPendingLongPress() { body.removeCallbacks(longPress) }
    private fun slide(to: Float) {
        done.visibility = VISIBLE
        body.animate().translationX(to).setDuration(180).withEndAction { if (to == 0f) done.visibility = INVISIBLE }.start()
    }
    fun setDragging(dragging: Boolean) {
        cancelPendingLongPress()
        elevation = if (dragging) context.dp(8).toFloat() else 0f
        body.background = context.glowCard(highlighted = dragging)
        alpha = if (dragging) .96f else 1f
        if (dragging) performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
    }
    fun cancelCompletion() { isDissolving = false; done.isEnabled = true }
    /** Shared by the swipe button and the desktop widget's focused completion screen. */
    fun requestCompletion() { if (!isDissolving) done.performClick() }
    fun dissolve(finished: () -> Unit) {
        cancelPendingLongPress()
        if (completionAnimator != null) return
        if (width == 0 || height == 0) { isDissolving = false; finished(); return }
        body.animate().cancel()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(body.translationX, 0f)
        body.draw(canvas)
        body.visibility = INVISIBLE; done.visibility = INVISIBLE; background = null
        val particles = GlowDissolveView(context, bitmap, task.id)
        addView(particles, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        completionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            interpolator = LinearInterpolator()
            addUpdateListener { particles.progress = it.animatedValue as Float; particles.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completionAnimator = null
                    particles.release()
                    isDissolving = false
                    finished()
                }
            })
            start()
        }
    }
    override fun onDetachedFromWindow() {
        cancelPendingLongPress()
        super.onDetachedFromWindow()
    }
}

private data class GlowParticle(
    val x: Float, val y: Float, val dx: Float, val dy: Float, val radius: Float,
    val birth: Float, val phase: Float, val brightness: Float, val cyan: Boolean
)

/** The actual row erodes from its trailing edge into light; no full-row flash or explosive burst. */
internal class GlowDissolveView(context: Context, private val source: Bitmap, seed: Long) : View(context) {
    var progress = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val density = resources.displayMetrics.density
    private val random = Random(seed xor (source.width.toLong() shl 16) xor source.height.toLong())
    private val points = buildList {
        // Reservoir sampling visits the entire bitmap; a cap must never bias particles to the first text line.
        val textSamples = mutableListOf<Pair<Int, Int>>()
        var candidates = 0
        val step = max(1, (density * 1.15f).roundToInt())
        for (y in 0 until source.height step step) for (x in 0 until source.width step step) {
            val pixel = source.getPixel(x, y)
            if (Color.alpha(pixel) > 80 && maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 105) {
                candidates++
                if (textSamples.size < 1000) textSamples.add(x to y)
                else {
                    val replacement = random.nextInt(candidates)
                    if (replacement < textSamples.size) textSamples[replacement] = x to y
                }
            }
        }
        fun particle(x: Int, y: Int, bright: Boolean) = GlowParticle(
            x.toFloat(), y.toFloat(), (14f + random.nextFloat() * 34f) * density,
            -(5f + random.nextFloat() * 14f) * density, (.35f + random.nextFloat() * .52f) * density,
            .03f + (1f - x.toFloat() / source.width) * .54f,
            random.nextFloat() * (Math.PI * 2).toFloat(), if (bright) .85f + random.nextFloat() * .15f else .15f,
            random.nextFloat() < .22f
        )
        textSamples.forEach { (x, y) -> add(particle(x, y, true)) }
        val cardStep = max(5, (density * 8f).roundToInt())
        for (y in cardStep / 2 until source.height step cardStep) for (x in cardStep / 2 until source.width step cardStep) {
            if (Color.alpha(source.getPixel(x, y)) > 80) add(particle(x, y, false))
        }
    }
    override fun onDraw(canvas: Canvas) {
        if (source.isRecycled) return
        val erosion = ((progress - .03f) / .54f).coerceIn(0f, 1f)
        val edge = source.width * (1f - erosion)
        if (erosion < 1f) {
            path.reset(); path.moveTo(0f, 0f); path.lineTo(edge, 0f)
            for (y in 0..source.height step max(1, (density * 3).roundToInt())) {
                val wave = sin(y / (density * 9f) + progress * 14f) * density * 3f * sin(erosion * Math.PI).toFloat()
                path.lineTo((edge + wave).coerceIn(0f, source.width.toFloat()), y.toFloat())
            }
            path.lineTo(edge, source.height.toFloat()); path.lineTo(0f, source.height.toFloat()); path.close()
            val checkpoint = canvas.save(); canvas.clipPath(path)
            paint.color = Color.WHITE; paint.alpha = 255; paint.style = Paint.Style.FILL
            canvas.drawBitmap(source, 0f, 0f, paint); canvas.restoreToCount(checkpoint)
        }
        for (point in points) {
            val age = (progress - point.birth) / .40f
            if (age <= 0f || age >= 1f) continue
            val travel = 1f - (1f - age).pow(1.45f)
            val x = point.x + point.dx * travel
            val y = point.y + point.dy * travel + sin(age * 3.5f + point.phase) * density * age
            val fade = sin(age * Math.PI).toFloat().pow(.75f) * point.brightness
            val radius = point.radius * (1f - age * .62f)
            paint.style = Paint.Style.FILL
            paint.color = GLOW; paint.alpha = (fade * 30).toInt()
            canvas.drawCircle(x, y, radius * 3.6f, paint)
            paint.alpha = (fade * 48).toInt(); canvas.drawCircle(x, y, radius * 1.9f, paint)
            if (point.brightness > .5f && age > .12f) {
                paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = max(.5f, radius * .7f)
                paint.alpha = (fade * 72 * (1f - age)).toInt()
                canvas.drawLine(x - density * (1.5f + age * 2.5f), y + density * age, x, y, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = if (point.cyan) Color.rgb(159, 238, 255) else Color.rgb(238, 252, 255)
            paint.alpha = (fade * 245).toInt()
            canvas.drawCircle(x, y, radius, paint)
        }
    }
    fun release() { if (!source.isRecycled) source.recycle() }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
}
