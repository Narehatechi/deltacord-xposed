package io.github.deltacord.xposed.tweaks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.deltacord.Logger
import io.github.deltacord.xposed.DeltacordConstants
import io.github.deltacord.xposed.hook
import io.github.deltacord.xposed.method
import io.github.deltacord.xposed.tweak
import java.io.File
import java.util.WeakHashMap
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * Deltacord's startup animation, shown instead of Discord's logo while Discord starts.
 *
 * In the style of deltarune.com: on black, "DEL", then "TA", then "CORD" glow in, one after the
 * other, in white Determination Mono, as one word running along the long side of the screen.
 * Then a little "by Narehatechi" spins in near the bottom edge and wobbles.
 * It stays (gently glowing) until Discord has drawn its first screen, then fades away.
 *
 * Discord normally holds its logo on screen until it's ready by blocking drawing; while the
 * animation is up that block is lifted (Discord's own check still decides when it's ready), so the
 * animation can be seen. Tapping the animation closes it.
 *
 * Turned off by creating `files/pyoncord/startup-animation-off` (Deltacord > Extras does this).
 */
val startupAnimation by tweak {
    val dataDir = appInfo.dataDir
    if (File(dataDir, "${DeltacordConstants.FILES_DIR}/startup-animation-off").exists()) {
        log.i("Startup animation is turned off")
        return@tweak
    }

    StartupAnimation.log = log
    StartupAnimation.fontFile = extractFont(modulePath, dataDir, log)
    StartupAnimation.waitingForDiscord = true

    // Let the window draw while the animation is up, and learn when Discord says it's ready.
    ViewTreeObserver::class.java.method("addOnPreDrawListener", ViewTreeObserver.OnPreDrawListener::class.java).hook {
        before {
            val listener = args[0] as? ViewTreeObserver.OnPreDrawListener ?: return@before
            if (listener is DrawGate || !StartupAnimation.waitingForDiscord) return@before
            args[0] = StartupAnimation.gateFor(listener)
        }
    }
    ViewTreeObserver::class.java.method("removeOnPreDrawListener", ViewTreeObserver.OnPreDrawListener::class.java).hook {
        before {
            val listener = args[0] as? ViewTreeObserver.OnPreDrawListener ?: return@before
            StartupAnimation.existingGate(listener)?.let { args[0] = it }
        }
    }

    withAppActivity { activity ->
        activity.runOnUiThread {
            try {
                StartupAnimation.show(activity)
            } catch (e: Throwable) {
                log.e("Couldn't show the startup animation", e)
                StartupAnimation.waitingForDiscord = false
            }
        }
    }
}

/** Copies the font out of the module APK once, so Android can load it. */
private fun extractFont(modulePath: String, dataDir: String, log: Logger): File? = try {
    val target = File(dataDir, "${DeltacordConstants.CACHE_DIR}/startup/DeterminationMono.ttf")
    ZipFile(modulePath).use { zip ->
        val entry = zip.getEntry("assets/fonts/DeterminationMono.ttf") ?: error("font missing from module")
        if (!target.exists() || target.length() != entry.size) {
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
    target
} catch (e: Throwable) {
    log.w("Startup animation font unavailable, using a fallback", e)
    null
}

/** Wraps a pre-draw listener so that, while the animation is up, it can't stop the window drawing. */
private class DrawGate(val original: ViewTreeObserver.OnPreDrawListener) : ViewTreeObserver.OnPreDrawListener {
    var blocked = false

    override fun onPreDraw(): Boolean {
        val result = original.onPreDraw()
        if (!result) {
            blocked = true
            // Discord still wants its logo. Draw anyway while the animation covers the screen.
            if (StartupAnimation.waitingForDiscord) return true
        } else if (blocked) {
            blocked = false
            StartupAnimation.onDiscordReady()
        }
        return result
    }
}

private object StartupAnimation {
    @Volatile
    var waitingForDiscord = false

    var log: Logger? = null
    var fontFile: File? = null

    private val gates = WeakHashMap<ViewTreeObserver.OnPreDrawListener, DrawGate>()
    private var view: AnimationView? = null
    private var discordReady = false
    private var sawBlockingListener = false
    private val handler = Handler(Looper.getMainLooper())

    /** Never cover Discord for longer than this after the animation has played. */
    private const val MAX_EXTRA_MS = 12_000L

    fun gateFor(listener: ViewTreeObserver.OnPreDrawListener): DrawGate =
        synchronized(gates) { gates.getOrPut(listener) { DrawGate(listener) } }

    fun existingGate(listener: ViewTreeObserver.OnPreDrawListener): DrawGate? =
        synchronized(gates) { gates[listener] }

    fun onDiscordReady() {
        sawBlockingListener = true
        discordReady = true
        handler.post { maybeFinish() }
    }

    fun show(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val typeface = fontFile?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        val animation = AnimationView(activity, typeface)
        animation.translationZ = 10_000f
        animation.setOnClickListener { finish(force = true) }
        decor.addView(animation, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = animation

        // Check regularly once the animation has played; give up waiting after a while.
        val started = SystemClock.uptimeMillis()
        val check = object : Runnable {
            override fun run() {
                if (view == null) return
                val elapsed = SystemClock.uptimeMillis() - started
                if (elapsed > AnimationView.PLAY_MS + MAX_EXTRA_MS) {
                    log?.w("Discord didn't report ready in time; closing the startup animation")
                    finish(force = true)
                    return
                }
                maybeFinish()
                if (view != null) handler.postDelayed(this, 200)
            }
        }
        handler.postDelayed(check, AnimationView.PLAY_MS)
    }

    private fun maybeFinish() {
        val animation = view ?: return
        if (!animation.played) return
        // Ready when Discord's own "keep the logo" check lets go, or (if it has none) when its screen has content.
        if (discordReady || (!sawBlockingListener && hasDiscordContent(animation.rootView))) finish(force = false)
    }

    private fun finish(force: Boolean) {
        val animation = view ?: return
        view = null
        waitingForDiscord = false
        animation.animate()
            .alpha(0f)
            .setDuration(if (force) 200 else 450)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    (animation.parent as? ViewGroup)?.removeView(animation)
                }
            })
            .start()
    }

    /** Whether Discord's React screen has drawn anything yet. */
    private fun hasDiscordContent(root: View): Boolean {
        val content = root.findViewById<ViewGroup>(android.R.id.content) ?: return false
        return findReactRoot(content)?.let { it.childCount > 0 && it.width > 0 } ?: false
    }

    private fun findReactRoot(group: ViewGroup): ViewGroup? {
        val name = group.javaClass.name
        if (name.contains("ReactRootView") || name.contains("ReactSurfaceView")) return group
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) as? ViewGroup ?: continue
            findReactRoot(child)?.let { return it }
        }
        return null
    }
}

/**
 * Draws the animation: black, then DEL / TA / CORD glowing in, then a slow breathing glow.
 * The word runs along the long side of the screen (turned 90 degrees).
 */
@SuppressLint("ViewConstructor")
private class AnimationView(context: Context, typeface: Typeface) : View(context) {
    companion object {
        private val PARTS = arrayOf("DEL", "TA", "CORD")
        private const val WORD = "DELTACORD"

        /** When each part starts appearing, and how long it takes (ms). */
        private val PART_START = longArrayOf(250, 900, 1550)
        private const val PART_FADE = 520L
        /** The whole word pulses brighter once it's complete. */
        private const val PULSE_START = 2100L
        private const val PULSE_LENGTH = 600L
        /** "by Narehatechi" spins in near the bottom edge, then wobbles. */
        private const val CREDIT = "by Narehatechi"
        private const val CREDIT_START = 2150L
        private const val CREDIT_SPIN = 750L
        const val PLAY_MS = 3200L
    }

    private val start = SystemClock.uptimeMillis()
    var played = false
        private set

    private val density = resources.displayMetrics.density
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val glow = Paint(core)
    private val halo = Paint(core)
    private val credit = Paint(core).apply { textAlign = Paint.Align.CENTER }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        // Shadow-based glow on text needs a software layer on older Android versions.
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    private fun fade(t: Long, from: Long, length: Long): Float =
        ((t - from).toFloat() / length).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } // smoothstep

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = SystemClock.uptimeMillis() - start
        if (t >= PLAY_MS) played = true

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Turned 90 degrees: the word runs along the long side.
        val along = max(w, h)
        val across = min(w, h)
        canvas.save()
        canvas.rotate(90f, w / 2, h / 2)
        canvas.translate((w - along) / 2, (h - across) / 2)

        // Size the word to fill most of the long side, without getting too tall for the short side.
        core.textSize = 100f
        val sizeForLength = 100f * (along * 0.84f) / core.measureText(WORD)
        val sizeForHeight = across * 0.42f
        val size = min(sizeForLength, sizeForHeight)
        core.textSize = size
        glow.textSize = size
        halo.textSize = size

        val wordWidth = core.measureText(WORD)
        val metrics = core.fontMetrics
        val x0 = (along - wordWidth) / 2
        val baseline = across / 2 - (metrics.ascent + metrics.descent) / 2

        // A brighter pulse once the word is complete, then a slow breath while Discord finishes starting.
        val pulse = if (t in PULSE_START until PULSE_START + PULSE_LENGTH) {
            val p = (t - PULSE_START).toFloat() / PULSE_LENGTH
            kotlin.math.sin(p * Math.PI).toFloat()
        } else 0f
        val breath = if (t >= PULSE_START + PULSE_LENGTH) {
            ((kotlin.math.sin((t - PULSE_START - PULSE_LENGTH) / 900.0) + 1) / 2).toFloat() * 0.35f
        } else 0f

        var x = x0
        for ((index, part) in PARTS.withIndex()) {
            val appear = fade(t, PART_START[index], PART_FADE)
            if (appear > 0f) {
                // Each part arrives with a strong glow that settles down.
                val settle = 1f - fade(t, PART_START[index] + PART_FADE / 2, PART_FADE * 2)
                val glowAmount = 0.55f + 0.45f * settle + pulse * 0.6f + breath

                halo.alpha = (90 * appear * glowAmount).toInt().coerceIn(0, 255)
                halo.setShadowLayer(size * 0.38f + density * 6, 0f, 0f, Color.WHITE)
                canvas.drawText(part, x, baseline, halo)

                glow.alpha = (170 * appear * glowAmount).toInt().coerceIn(0, 255)
                glow.setShadowLayer(size * 0.12f + density * 2, 0f, 0f, Color.WHITE)
                canvas.drawText(part, x, baseline, glow)

                core.alpha = (255 * appear).toInt()
                canvas.drawText(part, x, baseline, core)
            }
            x += core.measureText(part)
        }
        canvas.restore()

        drawCredit(canvas, t, w, h)

        postInvalidateOnAnimation()
    }

    /** Ease out with a little overshoot, so the spin lands with a bounce. */
    private fun backOut(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1
        val p = x - 1
        return 1 + c3 * p * p * p + c1 * p * p
    }

    /** "by Narehatechi": spins in near the bottom (short) edge, then wobbles like a splash text. */
    private fun drawCredit(canvas: Canvas, t: Long, w: Float, h: Float) {
        if (t < CREDIT_START) return
        val spin = ((t - CREDIT_START).toFloat() / CREDIT_SPIN).coerceIn(0f, 1f)
        val eased = backOut(spin)
        val since = (t - CREDIT_START).toDouble()

        val scale = max(0f, eased) * (1f + 0.045f * kotlin.math.sin(since / 170.0).toFloat() * spin)
        val angle = -720f * (1f - eased) + 4f * kotlin.math.sin(since / 320.0).toFloat() * spin
        if (scale <= 0.01f) return

        credit.textSize = min(w, h) * 0.075f
        credit.alpha = (255 * spin.coerceAtMost(1f)).toInt().coerceIn(0, 255)
        credit.setShadowLayer(credit.textSize * 0.35f, 0f, 0f, Color.WHITE)

        val cx = w / 2
        val cy = h * 0.9f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angle)
        canvas.scale(scale, scale)
        val metrics = credit.fontMetrics
        canvas.drawText(CREDIT, 0f, -(metrics.ascent + metrics.descent) / 2, credit)
        canvas.restore()
    }
}
