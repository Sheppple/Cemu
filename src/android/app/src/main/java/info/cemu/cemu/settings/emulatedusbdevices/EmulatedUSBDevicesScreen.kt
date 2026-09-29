package info.cemu.cemu.settings.emulatedusbdevices

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.ui.components.Button
import info.cemu.cemu.common.ui.components.Header
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.components.Toggle
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.emulation.emulatedusbdevices.SkylanderBackup
import info.cemu.cemu.emulation.emulatedusbdevices.SkylanderBackups
import info.cemu.cemu.emulation.emulatedusbdevices.createMissingSkylanderFigures
import info.cemu.cemu.emulation.emulatedusbdevices.findArt
import info.cemu.cemu.emulation.emulatedusbdevices.importSkylanderArt
import info.cemu.cemu.emulation.emulatedusbdevices.importSkylanderArtZip
import info.cemu.cemu.emulation.emulatedusbdevices.loadBundledSkylanderArtKeys
import info.cemu.cemu.emulation.emulatedusbdevices.loadPortalFigures
import info.cemu.cemu.emulation.emulatedusbdevices.loadSkylanderArtIndex
import info.cemu.cemu.emulation.emulatedusbdevices.skylanderFigureNames
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EmulatedUSBDevicesSettingsScreen(navigateBack: () -> Unit) {
    ScreenContent(
        appBarText = tr("Emulated USB Devices settings"),
        navigateBack = navigateBack,
    ) {
        Toggle(
            tr("Emulate Skylander Portal"),
            initialCheckedState = NativeSettings::isEmulateSkylanderPortalEnabled,
            onCheckedChanged = NativeSettings::setEmulateSkylanderPortalEnabled,
        )

        Toggle(
            tr("Emulate Infinity Base"),
            initialCheckedState = NativeSettings::isEmulateInfinityBaseEnabled,
            onCheckedChanged = NativeSettings::setEmulateInfinityBaseEnabled,
        )

        Toggle(
            tr("Emulate Dimensions Toypad"),
            initialCheckedState = NativeSettings::isEmulateDimensionsToypadEnabled,
            onCheckedChanged = NativeSettings::setEmulateDimensionsToypadEnabled,
        )

        SkylanderFiguresSection()
    }
}

private data class ArtStatus(val figureCount: Int, val missingArtNames: List<String>)

private fun loadArtStatus(assets: AssetManager): ArtStatus {
    val figures = loadPortalFigures(
        NativeEmulatedUSBDevices.getInstalledSkylanderFigures(),
        skylanderFigureNames(),
    )
    val artIndex = loadSkylanderArtIndex()
    val bundledArtKeys = loadBundledSkylanderArtKeys(assets)
    val missing = figures
        .filter { figure ->
            val key = figure.artKey
            // Bundled art falls back to the base variant, so count that as covered too.
            val hasBundledArt = key != null &&
                (key in bundledArtKeys || key.substringBefore('_') + "_0000" in bundledArtKeys)
            artIndex.findArt(figure) == null && !hasBundledArt
        }
        .map { it.suggestedArtName }
        .distinct()
        .sortedBy { it.lowercase() }
    return ArtStatus(figures.size, missing)
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

@Composable
private fun SkylanderFiguresSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var statusVersion by remember { mutableIntStateOf(0) }
    var artStatus by remember { mutableStateOf<ArtStatus?>(null) }
    var showMissing by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<SkylanderBackup>>(emptyList()) }
    var pendingRestore by remember { mutableStateOf<SkylanderBackup?>(null) }

    LaunchedEffect(statusVersion) {
        backups = withContext(Dispatchers.IO) { runCatching { SkylanderBackups.list() }.getOrDefault(emptyList()) }
    }

    LaunchedEffect(statusVersion) {
        artStatus = withContext(Dispatchers.IO) { runCatching { loadArtStatus(context.assets) }.getOrNull() }
    }

    fun runBusy(work: suspend () -> String) {
        if (isBusy) return
        isBusy = true
        scope.launch {
            val message = try {
                work()
            } catch (e: Exception) {
                tr("Failed: {0}", e.message ?: "")
            }
            isBusy = false
            progress = null
            statusVersion++
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun createAll(allVariants: Boolean) = runBusy {
        val created = withContext(Dispatchers.IO) {
            createMissingSkylanderFigures(allVariants) { done, total ->
                scope.launch { progress = done to total }
            }
        }
        tr("Created {0} figures", created)
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runBusy {
            val imported = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { importSkylanderArtZip(it) } ?: 0
            }
            tr("Imported {0} images", imported)
        }
    }

    val imagesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            runBusy {
                val imported = withContext(Dispatchers.IO) {
                    uris.count { uri ->
                        val name = displayName(context, uri) ?: return@count false
                        context.contentResolver.openInputStream(uri)
                            ?.use { importSkylanderArt(name, it) } ?: false
                    }
                }
                tr("Imported {0} images", imported)
            }
        }

    Header(tr("Skylanders figures"))

    Button(
        label = tr("Create all Skylanders figures"),
        description = tr("Creates one figure per character from its latest Series (S4 > S3 > S2 > S1), plus every trap and magic item. Eon's Elite, Legendary, Dark, LightCore and other variants are left out."),
        enabled = !isBusy,
        onClick = { createAll(allVariants = false) },
    )

    Button(
        label = tr("Create all variants too"),
        description = tr("Also creates every earlier Series, Eon's Elite, Legendary, Dark, LightCore and other variant. On the portal they appear under the Variants filter."),
        enabled = !isBusy,
        onClick = { createAll(allVariants = true) },
    )

    progress?.let { (done, total) ->
        LinearProgressIndicator(
            progress = { if (total == 0) 1f else done.toFloat() / total },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Text(
            text = tr("{0} of {1}", done, total),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    Header(tr("Skylanders figure backups"))

    Text(
        text = tr("The figures are backed up each time the portal opens in a game, if they've changed. The last {0} backups are kept.", SkylanderBackups.MAX_BACKUPS),
        fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    Button(
        label = tr("Back up figures now"),
        enabled = !isBusy,
        onClick = {
            runBusy {
                val made = withContext(Dispatchers.IO) { SkylanderBackups.backUpIfChanged() }
                if (made) tr("Figures backed up") else tr("No changes since the last backup")
            }
        },
    )

    if (backups.isEmpty()) {
        Text(
            text = tr("No backups yet."),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance() }
    backups.forEach { backup ->
        val isPending = backup == pendingRestore
        Button(
            label = dateFormat.format(java.util.Date(backup.createdAtMillis)),
            description = if (isPending) {
                tr("Tap again to restore these {0} figures. Your current figures are backed up first.", backup.figureCount)
            } else {
                tr("{0} figures · Tap to restore", backup.figureCount)
            },
            enabled = !isBusy,
            onClick = {
                if (!isPending) {
                    pendingRestore = backup
                } else {
                    pendingRestore = null
                    runBusy {
                        val restored = withContext(Dispatchers.IO) { SkylanderBackups.restore(backup) }
                        tr("Restored {0} figures", restored)
                    }
                }
            },
        )
    }

    Header(tr("Skylanders card art"))

    Button(
        label = tr("Import card art from a zip"),
        description = tr("Pick a .zip of images named after your figures, for example Spyro.png."),
        enabled = !isBusy,
        onClick = {
            zipLauncher.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
        },
    )

    Button(
        label = tr("Import card art images"),
        description = tr("Pick one or more PNG, JPG or WebP images named after your figures."),
        enabled = !isBusy,
        onClick = { imagesLauncher.launch(arrayOf("image/*")) },
    )

    val status = artStatus ?: return
    if (status.figureCount == 0) return
    val missing = status.missingArtNames

    Button(
        label = if (missing.isEmpty()) {
            tr("Every figure has card art")
        } else {
            tr("Missing card art: {0}", missing.size)
        },
        description = when {
            missing.isEmpty() -> null
            showMissing -> tr("Tap to hide the list")
            else -> tr("Tap to see which image names to add")
        },
        enabled = missing.isNotEmpty(),
        onClick = { showMissing = !showMissing },
    )

    if (showMissing && missing.isNotEmpty()) {
        Text(
            text = tr("Add images with these names (png, jpg, jpeg or webp). One image covers every variant of a figure."),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = missing.joinToString("\n"),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}
