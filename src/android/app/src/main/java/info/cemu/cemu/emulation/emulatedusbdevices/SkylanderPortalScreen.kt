package info.cemu.cemu.emulation.emulatedusbdevices

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import info.cemu.cemu.common.settings.SkylanderTeam
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices.InstalledFigure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Touch-first Skylanders portal meant to fill a secondary display: a row of portal slots, optional
 * filters and a grid of installed figures. Tapping a figure places it on the portal (swapping out
 * whatever is on its slot); tapping a figure that is already on the portal removes it.
 *
 * Traps and magic items go to their own slots. Swap Force halves go to the next free slot so that
 * both halves can be on the portal together. Everything else goes to the selected slot.
 *
 * Figures are shown with the name, element, game and type stored in the figure file (see
 * [SkylanderCatalog]). Card art is read from `<figures dir>/art/<file name or figure name>.png`
 * (or jpg/jpeg/webp) when present, then from the art bundled in the `skylanders_art` assets, with a
 * generated placeholder otherwise.
 *
 * Runs in a non-focusable window (see [SkylanderPortalPresentation]), so it deliberately avoids
 * dialogs, popups and text input.
 */
@Composable
fun SkylanderPortalScreen(viewModel: EmulatedUSBDevicesViewModel = viewModel()) {
    PortalTheme {
        PortalContent(viewModel)
    }
}

private const val PLAYER_1_SLOT = 0
private const val PLAYER_2_SLOT = 1
private const val TRAP_SLOT = 2
private const val ITEM_SLOT = 3
private const val NAMED_SLOT_COUNT = 4

private fun slotLabel(slot: Int): String = when (slot) {
    PLAYER_1_SLOT -> tr("Player 1")
    PLAYER_2_SLOT -> tr("Player 2")
    TRAP_SLOT -> tr("Trap")
    ITEM_SLOT -> tr("Magic Item")
    else -> tr("Slot {0}", slot + 1)
}

/** Picks the slot a tapped figure goes to, based on its type. */
private fun targetSlot(figure: PortalFigure, selectedSlot: Int, slots: Array<String?>): Int =
    when (figure.info?.type) {
        SkylanderType.TRAP -> TRAP_SLOT
        SkylanderType.ITEM, SkylanderType.TROPHY -> ITEM_SLOT
        SkylanderType.SWAPPER -> if (slots[selectedSlot] == null) {
            selectedSlot
        } else {
            slots.indices
                .filter { it != TRAP_SLOT && it != ITEM_SLOT }
                .firstOrNull { slots[it] == null } ?: selectedSlot
        }

        else -> selectedSlot
    }

@Composable
private fun PortalContent(viewModel: EmulatedUSBDevicesViewModel) {
    val slots by viewModel.skylanderSlots.state.collectAsState()
    val slotPaths by viewModel.skylanderSlotPaths.collectAsState()
    val installedFigures by viewModel.installedSkylanderFigures.state.collectAsState()
    val isSwapping by viewModel.isSwappingSkylander.collectAsState()
    var selectedSlot by rememberSaveable { mutableIntStateOf(PLAYER_1_SLOT) }
    var showAllSlots by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showTeams by rememberSaveable { mutableStateOf(false) }
    var artVersion by remember { mutableIntStateOf(0) }
    val teams by viewModel.skylanderTeams.collectAsState()
    var typeFilter by rememberSaveable { mutableStateOf<SkylanderType?>(null) }
    var elementFilter by rememberSaveable { mutableStateOf<SkylanderElement?>(null) }
    var gameFilter by rememberSaveable { mutableStateOf<SkylanderGame?>(null) }
    var versionFilter by rememberSaveable { mutableStateOf(VersionFilter.MAIN) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val favourites by viewModel.skylanderFavourites.collectAsState()

    // Only figures that work in the running game are shown. The game is detected from the running
    // title, and can be changed in the filters.
    val detectedGame = remember {
        runCatching { SkylanderVersions.gameForTitle(NativeEmulation.getForegroundTitleName()) }.getOrNull()
    }
    var isGameAuto by rememberSaveable { mutableStateOf(true) }
    var chosenGame by rememberSaveable { mutableStateOf<SkylanderGame?>(null) }
    val playingGame = if (isGameAuto) detectedGame else chosenGame

    val names = remember { skylanderFigureNames() }
    val figures by produceState(emptyList(), installedFigures) {
        value = withContext(Dispatchers.IO) { loadPortalFigures(installedFigures, names) }
    }
    val artIndex by produceState(emptyMap<String, File>(), artVersion) {
        value = withContext(Dispatchers.IO) { runCatching { loadSkylanderArtIndex() }.getOrDefault(emptyMap()) }
    }
    val figuresByPath = remember(figures) { figures.associateBy { it.installed.path } }
    val filteredFigures = remember(
        figures, typeFilter, elementFilter, gameFilter, versionFilter, favourites, playingGame,
    ) {
        val matching = figures.filter { figure ->
            (playingGame == null || figure.isAvailableIn(playingGame)) &&
                (typeFilter == null || figure.info?.type == typeFilter) &&
                (elementFilter == null || figure.element == elementFilter) &&
                (gameFilter == null || figure.info?.game == gameFilter) &&
                when (versionFilter) {
                    VersionFilter.MAIN -> figure.isMainVersion
                    VersionFilter.FAVOURITES -> figure.favouriteKey in favourites
                    VersionFilter.VARIANTS -> !figure.isMainVersion
                    VersionFilter.ALL -> true
                }
        }
        if (versionFilter == VersionFilter.FAVOURITES) {
            // One card per favourite character: its main version if installed, else another one.
            matching.groupBy { it.favouriteKey }
                .map { (_, versions) -> versions.firstOrNull { it.isMainVersion } ?: versions.first() }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.installed.name.lowercase() }))
        } else {
            matching
        }
    }
    val isFiltered = typeFilter != null || elementFilter != null || gameFilter != null || !isGameAuto

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
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tr("Portal of Power"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (isSwapping) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { showFilters = !showFilters }) {
                Icon(
                    painter = painterResource(R.drawable.ic_filter),
                    contentDescription = tr("Filters"),
                    tint = if (showFilters || isFiltered) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = { showTeams = !showTeams }) {
                Icon(
                    painter = painterResource(R.drawable.ic_lists),
                    contentDescription = tr("Teams"),
                    tint = if (showTeams) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = {
                viewModel.installedSkylanderFigures.refresh()
                artVersion++
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = tr("Refresh"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                enabled = !isSwapping && slots.any { it != null },
                onClick = viewModel::clearAllSkylanderFigures,
            ) {
                Text(tr("Clear all"))
            }
        }

        val slotFigures = slotPaths.map { path -> path?.let(figuresByPath::get) }
        PortalSlotsRow(
            slots = slots,
            slotFigures = slotFigures,
            selectedSlot = selectedSlot,
            showAllSlots = showAllSlots,
            enabled = !isSwapping,
            onSelectSlot = { selectedSlot = it },
            onClearSlot = viewModel::clearSkylandersFigure,
            onToggleAllSlots = {
                showAllSlots = !showAllSlots
                if (!showAllSlots && selectedSlot >= NAMED_SLOT_COUNT) {
                    selectedSlot = PLAYER_1_SLOT
                }
            },
        )

        if (showTeams) {
            TeamsRow(
                teams = teams,
                canSave = slots.any { it != null },
                enabled = !isSwapping,
                onSave = { viewModel.saveSkylanderTeam() },
                onLoad = viewModel::loadSkylanderTeam,
                onDelete = viewModel::deleteSkylanderTeam,
            )
        }

        VersionChips(selected = versionFilter, onSelectedChange = { versionFilter = it })

        if (showFilters) {
            PlayingGameChips(
                detectedGame = detectedGame,
                isAuto = isGameAuto,
                chosenGame = chosenGame,
                onAuto = { isGameAuto = true },
                onChoose = {
                    isGameAuto = false
                    chosenGame = it
                },
            )
            FilterRows(
                figures = figures,
                typeFilter = typeFilter,
                elementFilter = elementFilter,
                gameFilter = gameFilter,
                onTypeFilterChange = { typeFilter = it },
                onElementFilterChange = { elementFilter = it },
                onGameFilterChange = { gameFilter = it },
            )
        }

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
            val placeHint = tr(
                "Tap a figure to place it on {0}. Traps and magic items go to their own slots. Tap a glowing figure to remove it.",
                slotLabel(selectedSlot),
            )
            Text(
                text = if (playingGame != null) {
                    tr("Showing figures that work in {0}.", tr(playingGame.label)) + " " + placeHint
                } else {
                    placeHint
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (filteredFigures.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        figures.isEmpty() ->
                            tr("No figures found. Create figures from the Emulated USB Devices menu.")

                        versionFilter == VersionFilter.FAVOURITES ->
                            tr("No favourites yet. Tap the star on a figure to add it here.")

                        else -> tr("No figures match these filters.")
                    },
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            items(filteredFigures, key = { it.installed.path }) { figure ->
                val loadedSlot = slotPaths.indexOf(figure.installed.path).takeIf { it >= 0 }
                FigureCard(
                    figure = figure,
                    artFile = artIndex.findArt(figure),
                    loadedSlot = loadedSlot,
                    isFavourite = figure.favouriteKey in favourites,
                    onToggleFavourite = { viewModel.toggleSkylanderFavourite(figure.favouriteKey) },
                    enabled = !isSwapping,
                    onClick = {
                        if (loadedSlot != null) {
                            viewModel.clearSkylandersFigure(loadedSlot)
                        } else {
                            viewModel.placeSkylanderFigure(
                                figure.installed,
                                targetSlot(figure, selectedSlot, slots),
                            )
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
    slotFigures: List<PortalFigure?>,
    selectedSlot: Int,
    showAllSlots: Boolean,
    enabled: Boolean,
    onSelectSlot: (Int) -> Unit,
    onClearSlot: (Int) -> Unit,
    onToggleAllSlots: () -> Unit,
) {
    val visibleSlots = if (showAllSlots) slots.indices.toList() else (0..<NAMED_SLOT_COUNT).toList()
    val hiddenInUse =
        if (showAllSlots) 0 else (NAMED_SLOT_COUNT..<slots.size).count { slots[it] != null }

    PortalRing(slotFigures = slotFigures) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            items(visibleSlots) { slot ->
                SlotCard(
                    label = slotLabel(slot),
                    figureName = slots[slot],
                    element = slotFigures.getOrNull(slot)?.element,
                    isSelected = slot == selectedSlot,
                    enabled = enabled,
                    onClick = { onSelectSlot(slot) },
                    onClear = { onClearSlot(slot) },
                )
            }
            item {
                Card(
                    modifier = Modifier
                        .width(64.dp)
                        .height(72.dp)
                        .clickable(onClick = onToggleAllSlots),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = when {
                                showAllSlots -> tr("Less")
                                hiddenInUse > 0 -> tr("More\n({0})", hiddenInUse)
                                else -> tr("More")
                            },
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCard(
    label: String,
    figureName: String?,
    element: SkylanderElement?,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val accent = element?.color ?: MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier
            .width(116.dp)
            .height(72.dp)
            .clickable(enabled = enabled, onClick = onClick),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (figureName != null) {
                        Modifier.background(
                            Brush.horizontalGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent))
                        )
                    } else {
                        Modifier
                    }
                ),
        ) {
            if (figureName != null) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(accent),
                )
            }
            Column(modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 26.dp)) {
                Text(
                    text = label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = figureName ?: tr("Empty"),
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (figureName != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (figureName != null) {
                IconButton(
                    enabled = enabled,
                    onClick = onClear,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(30.dp),
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

@Composable
private fun FilterRows(
    figures: List<PortalFigure>,
    typeFilter: SkylanderType?,
    elementFilter: SkylanderElement?,
    gameFilter: SkylanderGame?,
    onTypeFilterChange: (SkylanderType?) -> Unit,
    onElementFilterChange: (SkylanderElement?) -> Unit,
    onGameFilterChange: (SkylanderGame?) -> Unit,
) {
    // Only offer filters that match at least one installed figure.
    val types = remember(figures) {
        SkylanderType.entries.filter { type -> figures.any { it.info?.type == type } }
    }
    val elements = remember(figures) {
        SkylanderElement.entries.filter { element -> figures.any { it.element == element } }
    }
    val games = remember(figures) {
        SkylanderGame.entries.filter { game -> figures.any { it.info?.game == game } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChipRow(
            options = types,
            selected = typeFilter,
            label = { it.label },
            onSelectedChange = onTypeFilterChange,
        )
        ChipRow(
            options = elements,
            selected = elementFilter,
            label = { it.label },
            dotColor = { it.color },
            onSelectedChange = onElementFilterChange,
        )
        ChipRow(
            options = games,
            selected = gameFilter,
            label = { it.label },
            onSelectedChange = onGameFilterChange,
        )
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelectedChange: (T?) -> Unit,
    dotColor: ((T) -> Color)? = null,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelectedChange(null) },
                label = { Text(tr("All")) },
                colors = portalChipColors(),
            )
        }
        items(options) { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelectedChange(if (option == selected) null else option) },
                label = { Text(tr(label(option))) },
                leadingIcon = if (dotColor != null) {
                    {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(dotColor(option), CircleShape),
                        )
                    }
                } else {
                    null
                },
                colors = portalChipColors(),
            )
        }
    }
}

@Composable
private fun portalChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@Composable
private fun FigureCard(
    figure: PortalFigure,
    artFile: File?,
    loadedSlot: Int?,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isLoaded = loadedSlot != null
    val elementColor = figure.element.color
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        border = if (isLoaded) {
            BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, elementColor.copy(alpha = 0.35f))
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) {
                lerp(MaterialTheme.colorScheme.surfaceVariant, elementColor, 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Box {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FigureArt(
                    figure = figure,
                    artFile = artFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Text(
                    text = figure.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp),
                )
                Text(
                    text = if (figure.showsFileName) figure.installed.name else figureSubtitle(figure),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                )
            }
            IconButton(
                onClick = onToggleFavourite,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .size(36.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_favorite),
                    contentDescription = if (isFavourite) tr("Remove from favourites") else tr("Add to favourites"),
                    tint = if (isFavourite) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .padding(5.dp)
                        .size(18.dp),
                )
            }
            if (loadedSlot != null) {
                Text(
                    text = slotLabel(loadedSlot),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun figureSubtitle(figure: PortalFigure): String {
    val info = figure.info ?: return ""
    val type = when (info.type) {
        SkylanderType.SKYLANDER, SkylanderType.UNKNOWN -> null
        else -> tr(info.type.label)
    }
    val series = figure.series?.let { tr("Series {0}", it) }
    val variant = if (figure.isMainVersion) null else tr("Variant")
    return listOfNotNull(tr(info.element.label), type, series, variant).joinToString(" · ")
}

@Composable
private fun VersionChips(selected: VersionFilter, onSelectedChange: (VersionFilter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(VersionFilter.entries) { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelectedChange(option) },
                label = { Text(tr(option.label)) },
                colors = portalChipColors(),
            )
        }
    }
}

/** Chips to pick which game's figures are shown: the detected game, all games, or a chosen one. */
@Composable
private fun PlayingGameChips(
    detectedGame: SkylanderGame?,
    isAuto: Boolean,
    chosenGame: SkylanderGame?,
    onAuto: () -> Unit,
    onChoose: (SkylanderGame?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FilterChip(
                selected = isAuto,
                onClick = onAuto,
                label = {
                    Text(
                        if (detectedGame != null) {
                            tr("Playing: {0}", tr(detectedGame.label))
                        } else {
                            tr("Playing: not detected")
                        }
                    )
                },
                colors = portalChipColors(),
            )
        }
        item {
            FilterChip(
                selected = !isAuto && chosenGame == null,
                onClick = { onChoose(null) },
                label = { Text(tr("Any game")) },
                colors = portalChipColors(),
            )
        }
        items(SkylanderGame.entries) { game ->
            FilterChip(
                selected = !isAuto && chosenGame == game,
                onClick = { onChoose(game) },
                label = { Text(tr("Works in {0}", tr(game.label))) },
                colors = portalChipColors(),
            )
        }
    }
}

/** Which versions of the figures the grid shows (see [SkylanderVersions]). */
private enum class VersionFilter(val label: String) {
    MAIN("Main roster"),
    FAVOURITES("★ Favourites"),
    VARIANTS("Variants"),
    ALL("All versions"),
}

@Composable
private fun FigureArt(figure: PortalFigure, artFile: File?, modifier: Modifier) {
    val assets = LocalContext.current.assets
    val art by produceState<ImageBitmap?>(
        initialValue = null,
        artFile?.path,
        artFile?.lastModified(),
        figure.artKey,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Images in the art folder win over the bundled art.
                artFile?.let(::decodeSkylanderArt) ?: decodeBundledSkylanderArt(assets, figure)
            }.getOrNull()
        }
    }

    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    val currentArt = art
    if (currentArt != null) {
        Image(
            bitmap = currentArt,
            contentDescription = figure.name,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
        return
    }

    PlaceholderArt(name = figure.name, element = figure.element, modifier = modifier.clip(shape))
}

@Composable
private fun PlaceholderArt(name: String, element: SkylanderElement, modifier: Modifier) {
    val initials = remember(name) {
        name.split(' ', '_', '-', '(', ')')
            .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "?" }
    }
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                listOf(element.color, lerp(element.color, Color.Black, 0.75f)),
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = tr(element.label).uppercase(),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        )
    }
}

/** A black theme with gold accents: easy to read on the handheld's AMOLED second screen. */
@Composable
private fun PortalTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFFFFC23D),
        onPrimary = Color(0xFF261A00),
        primaryContainer = Color(0xFF4A3800),
        onPrimaryContainer = Color(0xFFFFE08A),
        background = Color.Black,
        onBackground = Color.White,
        surface = Color.Black,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF141418),
        onSurfaceVariant = Color(0xFFB4B4BE),
        surfaceContainer = Color(0xFF101014),
        surfaceContainerHigh = Color(0xFF16161B),
        surfaceContainerLow = Color(0xFF0A0A0D),
        outline = Color(0xFF4A4A55),
        outlineVariant = Color(0xFF2A2A31),
        errorContainer = Color(0xFF5C1111),
        onErrorContainer = Color(0xFFFFDAD6),
    )
    MaterialTheme(colorScheme = colorScheme, typography = MaterialTheme.typography) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            content()
        }
    }
}

private fun ledColor(rgb: Int): Color? = if (rgb and 0xFFFFFF == 0) null else Color(0xFF000000.toInt() or rgb)

/**
 * A drawn portal behind the slot cards. The ring takes the colours the game sets on the real
 * portal's LEDs, falling back to the element colours of the figures on Player 1 and Player 2.
 * It flashes briefly when figures are placed or removed. It is not animated otherwise, so it
 * costs nothing while the game runs.
 */
@Composable
private fun PortalRing(slotFigures: List<PortalFigure?>, content: @Composable () -> Unit) {
    val ledColors by produceState(IntArray(3)) {
        while (true) {
            val colors = runCatching { NativeEmulatedUSBDevices.getSkylanderPortalColors() }.getOrNull()
            if (colors != null && colors.size == 3 && !colors.contentEquals(value)) {
                value = colors
            }
            delay(500)
        }
    }
    val gold = MaterialTheme.colorScheme.primary
    val leftTarget = ledColor(ledColors[0]) ?: slotFigures.getOrNull(PLAYER_1_SLOT)?.element?.color ?: gold
    val rightTarget = ledColor(ledColors[1]) ?: slotFigures.getOrNull(PLAYER_2_SLOT)?.element?.color ?: leftTarget
    val trapTarget = ledColor(ledColors[2]) ?: Color.Transparent
    val left by animateColorAsState(leftTarget, tween(600), label = "portalLeft")
    val right by animateColorAsState(rightTarget, tween(600), label = "portalRight")
    val trap by animateColorAsState(trapTarget, tween(600), label = "portalTrap")

    val flash = remember { Animatable(0f) }
    val occupancy = slotFigures.map { it?.installed?.path }
    LaunchedEffect(occupancy) {
        flash.snapTo(1f)
        flash.animateTo(0f, tween(900))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val glow = 0.35f + 0.5f * flash.value
            // Soft glow around the portal, squashed into an ellipse.
            withTransform({ scale(1f, size.height / size.width, center) }) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(lerp(left, right, 0.5f).copy(alpha = glow), Color.Transparent),
                        center = center,
                        radius = size.width / 2f,
                    ),
                    radius = size.width / 2f,
                    center = center,
                )
            }
            val ringInset = 6.dp.toPx()
            val ringSize = Size(size.width - ringInset * 2, size.height - ringInset * 2)
            val ringTopLeft = Offset(ringInset, ringInset)
            // The portal's top surface.
            drawOval(color = Color(0xFF07070A), topLeft = ringTopLeft, size = ringSize)
            // Trap light in the middle of the portal, as on the Trap Team portal.
            if (trap.alpha > 0f) {
                withTransform({ scale(1f, size.height / size.width, center) }) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(trap.copy(alpha = trap.alpha * 0.55f), Color.Transparent),
                            center = center,
                            radius = size.width * 0.3f,
                        ),
                        radius = size.width * 0.3f,
                        center = center,
                    )
                }
            }
            // The lit ring: left LED colour on the left, right LED colour on the right.
            drawOval(
                brush = Brush.horizontalGradient(listOf(left, right)),
                topLeft = ringTopLeft,
                size = ringSize,
                alpha = 0.75f + 0.25f * flash.value,
                style = Stroke(width = 3.dp.toPx() + 3.dp.toPx() * flash.value),
            )
        }
        content()
    }
}

private fun teamLabel(team: SkylanderTeam): String =
    team.figures.sortedBy { it.slot }.joinToString(", ") { it.name }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TeamsRow(
    teams: List<SkylanderTeam>,
    canSave: Boolean,
    enabled: Boolean,
    onSave: () -> Unit,
    onLoad: (SkylanderTeam) -> Unit,
    onDelete: (SkylanderTeam) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<SkylanderTeam?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(
                    modifier = Modifier
                        .height(56.dp)
                        .clickable(enabled = canSave && enabled) {
                            pendingDelete = null
                            onSave()
                        },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (canSave) 1f else 0.3f)),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tr("+ Save team"),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = if (canSave) 1f else 0.4f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            items(teams) { team ->
                val isPendingDelete = team == pendingDelete
                Card(
                    modifier = Modifier
                        .width(150.dp)
                        .height(56.dp)
                        .combinedClickable(
                            enabled = enabled,
                            onClick = {
                                if (isPendingDelete) {
                                    onDelete(team)
                                    pendingDelete = null
                                } else {
                                    pendingDelete = null
                                    onLoad(team)
                                }
                            },
                            onLongClick = { pendingDelete = if (isPendingDelete) null else team },
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPendingDelete) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = if (isPendingDelete) tr("Tap again to delete") else teamLabel(team),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isPendingDelete) MaterialTheme.colorScheme.onErrorContainer else Color.White,
                        )
                    }
                }
            }
        }
        Text(
            text = if (teams.isEmpty()) {
                tr("Put figures on the portal, then save them as a team to load them all with one tap.")
            } else {
                tr("Tap a team to load it. Long-press a team to delete it.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
