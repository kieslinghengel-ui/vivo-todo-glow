package io.github.vivotodoglow.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import io.github.vivotodoglow.data.TodoTask
import kotlin.math.*
import kotlin.random.Random

class TaskRow(context: Context, val task: TodoTask, onEdit: () -> Unit, onComplete: (TaskRow) -> Unit) : FrameLayout(context) {
    private val body = LinearLayout(context)
    private val done: Button = context.action("完成") {
        if (!isDissolving) { isDissolving = true; done.isEnabled = false; onComplete(this) }
    }
    var isDissolving = false
        private set
    private var startX = 0f
    private var startY = 0f
    private var initial = 0f
    private var horizontal = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val reveal = context.dp(76).toFloat()
    init {
        val rowHeight = max(context.dp(52), android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 36f, context.resources.displayMetrics).roundToInt())
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, rowHeight).apply { bottomMargin = context.dp(5) }
        background = rounded(CARD, context.dp(12).toFloat())
        clipChildren = false
        addView(done, LayoutParams(context.dp(76), LayoutParams.MATCH_PARENT, Gravity.END))
        done.visibility = INVISIBLE
        body.gravity = Gravity.CENTER_VERTICAL
        body.setPadding(context.dp(14), 0, context.dp(12), 0)
        body.background = rounded(CARD, context.dp(12).toFloat())
        val dot = context.label("○", 20f, GLOW)
        body.addView(dot, LinearLayout.LayoutParams(context.dp(28), LayoutParams.WRAP_CONTENT))
        body.addView(context.label(task.title, 14f).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.contentDescription = "${task.title}，点击编辑，向左滑动显示完成按钮"
        body.setOnClickListener { if (!isDissolving) onEdit() }
        body.setOnLongClickListener { if (!isDissolving) onEdit(); true }
        body.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, "显示完成按钮"))
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: android.os.Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_DISMISS) { slide(-reveal); return true }
                return super.performAccessibilityAction(host, action, arguments)
            }
        }
        body.setOnTouchListener { view, event ->
            if (isDissolving) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY; initial = body.translationX; horizontal = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (!horizontal && abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) {
                        horizontal = true; body.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) {
                        done.visibility = VISIBLE
                        body.translationX = (initial + dx).coerceIn(-reveal, 0f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    body.parent.requestDisallowInterceptTouchEvent(false)
                    if (horizontal) slide(if (body.translationX < -reveal * .4f) -reveal else 0f)
                    else if (abs(event.rawX - startX) < slop && abs(event.rawY - startY) < slop) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    body.parent.requestDisallowInterceptTouchEvent(false)
                    slide(if (body.translationX < -reveal * .4f) -reveal else 0f); true
                }
                else -> false
            }
        }
    }
    private fun slide(to: Float) {
        done.visibility = VISIBLE
        body.animate().translationX(to).setDuration(160).withEndAction { if (to == 0f) done.visibility = INVISIBLE }.start()
    }
    fun cancelCompletion() { isDissolving = false; done.isEnabled = true }
    fun dissolve(finished: () -> Unit) {
        if (width == 0 || height == 0) { isDissolving = false; finished(); return }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(body.translationX, 0f)
        body.draw(canvas)
        body.visibility = INVISIBLE; done.visibility = INVISIBLE; background = null
        val particles = ParticleView(context, bitmap)
        bitmap.recycle()
        addView(particles, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            addUpdateListener { particles.progress = it.animatedValue as Float; particles.invalidate() }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                isDissolving = false; finished()
            }
        })
        animator.start()
    }
}

private data class Particle(val x: Float, val y: Float, val dx: Float, val dy: Float, val radius: Float)
private class ParticleView(context: Context, bitmap: Bitmap) : View(context) {
    var progress = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val points = buildList {
        val step = max(3, context.dp(3))
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
            val pixel = bitmap.getPixel(x, y)
            val bright = max(Color.red(pixel), max(Color.green(pixel), Color.blue(pixel)))
            if (bright > 110 && Color.alpha(pixel) > 80 && size < 650) add(
                Particle(x.toFloat(), y.toFloat(), Random.nextFloat() * context.dp(76) - context.dp(20),
                    -Random.nextFloat() * context.dp(45), context.dp(1).toFloat() + Random.nextFloat() * context.dp(1))
            )
        }
        if (isEmpty()) repeat(90) {
            add(Particle(Random.nextFloat() * bitmap.width, Random.nextFloat() * bitmap.height,
                Random.nextFloat() * context.dp(60), -Random.nextFloat() * context.dp(36), context.dp(1.5f).toFloat()))
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in points) {
            val x = p.x + p.dx * progress; val y = p.y + p.dy * progress + context.dp(20) * progress * progress
            paint.color = GLOW; paint.alpha = ((1f - progress) * 40).toInt()
            canvas.drawCircle(x, y, p.radius * 3, paint)
            paint.color = Color.rgb(224, 252, 255); paint.alpha = ((1f - progress) * 255).toInt()
            canvas.drawCircle(x, y, p.radius * (1f - progress * .5f), paint)
        }
    }
}
