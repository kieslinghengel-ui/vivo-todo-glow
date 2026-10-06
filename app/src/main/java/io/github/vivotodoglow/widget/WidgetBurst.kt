package io.github.vivotodoglow.widget

import android.content.Context
import android.graphics.*
import android.text.TextUtils
import android.widget.RemoteViews
import io.github.vivotodoglow.R
import io.github.vivotodoglow.data.TodoTask
import kotlin.math.*
import kotlin.random.Random

/** Canvas renders the frames here; the launcher plays them locally, without per-frame IPC. */
internal object WidgetBurst {
    const val FRAME_MS = 40
    const val FRAMES = 20
    const val LIFETIME_MS = 900L
    const val WIDTH = 256
    const val HEIGHT = 64
    // Bounded bitmap memory: at most two bursts (~2.7 MiB) in any one widget update.
    private const val MAX_ACTIVE = 2
    data class Burst(val task: TodoTask, val index: Int, val started: Long, val frames: List<Bitmap>) {
        val deadline get() = started + LIFETIME_MS
        val stableId get() = Long.MIN_VALUE + task.id
    }
    private val bursts = LinkedHashMap<Long, Burst>()

    @Synchronized fun begin(task: TodoTask, index: Int): Burst {
        val frames = renderFrames(task)
        // Rendering precedes the countdown so the user gets the full animation.
        val burst = Burst(task, index, System.currentTimeMillis(), frames)
        bursts[task.id] = burst
        while (bursts.size > MAX_ACTIVE) bursts.remove(bursts.keys.first())
        return burst
    }
    @Synchronized fun discard(taskId: Long) { bursts.remove(taskId) }
    @Synchronized fun clear() { bursts.clear() }
    @Synchronized fun active(tasks: List<TodoTask>, now: Long = System.currentTimeMillis()): List<Burst> {
        bursts.entries.removeAll { now >= it.value.deadline }
        return bursts.values.filter { burst ->
            tasks.any { it.id == burst.task.id && (it.deleteAfter != null || it.hiddenUntil != null) }
        }.sortedWith(compareBy<Burst> { it.index }.thenBy { it.started })
    }

    fun views(context: Context, burst: Burst, now: Long): RemoteViews {
        val player = RemoteViews(context.packageName, R.layout.widget_burst)
        player.removeAllViews(R.id.widget_burst)
        burst.frames.forEach { bitmap ->
            player.addView(R.id.widget_burst, RemoteViews(context.packageName, R.layout.widget_burst_frame).apply {
                setImageViewBitmap(R.id.widget_burst_frame, bitmap)
            })
        }
        // Transparent tail prevents an immediate repeat while the completion broadcast
        // removes the animation row. Reuse the same bitmap; do not allocate extra frames.
        repeat(110) {
            player.addView(R.id.widget_burst, RemoteViews(context.packageName, R.layout.widget_burst_frame).apply {
                setImageViewBitmap(R.id.widget_burst_frame, burst.frames.last())
            })
        }
        val frame = ((now - burst.started).coerceAtLeast(0) / FRAME_MS).toInt().coerceAtMost(FRAMES - 1)
        player.setInt(R.id.widget_burst, "setDisplayedChild", frame)
        return player
    }

    fun renderFrames(task: TodoTask): List<Bitmap> {
        val random = Random(task.id)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xfff4fcff.toInt(); textSize = 17f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val title = TextUtils.ellipsize(task.title, textPaint, 198f, TextUtils.TruncateAt.END).toString()
        val source = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(source)
        paint.color = 0xee15364a.toInt()
        sourceCanvas.drawRoundRect(1f, 7f, 255f, 57f, 11f, 11f, paint)
        paint.color = 0x6679ddeb
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.7f
        sourceCanvas.drawRoundRect(1f, 7f, 255f, 57f, 11f, 11f, paint)
        paint.style = Paint.Style.FILL
        sourceCanvas.drawText(title, 12f, 38f, textPaint)
        textPaint.color = 0xffb5faff.toInt(); textPaint.textSize = 24f
        sourceCanvas.drawText("✓", 223f, 40f, textPaint)
        data class Spark(val x: Float, val y: Float, val vx: Float, val vy: Float, val size: Float, val color: Int)
        val candidates = mutableListOf<Pair<Float, Float>>()
        // Sample bright text and card edges so the title itself turns into glowing dust.
        for (y in 8 until 56 step 2) for (x in 3 until 253 step 2) {
            val pixel = source.getPixel(x, y)
            if (Color.red(pixel) > 90) candidates.add(x.toFloat() to y.toFloat())
        }
        val sparks = List(420) { index ->
            val origin = if (index < 280 && candidates.isNotEmpty()) candidates[random.nextInt(candidates.size)]
                else random.nextFloat() * 250f + 3f to random.nextFloat() * 46f + 9f
            Spark(origin.first, origin.second, 32f + random.nextFloat() * 72f,
                (random.nextFloat() - .5f) * 72f, .45f + random.nextFloat() * 1.25f,
                if (index % 5 == 0) 0xffd1b6ff.toInt() else if (index % 3 == 0) Color.WHITE else 0xff83efff.toInt())
        }
        val frames = List(FRAMES) { frame ->
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            if (frame == FRAMES - 1) return@List bitmap
            val canvas = Canvas(bitmap)
            val t = frame.toFloat() / (FRAMES - 1)
            val edge = WIDTH * (1f - (t / .72f).coerceIn(0f, 1f))
            val save = canvas.save()
            canvas.clipRect(0f, 0f, edge, HEIGHT.toFloat())
            paint.shader = null; paint.alpha = ((1f - t * .65f) * 255).toInt()
            canvas.drawBitmap(source, 0f, 0f, paint)
            canvas.restoreToCount(save)
            paint.alpha = 255
            if (t > 0f && t < .75f) {
                val glow = (sin(t * PI) * .8).toFloat()
                paint.shader = LinearGradient(edge - 18, 0f, edge + 18, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.argb((glow * 165).toInt(), 170, 250, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                canvas.drawRect(edge - 18, 2f, edge + 18, 62f, paint)
                paint.shader = null
            }
            sparks.forEach { spark ->
                val birth = (1f - spark.x / WIDTH) * .55f
                val age = t - birth
                if (age <= 0 || age > .58f) return@forEach
                val fade = sin((age / .58f) * PI).toFloat() * (1f - t).pow(.65f)
                val x = spark.x + spark.vx * age
                val y = spark.y + spark.vy * age + sin(age * 11 + spark.x) * 3f
                paint.shader = RadialGradient(x, y, spark.size * 3.5f,
                    intArrayOf(Color.argb((fade * 90).toInt(), 155, 244, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                paint.alpha = 255
                canvas.drawCircle(x, y, spark.size * 3.5f, paint)
                paint.shader = null; paint.color = spark.color; paint.alpha = (fade * 230).toInt()
                paint.strokeWidth = spark.size * .6f
                canvas.drawLine(x - spark.vx * .045f, y - spark.vy * .045f, x, y, paint)
                canvas.drawCircle(x, y, spark.size, paint)
                if (spark.size > 1.4f) {
                    paint.strokeWidth = .55f
                    canvas.drawLine(x - 3f, y, x + 3f, y, paint)
                    canvas.drawLine(x, y - 3f, x, y + 3f, paint)
                }
            }
            bitmap
        }
        source.recycle()
        return frames
    }
}
