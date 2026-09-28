package info.cemu.cemu.emulation.emulatedusbdevices

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices.InstalledFigure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Touch-first Skylanders portal meant to fill a secondary display: a row of portal slots and a grid
 * of installed figures. Tapping a figure places it on the selected slot (swapping out whatever is
 * there); tapping a figure that is already on the portal removes it.
 *
 * Card art is read from `<figures dir>/art/<figure name>.(png|jpg|jpeg|webp)` when present, with a
 * generated placeholder otherwise. No artwork is bundled.
 *
 * Runs in a non-focusable window (see [SkylanderPortalPresentation]), so it deliberately avoids
 * dialogs and text input.
 */
@Composable
fun SkylanderPortalScreen(viewModel: EmulatedUSBDevicesViewModel = viewModel()) {
    val slots by viewModel.skylanderSlots.state.collectAsState()
    val slotPaths by viewModel.skylanderSlotPaths.collectAsState()
    val installedFigures by viewModel.installedSkylanderFigures.state.collectAsState()
    val isSwapping by viewModel.isSwappingSkylander.collectAsState()
    var selectedSlot by rememberSaveable { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val sortedFigures = remember(installedFigures) {
        installedFigures.sortedBy { it.name.lowercase() }
    }

    LaunchedEffect(Unit) {
        // Figures may have been created or deleted since the view model was first used.
        viewModel.installedSkylanderFigures.refresh()
        viewModel.skylanderSlots.refresh()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            errorMessage = when (event) {
                UsbDeviceEvent.CreateFailed -> tr("Failed to create figure")
                UsbDeviceEvent.LoadFailed -> tr("Failed to load figure")
            }
        }
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            delay(3000)
            errorMessage = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tr("Skylanders Portal"),
                style = MaterialTheme.typography.titleLarge,
            )
            if (isSwapping) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.installedSkylanderFigures.refresh() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = tr("Refresh"),
                )
            }
            TextButton(
                enabled = !isSwapping && slots.any { it != null },
                onClick = viewModel::clearAllSkylanderFigures,
            ) {
                Text(tr("Clear all"))
            }
        }

        PortalSlotsRow(
            slots = slots,
            selectedSlot = selectedSlot,
            enabled = !isSwapping,
            onSelectSlot = { selectedSlot = it },
            onClearSlot = viewModel::clearSkylandersFigure,
        )

        val error = errorMessage
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
        } else {
            Text(
                text = tr(
                    "Tap a figure to place it on slot {0}. Tap a figure on the portal to remove it.",
                    selectedSlot + 1,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (sortedFigures.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tr("No figures found. Create figures from the Emulated USB Devices menu."),
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sortedFigures, key = { it.path }) { figure ->
                val loadedSlot = slotPaths.indexOf(figure.path).takeIf { it >= 0 }
                FigureCard(
                    figure = figure,
                    loadedSlot = loadedSlot,
                    enabled = !isSwapping,
                    onClick = {
                        if (loadedSlot != null) {
                            viewModel.clearSkylandersFigure(loadedSlot)
                        } else {
                            viewModel.placeSkylanderFigure(figure, selectedSlot)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PortalSlotsRow(
    slots: Array<String?>,
    selectedSlot: Int,
    enabled: Boolean,
    onSelectSlot: (Int) -> Unit,
    onClearSlot: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(NativeEmulatedUSBDevices.MAX_SKYLANDERS) { slot ->
            val figureName = slots[slot]
            val isSelected = slot == selectedSlot
            Card(
                modifier = Modifier
                    .width(112.dp)
                    .height(72.dp)
                    .clickable(enabled = enabled) { onSelectSlot(slot) },
                border = if (isSelected) {
                    BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                colors = CardDefaults.cardColors(
                    containerColor = if (figureName != null) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = tr("Slot {0}", slot + 1),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = figureName ?: tr("Empty"),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (figureName != null) {
                        IconButton(
                            enabled = enabled,
                            onClick = { onClearSlot(slot) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(32.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close_small),
                                contentDescription = tr("Clear"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FigureCard(
    figure: InstalledFigure,
    loadedSlot: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isLoaded = loadedSlot != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        border = if (isLoaded) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Box {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FigureArt(
                    figure = figure,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Text(
                    text = figure.name,
                    fontSize = 13.sp,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            if (loadedSlot != null) {
                Text(
                    text = "${loadedSlot + 1}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(top = 3.dp),
                )
            }
        }
    }
}

private val ART_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
private const val ART_TARGET_SIZE_PX = 320

private fun findFigureArt(figure: InstalledFigure): File? {
    val artDir = File(File(figure.path).parentFile ?: return null, "art")
    return ART_EXTENSIONS
        .map { File(artDir, "${figure.name}.$it") }
        .firstOrNull { it.isFile }
}

private fun decodeFigureArt(file: File): ImageBitmap? {
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

@Composable
private fun FigureArt(figure: InstalledFigure, modifier: Modifier) {
    val art by produceState<ImageBitmap?>(initialValue = null, figure.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { findFigureArt(figure)?.let(::decodeFigureArt) }.getOrNull()
        }
    }

    val currentArt = art
    if (currentArt != null) {
        Image(
            bitmap = currentArt,
            contentDescription = figure.name,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
        )
        return
    }

    PlaceholderArt(name = figure.name, modifier = modifier)
}

private val PLACEHOLDER_COLORS = listOf(
    Color(0xFF2E7D32), // life
    Color(0xFF1565C0), // water
    Color(0xFFC62828), // fire
    Color(0xFF6A1B9A), // magic
    Color(0xFF455A64), // tech
    Color(0xFF5D4037), // earth
    Color(0xFF00838F), // air
    Color(0xFF37474F), // undead
    Color(0xFFF9A825), // light
    Color(0xFF263238), // dark
)

@Composable
private fun PlaceholderArt(name: String, modifier: Modifier) {
    val color = remember(name) { PLACEHOLDER_COLORS[Math.floorMod(name.hashCode(), PLACEHOLDER_COLORS.size)] }
    val initials = remember(name) {
        name.split(' ', '_', '-')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "?" }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
