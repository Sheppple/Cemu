package info.cemu.cemu.emulation.emulatedusbdevices

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import info.cemu.cemu.nativeinterface.NativeActiveSettings
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices.InstalledFigure
import java.io.File
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
) {
    val element: SkylanderElement get() = info?.element ?: SkylanderElement.OTHER
    val showsFileName: Boolean get() = !installed.name.startsWith(name, ignoreCase = true)

    /** Names an image in the art folder can have to be used for this figure, most specific first. */
    val artNames: List<String> get() = listOfNotNull(installed.name, name, baseName).distinct()

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
