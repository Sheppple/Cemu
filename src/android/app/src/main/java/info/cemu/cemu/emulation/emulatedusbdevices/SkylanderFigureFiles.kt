package info.cemu.cemu.emulation.emulatedusbdevices

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import info.cemu.cemu.nativeinterface.NativeActiveSettings
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices.InstalledFigure
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/** An installed figure file together with what its contents say about it. */
data class PortalFigure(
    val installed: InstalledFigure,
    /** The figure's name, e.g. "Series 2 Spyro", or the file name if the figure is unknown. */
    val name: String,
    /** The name of the figure's base variant, e.g. "Spyro", when it differs from [name]. */
    val baseName: String?,
    val info: SkylanderInfo?,
    /** The figure's id and variant as stored in the file, or null if the file can't be read. */
    val idAndVariant: Pair<Int, Int>?,
    /**
     * Whether this figure is in the main roster: one entry per character, using its latest normal
     * Series (see [SkylanderVersions]). Everything else is shown as a variant.
     */
    val isMainVersion: Boolean = true,
    /** The Series number (1-4) of a normal character release, if known. */
    val series: Int? = null,
    /**
     * The earliest game this figure can be used in: for characters the game they debuted in (so
     * later Series of a character stay available too), for other toys the game they came out in.
     * Null if unknown.
     */
    val availableFrom: SkylanderGame? = null,
) {
    /** Favourites are per character, so they carry over between a character's versions. */
    val favouriteKey: String
        get() {
            val (id, variant) = idAndVariant ?: return "file:${installed.path}"
            return if (SkylanderVersions.isCharacter(info)) "character:$id" else "figure:$id:$variant"
        }

    /** Whether the figure works in [game]. Figures from unknown games are always shown. */
    fun isAvailableIn(game: SkylanderGame): Boolean =
        availableFrom == null || availableFrom.ordinal <= game.ordinal

    val element: SkylanderElement get() = info?.element ?: SkylanderElement.OTHER
    val showsFileName: Boolean get() = !installed.name.startsWith(name, ignoreCase = true)

    /**
     * The figure's id and variant as 4-digit hex, e.g. "0010_0000" for Spyro. Names the bundled art
     * and can also name images in the art folder.
     */
    val artKey: String?
        get() = idAndVariant?.let { (id, variant) -> "%04X_%04X".format(id, variant) }

    /** Names an image in the art folder can have to be used for this figure, most specific first. */
    val artNames: List<String> get() = listOfNotNull(installed.name, name, artKey, baseName).distinct()

    /** The simplest image name that covers this figure and all its variants. */
    val suggestedArtName: String get() = baseName ?: name
}

private fun readFigureIdAndVariant(path: String): Pair<Int, Int>? = runCatching {
    RandomAccessFile(path, "r").use { file ->
        val header = ByteArray(0x20)
        file.readFully(header)
        val id = (header[0x10].toInt() and 0xFF) or ((header[0x11].toInt() and 0xFF) shl 8)
        val variant = (header[0x1C].toInt() and 0xFF) or ((header[0x1D].toInt() and 0xFF) shl 8)
        id to variant
    }
}.getOrNull()

/** Figure names from Cemu's list, keyed by id and variant. */
fun skylanderFigureNames(): Map<Pair<Int, Int>, String> =
    NativeEmulatedUSBDevices.getSkylanderFigures()
        .associate { (it.id.toInt() to it.variant.toInt()) to it.name }

/**
 * Reads each figure file's id and variant, sorted by name, and marks which figures are in the main
 * roster. Does file IO.
 */
fun loadPortalFigures(
    installedFigures: Array<InstalledFigure>,
    names: Map<Pair<Int, Int>, String>,
): List<PortalFigure> = markMainVersions(readPortalFigures(installedFigures, names), names)

/**
 * For each character, keeps only the installed figure from its latest normal Series in the main
 * roster, and marks everything else (earlier Series, Eon's Elite, Legendary, Dark, LightCore and
 * other special editions) as a variant. Traps, items and other toys are never merged.
 */
private fun markMainVersions(
    figures: List<PortalFigure>,
    names: Map<Pair<Int, Int>, String>,
): List<PortalFigure> {
    val seriesNumbers = SkylanderVersions.seriesNumbers(names)
    fun isNormal(figure: PortalFigure): Boolean {
        val (_, variant) = figure.idAndVariant ?: return true
        return SkylanderVersions.isNormalRelease(
            variant,
            figure.name,
            isCharacter = SkylanderVersions.isCharacter(figure.info),
        )
    }

    val mainCharacterPaths = figures
        .filter { SkylanderVersions.isCharacter(it.info) && it.idAndVariant != null && isNormal(it) }
        .groupBy { it.idAndVariant!!.first }
        .map { (_, versions) -> versions.maxBy { SkylanderVersions.seriesRank(it.idAndVariant!!.second) } }
        .map { it.installed.path }
        .toSet()

    return figures.map { figure ->
        val isMain = if (SkylanderVersions.isCharacter(figure.info) && figure.idAndVariant != null) {
            figure.installed.path in mainCharacterPaths
        } else {
            isNormal(figure)
        }
        figure.copy(
            isMainVersion = isMain,
            series = figure.idAndVariant?.let { seriesNumbers[it] },
            availableFrom = figure.idAndVariant?.let { (id, variant) ->
                if (SkylanderVersions.isCharacter(figure.info)) {
                    SkylanderCatalog.debutGame(id)
                } else {
                    SkylanderCatalog.releaseGame(id, variant)
                }
            },
        )
    }
}

private fun readPortalFigures(
    installedFigures: Array<InstalledFigure>,
    names: Map<Pair<Int, Int>, String>,
): List<PortalFigure> = installedFigures.map { installed ->
    val idAndVariant = readFigureIdAndVariant(installed.path)
    val baseName = idAndVariant?.let { (id, _) -> names[id to 0] }
    val name = idAndVariant?.let { (id, variant) -> names[id to variant] } ?: baseName ?: installed.name
    PortalFigure(
        installed = installed,
        name = name,
        baseName = baseName?.takeIf { it != name },
        info = idAndVariant?.let { (id, variant) -> SkylanderCatalog.find(id, variant) },
        idAndVariant = idAndVariant,
    )
}.sortedWith(compareBy({ it.name.lowercase() }, { it.installed.name.lowercase() }))

val SKYLANDER_ART_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
private const val ART_TARGET_SIZE_PX = 320
private const val MAX_ART_FILE_BYTES = 20L * 1024 * 1024

/** The folder card art is read from: `<user data>/emulatedUSBDevices/skylanders/art`. */
fun skylanderArtDirectory(): File =
    File(NativeActiveSettings.getUserDataPath(), "emulatedUSBDevices/skylanders/art")

/** Maps lower-case image names (without extension) in the art folder to their files. Does file IO. */
fun loadSkylanderArtIndex(artDir: File = skylanderArtDirectory()): Map<String, File> =
    artDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.lowercase() in SKYLANDER_ART_EXTENSIONS }
        .associateBy { it.nameWithoutExtension.lowercase() }

fun Map<String, File>.findArt(figure: PortalFigure): File? =
    figure.artNames.firstNotNullOfOrNull { this[it.lowercase()] }

/** App assets folder with the bundled card art, one `<id>_<variant>.webp` per figure. */
private const val BUNDLED_ART_DIR = "skylanders_art"

/** The [PortalFigure.artKey]s that have bundled art. Does IO. */
fun loadBundledSkylanderArtKeys(assets: AssetManager): Set<String> =
    assets.list(BUNDLED_ART_DIR).orEmpty().map { it.substringBeforeLast('.') }.toSet()

/**
 * Decodes the bundled art for [figure], or returns null if there is none. A variant that isn't
 * bundled (for example from an unusual dump) falls back to the figure's base variant. Does IO.
 */
fun decodeBundledSkylanderArt(assets: AssetManager, figure: PortalFigure): ImageBitmap? {
    val key = figure.artKey ?: return null
    val baseKey = key.substringBefore('_') + "_0000"
    return listOf(key, baseKey).distinct().firstNotNullOfOrNull { candidate ->
        try {
            assets.open("$BUNDLED_ART_DIR/$candidate.webp").use(BitmapFactory::decodeStream)?.asImageBitmap()
        } catch (_: IOException) {
            null
        }
    }
}

/**
 * Decoded card art, so cards scrolling back into view don't decode their image again. Holds about
 * 64 images of 256 px (16 MB).
 */
private val artCache = LruCache<String, ImageBitmap>(64)

/** The cache key for the art [figure] shows, from its art-folder image or the bundled art. */
fun skylanderArtCacheKey(figure: PortalFigure, artFile: File?): String? =
    artFile?.let { "file:${it.path}:${it.lastModified()}" } ?: figure.artKey?.let { "asset:$it" }

fun cachedSkylanderArt(cacheKey: String): ImageBitmap? = artCache.get(cacheKey)

/** Decodes the art [figure] shows: an art-folder image if there is one, else the bundled art. */
fun loadSkylanderArt(assets: AssetManager, figure: PortalFigure, artFile: File?): ImageBitmap? {
    val cacheKey = skylanderArtCacheKey(figure, artFile) ?: return null
    artCache.get(cacheKey)?.let { return it }
    val art = artFile?.let(::decodeSkylanderArt) ?: decodeBundledSkylanderArt(assets, figure)
    return art?.also { artCache.put(cacheKey, it) }
}

/**
 * App assets folder with round character icons for figures standing on the portal: `<id>.webp` per
 * character, plus `<id>_<variant>.webp` for variants that have their own icon (e.g. Dark Spyro).
 */
private const val BUNDLED_ICON_DIR = "skylanders_icons"

/**
 * Decodes the bundled portal icon for [figure]: its variant's own icon, else its character's.
 * Returns null for figures without one (traps, items, vehicles). Does IO.
 */
fun loadSkylanderIcon(assets: AssetManager, figure: PortalFigure): ImageBitmap? {
    val key = figure.artKey ?: return null
    artCache.get("icon:$key")?.let { return it }
    val icon = listOf(key, key.substringBefore('_')).firstNotNullOfOrNull { candidate ->
        try {
            assets.open("$BUNDLED_ICON_DIR/$candidate.webp").use(BitmapFactory::decodeStream)?.asImageBitmap()
        } catch (_: IOException) {
            null
        }
    }
    return icon?.also { artCache.put("icon:$key", it) }
}

fun decodeSkylanderArt(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= ART_TARGET_SIZE_PX &&
        bounds.outHeight / (sampleSize * 2) >= ART_TARGET_SIZE_PX
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}

/**
 * Copies one image into the art folder under [fileName] (folders in the name are dropped).
 * Returns false if it is not a supported image. Does file IO.
 */
fun importSkylanderArt(fileName: String, input: InputStream, artDir: File = skylanderArtDirectory()): Boolean {
    val name = File(fileName).name
    val extension = File(name).extension.lowercase()
    if (name.startsWith(".") || extension !in SKYLANDER_ART_EXTENSIONS) {
        return false
    }
    artDir.mkdirs()
    val target = File(artDir, name)
    val temp = File(artDir, "$name.importing")
    var size = 0L
    temp.outputStream().use { output ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            if (size > MAX_ART_FILE_BYTES) {
                break
            }
            output.write(buffer, 0, read)
        }
    }
    if (size > MAX_ART_FILE_BYTES) {
        temp.delete()
        return false
    }
    target.delete()
    return temp.renameTo(target)
}

/** Copies every supported image in a zip into the art folder. Returns how many were imported. */
fun importSkylanderArtZip(zip: InputStream, artDir: File = skylanderArtDirectory()): Int {
    var imported = 0
    ZipInputStream(zip).use { entries ->
        while (true) {
            val entry = entries.nextEntry ?: break
            if (!entry.isDirectory && !entry.name.contains("__MACOSX") &&
                importSkylanderArt(entry.name, entries, artDir)
            ) {
                imported++
            }
            entries.closeEntry()
        }
    }
    return imported
}

/**
 * Creates a figure file for every Skylander in Cemu's list that isn't installed yet, matching
 * installed figures by the id and variant stored in them. With [allVariants] false, only the main
 * roster is created (see [SkylanderVersions.mainVersions]): one figure per character from its
 * latest normal Series, plus every normal trap, item and other toy. Calls [onProgress] with
 * (done, total). Returns how many were created. Does file IO.
 */
fun createMissingSkylanderFigures(
    allVariants: Boolean,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): Int {
    val installed = readPortalFigures(
        NativeEmulatedUSBDevices.getInstalledSkylanderFigures(),
        emptyMap(),
    ).mapNotNull { it.idAndVariant }.toSet()
    val names = skylanderFigureNames()
    val candidates = if (allVariants) names.keys.toList() else SkylanderVersions.mainVersions(names)
    val wanted = candidates.filter { it !in installed }.sortedWith(compareBy({ it.first }, { it.second }))
    var created = 0
    wanted.forEachIndexed { index, (id, variant) ->
        if (NativeEmulatedUSBDevices.createSkylanderFigure(id, variant)) {
            created++
        }
        onProgress(index + 1, wanted.size)
    }
    return created
}
