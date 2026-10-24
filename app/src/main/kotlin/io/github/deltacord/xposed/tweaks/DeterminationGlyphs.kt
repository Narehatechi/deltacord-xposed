package io.github.deltacord.xposed.tweaks

import android.graphics.Matrix
import android.graphics.Path

/**
 * The letters the startup animation needs, traced from Determination Mono (assets/fonts) as
 * outlines. Drawing outlines needs no font loading at all, so the animation always shows
 * Determination, whatever Android does with font files in a patched app.
 */
internal object DeterminationGlyphs {
    const val UNITS_PER_EM = 2048

    /** Character to (advance width, outline in font units with y pointing down). */
    private val GLYPHS: Map<Char, Pair<Int, String>> = mapOf(
        ' ' to (1024 to ""),
        'A' to (1024 to "M128 0 L128 -1024 L256 -1024 L256 -1152 L768 -1152 L768 -1024 L896 -1024 L896 0 L640 0 L640 -384 L384 -384 L384 0 Z M384 -512 L640 -512 L640 -1024 L384 -1024 Z"),
        'C' to (1024 to "M128 -128 L128 -1024 L256 -1024 L256 -1152 L768 -1152 L768 -1024 L896 -1024 L896 -768 L640 -768 L640 -1024 L384 -1024 L384 -128 L640 -128 L640 -384 L896 -384 L896 -128 L768 -128 L768 0 L256 0 L256 -128 Z"),
        'D' to (1024 to "M128 0 L128 -1152 L768 -1152 L768 -1024 L896 -1024 L896 -128 L768 -128 L768 0 Z M384 -128 L640 -128 L640 -1024 L384 -1024 Z"),
        'E' to (1024 to "M128 0 L128 -1152 L896 -1152 L896 -1024 L384 -1024 L384 -640 L768 -640 L768 -512 L384 -512 L384 -128 L896 -128 L896 0 Z"),
        'L' to (1024 to "M128 0 L128 -1152 L384 -1152 L384 -128 L896 -128 L896 0 Z"),
        'N' to (1024 to "M128 0 L128 -1152 L384 -1152 L384 -896 L512 -896 L512 -640 L384 -640 L384 0 Z M512 -384 L512 -640 L640 -640 L640 -1152 L896 -1152 L896 0 L640 0 L640 -384 Z"),
        'O' to (1024 to "M128 -128 L128 -1024 L256 -1024 L256 -1152 L768 -1152 L768 -1024 L896 -1024 L896 -128 L768 -128 L768 0 L256 0 L256 -128 Z M384 -128 L640 -128 L640 -1024 L384 -1024 Z"),
        'R' to (1024 to "M128 0 L128 -1152 L768 -1152 L768 -1024 L896 -1024 L896 -640 L768 -640 L768 -512 L896 -512 L896 0 L640 0 L640 -512 L384 -512 L384 0 Z M384 -640 L640 -640 L640 -1024 L384 -1024 Z"),
        'T' to (1024 to "M128 -1024 L128 -1152 L896 -1152 L896 -1024 L640 -1024 L640 0 L384 0 L384 -1024 Z"),
        'a' to (1024 to "M128 -128 L128 -384 L256 -384 L256 -512 L640 -512 L640 -768 L384 -768 L384 -640 L128 -640 L128 -768 L256 -768 L256 -896 L768 -896 L768 -768 L896 -768 L896 0 L256 0 L256 -128 Z M384 -128 L640 -128 L640 -384 L384 -384 Z"),
        'b' to (1024 to "M128 0 L128 -1152 L384 -1152 L384 -896 L768 -896 L768 -768 L896 -768 L896 -128 L768 -128 L768 0 Z M384 -128 L640 -128 L640 -768 L384 -768 Z"),
        'c' to (1024 to "M128 -128 L128 -768 L256 -768 L256 -896 L768 -896 L768 -768 L896 -768 L896 -512 L640 -512 L640 -768 L384 -768 L384 -128 L640 -128 L640 -384 L896 -384 L896 -128 L768 -128 L768 0 L256 0 L256 -128 Z"),
        'e' to (1024 to "M128 -128 L128 -768 L256 -768 L256 -896 L768 -896 L768 -768 L896 -768 L896 -384 L384 -384 L384 -128 L640 -128 L640 -256 L896 -256 L896 -128 L768 -128 L768 0 L256 0 L256 -128 Z M384 -512 L640 -512 L640 -768 L384 -768 Z"),
        'h' to (1024 to "M128 0 L128 -1152 L384 -1152 L384 -896 L768 -896 L768 -768 L896 -768 L896 0 L640 0 L640 -768 L384 -768 L384 0 Z"),
        'i' to (1024 to "M128 0 L128 -128 L384 -128 L384 -768 L128 -768 L128 -896 L640 -896 L640 -128 L896 -128 L896 0 Z M384 -1024 L384 -1280 L640 -1280 L640 -1024 Z"),
        'r' to (1024 to "M128 0 L128 -896 L768 -896 L768 -768 L896 -768 L896 -512 L640 -512 L640 -768 L384 -768 L384 0 Z"),
        't' to (1024 to "M128 -768 L128 -896 L384 -896 L384 -1152 L640 -1152 L640 -896 L896 -896 L896 -768 L640 -768 L640 -128 L896 -128 L896 0 L512 0 L512 -128 L384 -128 L384 -768 Z"),
        'y' to (1024 to "M128 -128 L128 -896 L384 -896 L384 -128 L640 -128 L640 -896 L896 -896 L896 256 L768 256 L768 384 L256 384 L256 256 L128 256 L128 128 L384 128 L384 256 L640 256 L640 0 L256 0 L256 -128 Z"),
    )

    fun advance(text: String): Int = text.sumOf { GLYPHS[it]?.first ?: (UNITS_PER_EM / 2) }

    /** Width of [text] at [size] pixels per em. */
    fun width(text: String, size: Float): Float = advance(text) * size / UNITS_PER_EM

    private val cache = HashMap<String, Path>()

    /** [text] as one path at [size] pixels per em, starting at x = 0 on the baseline (y = 0). */
    fun path(text: String, size: Float): Path {
        val key = "$text@$size"
        cache[key]?.let { return it }
        val out = Path().apply { fillType = Path.FillType.EVEN_ODD }
        var x = 0
        for (ch in text) {
            val (advance, outline) = GLYPHS[ch] ?: (UNITS_PER_EM / 2 to "")
            out.addPath(parse(outline), x.toFloat(), 0f)
            x += advance
        }
        val scale = size / UNITS_PER_EM
        out.transform(Matrix().apply { setScale(scale, scale) })
        if (cache.size > 32) cache.clear()
        cache[key] = out
        return out
    }

    private fun parse(outline: String): Path {
        val path = Path()
        val parts = outline.split(' ').filter { it.isNotEmpty() }
        var i = 0
        fun num(): Float = parts[i++].toFloat()
        while (i < parts.size) {
            // Each command letter is glued to its first number ("M120 0"); Z stands alone.
            val token = parts[i++]
            val command = token[0]
            if (command == 'Z') {
                path.close()
                continue
            }
            val first = token.substring(1).toFloat()
            when (command) {
                'M' -> path.moveTo(first, num())
                'L' -> path.lineTo(first, num())
                'Q' -> path.quadTo(first, num(), num(), num())
                'C' -> path.cubicTo(first, num(), num(), num(), num(), num())
            }
        }
        return path
    }
}
