package io.github.deltacord.xposed.tweaks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.floor
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
) {
    fun withCredit(text: String) = StartupPalette(id, glow, sparks, shine, if (rare) "* $text *" else text, rare)
}

/** The start-up animation styles and colour variants, and picking one for a launch. */
internal object StartupStyles {
    val IDS = listOf("starchart", "badsignal", "eclipse", "blockdrop", "nightsign", "bootlog")
    const val DEFAULT = "starchart"

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

    /** Letters the animation can draw (printable ASCII), upper-cased, at most 14. */
    fun cleanText(text: String): String =
        text.uppercase().filter { it in ' '..'~' }.trim().replace(Regex(" +"), " ").take(14)

    private fun color(value: String?): Int? = runCatching { if (value.isNullOrBlank()) null else Color.parseColor(value) }.getOrNull()

    /**
     * Your own colours from Deltacord's animation editor: "glow" (the glow, motes and fountain)
     * and up to four "sparks" (sparkles, bursts, the shine). Null when you haven't set any.
     */
    fun customPalette(config: org.json.JSONObject?): StartupPalette? {
        config ?: return null
        val glow = color(config.optString("glow").takeIf { config.optString("colors") == "custom" }) ?: return null
        val sparkList = config.optJSONArray("sparks")
        val sparks = (0 until (sparkList?.length() ?: 0)).mapNotNull { color(sparkList?.optString(it)) }.take(4)
            .ifEmpty { listOf(Color.WHITE, glow) }
        val all = (sparks + sparks + sparks).take(4).toIntArray()
        val shine = intArrayOf(Color.WHITE) + sparks.toIntArray() + glow + Color.WHITE
        return StartupPalette("custom", glow, all, shine)
    }

    /** Your own credit line from the animation editor (instead of "by Narehatechi"), if you set one. */
    fun creditOf(config: org.json.JSONObject?): String? =
        config?.optString("credit")?.filter { it in ' '..'~' }?.trim()?.take(30)?.takeIf { it.isNotEmpty() }

    fun pickPalette(random: java.util.Random): StartupPalette =
        if (random.nextInt(50) == 0) GOLD else COMMON[random.nextInt(COMMON.size)]
}
/**
 * Draws one start-up animation [style] in [palette]. Everything is drawn from the time since the
 * animation started, so a recreated screen can pick it up at the same moment.
 *
 * The stage is the screen as it stands, with the word across its width.
 */
internal class StartupScene(
    val style: String,
    val palette: StartupPalette,
    private val seed: Int,
    private val density: Float,
    /** The word the animation spells (your own from Deltacord's animation editor, or DELTACORD). */
    word: String = "DELTACORD",
) {
    private val WORD = word

    companion object {
        /** The animation always plays this long before Discord can show. */
        const val PLAY_MS = 6500L

        private val BOOT_LINES = arrayOf(
            "WAKING THE DARK", "LOADING THEME", "TUNING FONTS", "HIDING THE ADS", "STARTING PLUGINS",
            "POLISHING PIXELS", "FEEDING THE CAT", "COUNTING STARS", "CHECKING SAVES", "WARMING THE SOUL",
            "OPENING THE DOOR", "ALMOST THERE", "ALL SYSTEMS GO",
        )
        private const val PINK = 0xFFFF3FB4.toInt()
        private const val GREEN = 0xFF62FF9A.toInt()
    }

    private class Timing(val impacts: List<Pair<Float, Float>>, val sparkle: Float, val credit: Float)

    private val timing = when (style) {
        "badsignal" -> Timing(listOf(4450f to 5f), 4750f, 3900f)
        "eclipse" -> Timing(listOf(3300f to 4f), 4400f, 4300f)
        "blockdrop" -> Timing(listOf(4300f to 6f), 4800f, 4700f)
        "nightsign" -> Timing(emptyList(), 5000f, 4700f)
        "bootlog" -> Timing(emptyList(), 5000f, 4900f)
        else -> Timing(emptyList(), 4900f, 4800f)
    }

    /** The little squares the word is made of. */
    private class Cells(val x: FloatArray, val y: FloatArray, val letter: IntArray, val row: IntArray)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glyphs = HashMap<String, Path>()
    private val measure = PathMeasure()
    private val segment = Path()
    private val ground = Path()
    private val hsv = FloatArray(3)

    // Layout.
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
    /** One unit of the designs (they were drawn on a 360-wide screen). */
    private var k = 1f
    /** One square of a letter. */
    private var u = 1f
    /** How much later each letter starts, as a share of the nine-letter timing. */
    private val pace = 9f / max(1, WORD.length)
    private val charX = FloatArray(WORD.length)
    private val charW = FloatArray(WORD.length)
    private var cells: Cells? = null

    // ---------- maths ----------

    private fun clamp(x: Float, a: Float = 0f, b: Float = 1f) = x.coerceIn(a, b)
    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
    private fun smooth(x: Float): Float { val c = clamp(x); return c * c * (3 - 2 * c) }
    private fun fade(t: Float, from: Float, length: Float) = smooth((t - from) / length)
    /** 0 before [from], 1 after [to]. */
    private fun span(t: Float, from: Float, to: Float) = clamp((t - from) / (to - from))
    private fun easeOut(x: Float): Float { val c = clamp(x); return 1 - (1 - c).pow(3) }
    private fun easeInOut(x: Float): Float {
        val c = clamp(x)
        return if (c < 0.5f) 4 * c * c * c else 1 - (-2 * c + 2).pow(3) / 2
    }
    private fun fract(x: Double) = x - floor(x)
    private fun sinf(x: Float) = sin(x.toDouble()).toFloat()
    private fun cosf(x: Float) = cos(x.toDouble()).toFloat()

    /** The same "random" number for the same inputs, so frames can be redrawn exactly. */
    private fun rnd(i: Int, k: Int = 0): Float =
        fract(sin(i * 127.1 + k * 311.7 + seed * 0.731) * 43758.5453).toFloat()

    private fun alpha(a: Float) = (clamp(a) * 255).roundToInt()
    private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or (alpha(a) shl 24)
    private fun rgb(r: Float, g: Float, b: Float) = Color.rgb(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))

    private fun impact(t: Float, length: Float): Float {
        var most = 0f
        for ((at, amount) in timing.impacts) {
            val p = (t - at) / length
            if (p in 0f..1f) most = max(most, amount * (1 - p) * (1 - p))
        }
        return most
    }

    // ---------- layout and letters ----------

    private fun layout(w: Int, h: Int) {
        laidOutW = w
        laidOutH = h
        along = w.toFloat()
        across = h.toFloat()
        val wide = w > h
        // The eclipse needs room above the word for its sun.
        val tallest = if (style == "eclipse" && wide) 0.14f else 0.42f
        size = min(along * 0.8f / DeterminationGlyphs.width(WORD, 1f), across * tallest)
        wordWidth = DeterminationGlyphs.width(WORD, size)
        x0 = (along - wordWidth) / 2
        y = across * when {
            style != "eclipse" -> 0.445f
            wide -> 0.78f
            else -> 0.535f
        }
        cx = along / 2
        cap = size * 1152f / DeterminationGlyphs.UNITS_PER_EM
        k = size / 64f
        u = size / 16f
        WORD.forEachIndexed { i, ch ->
            charX[i] = x0 + DeterminationGlyphs.width(WORD.substring(0, i), size)
            charW[i] = DeterminationGlyphs.width(ch.toString(), size)
        }
        cells = null
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

    /** Small text centred on [centre]. */
    private fun label(canvas: Canvas, text: String, centre: Float, mid: Float, color: Int, a: Float, at: Float) =
        plain(canvas, text, centre - DeterminationGlyphs.width(text, at) / 2, mid, color, a, at)

    /** Text with a two-layer glow. */
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

    /** The squares that make up the word, read off the letters themselves. */
    private fun wordCells(): Cells {
        cells?.let { return it }
        val scale = min(1f, 900f / max(1f, wordWidth))
        val top = y + cap / 2 - 10 * u
        val bw = max(1, (wordWidth * scale).toInt() + 2)
        val bh = max(1, (12 * u * scale).toInt() + 2)
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(scale, scale)
        c.translate(-x0, -top)
        text(c, WORD, x0, y, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val pixels = IntArray(bw * bh)
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        bitmap.recycle()

        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val letters = ArrayList<Int>(); val rows = ArrayList<Int>()
        for (i in WORD.indices) {
            val cols = (charW[i] / u).roundToInt()
            for (col in 0 until cols) for (row in -10..1) {
                val px = ((charX[i] - x0 + (col + 0.5f) * u) * scale).toInt()
                val py = (((row + 10) + 0.5f) * u * scale).toInt()
                if (px in 0 until bw && py in 0 until bh && (pixels[py * bw + px] ushr 24) > 128) {
                    xs.add(charX[i] + col * u); ys.add(top + (row + 10) * u); letters.add(i); rows.add(row)
                }
            }
        }
        return Cells(xs.toFloatArray(), ys.toFloatArray(), letters.toIntArray(), rows.toIntArray()).also { cells = it }
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

    /** A soft round glow. */
    private fun glowDot(canvas: Canvas, gx: Float, gy: Float, r: Float, color: Int, a: Float) {
        if (a <= 0f || r <= 0f) return
        fill.clearShadowLayer()
        fill.color = Color.WHITE
        fill.shader = RadialGradient(gx, gy, r, withAlpha(color, a), color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(gx, gy, r, fill)
        fill.shader = null
    }

    /** A four-pointed twinkle with thin, tapering arms. */
    private fun twinkle(canvas: Canvas, sx: Float, sy: Float, r: Float, color: Int, a: Float) {
        if (a <= 0f || r <= 0f) return
        segment.rewind()
        segment.moveTo(sx, sy - r)
        segment.quadTo(sx, sy, sx + r, sy)
        segment.quadTo(sx, sy, sx, sy + r)
        segment.quadTo(sx, sy, sx - r, sy)
        segment.quadTo(sx, sy, sx, sy - r)
        segment.close()
        fill.shader = null
        fill.clearShadowLayer()
        fill.color = withAlpha(color, a)
        canvas.drawPath(segment, fill)
    }

    /** Draws the first [p] of letter [i]'s outline with [line]. */
    private fun trace(canvas: Canvas, i: Int, p: Float) {
        canvas.save()
        canvas.translate(charX[i], y + cap / 2)
        measure.setPath(glyph(WORD[i].toString(), size), true)
        do {
            segment.rewind()
            if (measure.getSegment(0f, measure.length * clamp(p), segment, true)) {
                segment.rLineTo(0f, 0f)
                canvas.drawPath(segment, line)
            }
        } while (measure.nextContour())
        canvas.restore()
    }

    private fun scanlines(canvas: Canvas, a: Float) {
        fill.shader = null
        fill.clearShadowLayer()
        fill.color = withAlpha(Color.BLACK, a)
        val step = max(3f, 3 * k)
        var sy = 0f
        while (sy < across) { canvas.drawRect(0f, sy, along, sy + step / 3, fill); sy += step }
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
        line.shader = null
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
        val shake = impact(t, 350f) * density
        canvas.translate(sinf(t * 0.11f) * shake, cosf(t * 0.137f) * shake)
        when (style) {
            "badsignal" -> badSignal(canvas, t)
            "eclipse" -> eclipse(canvas, t)
            "blockdrop" -> blockDrop(canvas, t)
            "nightsign" -> nightSign(canvas, t)
            "bootlog" -> bootLog(canvas, t)
            else -> starChart(canvas, t)
        }
        if (t >= timing.sparkle) sparkle(canvas, t, timing.sparkle)
        canvas.restore()

        credit(canvas, t, timing.credit, w.toFloat(), h.toFloat())

        val flash = impact(t, 200f) / 13 * 0.28f
        if (flash > 0f) canvas.drawColor(withAlpha(palette.glow, flash))
    }

    // ---------- the six styles ----------

    /** Stars join up into the name, one letter at a time. */
    private fun starChart(canvas: Canvas, t: Float) {
        fill.clearShadowLayer()
        fill.color = Color.WHITE
        fill.shader = LinearGradient(0f, 0f, 0f, across, 0xFF03040F.toInt(), 0xFF0D1030.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, along, across, fill)
        fill.shader = null
        for (i in 0 until 110) {
            val s = k * (0.6f + rnd(i, 3) * 1.4f)
            rect(canvas, rnd(i, 0) * along, rnd(i, 1) * across, s, s, 0xFFC8DCFF.toInt(), 0.25f + 0.5f * max(0f, sinf(t / 500 + rnd(i, 2) * 6.3f)))
        }

        val filled = span(t, 4000f, 4800f)
        line.pathEffect = null
        line.shader = null
        line.strokeWidth = max(density, 1.2f * k)
        line.color = withAlpha(0xFF96CDFF.toInt(), 0.9f * (1 - filled * 0.7f))
        line.setShadowLayer(4 * k, 0f, 0f, 0xFF96CDFF.toInt())
        for (i in WORD.indices) {
            val p = easeInOut(span(t, 500f + i * 330 * pace, 1500f + i * 330 * pace))
            if (p > 0f) trace(canvas, i, p)
        }
        line.clearShadowLayer()
        if (filled > 0f) glowText(canvas, WORD, x0, y, filled, 1 - span(t, 4800f, 5600f) * 0.5f, shadow = 0xFF8FC8FF.toInt())

        // A shooting star crosses as the letters fill in.
        val s = span(t, 4200f, 5000f)
        if (s > 0f && s < 1f) {
            val sx = lerp(-40 * k, along + 60 * k, s)
            val sy = y + lerp(-200 * k, -60 * k, s)
            line.strokeWidth = 2 * k
            line.color = Color.WHITE
            line.shader = LinearGradient(sx - 90 * k, sy - 32 * k, sx, sy, 0x00FFFFFF, Color.WHITE, Shader.TileMode.CLAMP)
            canvas.drawLine(sx - 90 * k, sy - 32 * k, sx, sy, line)
            line.shader = null
            twinkle(canvas, sx, sy, 7 * k, Color.WHITE, 1f)
        }
    }

    /** Static and a torn picture that snaps into focus. */
    private fun badSignal(canvas: Canvas, t: Float) {
        canvas.drawColor(0xFF050507.toInt())
        val hit = if (t > 4450 && t < 4620) 1f else 0f
        val settle = max(0f, easeOut(span(t, 2500f, 3600f)) - hit * 0.7f)
        val frame = (t / 55).toInt()
        val specks = (420 * (1 - settle * 0.93f)).toInt()
        for (i in 0 until specks) {
            rect(canvas, rnd(i, frame + 1) * along, rnd(i, frame + 2) * across, k * (2 + rnd(i, 5) * 22), 2 * k, Color.WHITE, 0.05f + rnd(i, frame) * 0.28f)
        }

        if (t >= 500) {
            val split = k * (1 + 7 * (1 - settle))
            val top = y - cap * 0.8f
            val bandH = cap * 1.6f / 14
            val tear = (1 - settle).pow(1.6f)
            for (band in 0 until 14) {
                val dx = (rnd(band + 500, frame) - 0.5f) * along * 0.7f * tear
                canvas.save()
                canvas.clipRect(0f, top + band * bandH, along, top + (band + 1) * bandH)
                plain(canvas, WORD, x0 + dx - split, y, 0xFFFF2BD6.toInt(), 0.75f)
                plain(canvas, WORD, x0 + dx + split, y, 0xFF22E6FF.toInt(), 0.75f)
                plain(canvas, WORD, x0 + dx, y, Color.WHITE, 1f)
                canvas.restore()
            }
        }

        scanlines(canvas, 0.28f)
        val roll = (t / 9 * k) % (across + 160 * k) - 80 * k
        rect(canvas, 0f, roll, along, 46 * k, Color.WHITE, 0.05f)

        val small = 18 * k
        val labelY = across * 0.066f
        if (settle < 0.9f) {
            if ((t / 380).toInt() % 2 == 1) plain(canvas, "NO SIGNAL", 18 * k, labelY, 0xFFFF3355.toInt(), 1f, small)
        } else {
            plain(canvas, "SIGNAL FOUND", 18 * k, labelY, GREEN, 1f, small)
            rect(canvas, 18 * k, labelY + small * 0.5f, DeterminationGlyphs.width("SIGNAL FOUND", small) * span(t, 3600f, 4300f), 3 * k, GREEN, 1f)
        }
        plain(canvas, "CH 03", along - 18 * k - DeterminationGlyphs.width("CH 03", small), labelY, Color.WHITE, 0.6f, small)
    }

    /** The light goes out, and the name rises in the dark. */
    private fun eclipse(canvas: Canvas, t: Float) {
        val cover = easeInOut(span(t, 300f, 2700f))
        val sx = cx
        val sy = y - 208 * k
        val r = 62 * k
        val mx = sx - 150 * k * (1 - cover) + 26 * k * span(t, 3300f, 6500f)

        fill.clearShadowLayer()
        fill.color = Color.WHITE
        fill.shader = LinearGradient(
            0f, 0f, 0f, across,
            rgb(lerp(58f, 4f, cover), lerp(46f, 3f, cover), lerp(110f, 14f, cover)),
            rgb(lerp(200f, 10f, cover), lerp(110f, 6f, cover), lerp(90f, 20f, cover)),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, along, across, fill)
        fill.shader = null
        for (i in 0 until 70) {
            rect(canvas, rnd(i, 0) * along, rnd(i, 1) * across * 0.8f, 1.5f * k, 1.5f * k, Color.WHITE, cover * cover * (0.3f + 0.5f * max(0f, sinf(t / 420 + rnd(i, 2) * 6.3f))))
        }

        // The corona, at its brightest while the sun is fully covered.
        val total = span(t, 2300f, 2900f) * (1 - span(t, 3300f, 3700f) * 0.55f)
        if (total > 0f) {
            line.pathEffect = null
            line.shader = null
            line.clearShadowLayer()
            line.strokeWidth = 5 * k
            val turn = t / 9000
            for (i in 0 until 28) {
                val a = i / 28f * 6.2832f + turn
                val len = r + 30 * k + 46 * k * rnd(i, 9) + 8 * k * sinf(t / 300 + i)
                val mid = lerp(r, len, 0.45f)
                line.color = withAlpha(0xFFFFF4D6.toInt(), 0.5f * total)
                canvas.drawLine(sx + cosf(a) * r * 0.6f, sy + sinf(a) * r * 0.6f, sx + cosf(a) * mid, sy + sinf(a) * mid, line)
                line.color = withAlpha(0xFFFFF4D6.toInt(), 0.18f * total)
                canvas.drawLine(sx + cosf(a) * mid, sy + sinf(a) * mid, sx + cosf(a) * len, sy + sinf(a) * len, line)
            }
        }
        glowDot(canvas, sx, sy, r * 2.3f, 0xFFFFD696.toInt(), 0.7f - cover * 0.35f)
        fill.color = 0xFFFFF6DC.toInt()
        canvas.drawCircle(sx, sy, r, fill)
        fill.color = 0xFF04030C.toInt()
        canvas.drawCircle(mx, sy, r + 1.5f * k, fill)

        // The first light coming back around the edge.
        val ring = span(t, 3300f, 3600f) * (1 - span(t, 3600f, 4600f))
        if (ring > 0f) {
            glowDot(canvas, sx - r * 0.98f, sy - 8 * k, 60 * k * ring, Color.WHITE, 0.95f)
            twinkle(canvas, sx - r * 0.98f, sy - 8 * k, 44 * k * ring, Color.WHITE, 1f)
        }

        val up = easeOut(span(t, 2700f, 3800f))
        if (up > 0f) glowText(canvas, WORD, x0, y + (1 - up) * 26 * k, up, 0.8f, shadow = 0xFFFFE9B8.toInt())

        // Hills along the bottom.
        ground.rewind()
        ground.moveTo(0f, across)
        var gx = 0f
        while (gx <= along + 12 * k) {
            ground.lineTo(gx, across - 70 * k - 26 * k * rnd((gx / (36 * k)).toInt(), 4) - 14 * k * sinf(gx / (50 * k)))
            gx += 12 * k
        }
        ground.lineTo(along, across)
        ground.close()
        fill.color = 0xFF04030C.toInt()
        canvas.drawPath(ground, fill)
    }

    /** Blocks rain down and stack up into the letters. */
    private fun blockDrop(canvas: Canvas, t: Float) {
        canvas.drawColor(0xFF08070F.toInt())
        for (i in WORD.indices) rect(canvas, charX[i], 0f, max(1f, k * 0.6f), across, Color.WHITE, 0.05f)
        rect(canvas, x0 + wordWidth, 0f, max(1f, k * 0.6f), across, Color.WHITE, 0.05f)

        val white = span(t, 4300f, 4800f)
        val c = wordCells()
        fill.shader = null
        fill.clearShadowLayer()
        for (n in c.x.indices) {
            val at = 350f + (1 - c.row[n]) * 250 + c.letter[n] * 45 * pace + rnd(n, 7) * 160
            val p = span(t, at, at + 420)
            if (p <= 0f) continue
            val cy = lerp(c.y[n] - 420 * k, c.y[n], p * p)
            if (white >= 1f) fill.color = Color.WHITE
            else {
                hsv[0] = (c.letter[n] * 40f + 180f) % 360f
                hsv[1] = 0.7f * (1 - white)
                hsv[2] = lerp(0.96f, 1f, white)
                fill.color = Color.HSVToColor(hsv)
            }
            canvas.drawRect(c.x[n], cy, c.x[n] + u + 0.5f, cy + u + 0.5f, fill)
            if (p >= 1f && t - at - 420 < 120) {
                fill.color = withAlpha(Color.WHITE, 0.8f)
                canvas.drawRect(c.x[n] - k, c.y[n] - k, c.x[n] + u + k, c.y[n] + u + k, fill)
            }
        }
        val flash = span(t, 4300f, 4420f) * (1 - span(t, 4420f, 4800f))
        rect(canvas, 0f, y - cap * 0.75f, along, cap * 1.5f, Color.WHITE, flash * 0.6f)

        plain(canvas, "LINES %02d".format(WORD.length), 16 * k, across * 0.048f, Color.WHITE, 0.6f, 16 * k)
        if (t > 4500 && (t / 300).toInt() % 2 == 1) label(canvas, "CLEAR!", cx, y + cap / 2 + 44 * k, 0xFF7CF3C4.toInt(), 1f, 22 * k)
    }

    /** A neon sign buzzes on, one tube at a time. */
    private fun nightSign(canvas: Canvas, t: Float) {
        canvas.drawColor(0xFF120A14.toInt())
        // A brick wall.
        var by = 0f
        var rowIndex = 0
        while (by < across) {
            rect(canvas, 0f, by, along, 1.5f * k, Color.WHITE, 0.035f)
            var bx = (rowIndex % 2) * 26 * k
            while (bx < along) { rect(canvas, bx, by, 1.5f * k, 22 * k, Color.WHITE, 0.035f); bx += 52 * k }
            by += 22 * k
            rowIndex++
        }

        val flickery = min(WORD.length - 1, WORD.length * 5 / 9)
        var lit = 0
        line.pathEffect = null
        line.shader = null
        line.strokeWidth = 0.55f * u
        for (i in WORD.indices) {
            val on = 500f + i * 300 * pace
            val bad = i == flickery
            val steady = t > on + (if (bad) 3000 else 500)
            val isOn = t > on && (steady || rnd(i, (t / 70).toInt() + 900) > (if (bad) 0.45f else 0.3f))
            canvas.save()
            canvas.translate(charX[i], y + cap / 2)
            val path = glyph(WORD[i].toString(), size)
            if (isOn) {
                lit++
                fill.shader = null
                fill.clearShadowLayer()
                fill.color = withAlpha(PINK, 0.22f)
                canvas.drawPath(path, fill)
                line.color = 0xFFFFD6F0.toInt()
                line.setShadowLayer(16 * k, 0f, 0f, PINK)
                canvas.drawPath(path, line)
                canvas.drawPath(path, line)
                line.clearShadowLayer()
            } else {
                line.color = withAlpha(Color.WHITE, 0.12f)
                canvas.drawPath(path, line)
            }
            canvas.restore()
        }
        glowDot(canvas, cx, y, 260 * k, PINK, 0.03f * lit * pace)

        val cyan = 0xFFD6FBFF.toInt()
        val under = easeOut(span(t, 3700f, 4500f))
        if (under > 0f) {
            fill.shader = null
            fill.color = cyan
            fill.setShadowLayer(14 * k, 0f, 0f, 0xFF22E6FF.toInt())
            canvas.drawRect(x0, y + cap / 2 + 16 * k, x0 + wordWidth * under, y + cap / 2 + 19 * k, fill)
            fill.clearShadowLayer()
        }
        if (t > 4600) label(canvas, "OPEN ALL NIGHT", cx, y + cap / 2 + 46 * k, cyan, if ((t / 90).toInt() % 9 == 0) 0.3f else 1f, 16 * k)
    }

    /** A terminal checks everything, then prints the name. */
    private fun bootLog(canvas: Canvas, t: Float) {
        canvas.drawColor(0xFF020603.toInt())
        val small = 14 * k
        val top = across * 0.07f
        val pale = 0xFFB9FFD2.toInt()
        if (t < 2700) {
            val shown = clamp((t - 200) / 170, 0f, BOOT_LINES.size.toFloat()).toInt()
            canvas.save()
            canvas.clipRect(0f, across * span(t, 2450f, 2700f), along, across)
            for (n in 0 until shown) {
                val ly = top + n * 24 * k
                plain(canvas, "[ OK ]", 16 * k, ly, GREEN, 1f, small)
                plain(canvas, BOOT_LINES[n], 70 * k, ly, pale, 1f, small)
            }
            if (shown < BOOT_LINES.size && (t / 120).toInt() % 2 == 1) rect(canvas, 16 * k, top + shown * 24 * k - 6 * k, 8 * k, 13 * k, GREEN, 1f)
            canvas.restore()
        } else {
            val row = floor((t - 2900) / 115).toInt() - 10
            val done = span(t, 4400f, 5000f)
            val settled = rgb(lerp(98f, 255f, done), 255f, lerp(154f, 255f, done))
            val c = wordCells()
            fill.shader = null
            fill.clearShadowLayer()
            for (n in c.x.indices) {
                if (c.row[n] > row) continue
                fill.color = if (c.row[n] == row) Color.WHITE else settled
                canvas.drawRect(c.x[n], c.y[n], c.x[n] + u + 0.5f, c.y[n] + u + 0.5f, fill)
            }
            if (row in -10..1) rect(canvas, 0f, y + cap / 2 + row * u, along, u, GREEN, 0.35f)
            plain(canvas, "> $WORD", 16 * k, top, GREEN, 1f, small)
            if (t > 4400) {
                val readyY = y + cap / 2 + 40 * k
                plain(canvas, "READY.", x0, readyY, GREEN, 1f, 16 * k)
                if ((t / 300).toInt() % 2 == 1) rect(canvas, x0 + DeterminationGlyphs.width("READY. ", 16 * k), readyY - 7 * k, 9 * k, 14 * k, GREEN, 1f)
            }
        }
        scanlines(canvas, 0.25f)
    }
}
