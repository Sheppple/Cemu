package info.cemu.cemu.emulation.emulatedusbdevices

import info.cemu.cemu.nativeinterface.NativeActiveSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved copy of the Skylanders figure files. */
data class SkylanderBackup(
    val directory: File,
    val createdAtMillis: Long,
    val figureCount: Int,
)

/**
 * Automatic backups of the Skylanders figure files. The games save progress into the figure
 * files while you play, so a crash or bad write could lose a figure's levels and gold. A backup is
 * made each time the portal opens in a game (before any figure is placed that session), unless the
 * figures are unchanged since the last backup, and only the latest [MAX_BACKUPS] are kept.
 *
 * Backups live in `<user data>/emulatedUSBDevices/skylanders_backups/<date-time>/`. Card art isn't
 * backed up.
 */
object SkylanderBackups {
    const val MAX_BACKUPS = 5
    private const val DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss"

    private fun usbDevicesDirectory() = File(NativeActiveSettings.getUserDataPath(), "emulatedUSBDevices")
    private fun figuresDirectory() = File(usbDevicesDirectory(), "skylanders")
    private fun backupsDirectory() = File(usbDevicesDirectory(), "skylanders_backups")

    private fun figureFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

    /** The saved backups, newest first. Does file IO. */
    fun list(): List<SkylanderBackup> =
        backupsDirectory().listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.endsWith(".partial") }
            .map { dir ->
                val createdAt = runCatching {
                    SimpleDateFormat(DATE_FORMAT, Locale.ROOT).parse(dir.name)?.time
                }.getOrNull() ?: dir.lastModified()
                SkylanderBackup(dir, createdAt, figureFiles(dir).size)
            }
            .sortedByDescending { it.createdAtMillis }

    private fun sameFigures(a: List<File>, b: List<File>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) ->
            x.name == y.name && x.length() == y.length() && x.readBytes().contentEquals(y.readBytes())
        }

    /**
     * Backs up the figure files unless they're the same as in the newest backup, then deletes
     * backups beyond [MAX_BACKUPS]. Returns true if a backup was made. Does file IO.
     */
    fun backUpIfChanged(): Boolean {
        val figures = figureFiles(figuresDirectory())
        if (figures.isEmpty()) return false
        val newest = list().firstOrNull()
        if (newest != null && sameFigures(figures, figureFiles(newest.directory))) return false

        val name = SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(Date())
        val target = File(backupsDirectory(), name)
        val temp = File(backupsDirectory(), "$name.partial")
        temp.deleteRecursively()
        temp.mkdirs()
        figures.forEach { it.copyTo(File(temp, it.name), overwrite = true) }
        if (!temp.renameTo(target)) {
            temp.deleteRecursively()
            return false
        }

        list().drop(MAX_BACKUPS).forEach { it.directory.deleteRecursively() }
        return true
    }

    /**
     * Copies a backup's figure files back over the current ones. Figures created since the backup
     * are kept. The current figures are backed up first, so a restore can itself be undone.
     * Returns how many figures were restored. Does file IO.
     */
    fun restore(backup: SkylanderBackup): Int {
        // Read the backup first: backing up the current figures can prune the oldest backup,
        // which may be this one.
        val files = figureFiles(backup.directory).map { it.name to it.readBytes() }
        backUpIfChanged()
        val figuresDir = figuresDirectory().also { it.mkdirs() }
        files.forEach { (name, bytes) -> File(figuresDir, name).writeBytes(bytes) }
        return files.size
    }
}
