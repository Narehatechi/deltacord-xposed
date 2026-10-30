package io.github.deltacord.xposed.tweaks

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import io.github.deltacord.xposed.DeltacordConstants
import io.github.deltacord.xposed.api.registerNativeMethod
import io.github.deltacord.xposed.methodHook
import io.github.deltacord.xposed.tweak
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The animated dark world over the whole app, with Deltacord's own themes: a scrolling diamond
 * grid, coloured lights drifting and changing colour, a glowing pulse, and sparkles. Everything but
 * the sparkles is drawn with the "screen" blend, which only brightens: dark backgrounds glow,
 * white text stays as it is. It never takes taps, pauses when Discord isn't on screen and in
 * battery saver, and follows `files/pyoncord/deltacord-world.json` (written by Deltacord when the
 * theme or the setting changes).
 */
private const val FPS = 24
private const val CONFIG_FILE = "deltacord-world.json"

private class World(val grid: Int, val lights: IntArray, val sparkles: IntArray)

private val WORLDS = mapOf(
    "deltarune" to World(0xFF3A1462.toInt(), intArrayOf(0xFF7A2CFF.toInt(), 0xFFFF2E9A.toInt(), 0xFF14E0FF.toInt(), 0xFF6A3BFF.toInt()), intArrayOf(Color.WHITE, 0xFFFFF27A.toInt(), 0xFF7AF4FF.toInt(), 0xFFFF8AD8.toInt())),
    "lightworld" to World(0xFF2E2E2E.toInt(), intArrayOf(0xFF8C8C8C.toInt(), 0xFFFFFFFF.toInt(), 0xFF6E6E6E.toInt()), intArrayOf(Color.WHITE, 0xFFFFF27A.toInt())),
    "crimson" to World(0xFF4A0B12.toInt(), intArrayOf(0xFFFF2A2A.toInt(), 0xFFFF7A3C.toInt(), 0xFFB0103A.toInt()), intArrayOf(Color.WHITE, 0xFFFF6B6B.toInt(), 0xFFFFB36B.toInt())),
)

private data class WorldConfig(val on: Boolean, val world: String, val strength: Float)

/** A short effect: a tap's burst, or hearts rising from a sent message. */
private class Effect(val kind: String, val x: Float, val y: Float, val start: Long, val seed: Int)

private const val TAP_MS = 420L
private const val SEND_MS = 1100L

@SuppressLint("ViewConstructor")
private class DarkWorldView(context: Context, private val configFile: File) : View(context) {
    private val started = SystemClock.uptimeMillis()
    private var config = WorldConfig(false, "deltarune", 1f)
    private var configReadAt = 0L
    private var configModified = -1L
    var running = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation()
        }
    private val power = context.getSystemService(PowerManager::class.java)
    private val density = resources.displayMetrics.density
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.SCREEN else xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val sparklePaint = Paint()
    private val effects = ArrayList<Effect>()
    private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** Where the last tap was (on screen), so a sent message's hearts rise from the send button. */
    var lastTap: Pair<Float, Float>? = null

    /** Plays an effect at a point on the screen. */
    fun play(kind: String, screenX: Float, screenY: Float) {
        if (!config.on) return
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        effects.add(Effect(kind, screenX - loc[0], screenY - loc[1], SystemClock.uptimeMillis(), (screenX * 31 + screenY).toInt()))
        if (effects.size > 12) effects.removeAt(0)
        invalidate()
    }

    private val sparkles = List(18) { Random(it * 104729 + 3).let { r -> floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()) } }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setWillNotDraw(false)
    }

    // Never takes a touch: everything goes through to Discord.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    private fun readConfig() {
        val now = SystemClock.uptimeMillis()
        if (now - configReadAt < 2000) return
        configReadAt = now
        val modified = if (configFile.exists()) configFile.lastModified() else 0L
        if (modified == configModified) return
        configModified = modified
        config = runCatching {
            val json = JSONObject(configFile.readText())
            val strength = when (json.optString("strength")) { "subtle" -> 0.55f; "strong" -> 1.6f; else -> 1f }
            WorldConfig(json.optBoolean("on"), json.optString("world", "deltarune"), strength)
        }.getOrDefault(WorldConfig(false, "deltarune", 1f))
    }

    private fun mix(a: Int, b: Int, k: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
    )

    private fun cycle(colors: IntArray, x: Float): Int {
        val f = ((x % 1f) + 1f) % 1f * colors.size
        val i = floor(f).toInt()
        return mix(colors[i % colors.size], colors[(i + 1) % colors.size], f - i)
    }

    private fun withAlpha(color: Int, alpha: Float) = Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        readConfig()
        val active = running && config.on && power?.isPowerSaveMode != true
        if (!active) {
            // Check again in a bit (the setting or battery saver may change).
            if (running) postInvalidateDelayed(1000)
            return
        }
        val world = WORLDS[config.world] ?: WORLDS.getValue("deltarune")
        val s = config.strength
        val t = (SystemClock.uptimeMillis() - started) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()

        // Two coloured lights drifting, their colours cycling.
        for (k in 0..1) {
            val color = cycle(world.lights, t * 0.03f + k * 0.5f)
            val cx = if (k == 0) w * (0.3f + 0.2f * sin(t * 0.21f)) else w * (0.75f + 0.15f * cos(t * 0.19f))
            val cy = if (k == 0) h * (0.25f + 0.1f * cos(t * 0.17f)) else h * (0.7f + 0.12f * sin(t * 0.23f))
            val r = max(w, h) * 0.6f
            screenPaint.shader = RadialGradient(cx, cy, r, withAlpha(color, 0.13f * s), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, screenPaint)
        }
        screenPaint.shader = null

        // The scrolling diamond grid.
        val cell = 46 * density
        val shift = (t * 16 * density) % cell
        screenPaint.color = withAlpha(world.grid, 0.55f * s)
        screenPaint.strokeWidth = 1.5f * density
        var x = -h - cell + shift
        while (x < w + cell) {
            canvas.drawLine(x, 0f, x + h, h, screenPaint)
            canvas.drawLine(x + h, 0f, x, h, screenPaint)
            x += cell
        }

        // A glowing band travelling down the screen.
        val pulseY = (t * 0.25f % 1.4f - 0.2f) * h
        val pulse = cycle(world.lights, t * 0.03f)
        screenPaint.shader = LinearGradient(0f, pulseY - 140f, 0f, pulseY + 140f,
            intArrayOf(Color.TRANSPARENT, withAlpha(pulse, 0.09f * s), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, max(0f, pulseY - 140f), w, pulseY + 140f, screenPaint)
        screenPaint.shader = null

        // Sparkles drifting up, drawn normally so they show everywhere.
        val p = 1.5f * density
        for (star in sparkles) {
            val (fx, fy, phase, speed) = star.toList()
            val y = ((fy * h - t * (10 + speed * 22) * density / 2.5f) % h + h) % h
            val sx = fx * w + sin(t * 0.6f + phase * 6) * 12
            val twinkle = (sin(t * (1.5f + speed * 2.5f) + phase * 6.283f) + 1) / 2
            sparklePaint.color = withAlpha(world.sparkles[(phase * world.sparkles.size).toInt() % world.sparkles.size], (0.1f + 0.45f * twinkle) * minOf(1f, s))
            val arm = (2.5f + speed * 3.5f) * density * (0.5f + 0.5f * twinkle)
            canvas.drawRect(sx - arm, y - p / 2, sx + arm, y + p / 2, sparklePaint)
            canvas.drawRect(sx - p / 2, y - arm, sx + p / 2, y + arm, sparklePaint)
        }

        drawEffects(canvas, world)

        if (running) postInvalidateDelayed(1000L / FPS)
    }

    private fun drawEffects(canvas: Canvas, world: World) {
        val now = SystemClock.uptimeMillis()
        effects.removeAll { now - it.start > (if (it.kind == "send") SEND_MS else TAP_MS) }
        val d = density
        for (e in effects) {
            val rnd = Random(e.seed)
            if (e.kind == "send") {
                val k = (now - e.start) / SEND_MS.toFloat()
                // A ring and a flash where the message went.
                ringPaint.strokeWidth = 3 * d
                ringPaint.color = withAlpha(Color.WHITE, (1 - k) * 0.9f)
                canvas.drawCircle(e.x, e.y, (10 + 70 * k) * d, ringPaint)
                // Little SOULs rising and swaying, fading out.
                for (i in 0 until 6) {
                    val sx = e.x + (rnd.nextFloat() - 0.5f) * 90 * d + sin(k * 9f + i) * 10 * d
                    val sy = e.y - (40 + rnd.nextFloat() * 120) * d * k - 10 * d
                    val size = (1.6f + rnd.nextFloat() * 1.2f) * d
                    drawHeart(canvas, sx, sy, size, withAlpha(0xFFFF2020.toInt(), (1 - k) * 1.0f))
                }
                // And sparkles bursting out.
                for (i in 0 until 10) {
                    val ang = rnd.nextFloat() * 6.283f
                    val dist = (20 + rnd.nextFloat() * 70) * d * k
                    val c = world.sparkles[i % world.sparkles.size]
                    effectPaint.color = withAlpha(c, (1 - k) * 0.95f)
                    val sz = 3 * d * (1 - k * 0.5f)
                    canvas.drawRect(e.x + cos(ang) * dist - sz / 2, e.y + sin(ang) * dist - sz / 2, e.x + cos(ang) * dist + sz / 2, e.y + sin(ang) * dist + sz / 2, effectPaint)
                }
            } else {
                val k = (now - e.start) / TAP_MS.toFloat()
                // An expanding ring in the world's colour, with pixels flying out.
                val c = cycle(world.lights, e.seed * 0.013f)
                ringPaint.strokeWidth = 2.5f * d * (1 - k)
                ringPaint.color = withAlpha(Color.WHITE, (1 - k) * 0.85f)
                canvas.drawCircle(e.x, e.y, (6 + 34 * k) * d, ringPaint)
                ringPaint.color = withAlpha(c, (1 - k) * 0.8f)
                canvas.drawCircle(e.x, e.y, (4 + 22 * k) * d, ringPaint)
                for (i in 0 until 8) {
                    val ang = i * 0.785f + rnd.nextFloat() * 0.4f
                    val dist = (8 + 36 * k) * d
                    effectPaint.color = withAlpha(world.sparkles[i % world.sparkles.size], (1 - k))
                    val sz = 2.5f * d
                    canvas.drawRect(e.x + cos(ang) * dist - sz / 2, e.y + sin(ang) * dist - sz / 2, e.x + cos(ang) * dist + sz / 2, e.y + sin(ang) * dist + sz / 2, effectPaint)
                }
            }
        }
        if (effects.isNotEmpty() && !running) invalidate()
    }

    private val heart = arrayOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...")

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, px: Float, color: Int) {
        effectPaint.color = color
        val left = cx - heart[0].length * px / 2
        val top = cy - heart.size * px / 2
        for (y in heart.indices) for (x in heart[y].indices) {
            if (heart[y][x] == 'X') canvas.drawRect(left + x * px, top + y * px, left + (x + 1) * px + 0.5f, top + (y + 1) * px + 0.5f, effectPaint)
        }
    }
}

private val overlays = WeakHashMap<Activity, DarkWorldView>()

/** The overlay of the screen that's showing. */
private fun current(): DarkWorldView? = overlays.values.firstOrNull { it.running }

/** Where a touch went down (to tell taps from scrolls). */
private var downX = 0f
private var downY = 0f
private var downAt = 0L

val darkWorldOverlay by tweak {
    XposedBridge.hookAllMethods(Activity::class.java, "onResume", methodHook {
        after {
            val activity = thisObject as? Activity ?: return@after
            runCatching {
                val view = overlays[activity] ?: run {
                    val decor = activity.window?.decorView as? ViewGroup ?: return@after
                    val file = File(activity.applicationInfo.dataDir, "${DeltacordConstants.FILES_DIR}/$CONFIG_FILE")
                    DarkWorldView(activity, file).also {
                        // Over Discord, under the startup animation.
                        it.translationZ = 5_000f
                        decor.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        overlays[activity] = it
                    }
                }
                view.running = true
            }
        }
    }.build())
    XposedBridge.hookAllMethods(Activity::class.java, "onPause", methodHook {
        after { overlays[thisObject as? Activity]?.running = false }
    }.build())

    // Taps (not scrolls): a burst where the finger lifted. Only watched, never changed.
    XposedBridge.hookAllMethods(Activity::class.java, "dispatchTouchEvent", methodHook {
        before {
            val event = args.firstOrNull() as? android.view.MotionEvent ?: return@before
            val view = overlays[thisObject as? Activity] ?: return@before
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; downAt = SystemClock.uptimeMillis()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val slop = 14 * view.resources.displayMetrics.density
                    val quick = SystemClock.uptimeMillis() - downAt < 400
                    if (quick && kotlin.math.abs(event.rawX - downX) < slop && kotlin.math.abs(event.rawY - downY) < slop) {
                        view.lastTap = event.rawX to event.rawY
                        view.play("tap", event.rawX, event.rawY)
                    }
                }
            }
        }
    }.build())

    // Deltacord asks for effects: "send" (hearts rising from the send button, the last tap).
    registerNativeMethod("deltacord.world.effect") { args ->
        val kind = args.firstOrNull() as? String ?: "send"
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val view = current() ?: return@post
            val (x, y) = view.lastTap ?: (view.width * 0.88f to view.height * 0.9f)
            view.play(kind, x, y)
        }
        true
    }
}
