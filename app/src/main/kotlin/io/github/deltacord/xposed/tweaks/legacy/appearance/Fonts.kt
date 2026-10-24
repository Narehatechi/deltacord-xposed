@file:Suppress("DEPRECATION")

package io.github.deltacord.xposed.tweaks.legacy.appearance

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.Typeface.CustomFallbackBuilder
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import io.github.deltacord.Logger
import io.github.deltacord.xposed.*
import io.github.deltacord.xposed.tweaks.legacy.DeltacordPayloadBuilder
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

@Serializable
data class FontDefinition(
    val name: String? = null,
    val description: String? = null,
    val spec: Int? = null,
    val main: Map<String, String>,
)

/**
 * Custom font loading + ReactFontManager hijack.
 */
val fonts by tweak {
    val log: Logger = this.log

    DeltacordPayloadBuilder.contribute { put("fontPatch", 2) }

    // ReactFontManager hijack runs regardless of fonts.json presence  it falls back to the default Typeface chain if no custom font file is found.
    val clazz = classLoader.loadClassOrNull($$"com.facebook.react.common.assets.ReactFontManager$Companion")
        ?: classLoader.loadClassOrNull($$"com.facebook.react.views.text.ReactFontManager$Companion")
    
    clazz!!.method("createAssetTypeface", String::class.java, Int::class.java, AssetManager::class.java).hook {
        before {
            val fontFamilyName: String = args[0].toString()
            val style: Int = args[1] as Int
            val assetManager: AssetManager = args[2] as AssetManager
            result = FontsState.createAssetTypeface(fontFamilyName, style, assetManager)
        }
    }

    withAppContext { ctx ->
        val dataDir = ctx.dataDir.absolutePath
        val fontDefFile = File(dataDir, "${DeltacordConstants.FILES_DIR}/fonts.json").apply { ensureFile() }

        val chosen = if (!fontDefFile.exists()) null else try {
            DeltacordJson.decodeFromString<FontDefinition>(fontDefFile.readText())
        } catch (e: Throwable) {
            log.w("fonts.json malformed: ${e.message}")
            null
        }
        // Deltacord's own themes come with Determination: use it from the very first frame, even
        // before Deltacord has written fonts.json (unless it's turned off in Extras).
        val fontDef: FontDefinition = chosen?.takeIf { it.name != null }
            ?: (if (DeterminationFont.wantedByTheme(dataDir)) DeterminationFont.definition() else null)
            ?: return@withAppContext
        val setName = fontDef.name ?: return@withAppContext

        val downloadsDir = File(dataDir, "${DeltacordConstants.FILES_DIR}/downloads/fonts").apply { ensureDir() }
        val setDir = File(downloadsDir, setName).apply { ensureDir() }
        FontsState.fontsDownloadsDir = downloadsDir
        FontsState.fontsAbsPath = setDir.absolutePath + "/"

        // Prune stale font files for this set.
        setDir.listFiles()?.forEach { file ->
            val fileName = file.name
            if (!fileName.startsWith(".")) {
                val fontName = fileName.split('.')[0]
                if (fontDef.main.keys.none { it == fontName }) {
                    log.i("Deleting stale font file: $fileName")
                    file.delete()
                }
            }
        }

        // Determination is built into this module: put it in place right away instead of
        // downloading it, so the font is there as Discord starts.
        // Android can quietly fail to load a font file (giving its default font instead), so for
        // Determination the font is used straight from the module, not through files.
        if (fontDef.main.isNotEmpty() && fontDef.main.values.all { DeterminationFont.isDeterminationUrl(it) }) {
            val builtin = io.github.deltacord.xposed.tweaks.BuiltinFont.get(modulePath)
            if (builtin == null) log.w("Built-in Determination font unavailable: ${io.github.deltacord.xposed.tweaks.BuiltinFont.problem}")
            FontsState.builtinKeys = fontDef.main.keys
            FontsState.builtin = builtin
        }

        val pending = fontDef.main.filter { (name, url) ->
            !(DeterminationFont.isDeterminationUrl(url) && DeterminationFont.install(modulePath, File(setDir, "$name.ttf"), log))
        }

        CoroutineScope(Dispatchers.IO).launch {
            pending.entries.map { (name, url) ->
                async {
                    try {
                        log.i("Downloading $name from $url")
                        val ext = FontsState.FILE_EXTENSIONS.firstOrNull { url.endsWith(it) } ?: ".ttf"
                        val file = File(setDir, "$name$ext").apply { ensureFile() }
                        if (file.exists()) return@async
                        val response: HttpResponse = httpClient.get(url)
                        if (response.status == HttpStatusCode.OK) {
                            file.writeBytes(response.body())
                        }
                    } catch (e: Throwable) {
                        log.e("Failed to download font ($name from $url)", e)
                    }
                }
            }.awaitAll()
        }
    }
}

/**
 * Holds the per-process font-state used by the `createAssetTypeface` hijack. Mirrors the static
 * fields of the old `FontsModule`.
 */
private object FontsState {
    val EXTENSIONS = arrayOf("", "_bold", "_italic", "_bold_italic")
    val FILE_EXTENSIONS = arrayOf(".ttf", ".otf")
    const val FONTS_ASSET_PATH = "fonts/"

    @Volatile
    var fontsDownloadsDir: File? = null

    @Volatile
    var fontsAbsPath: String? = null

    /** Determination, loaded from the module, for the fonts in [builtinKeys]. */
    @Volatile
    var builtin: Typeface? = null

    @Volatile
    var builtinKeys: Set<String> = emptySet()

    fun createAssetTypeface(
        rawName: String,
        style: Int,
        assetManager: AssetManager,
    ): Typeface? {
        val fontFamilyNames = rawName.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        builtin?.let { typeface -> if (fontFamilyNames.any { it in builtinKeys }) return typeface }

        var fontFamilyName = rawName
        if (fontFamilyNames.size > 1) {
            fontFamilyName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return createAssetTypefaceWithFallbacks(fontFamilyNames, style, assetManager)
            } else {
                fontFamilyNames[0]
            }
        }

        val extension = EXTENSIONS.getOrElse(style) { "" }

        try {
            for (fileExt in FILE_EXTENSIONS) {
                val split = fontFamilyName.split(":")
                if (split.size != 2) break
                val (customName, refName) = split
                val downloads = fontsDownloadsDir ?: break
                val file = File(downloads, "$customName/$refName.$fileExt").apply { ensureFile() }
                if (!file.exists()) continue
                return Typeface.createFromFile(file.absolutePath)
            }
        } catch (_: Throwable) {
        }

        for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
            for (fileExt in FILE_EXTENSIONS) {
                val fileName = "$fontRootPath$fontFamilyName$extension$fileExt"
                return try {
                    if (fileName[0] == '/') Typeface.createFromFile(fileName)
                    else Typeface.createFromAsset(assetManager, fileName)
                } catch (_: RuntimeException) {
                    continue
                }
            }
        }
        return Typeface.create(fontFamilyName, style)
    }

    private fun createAssetTypefaceWithFallbacks(
        fontFamilyNames: Array<String>,
        style: Int,
        assetManager: AssetManager,
    ): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val fontFamilies: MutableList<FontFamily> = ArrayList()
        for (fontFamilyName in fontFamilyNames) {
            try {
                for (fileExt in FILE_EXTENSIONS) {
                    val split = fontFamilyName.split(":")
                    if (split.size != 2) break
                    val (customName, refName) = split
                    val downloads = fontsDownloadsDir ?: break
                    val file = File(downloads, "$customName/$refName.$fileExt").apply { ensureFile() }
                    if (!file.exists()) continue
                    val font = Font.Builder(file).build()
                    fontFamilies.add(FontFamily.Builder(font).build())
                }
            } catch (_: Throwable) {
            }

            for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
                for (fileExt in FILE_EXTENSIONS) {
                    val fileName = "$fontRootPath$fontFamilyName$fileExt"
                    try {
                        val builder = if (fileName[0] == '/') Font.Builder(File(fileName))
                        else Font.Builder(assetManager, fileName)
                        val font = builder.build()
                        fontFamilies.add(FontFamily.Builder(font).build())
                    } catch (_: RuntimeException) {
                        continue
                    } catch (_: IOException) {
                        continue
                    }
                }
            }
        }

        if (fontFamilies.isEmpty()) return createAssetTypeface(fontFamilyNames[0], style, assetManager)

        val fallbackBuilder = CustomFallbackBuilder(fontFamilies[0])
        for (i in 1 until fontFamilies.size) fallbackBuilder.addCustomFallback(fontFamilies[i])
        return fallbackBuilder.build()
    }
}


/** Determination Mono, shipped in this module (assets/fonts), for Deltacord's built-in themes. */
private object DeterminationFont {
    private const val ASSET = "assets/fonts/DeterminationMono.ttf"
    private const val URL = "https://raw.githubusercontent.com/Narehatechi/deltacord-bundle/main/themes/fonts/DeterminationMono.ttf"

    /** The font keys Deltacord's themes replace (Discord's main, heading and code fonts). */
    private val KEYS = listOf(
        "ggsans-Normal", "ggsans-Medium", "ggsans-Semibold", "ggsans-Bold", "ggsans-ExtraBold",
        "ggsans-NormalItalic", "ggsans-MediumItalic", "ggsans-SemiboldItalic", "ggsans-BoldItalic", "ggsans-ExtraBoldItalic",
        "ggmono-Normal", "ggmono-Bold",
        "ABCGintoNord-ExtraBold",
    )

    fun definition() = FontDefinition(name = "Determination", spec = 1, main = KEYS.associateWith { URL })

    fun isDeterminationUrl(url: String) = url.endsWith("/DeterminationMono.ttf")

    /** A Deltacord built-in theme is on and "Determination font with Deltacord's themes" isn't off. */
    fun wantedByTheme(dataDir: String): Boolean = try {
        val theme = File(dataDir, "${DeltacordConstants.FILES_DIR}/current-theme.json")
        val builtin = theme.exists() && org.json.JSONObject(theme.readText()).optString("id").startsWith("deltacord://builtin/")
        val extras = File(dataDir, "files/vd_mmkv/DELTACORD_EXTRAS")
        val enabled = !extras.exists() || org.json.JSONObject(extras.readText()).optBoolean("themeFont", true)
        builtin && enabled
    } catch (_: Throwable) {
        false
    }

    /** Copies the built-in font to [target] if it isn't there yet. True if it's in place. */
    fun install(modulePath: String, target: File, log: Logger): Boolean = try {
        if (!target.exists() || target.length() == 0L) {
            java.util.zip.ZipFile(modulePath).use { zip ->
                val entry = zip.getEntry(ASSET) ?: error("font missing from module")
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        true
    } catch (e: Throwable) {
        log.w("Couldn't use the built-in Determination font; downloading it instead", e)
        false
    }
}
