package io.github.deltacord.xposed.tweaks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The colours one launch's animation uses. */
internal class StartupPalette(
    val id: String,
    val glow: Int,
    val sparks: IntArray,
    val shine: IntArray,
    val credit: String = "by Narehatechi",
    val rare: Boolean = false,
)

/** The start-up animation styles and colour variants, and picking one for a launch. */
internal object StartupStyles {
    val IDS = listOf(
        "upgraded", "fountain", "soul", "battle", "crt", "savepoint", "drop",
        "warp", "door", "neon", "dither", "orbit", "rain", "fireworks",
    )
    const val DEFAULT = "upgraded"

    private fun c(hex: Long) = hex.toInt()

    private val COMMON = listOf(
        StartupPalette(
            "classic", Color.WHITE,
            intArrayOf(Color.WHITE, c(0xFFFFFF00), c(0xFF66FFFF), c(0xFFFF77DD)),
            intArrayOf(Color.WHITE, c(0xFFFF5577), c(0xFFFFDD33), c(0xFF55FFAA), c(0xFF55CCFF), c(0xFFCC77FF), Color.WHITE),
        ),
        StartupPalette(
            "dark", c(0xFFB48CFF),
            intArrayOf(Color.WHITE, c(0xFFB48CFF), c(0xFF66FFFF), c(0xFF77AAFF)),
            intArrayOf(Color.WHITE, c(0xFFD9C2FF), c(0xFF8A7DFF), c(0xFF66FFFF), c(0xFF8A7DFF), c(0xFFD9C2FF), Color.WHITE),
        ),
        StartupPalette(
            "determination", c(0xFFFF4D5E),
            intArrayOf(Color.WHITE, c(0xFFFFFF00), c(0xFFFF4D5E), c(0xFFFFB000)),
            intArrayOf(Color.WHITE, c(0xFFFF4D5E), c(0xFFFF8A00), c(0xFFFFE14D), c(0xFFFF8A00), c(0xFFFF4D5E), Color.WHITE),
        ),
        StartupPalette(
            "cyber", c(0xFF4DFCFF),
            intArrayOf(Color.WHITE, c(0xFF4DFCFF), c(0xFFFF4DE1), c(0xFFFFE14D)),
            intArrayOf(Color.WHITE, c(0xFF4DFCFF), c(0xFF7A5CFF), c(0xFFFF4DE1), c(0xFF7A5CFF), c(0xFF4DFCFF), Color.WHITE),
        ),
    )

    /** The rare one, about one launch in fifty. */
    private val GOLD = StartupPalette(
        "gold", c(0xFFFFCC33),
        intArrayOf(c(0xFFFFF7CC), c(0xFFFFCC33), c(0xFFFFE999), Color.WHITE),
        intArrayOf(Color.WHITE, c(0xFFFFF2B3), c(0xFFFFCC33), c(0xFFFFE999), c(0xFFFFCC33), c(0xFFFFF2B3), Color.WHITE),
        credit = "* by Narehatechi *", rare = true,
    )

    /** [setting] is what Deltacord > Extras saved: a style, "random", or nothing (the default). */
    fun pickStyle(setting: String?, random: java.util.Random): String = when {
        setting == "random" -> IDS[random.nextInt(IDS.size)]
        setting != null && setting in IDS -> setting
        else -> DEFAULT
    }

    fun pickPalette(random: java.util.Random): StartupPalette =
        if (random.nextInt(50) == 0) GOLD else COMMON[random.nextInt(COMMON.size)]
}

/**
 * Draws one start-up animation [style] in [palette]. Everything is drawn from the time since the
 * animation started, so a recreated screen can pick it up at the same moment.
 */
internal class StartupScene(
    val style: String,
    val palette: StartupPalette,
    private val seed: Int,
    private val density: Float,
) {
    companion object {
        /** The animation always plays this long before Discord can show. */
        const val PLAY_MS = 6500L

        private const val WORD = "DELTACORD"
        private val PARTS = arrayOf("DEL", "TA", "CORD")
        private const val LINE = "* DELTACORD draws near."
        private const val SAVE_LINE = "* The DELTACORD fills you with DETERMINATION."
        private const val RAIN_CHARS = "DELTACORD*+<>#%&@0123456789"
        private val SOULS = intArrayOf(
            0xFFFF1A2E.toInt(), 0xFFFF8A00.toInt(), 0xFFFFE14D.toInt(), 0xFF33DD55.toInt(),
            0xFF33E0FF.toInt(), 0xFF3366FF.toInt(), 0xFFBB44FF.toInt(),
        )
        private const val SOUL_RED = 0xFFFF1A2E.toInt()
        private const val SAVE_YELLOW = 0xFFFFE14D.toInt()
        private val HEART = arrayOf(
            "..XX...XX..",
            ".XXXX.XXXX.",
            "XXXXXXXXXXX",
            "XXXXXXXXXXX",
            "XXXXXXXXXXX",
            ".XXXXXXXXX.",
            "..XXXXXXX..",
            "...XXXXX...",
            "....XXX....",
            ".....X.....",
        )
        private val CRACK = intArrayOf(5, 5, 4, 6, 4, 6, 5, 4, 5, 5)
        private val HEART_CELLS: List<IntArray> = buildList {
            HEART.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> if (ch == 'X') add(intArrayOf(c, r)) } }
        }
    }

    private class Timing(val impacts: List<Pair<Float, Float>>, val sparkle: Float, val credit: Float)

    private val timing = when (style) {
        "fountain" -> Timing(listOf(4800f to 6f), 4900f, 4900f)
        "soul" -> Timing(listOf(880f to 4f, 1100f to 3f, 1700f to 3f, 2300f to 4f, 3400f to 9f, 5200f to 5f), 5300f, 5200f)
        "battle" -> Timing(listOf(250f to 3f, 4490f to 13f), 4700f, 4800f)
        "crt" -> Timing(listOf(600f to 4f, 3000f to 9f), 3300f, 3500f)
        "savepoint" -> Timing(listOf(3700f to 10f), 4000f, 4200f)
        "drop" -> Timing(List(WORD.length) { (700f + it * 190 + 240) to 3f } + (3000f to 7f), 3300f, 3500f)
        "warp" -> Timing(listOf(2800f to 12f), 3200f, 3400f)
        "door" -> Timing(listOf(3100f to 8f), 3700f, 3900f)
        "neon" -> Timing(listOf(3000f to 6f), 3300f, 3500f)
        "dither" -> Timing(listOf(2800f to 8f), 3200f, 3400f)
        "orbit" -> Timing(listOf(2700f to 11f), 3100f, 3300f)
        "rain" -> Timing(listOf(2900f to 7f), 3300f, 3500f)
        "fireworks" -> Timing(listOf(900f to 6f, 1600f to 6f, 2300f to 7f, 3000f to 5f), 3300f, 3500f)
        else -> Timing(listOf(760f to 7f, 1460f to 7f, 2160f to 11f), 2700f, 3000f)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glyphs = HashMap<String, Path>()
    private val random = kotlin.random.Random(seed)

    // Layout, in the turned stage (the word runs along the long side).
    private var laidOutW = -1
    private var laidOutH = -1
    private var along = 0f
    private var across = 0f
    private var size = 0f
    private var wordWidth = 0f
    private var x0 = 0f
    private var y = 0f
    private var cx = 0f
    private var cap = 0f
    private val partX = FloatArray(3)
    private val partW = FloatArray(3)
    private val charX = FloatArray(WORD.length)
    private val charW = FloatArray(WORD.length)
    private var points: FloatArray? = null
    private var pstep = 3f

    // ---------- maths ----------

    private fun clamp(x: Float, a: Float = 0f, b: Float = 1f) = x.coerceIn(a, b)
    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
    private fun smooth(x: Float): Float { val c = clamp(x); return c * c * (3 - 2 * c) }
    private fun fade(t: Float, from: Float, length: Float) = smooth((t - from) / length)
    private fun easeOut(x: Float): Float { val c = clamp(x); return 1 - (1 - c).pow(3) }
    private fun easeIn(x: Float): Float { val c = clamp(x); return c * c * c }
    private fun easeInOut(x: Float): Float {
        val c = clamp(x)
        return if (c < 0.5f) 4 * c * c * c else 1 - (-2 * c + 2).pow(3) / 2
    }
    private fun backOut(x: Float): Float {
        val c = clamp(x); val c1 = 1.70158f; val c3 = c1 + 1; val p = c - 1
        return 1 + c3 * p * p * p + c1 * p * p
    }
    private fun bounceOut(x: Float): Float {
        var c = clamp(x); val n = 7.5625f; val d = 2.75f
        return when {
            c < 1 / d -> n * c * c
            c < 2 / d -> { c -= 1.5f / d; n * c * c + 0.75f }
            c < 2.5f / d -> { c -= 2.25f / d; n * c * c + 0.9375f }
            else -> { c -= 2.625f / d; n * c * c + 0.984375f }
        }
    }
    private fun fract(x: Double) = x - floor(x)
    private fun fract(x: Float) = x - floor(x)
    private fun sinf(x: Float) = sin(x.toDouble()).toFloat()
    private fun cosf(x: Float) = cos(x.toDouble()).toFloat()

    /** The same "random" number for the same inputs, so frames can be redrawn exactly. */
    private fun rnd(i: Int, k: Int = 0): Float =
        fract(sin(i * 127.1 + k * 311.7 + seed * 0.731) * 43758.5453).toFloat()

    private fun pulse(t: Float, t0: Float) = if (t >= t0 && t < t0 + 600) sinf((t - t0) / 600 * PI.toFloat()) * 0.6f else 0f
    private fun breath(t: Float, t0: Float) = if (t > t0) (sinf((t - t0) / 900) + 1) / 2 * 0.35f else 0f
    private fun alpha(a: Float) = (clamp(a) * 255).roundToInt()
    private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or (alpha(a) shl 24)

    private fun impact(t: Float, length: Float): Float {
        var most = 0f
        for ((at, amount) in timing.impacts) {
            val p = (t - at) / length
            if (p in 0f..1f) most = max(most, amount * (1 - p) * (1 - p))
        }
        return most
    }

    private fun beat(t: Float): Float {
        var b = 0f
        for (bt in floatArrayOf(1100f, 1700f, 2300f)) {
            val p = (t - bt) / 260
            if (p in 0f..1f) b += sinf(p * PI.toFloat())
        }
        return b
    }

    private fun camera(t: Float): Float = when (style) {
        "upgraded" -> lerp(0.9f, 1f, easeOut(t / 2600))
        "fountain" -> lerp(1.06f, 1f, easeInOut(t / 5000))
        "soul" -> 1 + beat(t) * 0.05f
        "savepoint" -> lerp(1.05f, 1f, easeInOut(t / 3700))
        "door" -> if (t < 3100) lerp(1f, 1.15f, easeIn((t - 1400) / 1700)) else lerp(1.15f, 1f, easeOut((t - 3100) / 600))
        "dither" -> lerp(1.05f, 1f, easeOut(t / 2800))
        "orbit" -> if (t < 2700) lerp(1f, 1.1f, easeIn(t / 2700)) else lerp(1.1f, 1f, easeOut((t - 2700) / 500))
        else -> 1f
    }

    // ---------- layout and letters ----------

    private fun layout(w: Int, h: Int) {
        laidOutW = w
        laidOutH = h
        along = max(w, h).toFloat()
        across = min(w, h).toFloat()
        size = min(along * 0.8f / DeterminationGlyphs.width(WORD, 1f), across * 0.42f)
        wordWidth = DeterminationGlyphs.width(WORD, size)
        x0 = (along - wordWidth) / 2
        y = across / 2
        cx = along / 2
        cap = size * 1152f / DeterminationGlyphs.UNITS_PER_EM
        var x = x0
        PARTS.forEachIndexed { i, part ->
            partX[i] = x
            partW[i] = DeterminationGlyphs.width(part, size)
            x += partW[i]
        }
        WORD.forEachIndexed { i, ch ->
            charX[i] = x0 + DeterminationGlyphs.width(WORD.substring(0, i), size)
            charW[i] = DeterminationGlyphs.width(ch.toString(), size)
        }
        points = null
        glyphs.clear()
    }

    private fun glyph(text: String, at: Float): Path =
        glyphs.getOrPut("$text@$at") { Path(DeterminationGlyphs.path(text, at)) }

    /** Draws [text] with its left edge at [x] and its capitals centred on [mid]. */
    private fun text(canvas: Canvas, text: String, x: Float, mid: Float, paint: Paint, at: Float = size) {
        canvas.save()
        canvas.translate(x, mid + at * 1152f / DeterminationGlyphs.UNITS_PER_EM / 2)
        canvas.drawPath(glyph(text, at), paint)
        canvas.restore()
    }

    private fun plain(canvas: Canvas, text: String, x: Float, mid: Float, color: Int, a: Float, at: Float = size) {
        if (a <= 0f) return
        fill.shader = null
        fill.clearShadowLayer()
        fill.color = withAlpha(color, a)
        text(canvas, text, x, mid, fill, at)
    }

    /** Text with the two-layer glow the old animation had. */
    private fun glowText(
        canvas: Canvas, text: String, x: Float, mid: Float, a: Float, glow: Float,
        color: Int = Color.WHITE, shadow: Int = palette.glow,
    ) {
        if (a <= 0f) return
        fill.shader = null
        fill.color = withAlpha(color, 90 / 255f * a * glow)
        fill.setShadowLayer(size * 0.38f + density * 6, 0f, 0f, shadow)
        text(canvas, text, x, mid, fill)
        fill.color = withAlpha(color, 170 / 255f * a * glow)
        fill.setShadowLayer(size * 0.12f + density * 2, 0f, 0f, shadow)
        text(canvas, text, x, mid, fill)
        fill.clearShadowLayer()
        fill.color = withAlpha(color, a)
        text(canvas, text, x, mid, fill)
    }

    private fun wordAt(canvas: Canvas, a: Float, glow: Float, scale: Float = 1f) {
        canvas.save()
        if (scale != 1f) canvas.scale(scale, scale, cx, y)
        glowText(canvas, WORD, x0, y, a, glow)
        canvas.restore()
    }

    /** Points that fill the DELTACORD letters, for styles where the word forms out of particles. */
    private fun letterPoints(): FloatArray {
        points?.let { return it }
        val scale = min(1f, 900f / along)
        val bw = max(1, (along * scale).toInt())
        val bh = max(1, (across * scale).toInt())
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        text(c, WORD, x0, y, paint)
        val pixels = IntArray(bw * bh)
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        bitmap.recycle()

        var step = max(2f, (size / 16).roundToInt().toFloat())
        var out: ArrayList<Float>
        while (true) {
            out = ArrayList()
            var yy = floor(y - size * 0.7f)
            while (yy < y + size * 0.7f) {
                var xx = floor(x0)
                while (xx < x0 + wordWidth) {
                    val px = (xx * scale).toInt()
                    val py = (yy * scale).toInt()
                    if (px in 0 until bw && py in 0 until bh && (pixels[py * bw + px] ushr 24) > 128) {
                        out.add(xx); out.add(yy)
                    }
                    xx += step
                }
                yy += step
            }
            if (out.size / 2 <= 700) break
            step += 1f
        }
        pstep = step
        return out.toFloatArray().also { points = it }
    }

    // ---------- shared pieces ----------

    private fun rect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, a: Float, blur: Float = 0f) {
        if (a <= 0f) return
        fill.shader = null
        fill.color = withAlpha(color, a)
        if (blur > 0f) fill.setShadowLayer(blur, 0f, 0f, color) else fill.clearShadowLayer()
        canvas.drawRect(x, y, x + w, y + h, fill)
        if (blur > 0f) fill.clearShadowLayer()
    }

    private fun burst(canvas: Canvas, t: Float, t0: Float, bx: Float, by: Float, n: Int, k: Int, spread: Float, colors: IntArray) {
        val p = (t - t0) / 700
        if (p < 0 || p > 1) return
        val e = easeOut(p)
        for (i in 0 until n) {
            val a = rnd(i, k) * PI.toFloat() * 2
            val d = (0.3f + rnd(i, k + 1) * 0.7f) * spread * e
            val s = size * (0.03f + rnd(i, k + 2) * 0.05f) * (1 - p * 0.6f)
            rect(canvas, bx + cosf(a) * d - s / 2, by + sinf(a) * d - s / 2, s, s, colors[i % colors.size], 1 - p, s * 2)
        }
    }

    private fun ring(canvas: Canvas, t: Float, t0: Float, rx: Float, ry: Float, maxR: Float, length: Float = 800f, color: Int = palette.glow) {
        val p = (t - t0) / length
        if (p < 0 || p > 1) return
        line.pathEffect = null
        line.color = withAlpha(color, (1 - p) * 0.9f)
        line.setShadowLayer(size * 0.2f, 0f, 0f, color)
        line.strokeWidth = size * 0.07f * (1 - p) + density
        canvas.drawCircle(rx, ry, easeOut(p) * maxR, line)
        line.clearShadowLayer()
    }

    private fun motes(canvas: Canvas, t: Float, n: Int, mul: Float) {
        if (mul <= 0f) return
        fill.shader = null
        fill.clearShadowLayer()
        for (i in 0 until n) {
            val speed = 0.04f + rnd(i, 40) * 0.08f
            val ph = fract(t / 1000 * speed + rnd(i, 41))
            val x = rnd(i, 42) * along + sinf(t / 1400 + i) * size * 0.15f
            val my = across * (1.05f - ph * 1.1f)
            val s = density * (1 + rnd(i, 43) * 2.2f)
            fill.color = withAlpha(palette.glow, sinf(ph * PI.toFloat()) * (0.15f + rnd(i, 44) * 0.45f) * mul)
            canvas.drawRect(x, my, x + s, my + s, fill)
        }
    }

    /** A pixel heart. [split] pushes the two halves apart; [crack] (0..1) opens the crack row by row. */
    private fun drawHeart(canvas: Canvas, hx: Float, hy: Float, hp: Float, a: Float, split: Float = 0f, crack: Float = 0f, color: Int = SOUL_RED) {
        if (a <= 0f) return
        fill.shader = null
        fill.color = withAlpha(color, a)
        fill.setShadowLayer(hp * 2.5f, 0f, 0f, color)
        val rows = floor(crack * 10.99f).toInt()
        for (cell in HEART_CELLS) {
            val c = cell[0]; val r = cell[1]; val k = CRACK[r]
            if (c == k && (r < rows || split > 0)) continue
            val dx = if (split > 0) (if (c < k) -split else split) else 0f
            val left = hx + (c - 5.5f) * hp + dx
            val top = hy + (r - 5) * hp
            canvas.drawRect(left, top, left + hp + 0.6f, top + hp + 0.6f, fill)
        }
        fill.clearShadowLayer()
    }

    private fun starPath(r: Float): Path = Path().apply {
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4
            val q = if (i % 2 == 1) r * 0.3f else r
            if (i == 0) moveTo(cosf(a) * q, sinf(a) * q) else lineTo(cosf(a) * q, sinf(a) * q)
        }
        close()
    }

    /** A four-pointed save-point star, white in the middle. */
    private fun drawStar(canvas: Canvas, sx: Float, sy: Float, r: Float, rot: Float, color: Int, a: Float) {
        if (a <= 0f || r <= 0f) return
        canvas.save()
        canvas.translate(sx, sy)
        canvas.rotate(rot * 180 / PI.toFloat())
        fill.shader = null
        fill.color = withAlpha(color, a)
        fill.setShadowLayer(r * 0.9f, 0f, 0f, color)
        canvas.drawPath(starPath(r), fill)
        fill.clearShadowLayer()
        fill.color = withAlpha(Color.WHITE, a)
        canvas.drawPath(starPath(r * 0.45f), fill)
        canvas.restore()
    }

    /** A firework: sparks fly out, fall a little and twinkle out. */
    private fun firework(canvas: Canvas, t: Float, t0: Float, fx: Float, fy: Float, n: Int, k: Int, r: Float, colors: IntArray) {
        val p = (t - t0) / 1100
        if (p < 0 || p > 1) return
        val e = easeOut(p)
        for (i in 0 until n) {
            val a = i.toFloat() / n * PI.toFloat() * 2 + rnd(i, k) * 0.25f
            val v = r * (0.55f + rnd(i, k + 1) * 0.45f)
            val x = fx + cosf(a) * v * e
            val py = fy + sinf(a) * v * e + p * p * r * 0.45f
            val s = density * (2 + rnd(i, k + 2) * 2.5f) * (1 - p * 0.5f)
            val twinkle = if (p > 0.55f && rnd(i, k + 3 + (t / 50).toInt()) > 0.5f) 0.25f else 1f
            rect(canvas, x - s / 2, py - s / 2, s, s, colors[i % colors.size], (1 - p) * twinkle, s * 2)
        }
    }

    /** The Deltacord Manager title's sparkle: rainbow shine, + sparkles and a quick red/cyan glitch. */
    private fun sparkle(canvas: Canvas, t: Float, t0: Float) {
        val since = (t - t0) / 1000
        val intro = fade(t, t0, 400f)
        val top = y - cap / 2
        val bottom = y + cap / 2

        val sweep = (since * 0.4f) % 1.8f - 0.4f
        val band = wordWidth * 0.45f
        val centre = x0 + sweep * wordWidth
        val colors = IntArray(palette.shine.size + 2)
        val stops = FloatArray(palette.shine.size + 2)
        colors[0] = 0x00FFFFFF
        colors[colors.size - 1] = 0x00FFFFFF
        stops[0] = 0f
        stops[stops.size - 1] = 1f
        palette.shine.forEachIndexed { i, c ->
            colors[i + 1] = c
            stops[i + 1] = 0.18f + i * 0.64f / (palette.shine.size - 1)
        }
        fill.shader = LinearGradient(centre - band, top - cap * 0.4f, centre + band, bottom + cap * 0.4f, colors, stops, Shader.TileMode.CLAMP)
        fill.color = withAlpha(Color.WHITE, 0.9f * intro)
        fill.setShadowLayer(size * 0.08f + density * 2, 0f, 0f, withAlpha(palette.glow, 0.8f))
        text(canvas, WORD, x0, y, fill)
        fill.shader = null
        fill.clearShadowLayer()

        val cycle = since % 2.5f
        if (since > 0.8f && cycle < 0.18f) {
            val shake = density * 3 * sinf(cycle * 180)
            val gap = size * 0.035f
            plain(canvas, WORD, x0 - gap + shake, y, Color.RED, 0.67f)
            plain(canvas, WORD, x0 + gap - shake, y, Color.CYAN, 0.67f)
        }

        line.pathEffect = null
        line.strokeWidth = max(density * 1.6f, size * 0.025f)
        for (i in 0 until 14) {
            val fx = rnd(i, 80) * 1.1f - 0.05f
            val fy = if (rnd(i, 81) < 0.5f) -0.35f - rnd(i, 82) * 0.45f else 1.1f + rnd(i, 82) * 0.45f
            val speed = 0.6f + rnd(i, 83) * 0.8f
            val wave = sinf(since * 2.4f * speed + rnd(i, 84) * 6.28f)
            if (wave <= 0f) continue
            val sx = x0 + fx * wordWidth
            val sy = top + fy * cap
            val arm = size * 0.11f * wave * speed
            val color = palette.sparks[i % palette.sparks.size]
            line.color = withAlpha(color, wave * intro)
            line.setShadowLayer(arm * 0.9f + density, 0f, 0f, color)
            canvas.drawLine(sx - arm, sy, sx + arm, sy, line)
            canvas.drawLine(sx, sy - arm, sx, sy + arm, line)
        }
        line.clearShadowLayer()
    }

    /** "by Narehatechi": spins in near the bottom edge, then wobbles like a splash text. */
    private fun credit(canvas: Canvas, t: Float, t0: Float, w: Float, h: Float) {
        if (t < t0) return
        val spin = min(1f, (t - t0) / 750)
        val p = spin - 1
        val since = t - t0
        val eased = 1 + 2.70158f * p * p * p + 1.70158f * p * p
        val scale = max(0f, eased) * (1 + 0.045f * sinf(since / 170) * spin)
        val angle = -720 * (1 - eased) + 4 * sinf(since / 320) * spin
        if (scale <= 0.01f) return
        val fontSize = min(w, h) * 0.075f
        canvas.save()
        canvas.translate(w / 2, h * 0.955f)
        canvas.rotate(angle)
        canvas.scale(scale, scale)
        fill.shader = null
        fill.color = withAlpha(if (palette.rare) palette.glow else Color.WHITE, spin)
        fill.setShadowLayer(fontSize * 0.35f, 0f, 0f, palette.glow)
        text(canvas, palette.credit, -DeterminationGlyphs.width(palette.credit, fontSize) / 2, 0f, fill, fontSize)
        fill.clearShadowLayer()
        canvas.restore()
    }

    // ---------- drawing a frame ----------

    fun draw(canvas: Canvas, timeMs: Long, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w != laidOutW || h != laidOutH) layout(w, h)
        val t = timeMs.toFloat()
        canvas.drawColor(Color.BLACK)

        canvas.save()
        if (h > w) {
            canvas.rotate(90f, w / 2f, h / 2f)
            canvas.translate((w - along) / 2, (h - across) / 2)
        }
        val shake = impact(t, 350f) * density
        canvas.translate(sinf(t * 0.11f) * shake, cosf(t * 0.137f) * shake)
        val cam = camera(t)
        canvas.scale(cam, cam, cx, y)
        when (style) {
            "fountain" -> fountain(canvas, t)
            "soul" -> soul(canvas, t)
            "battle" -> battle(canvas, t)
            "crt" -> crt(canvas, t)
            "savepoint" -> savePoint(canvas, t)
            "drop" -> drop(canvas, t)
            "warp" -> warp(canvas, t)
            "door" -> door(canvas, t)
            "neon" -> neon(canvas, t)
            "dither" -> dither(canvas, t)
            "orbit" -> orbit(canvas, t)
            "rain" -> rain(canvas, t)
            "fireworks" -> fireworks(canvas, t)
            else -> upgraded(canvas, t)
        }
        if (t >= timing.sparkle) sparkle(canvas, t, timing.sparkle)
        canvas.restore()

        credit(canvas, t, timing.credit, w.toFloat(), h.toFloat())

        val flash = impact(t, 200f) / 13 * 0.28f
        if (flash > 0f) canvas.drawColor(withAlpha(palette.glow, flash))
    }

    // ---------- the fourteen styles ----------

    private fun upgraded(canvas: Canvas, t: Float) {
        motes(canvas, t, 70, fade(t, 0f, 800f))
        val starts = floatArrayOf(500f, 1200f, 1900f)
        PARTS.forEachIndexed { i, part ->
            val s0 = starts[i]
            val p = (t - s0) / 260
            if (p < 0) return@forEachIndexed
            val sc = lerp(1.8f, 1f, easeIn(p))
            val settle = 1 - fade(t, s0 + 260, 700f)
            val glow = 0.55f + 0.9f * settle + pulse(t, 2500f) + breath(t, 3100f)
            val pcx = partX[i] + partW[i] / 2
            canvas.save()
            canvas.scale(sc, sc, pcx, y)
            glowText(canvas, part, partX[i], y, clamp(p * 1.6f), glow)
            canvas.restore()
            burst(canvas, t, s0 + 260, pcx, y, 22, i * 10 + 100, size * 1.4f, palette.sparks)
        }
        ring(canvas, t, 2500f, cx, y, along * 0.6f)
    }

    private fun fountain(canvas: Canvas, t: Float) {
        val gy = across * 0.9f
        val crack = easeOut(t / 700) * (1 - fade(t, 4400f, 600f))
        if (crack > 0) {
            line.pathEffect = null
            line.color = withAlpha(palette.glow, crack * (0.7f + 0.3f * sinf(t / 60)))
            line.setShadowLayer(size * 0.25f, 0f, 0f, palette.glow)
            line.strokeWidth = density * 2 + size * 0.03f
            val half = along * 0.32f * crack
            canvas.drawLine(cx - half, gy, cx + half, gy, line)
            line.clearShadowLayer()
        }
        val rise = easeOut((t - 600) / 700)
        val sink = easeIn((t - 3600) / 900)
        if (rise > 0 && sink < 1) {
            val top = lerp(lerp(gy, -across * 0.1f, rise), gy, sink)
            val cw = across * 0.13f * (1 + 0.08f * sinf(t / 90)) * (1 - sink * 0.5f)
            fill.shader = LinearGradient(
                0f, gy, 0f, top,
                intArrayOf(withAlpha(palette.glow, 0.95f), withAlpha(palette.glow, 0.35f), withAlpha(palette.glow, 0f)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
            fill.color = Color.WHITE
            fill.setShadowLayer(size * 0.5f, 0f, 0f, palette.glow)
            canvas.drawRect(cx - cw / 2, top, cx + cw / 2, gy, fill)
            fill.clearShadowLayer()
            fill.shader = LinearGradient(0f, gy, 0f, top, withAlpha(Color.WHITE, 0.9f), 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(cx - cw * 0.18f, top, cx + cw * 0.18f, gy, fill)
            fill.shader = null
            for (j in 0 until 90) {
                val speed = 0.5f + rnd(j, 50) * 0.9f
                val ph = fract(t / 1000 * speed + rnd(j, 51))
                val yy = lerp(gy, top, ph)
                val xx = cx + (rnd(j, 52) - 0.5f) * cw * (0.6f + ph * 2.2f)
                val s = density * (1.5f + rnd(j, 53) * 3)
                rect(canvas, xx - s / 2, yy - s / 2, s, s, if (j % 3 != 0) Color.WHITE else palette.sparks[j % palette.sparks.size], (1 - ph) * (1 - sink) * rise)
            }
        }
        val pts = letterPoints()
        val ps = pstep * 0.95f
        val wordIn = fade(t, 4300f, 500f)
        if (wordIn < 1) {
            for (i in 0 until pts.size / 2) {
                val launch = 1500 + rnd(i, 60) * 1600
                val up = (t - (launch - 900)) / 900
                if (up < 0) continue
                val tx = pts[i * 2]; val ty = pts[i * 2 + 1]
                val sx = cx + (rnd(i, 61) - 0.5f) * across * 0.12f
                val sprayY = lerp(across * 0.05f, across * 0.35f, rnd(i, 62))
                val fx = (cx + (rnd(i, 63) - 0.5f) * along * 0.5f) * 0.3f + sx * 0.7f
                val x: Float
                val py: Float
                if (up < 1) {
                    val e = easeOut(up)
                    x = lerp(sx, fx, e); py = lerp(gy, sprayY, e)
                } else {
                    val hp = easeInOut((t - launch) / 1300)
                    x = lerp(fx, tx, hp)
                    py = lerp(sprayY, ty, hp) + sinf(hp * PI.toFloat()) * size * 0.25f * (rnd(i, 64) - 0.5f)
                }
                val arrived = clamp((t - launch) / 1300)
                rect(canvas, x - ps / 2, py - ps / 2, ps, ps, if (arrived >= 1) Color.WHITE else palette.sparks[i % palette.sparks.size], (1 - wordIn) * (0.6f + 0.4f * arrived))
            }
        }
        if (wordIn > 0) wordAt(canvas, wordIn, 0.6f + pulse(t, 4800f) + breath(t, 5400f) + (1 - fade(t, 4300f, 800f)) * 0.6f)
        ring(canvas, t, 4800f, cx, y, along * 0.6f)
    }

    private fun soul(canvas: Canvas, t: Float) {
        motes(canvas, t, 40, fade(t, 1000f, 1000f) * 0.7f)
        val hp = size * 0.09f
        if (t < 3400) {
            val cy = lerp(-across * 0.2f, y, bounceOut(t / 900))
            val b = 1 + beat(t) * 0.25f
            canvas.save()
            canvas.scale(b, b, cx, cy)
            val shake = if (t > 2700) sinf(t * 0.9f) * hp * 0.25f * fade(t, 2700f, 300f) else 0f
            val split = if (t >= 3100) easeOut((t - 3100) / 300) * hp * 1.2f else 0f
            drawHeart(canvas, cx + shake, cy, hp, 1f, split, clamp((t - 2700) / 400))
            canvas.restore()
            for (bt in floatArrayOf(1100f, 1700f, 2300f)) ring(canvas, t, bt, cx, cy, size * 1.3f, 600f, SOUL_RED)
        }
        val pts = letterPoints()
        val ps = pstep * 0.95f
        val wordIn = fade(t, 5000f, 400f)
        if (t >= 3400 && wordIn < 1) {
            for (i in 0 until pts.size / 2) {
                val cell = HEART_CELLS[(rnd(i, 70) * HEART_CELLS.size).toInt().coerceIn(0, HEART_CELLS.size - 1)]
                val side = if (cell[0] < CRACK[cell[1]]) -1 else 1
                val sx = cx + (cell[0] - 5.5f + rnd(i, 71)) * hp + side * hp * 1.2f
                val sy = y + (cell[1] - 5 + rnd(i, 72)) * hp
                val a = rnd(i, 73) * PI.toFloat() * 2
                val d = (0.3f + rnd(i, 74) * 0.7f) * size * 0.75f
                val bp = easeOut((t - 3400) / 500)
                val h = easeInOut((t - (3900 + rnd(i, 75) * 300)) / 1100)
                val x = lerp(sx + cosf(a) * d * bp, pts[i * 2], h)
                val py = lerp(sy + sinf(a) * d * bp, pts[i * 2 + 1], h)
                val color = Color.rgb(255, lerp(26f, 255f, h).roundToInt(), lerp(46f, 255f, h).roundToInt())
                rect(canvas, x - ps / 2, py - ps / 2, ps, ps, color, 1 - wordIn)
            }
        }
        if (wordIn > 0) wordAt(canvas, wordIn, 0.6f + pulse(t, 5200f) + breath(t, 5800f) + (1 - wordIn) * 0.5f)
        ring(canvas, t, 5200f, cx, y, along * 0.6f)
    }

    private fun battle(canvas: Canvas, t: Float) {
        val hp = size * 0.05f
        if (t >= 250 && t < 1500) {
            val blink = if (t > 800) ((t - 800) / 110).toInt() % 2 == 0 else true
            if (blink) drawHeart(canvas, cx, y, hp, 1 - fade(t, 1350f, 150f))
            if (t < 800) {
                val s = backOut((t - 250) / 200)
                val at = size * 0.5f
                canvas.save()
                canvas.translate(cx, y - hp * 10)
                canvas.scale(s, s)
                fill.shader = null
                fill.color = Color.WHITE
                fill.setShadowLayer(size * 0.1f, 0f, 0f, palette.glow)
                text(canvas, "!", -DeterminationGlyphs.width("!", at) / 2, -at * 0.3f, fill, at)
                fill.clearShadowLayer()
                canvas.restore()
            }
        }
        val fs = size * 0.2f
        val bw = max(DeterminationGlyphs.width(LINE, fs) + fs * 2.4f, wordWidth * 0.95f)
        val bh = size * 0.95f
        var wP = easeOut((t - 1400) / 250)
        var hP = easeOut((t - 1650) / 250)
        if (t >= 3900) {
            hP = 1 - easeIn((t - 3900) / 180)
            wP = 1 - easeIn((t - 4080) / 150)
        }
        if (wP > 0 && t < 4240) {
            val lw = fs * 0.18f
            val bwN = bw * wP
            val bhN = max(lw, bh * hP)
            rect(canvas, cx - bwN / 2, y - bhN / 2, bwN, bhN, Color.BLACK, 1f)
            line.pathEffect = null
            line.clearShadowLayer()
            line.color = Color.WHITE
            line.strokeWidth = lw
            canvas.drawRect(cx - bwN / 2, y - bhN / 2, cx + bwN / 2, y + bhN / 2, line)
            if (hP >= 1 && t < 3900) {
                val chars = ((t - 1950) / 65).toInt().coerceIn(0, LINE.length)
                if (chars > 0) plain(canvas, LINE.substring(0, chars), cx - bw / 2 + fs * 1.2f, y - bh / 2 + fs * 1.5f, Color.WHITE, 1f, fs)
            }
        }
        if (t >= 4250) {
            val p = (t - 4250) / 240
            wordAt(canvas, clamp(p * 3), 0.6f + (1 - fade(t, 4490f, 700f)) * 0.9f + breath(t, 5100f), lerp(2.6f, 1f, easeIn(p)))
            burst(canvas, t, 4490f, cx, y, 40, 300, size * 2, palette.sparks)
        }
        ring(canvas, t, 4490f, cx, y, along * 0.6f)
    }

    private fun crt(canvas: Canvas, t: Float) {
        val wAll = along
        val hAll = across
        if (t < 600) {
            val p = easeOut((t - 150) / 450)
            if (p <= 0) return
            val lw = wAll * p
            val lh = density * 3
            rect(canvas, cx - lw / 2, y - lh / 2, lw, lh, Color.WHITE, 1f, size * 0.3f)
            return
        }
        val hh = hAll * easeOut((t - 600) / 350)
        canvas.save()
        canvas.clipRect(-wAll, y - hh / 2, wAll * 2, y + hh / 2)
        val noise = 1 - fade(t, 1500f, 1500f)
        if (noise > 0) {
            val s = max(2f, size * 0.035f)
            fill.shader = null
            fill.clearShadowLayer()
            repeat(500) {
                val bright = random.nextBoolean()
                fill.color = if (bright) withAlpha(Color.WHITE, noise * (0.2f + random.nextFloat() * 0.5f))
                else withAlpha(Color.rgb(110, 110, 140), noise * (0.2f + random.nextFloat() * 0.5f))
                val nx = random.nextFloat() * wAll
                val ny = random.nextFloat() * hAll
                canvas.drawRect(nx, ny, nx + s * (1 + random.nextFloat() * 3), ny + s, fill)
            }
        }
        val appear = fade(t, 1400f, 900f)
        if (appear > 0) {
            val gap = size * 0.18f * (1 - fade(t, 1400f, 1600f))
            val jitter = (random.nextFloat() - 0.5f) * size * 0.06f * noise
            plain(canvas, WORD, x0 - gap + jitter, y, 0xFFFF0030.toInt(), appear * 0.75f)
            plain(canvas, WORD, x0 + gap - jitter, y, 0xFF00E5FF.toInt(), appear * 0.75f)
            if (t >= 3000) wordAt(canvas, fade(t, 3000f, 150f), 0.6f + (1 - fade(t, 3000f, 700f)) * 0.8f + breath(t, 3600f))
        }
        val step = max(3f, density * 3)
        fill.shader = null
        fill.clearShadowLayer()
        fill.color = withAlpha(Color.BLACK, 0.3f)
        var yy = 0f
        while (yy < hAll) {
            canvas.drawRect(-wAll, yy, wAll * 2, yy + step / 2, fill)
            yy += step
        }
        val by = fract(t / 2200) * hAll * 1.2f - hAll * 0.1f
        fill.shader = LinearGradient(0f, by - hAll * 0.08f, 0f, by + hAll * 0.08f, intArrayOf(0x00FFFFFF, 0x12FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        fill.color = Color.WHITE
        canvas.drawRect(-wAll, by - hAll * 0.08f, wAll * 2, by + hAll * 0.08f, fill)
        fill.shader = null
        canvas.restore()
    }

    private fun savePoint(canvas: Canvas, t: Float) {
        motes(canvas, t, 50, fade(t, 0f, 1000f) * 0.8f)
        val flyP = easeIn((t - 3300) / 400)
        if (t < 3700) {
            val sy = lerp(y - size * 0.5f, y, flyP)
            val r = size * 0.3f * max(0f, backOut((t - 200) / 600)) * (1 + 0.12f * sinf(t / 180)) * (1 + flyP * 1.5f)
            drawStar(canvas, cx, sy, r, t / 900 + flyP * 6, SAVE_YELLOW, clamp((t - 200) / 300))
        }
        val fs = size * 0.16f
        val bw = max(DeterminationGlyphs.width(SAVE_LINE, fs) + fs * 2.4f, wordWidth * 0.95f)
        val bh = fs * 2.6f
        val by = y + size * 0.35f
        val boxIn = easeOut((t - 900) / 250) * (1 - easeIn((t - 3150) / 200))
        if (boxIn > 0) {
            rect(canvas, cx - bw / 2, by, bw, bh, Color.BLACK, boxIn)
            line.pathEffect = null
            line.clearShadowLayer()
            line.color = withAlpha(Color.WHITE, boxIn)
            line.strokeWidth = fs * 0.18f
            canvas.drawRect(cx - bw / 2, by, cx + bw / 2, by + bh, line)
            val chars = ((t - 1150) / 42).toInt().coerceIn(0, SAVE_LINE.length)
            if (chars > 0) plain(canvas, SAVE_LINE.substring(0, chars), cx - bw / 2 + fs * 1.2f, by + bh / 2, Color.WHITE, boxIn, fs)
        }
        if (t >= 3700) {
            wordAt(canvas, fade(t, 3700f, 120f), 0.6f + (1 - fade(t, 3700f, 800f)) + breath(t, 4300f), lerp(1.25f, 1f, easeOut((t - 3700) / 400)))
            burst(canvas, t, 3700f, cx, y, 36, 400, size * 1.8f, intArrayOf(SAVE_YELLOW, Color.WHITE, 0xFFFFF3A0.toInt()))
        }
        ring(canvas, t, 3700f, cx, y, along * 0.6f, 800f, SAVE_YELLOW)
    }

    private fun drop(canvas: Canvas, t: Float) {
        motes(canvas, t, 50, 0.6f)
        WORD.forEachIndexed { i, ch ->
            val t0 = 700f + i * 190
            if (t < t0) return@forEachIndexed
            val dy = lerp(-size * 1.2f, y, bounceOut((t - t0) / 650))
            val rot = (1 - easeOut((t - t0) / 500)) * (rnd(i, 90) - 0.5f) * 1.6f
            val wave = if (t > 2500 && t < 3300) sinf((t - 2500) / 800 * PI.toFloat()) * sinf((t - 2500) / 110 - i * 0.7f) * size * 0.12f else 0f
            val ccx = charX[i] + charW[i] / 2
            canvas.save()
            canvas.translate(ccx, dy + wave)
            canvas.rotate(rot * 180 / PI.toFloat())
            canvas.translate(-ccx, -y)
            glowText(canvas, ch.toString(), charX[i], y, 1f, 0.55f + (1 - fade(t, t0 + 240, 500f)) * 0.8f + pulse(t, 3000f) + breath(t, 3600f))
            canvas.restore()
            burst(canvas, t, t0 + 240, ccx, y + cap * 0.5f, 8, i * 7 + 200, size * 0.5f, palette.sparks)
        }
        ring(canvas, t, 3000f, cx, y, along * 0.6f)
    }

    private fun warpSpeed(t: Float): Float = when {
        t < 2600 -> 0.1f + 1.4f * smooth(t / 2600)
        t < 2800 -> 1.5f
        else -> 0.1f + 1.4f * exp(-(t - 2800) / 300)
    }

    private fun warpPhase(t: Float): Float {
        val x = min(t, 2600f) / 2600
        var ph = 0.1f * t / 1000 + 1.4f * 2.6f * (x * x * x - x * x * x * x / 2)
        if (t > 2600) ph += 1.4f * (min(t, 2800f) - 2600) / 1000
        if (t > 2800) ph += 1.4f * 0.3f * (1 - exp(-(t - 2800) / 300))
        return ph
    }

    private fun warp(canvas: Canvas, t: Float) {
        val ph = warpPhase(t)
        val v = warpSpeed(t)
        val maxR = hypot(along, across) / 2
        line.pathEffect = null
        line.clearShadowLayer()
        for (i in 0 until 180) {
            val a = rnd(i, 20) * PI.toFloat() * 2
            val z = fract(rnd(i, 21) + ph * (0.6f + rnd(i, 22) * 0.8f))
            val r = z * z * maxR + size * 0.15f
            val len = min(r * 0.9f, r * v * 0.5f + density * 2)
            line.color = withAlpha(if (i % 5 == 0) palette.sparks[i % palette.sparks.size] else Color.WHITE, clamp(z * 1.5f) * fade(t, 0f, 400f))
            line.strokeWidth = density * (1 + z * 2.5f)
            canvas.drawLine(cx + cosf(a) * (r - len), y + sinf(a) * (r - len), cx + cosf(a) * r, y + sinf(a) * r, line)
        }
        val core = fade(t, 1200f, 1400f) * (1 - fade(t, 2800f, 300f))
        if (core > 0) {
            val radius = size * 1.4f
            fill.clearShadowLayer()
            fill.shader = RadialGradient(
                cx, y, radius,
                intArrayOf(withAlpha(Color.WHITE, core), withAlpha(palette.glow, core * 0.5f), withAlpha(palette.glow, 0f)),
                floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP,
            )
            fill.color = Color.WHITE
            canvas.drawRect(cx - radius, y - radius, cx + radius, y + radius, fill)
            fill.shader = null
        }
        if (t >= 2400) {
            val p = (t - 2400) / 400
            wordAt(canvas, clamp(p * 2), 0.6f + (1 - fade(t, 2800f, 800f)) + breath(t, 3500f), if (p < 1) lerp(0.04f, 1f, easeIn(p)) else 1f)
        }
        ring(canvas, t, 2800f, cx, y, along * 0.6f)
    }

    private fun door(canvas: Canvas, t: Float) {
        motes(canvas, t, 40, 0.6f * (1 - fade(t, 2600f, 400f)) + 0.6f * fade(t, 3300f, 600f))
        val dh = across * 0.62f
        val dw = dh * 0.48f
        val dx = cx - dw / 2
        val dy = y - dh / 2
        if (t >= 3100) wordAt(canvas, fade(t, 3100f, 500f), 0.6f + (1 - fade(t, 3100f, 900f)) * 0.9f + breath(t, 3800f))
        if (t < 3300) {
            val lit = easeInOut((t - 1500) / 900)
            if (lit > 0) {
                val rays = fade(t, 1900f, 900f)
                if (rays > 0) {
                    fill.shader = null
                    fill.clearShadowLayer()
                    fill.color = withAlpha(palette.glow, rays * 0.22f)
                    for (i in 0 until 14) {
                        val a = i / 14f * PI.toFloat() * 2 + t / 3000
                        val r = along * rays
                        val ray = Path().apply {
                            moveTo(cx, y)
                            lineTo(cx + cosf(a - 0.07f) * r, y + sinf(a - 0.07f) * r)
                            lineTo(cx + cosf(a + 0.07f) * r, y + sinf(a + 0.07f) * r)
                            close()
                        }
                        canvas.drawPath(ray, fill)
                    }
                }
                rect(canvas, dx, dy, dw, dh, Color.WHITE, lit, size * 0.6f * lit + 0.01f)
            }
            val drawn = clamp((t - 300) / 1000)
            line.color = Color.WHITE
            line.strokeWidth = density * 2 + size * 0.02f
            line.setShadowLayer(size * 0.1f, 0f, 0f, palette.glow)
            val perim = (dw + dh) * 2
            line.pathEffect = DashPathEffect(floatArrayOf(max(0.01f, perim * drawn), perim), 0f)
            val frame = Path().apply { addRect(dx, dy, dx + dw, dy + dh, Path.Direction.CW) }
            canvas.drawPath(frame, line)
            line.pathEffect = null
            if (drawn >= 1) {
                val pw = dw * cosf(lit * PI.toFloat() / 2 * 0.92f)
                val bulge = dh * 0.07f * lit
                val panel = Path().apply {
                    moveTo(dx, dy); lineTo(dx + pw, dy - bulge); lineTo(dx + pw, dy + dh + bulge); lineTo(dx, dy + dh); close()
                }
                fill.shader = null
                fill.clearShadowLayer()
                fill.color = 0xFF0B0A12.toInt()
                canvas.drawPath(panel, fill)
                canvas.drawPath(panel, line)
                fill.color = Color.WHITE
                canvas.drawCircle(dx + pw * 0.82f, y, size * 0.03f + density, fill)
            }
            line.clearShadowLayer()
        }
        val flood = fade(t, 2700f, 400f) * (1 - fade(t, 3100f, 700f))
        if (flood > 0) rect(canvas, -along, -across, along * 3, across * 3, Color.WHITE, flood)
    }

    private fun neon(canvas: Canvas, t: Float) {
        val colours = intArrayOf(palette.sparks[3], palette.sparks[1], palette.sparks[2])
        line.pathEffect = null
        line.clearShadowLayer()
        WORD.forEachIndexed { i, ch ->
            val colour = colours[if (i < 3) 0 else if (i < 5) 1 else 2]
            val on = 500 + rnd(i, 30) * 2000
            var lit = 0f
            if (t >= on + 350) lit = 1f
            else if (t >= on) lit = if (rnd((t / 45).toInt(), i + 31) > 0.5f) 1f else 0.15f
            if (t > 3600 && rnd((t / 70).toInt(), i + 50) > 0.985f) lit = 0.3f
            line.color = withAlpha(colour, 0.22f)
            line.strokeWidth = density * 1.2f
            text(canvas, ch.toString(), charX[i], y, line)
            if (lit > 0) {
                val core = if (lit >= 1 && t >= 3000 && t < 3150) Color.WHITE else colour
                glowText(canvas, ch.toString(), charX[i], y, lit, 0.9f + pulse(t, 3000f) + breath(t, 3600f), core, colour)
            }
        }
        ring(canvas, t, 3000f, cx, y, along * 0.6f, 800f, colours[0])
    }

    private fun dither(canvas: Canvas, t: Float) {
        motes(canvas, t, 40, 0.5f)
        val pts = letterPoints()
        val merge = fade(t, 2700f, 250f)
        if (merge < 1) {
            for (i in 0 until pts.size / 2) {
                val x = pts[i * 2]; val py = pts[i * 2 + 1]
                val t0 = 400 + (x - x0) / wordWidth * 1700 + rnd(i, 40) * 400
                val p = (t - t0) / 250
                if (p < 0) continue
                val s = pstep * 0.92f * (if (p < 1) max(0f, backOut(p)) else 1f)
                rect(canvas, x - s / 2, py - s / 2, s, s, if (p < 1) palette.sparks[i % palette.sparks.size] else Color.WHITE, 1 - merge)
            }
            val scan = (t - 600) / 1700
            if (scan > 0 && scan < 1.15f) {
                val sx = x0 + scan * wordWidth
                rect(canvas, sx - density, y - size * 0.6f, density * 2, size * 1.2f, palette.glow, 0.8f * (1 - merge), size * 0.3f)
            }
        }
        if (merge > 0) wordAt(canvas, merge, 0.6f + (1 - fade(t, 2800f, 800f)) * 0.9f + breath(t, 3400f))
        ring(canvas, t, 2800f, cx, y, along * 0.6f)
    }

    private fun orbit(canvas: Canvas, t: Float) {
        motes(canvas, t, 50, 0.7f)
        if (t < 2700) {
            val p = clamp(t / 2700)
            val hp = size * 0.035f
            val appear = fade(t, 0f, 500f)
            SOULS.forEachIndexed { k, colour ->
                for (j in 6 downTo 0) {
                    val q = clamp(p - j * 0.012f)
                    val rq = lerp(across * 0.4f, 0f, easeIn(q))
                    val a = 1.2f * q * 2.7f + 6 * 2.7f * q * q * q / 3 + k * PI.toFloat() * 2 / 7
                    val x = cx + cosf(a) * rq * 1.7f
                    val py = y + sinf(a) * rq
                    if (j == 0) drawHeart(canvas, x, py, hp, appear, color = colour)
                    else rect(canvas, x - hp, py - hp, hp * 2, hp * 2, colour, appear * (1 - j / 7f) * 0.5f)
                }
            }
        }
        if (t >= 2700) wordAt(canvas, fade(t, 2700f, 100f), 0.6f + (1 - fade(t, 2700f, 900f)) + breath(t, 3400f), lerp(0.5f, 1f, backOut((t - 2700) / 500)))
        burst(canvas, t, 2700f, cx, y, 42, 500, size * 2, SOULS)
        ring(canvas, t, 2700f, cx, y, along * 0.6f, 800f, Color.WHITE)
    }

    private fun rain(canvas: Canvas, t: Float) {
        val fs = size * 0.2f
        val cols = max(1, (along / (fs * 0.75f)).toInt())
        val show = (1 - fade(t, 2900f, 700f)) * fade(t, 0f, 400f)
        if (show > 0) {
            for (c in 0 until cols) {
                val head = (fract(rnd(c, 11) + t / 1000 * (0.35f + rnd(c, 10) * 0.5f)) * 1.6f - 0.3f) * across
                val colX = (c + 0.5f) * along / cols
                for (j in 0 until 10) {
                    val py = head - j * fs * 1.05f
                    if (py < -fs || py > across + fs) continue
                    val ch = RAIN_CHARS[(rnd(c * 31 + j, (t / 120).toInt()) * RAIN_CHARS.length).toInt().coerceIn(0, RAIN_CHARS.length - 1)].toString()
                    val a = (if (j == 0) 1f else (1 - j / 10f) * 0.6f) * show
                    plain(canvas, ch, colX - DeterminationGlyphs.width(ch, fs) / 2, py, if (j == 0) Color.WHITE else palette.glow, a, fs)
                }
            }
        }
        WORD.forEachIndexed { i, ch ->
            val lock = 1100f + i * 190
            if (t >= lock - 400 && t < lock) glowText(canvas, ch.toString(), charX[i], lerp(-size, y, easeIn((t - lock + 400) / 400)), 0.8f, 0.7f)
            if (t >= lock) glowText(canvas, ch.toString(), charX[i], y, 1f, 0.55f + (1 - fade(t, lock, 500f)) * 1.2f + pulse(t, 2900f) + breath(t, 3500f))
            burst(canvas, t, lock, charX[i] + charW[i] / 2, y, 7, i * 5 + 600, size * 0.45f, palette.sparks)
        }
        ring(canvas, t, 2900f, cx, y, along * 0.6f)
    }

    private fun fireworks(canvas: Canvas, t: Float) {
        motes(canvas, t, 30, 0.5f)
        floatArrayOf(900f, 1600f, 2300f).forEachIndexed { i, tb ->
            val pcx = partX[i] + partW[i] / 2
            val launch = tb - 600
            if (t >= launch && t < tb) {
                val p = easeOut((t - launch) / 600)
                val sx = pcx + (rnd(i, 1) - 0.5f) * size
                val x = lerp(sx, pcx, p)
                val ry = lerp(across * 1.05f, y, p)
                for (j in 0 until 8) {
                    val s = density * (3 - j * 0.25f)
                    rect(
                        canvas, x - s / 2 + (random.nextFloat() - 0.5f) * density * 3, ry + j * size * 0.08f - s / 2, s, s,
                        if (j == 0) Color.WHITE else palette.sparks[(i + j) % palette.sparks.size],
                        (1 - j / 8f) * (0.5f + 0.5f * random.nextFloat()),
                    )
                }
            }
            firework(canvas, t, tb, pcx, y, 46, i * 20 + 700, size * 1.5f, palette.sparks)
            ring(canvas, t, tb, pcx, y, size * 1.3f, 600f, palette.sparks[(i + 1) % palette.sparks.size])
            if (t >= tb) glowText(canvas, PARTS[i], partX[i], y, fade(t, tb, 150f), 0.55f + (1 - fade(t, tb, 700f)) * 1.2f + pulse(t, 3000f) + breath(t, 3600f))
        }
        for (j in 0 until 5) {
            val fx = along * (0.1f + rnd(j, 95) * 0.8f)
            val fy = across * (0.12f + rnd(j, 96) * 0.25f) * (if (j % 2 == 1) 1f else 3.2f)
            firework(canvas, t, 3000f + j * 110, fx, fy, 34, j * 13 + 900, size * 0.9f, palette.sparks)
        }
    }
}
