package info.cemu.cemu.emulation.emulatedusbdevices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.settings.SkylanderPortalSettings
import info.cemu.cemu.common.settings.SkylanderTeam
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices.InstalledFigure
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Touch-first Skylanders portal meant to fill a secondary display. It has three pages, swiped
 * between:
 * - Portal: a drawn Portal of Power with the figures on it, the portal slots and saved teams.
 * - Collection: every figure that works in the running game, with sorting, filters and an A-Z bar.
 * - Recent: figures recently used in this game, and favourites.
 *
 * Tapping a figure places it on the portal (swapping out whatever is on its slot); tapping a figure
 * that is already on the portal removes it. Long-pressing a figure shows its details, saved
 * progress and other versions.
 *
 * Traps and magic items go to their own slots. Swap Force halves go to the next free slot so that
 * both halves can be on the portal together. Everything else goes to the selected slot.
 *
 * Runs in a non-focusable window (see [SkylanderPortalPresentation]), so it deliberately avoids
 * dialogs, popups and text input; panels are drawn inside the window instead.
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

/** The pages of the portal screen, swiped between. */
private enum class PortalPage(val label: String) {
    PORTAL("Portal"),
    COLLECTION("Collection"),
    RECENT("Recent"),
}

/** The pages in the order chosen in the portal settings, with any missing pages at the end. */
private fun resolvePageOrder(names: List<String>): List<PortalPage> {
    val chosen = names.mapNotNull { name -> PortalPage.entries.firstOrNull { it.name == name } }.distinct()
    return chosen + PortalPage.entries.filter { it !in chosen }
}

/** Figures just removed from the portal, which the Undo message can put back. */
private data class RemovedFigures(
    val message: String,
    val figures: List<Pair<InstalledFigure, Int>>,
)

private fun cardMinSize(size: String): Dp = when (size) {
    "SMALL" -> 84.dp
    "LARGE" -> 132.dp
    else -> 104.dp
}

private fun textScale(size: String): Float = when (size) {
    "SMALL" -> 0.9f
    "LARGE" -> 1.2f
    else -> 1f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortalContent(viewModel: EmulatedUSBDevicesViewModel) {
    val portalSettings by viewModel.skylanderPortalSettings.collectAsState()
    // Text size setting: scale all text on the portal screen.
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * textScale(portalSettings.textSize)),
    ) {
        PortalContentScaled(viewModel, portalSettings)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortalContentScaled(viewModel: EmulatedUSBDevicesViewModel, portalSettings: SkylanderPortalSettings) {
    val slots by viewModel.skylanderSlots.state.collectAsState()
    val slotPaths by viewModel.skylanderSlotPaths.collectAsState()
    val installedFigures by viewModel.installedSkylanderFigures.state.collectAsState()
    val isSwapping by viewModel.isSwappingSkylander.collectAsState()
    val teams by viewModel.skylanderTeams.collectAsState()
    val favourites by viewModel.skylanderFavourites.collectAsState()
    val lastUsed by viewModel.skylanderLastUsed.collectAsState()
    val playingGame by viewModel.runningSkylanderGame.collectAsState()
    val gameOverride by viewModel.skylanderGameOverride.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedSlot by rememberSaveable { mutableIntStateOf(PLAYER_1_SLOT) }
    var showAllSlots by rememberSaveable { mutableStateOf(false) }
    var typeFilter by rememberSaveable { mutableStateOf<SkylanderType?>(null) }
    var elementFilter by rememberSaveable { mutableStateOf<SkylanderElement?>(null) }
    var versionFilter by rememberSaveable { mutableStateOf(VersionFilter.MAIN) }
    var sortOrder by rememberSaveable { mutableStateOf(SortOrder.NAME) }
    // The figure whose details and versions are shown, by file path. Opened with a long press.
    var detailsPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var artVersion by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showTip by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf<RemovedFigures?>(null) }
    var isCreatingFigures by remember { mutableStateOf(false) }

    val names = remember { skylanderFigureNames() }
    val figures by produceState<List<PortalFigure>?>(null, installedFigures) {
        value = withContext(Dispatchers.IO) { loadPortalFigures(installedFigures, names) }
    }
    val loadedFigures = figures.orEmpty()
    val artIndex by produceState(emptyMap<String, File>(), artVersion) {
        value = withContext(Dispatchers.IO) { runCatching { loadSkylanderArtIndex() }.getOrDefault(emptyMap()) }
    }
    val figuresByPath = remember(loadedFigures) { loadedFigures.associateBy { it.installed.path } }
    // Only figures that work in the running game are ever shown. If the game isn't known,
    // everything is shown.
    val availableFigures = remember(loadedFigures, playingGame) {
        val game = playingGame
        loadedFigures.filter { game == null || it.isAvailableIn(game) }
    }
    val collection = remember(availableFigures, typeFilter, elementFilter, versionFilter, sortOrder, lastUsed) {
        val matching = availableFigures.filter { figure ->
            (typeFilter == null || figure.info?.type == typeFilter) &&
                (elementFilter == null || figure.element == elementFilter) &&
                when (versionFilter) {
                    VersionFilter.MAIN -> figure.isMainVersion
                    VersionFilter.VARIANTS -> !figure.isMainVersion
                    VersionFilter.ALL -> true
                }
        }
        sortFigures(matching, sortOrder, lastUsed)
    }
    val recentFigures = remember(availableFigures, lastUsed) {
        val availablePaths = availableFigures.associateBy { it.installed.path }
        lastUsed.entries.sortedByDescending { it.value }
            .mapNotNull { availablePaths[it.key] }
            .take(MAX_RECENT)
    }
    val favouriteFigures = remember(availableFigures, favourites) {
        // One card per favourite character: its main version if installed, else another one.
        availableFigures.filter { it.favouriteKey in favourites }
            .groupBy { it.favouriteKey }
            .map { (_, versions) -> versions.firstOrNull { it.isMainVersion } ?: versions.first() }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.installed.name.lowercase() }))
    }
    val filterCount = listOfNotNull(typeFilter, elementFilter).size +
        (if (versionFilter != VersionFilter.MAIN) 1 else 0)
    // True once the figure list has loaded and there are none: offer to create them all.
    val hasNoFigures = figures?.isEmpty() == true

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

    LaunchedEffect(removed) {
        if (removed != null) {
            delay(UNDO_DURATION_MS)
            removed = null
        }
    }

    LaunchedEffect(Unit) {
        // The tip is only shown for the first few launches. Read the saved count first, so it
        // doesn't flash up before the settings have loaded.
        if (viewModel.loadSkylanderPortalSettings().tipShownCount < MAX_TIP_SHOWS) {
            viewModel.updateSkylanderPortalSettings { it.copy(tipShownCount = it.tipShownCount + 1) }
            showTip = true
            delay(TIP_DURATION_MS)
            showTip = false
        }
    }

    /** Takes figures off the portal and offers to put them back. */
    fun removeFromSlots(slotsToClear: List<Int>) {
        val figuresRemoved = slotsToClear.mapNotNull { slot ->
            val path = slotPaths.getOrNull(slot) ?: return@mapNotNull null
            val name = figuresByPath[path]?.name ?: slots.getOrNull(slot) ?: File(path).nameWithoutExtension
            Triple(InstalledFigure(name, path), slot, name)
        }
        if (figuresRemoved.isEmpty()) return
        figuresRemoved.forEach { (_, slot, _) -> viewModel.clearSkylandersFigure(slot) }
        removed = RemovedFigures(
            message = if (figuresRemoved.size == 1) {
                tr("Removed {0}", figuresRemoved.first().third)
            } else {
                tr("Removed {0} figures", figuresRemoved.size)
            },
            figures = figuresRemoved.map { (figure, slot, _) -> figure to slot },
        )
    }

    fun toggleFigure(figure: PortalFigure) {
        val loadedSlot = slotPaths.indexOf(figure.installed.path).takeIf { it >= 0 }
        if (loadedSlot != null) {
            removeFromSlots(listOf(loadedSlot))
        } else {
            viewModel.placeSkylanderFigure(figure.installed, targetSlot(figure, selectedSlot, slots))
        }
    }

    fun createAllFigures() {
        if (isCreatingFigures) return
        isCreatingFigures = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { createMissingSkylanderFigures(allVariants = false) } }
            viewModel.installedSkylanderFigures.refresh()
            isCreatingFigures = false
        }
    }

    val cardSize = cardMinSize(portalSettings.cardSize)

    @Composable
    fun figureCard(figure: PortalFigure) {
        FigureCard(
            figure = figure,
            artFile = artIndex.findArt(figure),
            loadedSlot = slotPaths.indexOf(figure.installed.path).takeIf { it >= 0 },
            isFavourite = figure.favouriteKey in favourites,
            enabled = !isSwapping,
            onClick = { toggleFigure(figure) },
            onLongClick = { detailsPath = figure.installed.path },
        )
    }

    val pageOrder = remember(portalSettings.pageOrder) { resolvePageOrder(portalSettings.pageOrder) }
    val pagerState = rememberPagerState(pageCount = { pageOrder.size })
    val isOverlayOpen = detailsPath != null || showFilters || showSettings
    val isPortalPageShown = pageOrder.getOrNull(pagerState.currentPage) == PortalPage.PORTAL &&
        !pagerState.isScrollInProgress

    fun goToPage(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page.coerceIn(0, pageOrder.lastIndex)) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PortalTopBar(
                pages = pageOrder,
                currentPage = pagerState.currentPage,
                selectedSlotLabel = slotLabel(selectedSlot),
                isSwapping = isSwapping,
                onPageSelected = ::goToPage,
                onSettings = { showSettings = true },
            )

            HorizontalPager(
                state = pagerState,
                // Swiping can be turned off in the settings; the arrows and page dots always work.
                userScrollEnabled = portalSettings.isSwipeEnabled,
                // Keep the next and previous pages ready, so swiping to them doesn't stutter while
                // they're built.
                beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = 0.3f),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                when (pageOrder[page]) {
                    PortalPage.PORTAL -> PortalPageContent(
                        slots = slots,
                        slotPaths = slotPaths,
                        figuresByPath = figuresByPath,
                        artIndex = artIndex,
                        teams = teams,
                        selectedSlot = selectedSlot,
                        showAllSlots = showAllSlots,
                        isSwapping = isSwapping,
                        portalSettings = portalSettings,
                        isPortalAnimating = isPortalPageShown && !isOverlayOpen,
                        hasNoFigures = hasNoFigures,
                        isCreatingFigures = isCreatingFigures,
                        onCreateAllFigures = ::createAllFigures,
                        onSelectSlot = { selectedSlot = it },
                        onClearSlot = { slot -> removeFromSlots(listOf(slot)) },
                        onClearAll = { removeFromSlots(slots.indices.toList()) },
                        onToggleAllSlots = {
                            showAllSlots = !showAllSlots
                            if (!showAllSlots && selectedSlot >= NAMED_SLOT_COUNT) {
                                selectedSlot = PLAYER_1_SLOT
                            }
                        },
                        onSaveTeam = { viewModel.saveSkylanderTeam() },
                        onLoadTeam = viewModel::loadSkylanderTeam,
                        onDeleteTeam = viewModel::deleteSkylanderTeam,
                    )

                    PortalPage.COLLECTION -> CollectionPageContent(
                        figures = collection,
                        hasNoFigures = hasNoFigures,
                        isCreatingFigures = isCreatingFigures,
                        onCreateAllFigures = ::createAllFigures,
                        cardMinSize = cardSize,
                        sortOrder = sortOrder,
                        filterCount = filterCount,
                        onCycleSort = {
                            sortOrder = SortOrder.entries[(sortOrder.ordinal + 1) % SortOrder.entries.size]
                        },
                        onShowFilters = { showFilters = true },
                        figureCard = { figureCard(it) },
                    )

                    PortalPage.RECENT -> RecentPageContent(
                        recentFigures = recentFigures,
                        favouriteFigures = favouriteFigures,
                        cardWidth = cardSize + 8.dp,
                        figureCard = { figureCard(it) },
                    )
                }
            }
        }

        // A short tip for the first few launches, instead of a hint line that takes up space.
        AnimatedVisibility(
            visible = showTip,
            enter = fadeIn(),
            exit = fadeOut(tween(800)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        ) {
            FloatingMessage(
                text = tr("Use the arrows or swipe for your collection and favourites. Tap a figure to place it, long-press it for details."),
                color = MaterialTheme.colorScheme.primaryContainer,
                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        // Undo for figures that were just taken off the portal.
        val lastRemoved = removed
        AnimatedVisibility(
            visible = lastRemoved != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        ) {
            UndoMessage(
                text = lastRemoved?.message ?: "",
                enabled = !isSwapping,
                onUndo = {
                    lastRemoved?.let { viewModel.restoreSkylanderFigures(it.figures) }
                    removed = null
                },
            )
        }

        val error = errorMessage
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp, start = 16.dp, end = 16.dp),
        ) {
            FloatingMessage(
                text = error ?: "",
                color = MaterialTheme.colorScheme.errorContainer,
                textColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        val detailsFigure = detailsPath?.let(figuresByPath::get)
        if (detailsFigure != null) {
            FigureDetailsPanel(
                figure = detailsFigure,
                allFigures = loadedFigures,
                names = names,
                artIndex = artIndex,
                slotPaths = slotPaths,
                isFavourite = detailsFigure.favouriteKey in favourites,
                enabled = !isSwapping,
                onToggleFavourite = { viewModel.toggleSkylanderFavourite(detailsFigure.favouriteKey) },
                onPlace = { version ->
                    viewModel.placeSkylanderFigure(version.installed, targetSlot(version, selectedSlot, slots))
                    detailsPath = null
                },
                onRemove = { slot ->
                    removeFromSlots(listOf(slot))
                    detailsPath = null
                },
                onCreate = { id, variant -> viewModel.createSkylanderFigure(id, variant) },
                onShowVersion = { version -> detailsPath = version.installed.path },
                onDismiss = { detailsPath = null },
            )
        }

        if (showFilters) {
            FilterPanel(
                figures = availableFigures,
                versionFilter = versionFilter,
                sortOrder = sortOrder,
                typeFilter = typeFilter,
                elementFilter = elementFilter,
                onVersionFilterChange = { versionFilter = it },
                onSortOrderChange = { sortOrder = it },
                onTypeFilterChange = { typeFilter = it },
                onElementFilterChange = { elementFilter = it },
                onReset = {
                    versionFilter = VersionFilter.MAIN
                    sortOrder = SortOrder.NAME
                    typeFilter = null
                    elementFilter = null
                },
                onDismiss = { showFilters = false },
            )
        }

        if (showSettings) {
            PortalSettingsPanel(
                settings = portalSettings,
                detectedGame = viewModel.detectedSkylanderGame,
                gameOverride = gameOverride,
                canChooseGame = viewModel.runningTitleId != null,
                onGameOverrideChange = viewModel::setSkylanderGameOverride,
                onChange = viewModel::updateSkylanderPortalSettings,
                onRefresh = {
                    viewModel.installedSkylanderFigures.refresh()
                    artVersion++
                },
                onDismiss = { showSettings = false },
            )
        }
    }
}

private const val MAX_RECENT = 20
private const val TIP_DURATION_MS = 4500L
private const val MAX_TIP_SHOWS = 3
private const val UNDO_DURATION_MS = 5000L

@Composable
private fun FloatingMessage(text: String, color: Color, textColor: Color) {
    Text(
        text = text,
        color = textColor,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(color.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun UndoMessage(text: String, enabled: Boolean, onUndo: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.97f), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(enabled = enabled, onClick = onUndo) {
            Text(tr("Undo"), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Arrows and dots to change page, the current page's name, the slot that tapped figures go to,
 * and the settings button.
 */
@Composable
private fun PortalTopBar(
    pages: List<PortalPage>,
    currentPage: Int,
    selectedSlotLabel: String,
    isSwapping: Boolean,
    onPageSelected: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            enabled = currentPage > 0,
            onClick = { onPageSelected(currentPage - 1) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = tr("Previous page"),
                modifier = Modifier.rotate(180f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
            Text(
                text = tr(pages.getOrNull(currentPage)?.label ?: ""),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
                pages.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentPage) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                            .clickable { onPageSelected(index) },
                    )
                }
            }
        }
        IconButton(
            enabled = currentPage < pages.lastIndex,
            onClick = { onPageSelected(currentPage + 1) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = tr("Next page"),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (isSwapping) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(16.dp),
                strokeWidth = 2.dp,
            )
        }
        // Where tapped figures go; tapping it opens the portal page to change it.
        Text(
            text = "→ $selectedSlotLabel",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onPageSelected(pages.indexOf(PortalPage.PORTAL).coerceAtLeast(0)) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = tr("Portal settings"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A prompt shown when there are no figures yet, with a button to create them all. */
@Composable
private fun CreateFiguresPrompt(isCreating: Boolean, onCreate: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(
            text = tr("No figures yet"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = tr("Create one figure for every Skylander, trap and magic item, using each character's latest Series."),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isCreating) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Button(onClick = onCreate) { Text(tr("Create all figures")) }
        }
    }
}

/** The portal page: the drawn Portal of Power, the slots and saved teams. */
@Composable
private fun PortalPageContent(
    slots: Array<String?>,
    slotPaths: List<String?>,
    figuresByPath: Map<String, PortalFigure>,
    artIndex: Map<String, File>,
    teams: List<SkylanderTeam>,
    selectedSlot: Int,
    showAllSlots: Boolean,
    isSwapping: Boolean,
    portalSettings: SkylanderPortalSettings,
    isPortalAnimating: Boolean,
    hasNoFigures: Boolean,
    isCreatingFigures: Boolean,
    onCreateAllFigures: () -> Unit,
    onSelectSlot: (Int) -> Unit,
    onClearSlot: (Int) -> Unit,
    onClearAll: () -> Unit,
    onToggleAllSlots: () -> Unit,
    onSaveTeam: () -> Unit,
    onLoadTeam: (SkylanderTeam) -> Unit,
    onDeleteTeam: (SkylanderTeam) -> Unit,
) {
    val slotFigures = slotPaths.map { path -> path?.let(figuresByPath::get) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (portalSettings.isPortalVisible) {
            PortalOfPower(
                figuresOnPortal = slotFigures.mapIndexedNotNull { slot, figure -> figure?.let { slot to it } },
                artIndex = artIndex,
                isGlowEnabled = portalSettings.isGlowEnabled,
                isAnimating = isPortalAnimating,
                // Tapping a figure standing on the portal takes it off.
                onFigureClick = { slot -> if (!isSwapping) onClearSlot(slot) },
            )
        }

        if (hasNoFigures) {
            CreateFiguresPrompt(isCreating = isCreatingFigures, onCreate = onCreateAllFigures)
        }

        SectionHeader(tr("On the portal")) {
            TextButton(enabled = !isSwapping && slots.any { it != null }, onClick = onClearAll) {
                Text(tr("Clear all"))
            }
        }
        PortalSlotsRow(
            slots = slots,
            slotFigures = slotFigures,
            selectedSlot = selectedSlot,
            showAllSlots = showAllSlots,
            enabled = !isSwapping,
            onSelectSlot = onSelectSlot,
            onClearSlot = onClearSlot,
            onToggleAllSlots = onToggleAllSlots,
        )

        SectionHeader(tr("Teams")) {}
        TeamsRow(
            teams = teams,
            canSave = slots.any { it != null },
            enabled = !isSwapping,
            onSave = onSaveTeam,
            onLoad = onLoadTeam,
            onDelete = onDeleteTeam,
        )
    }
}

@Composable
private fun SectionHeader(title: String, actions: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** The collection page: every figure that works in the running game, with sort and filters. */
@Composable
private fun CollectionPageContent(
    figures: List<PortalFigure>,
    hasNoFigures: Boolean,
    isCreatingFigures: Boolean,
    onCreateAllFigures: () -> Unit,
    cardMinSize: Dp,
    sortOrder: SortOrder,
    filterCount: Int,
    onCycleSort: () -> Unit,
    onShowFilters: () -> Unit,
    figureCard: @Composable (PortalFigure) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Where each letter's first figure is in the grid, for the A-Z jump bar.
    val letterIndex = remember(figures) {
        buildMap {
            figures.forEachIndexed { index, figure -> putIfAbsent(jumpLetter(figure.name), index) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tr("{0} figures", figures.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = false,
                onClick = onCycleSort,
                label = { Text(tr("Sort: {0}", tr(sortOrder.label))) },
                colors = portalChipColors(),
            )
            Spacer(modifier = Modifier.width(6.dp))
            FilterChip(
                selected = filterCount > 0,
                onClick = onShowFilters,
                label = { Text(if (filterCount > 0) tr("Filters ({0})", filterCount) else tr("Filters")) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_filter),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = portalChipColors(),
            )
        }

        if (hasNoFigures) {
            CreateFiguresPrompt(isCreating = isCreatingFigures, onCreate = onCreateAllFigures)
            return@Column
        }
        if (figures.isEmpty()) {
            EmptyMessage(tr("No figures match these filters."))
            return@Column
        }

        Row(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = cardMinSize),
                state = gridState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(figures, key = { it.installed.path }) { figure -> figureCard(figure) }
            }
            // The jump bar only makes sense when the grid is in alphabetical order.
            if (sortOrder == SortOrder.NAME && figures.size > 12) {
                AlphabetJumpBar(
                    availableLetters = letterIndex.keys,
                    onJump = { letter ->
                        letterIndex[letter]?.let { index -> scope.launch { gridState.scrollToItem(index) } }
                    },
                )
            }
        }
    }
}

/**
 * The recent page: figures recently used in this game, then favourites. Each is one row that
 * scrolls sideways, so both stay on screen however many figures they hold.
 */
@Composable
private fun RecentPageContent(
    recentFigures: List<PortalFigure>,
    favouriteFigures: List<PortalFigure>,
    cardWidth: Dp,
    figureCard: @Composable (PortalFigure) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FigureRowSection(
            title = tr("Recently used"),
            figures = recentFigures,
            emptyText = tr("Figures you place in this game show up here."),
            keyPrefix = "recent:",
            cardWidth = cardWidth,
            figureCard = figureCard,
        )
        FigureRowSection(
            title = tr("★ Favourites"),
            figures = favouriteFigures,
            emptyText = tr("Tap the star on a figure to add it here."),
            keyPrefix = "favourite:",
            cardWidth = cardWidth,
            figureCard = figureCard,
        )
    }
}

/** A titled row of figure cards that scrolls sideways. */
@Composable
private fun FigureRowSection(
    title: String,
    figures: List<PortalFigure>,
    emptyText: String,
    keyPrefix: String,
    cardWidth: Dp,
    figureCard: @Composable (PortalFigure) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(if (figures.isEmpty()) title else "$title (${figures.size})") {}
        if (figures.isEmpty()) {
            EmptyMessage(emptyText)
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(figures, key = { keyPrefix + it.installed.path }) { figure ->
                Box(modifier = Modifier.width(cardWidth)) {
                    figureCard(figure)
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 8.dp),
    )
}

/**
 * A panel over the screen, drawn inside the portal window rather than as a dialog, which would
 * take input focus from the game. Tapping outside it closes it.
 */
@Composable
private fun OverlayPanel(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                // Swallow taps so they don't reach the scrim and close the panel.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(painter = painterResource(R.drawable.ic_close), contentDescription = tr("Close"))
                }
            }
            // Scrollable, so every option can be reached on a short screen.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(
    title: String,
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    onClick: (T) -> Unit,
    dotColor: ((T) -> Color)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = isSelected(option),
                    onClick = { onClick(option) },
                    label = { Text(label(option)) },
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
}

/** Sort order and filters for the collection page. */
@Composable
private fun FilterPanel(
    figures: List<PortalFigure>,
    versionFilter: VersionFilter,
    sortOrder: SortOrder,
    typeFilter: SkylanderType?,
    elementFilter: SkylanderElement?,
    onVersionFilterChange: (VersionFilter) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onTypeFilterChange: (SkylanderType?) -> Unit,
    onElementFilterChange: (SkylanderElement?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Only offer filters that match at least one figure in this game.
    val types = remember(figures) {
        SkylanderType.entries.filter { type -> figures.any { it.info?.type == type } }
    }
    val elements = remember(figures) {
        SkylanderElement.entries.filter { element -> figures.any { it.element == element } }
    }

    OverlayPanel(title = tr("Sort and filter"), onDismiss = onDismiss) {
        ChipGroup(
            title = tr("Show"),
            options = VersionFilter.entries,
            isSelected = { it == versionFilter },
            label = { tr(it.label) },
            onClick = onVersionFilterChange,
        )
        ChipGroup(
            title = tr("Sort by"),
            options = SortOrder.entries,
            isSelected = { it == sortOrder },
            label = { tr(it.label) },
            onClick = onSortOrderChange,
        )
        ChipGroup(
            title = tr("Type"),
            options = listOf<SkylanderType?>(null) + types,
            isSelected = { it == typeFilter },
            label = { it?.let { type -> tr(type.label) } ?: tr("All") },
            onClick = onTypeFilterChange,
        )
        ChipGroup(
            title = tr("Element"),
            options = listOf<SkylanderElement?>(null) + elements,
            isSelected = { it == elementFilter },
            label = { it?.let { element -> tr(element.label) } ?: tr("All") },
            onClick = onElementFilterChange,
            dotColor = { it?.color ?: Color.Transparent },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onReset) { Text(tr("Reset")) }
            Button(onClick = onDismiss) { Text(tr("Done")) }
        }
    }
}

/** Games that can be chosen by hand when a title isn't detected. */
private val CHOOSABLE_GAMES = listOf(
    SkylanderGame.SWAP_FORCE,
    SkylanderGame.TRAP_TEAM,
    SkylanderGame.SUPERCHARGERS,
)

private val SIZE_OPTIONS = listOf("SMALL", "MEDIUM", "LARGE")

private fun sizeLabel(size: String): String = when (size) {
    "SMALL" -> tr("Small")
    "LARGE" -> tr("Large")
    else -> tr("Medium")
}

/** Settings for how the portal screen looks and behaves. */
@Composable
private fun PortalSettingsPanel(
    settings: SkylanderPortalSettings,
    detectedGame: SkylanderGame?,
    gameOverride: String?,
    canChooseGame: Boolean,
    onGameOverrideChange: (String?) -> Unit,
    onChange: ((SkylanderPortalSettings) -> SkylanderPortalSettings) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayPanel(title = tr("Portal settings"), onDismiss = onDismiss) {
        if (canChooseGame) {
            // Which game's figures are shown. Automatic uses the game detected from the title;
            // a game chosen here is remembered for this title.
            val autoLabel = detectedGame?.let { tr("Automatic ({0})", tr(it.label)) } ?: tr("Automatic (not detected)")
            ChipGroup(
                title = tr("Game"),
                options = listOf<String?>(null) + CHOOSABLE_GAMES.map { it.name } +
                    EmulatedUSBDevicesViewModel.GAME_OVERRIDE_ANY,
                isSelected = { it == gameOverride },
                label = { option ->
                    when (option) {
                        null -> autoLabel
                        EmulatedUSBDevicesViewModel.GAME_OVERRIDE_ANY -> tr("All figures")
                        else -> tr(SkylanderGame.valueOf(option).label)
                    }
                },
                onClick = onGameOverrideChange,
            )
        }
        SettingSwitch(
            title = tr("Portal glow"),
            description = tr("Light up and animate the centre of the portal. Turn off for an unlit portal with no animation."),
            checked = settings.isGlowEnabled,
            onCheckedChange = { checked -> onChange { it.copy(isGlowEnabled = checked) } },
        )
        SettingSwitch(
            title = tr("Show portal"),
            description = tr("Draw the Portal of Power above the slots. Turn off to leave more room for the slots and teams."),
            checked = settings.isPortalVisible,
            onCheckedChange = { checked -> onChange { it.copy(isPortalVisible = checked) } },
        )
        SettingSwitch(
            title = tr("Swipe between pages"),
            description = tr("Change pages by swiping as well as with the arrows. Turn off if swiping gets in the way."),
            checked = settings.isSwipeEnabled,
            onCheckedChange = { checked -> onChange { it.copy(isSwipeEnabled = checked) } },
        )
        ChipGroup(
            title = tr("Card size"),
            options = SIZE_OPTIONS,
            isSelected = { it == settings.cardSize },
            label = ::sizeLabel,
            onClick = { size -> onChange { it.copy(cardSize = size) } },
        )
        ChipGroup(
            title = tr("Text size"),
            options = SIZE_OPTIONS,
            isSelected = { it == settings.textSize },
            label = ::sizeLabel,
            onClick = { size -> onChange { it.copy(textSize = size) } },
        )
        PageOrderSetting(
            pages = resolvePageOrder(settings.pageOrder),
            onOrderChange = { order -> onChange { it.copy(pageOrder = order.map(PortalPage::name)) } },
        )
        TextButton(onClick = onRefresh) { Text(tr("Reload figures and card art")) }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A panel over the grid with a figure's details, its saved stats and all versions of the
 * character, where any installed version can be placed and missing ones created. Drawn inside the
 * portal window rather than as a dialog, which would need input focus.
 */
@Composable
private fun FigureDetailsPanel(
    figure: PortalFigure,
    allFigures: List<PortalFigure>,
    names: Map<Pair<Int, Int>, String>,
    artIndex: Map<String, File>,
    slotPaths: List<String?>,
    isFavourite: Boolean,
    enabled: Boolean,
    onToggleFavourite: () -> Unit,
    onPlace: (PortalFigure) -> Unit,
    onRemove: (Int) -> Unit,
    onCreate: (Int, Int) -> Unit,
    onShowVersion: (PortalFigure) -> Unit,
    onDismiss: () -> Unit,
) {
    val isCharacter = SkylanderVersions.isCharacter(figure.info)
    val isTrap = figure.info?.type == SkylanderType.TRAP
    val assets = LocalContext.current.assets
    val baseStats by produceState<SkylanderBaseStats?>(null, figure.idAndVariant) {
        value = if (isCharacter) {
            withContext(Dispatchers.IO) { SkylanderBaseStatsTable.find(assets, figure) }
        } else {
            null
        }
    }
    // Read again when the trap's file changes, e.g. after catching a villain.
    val trappedVillain by produceState<TrappedVillain?>(null, figure.installed.path, File(figure.installed.path).lastModified()) {
        value = if (isTrap) {
            withContext(Dispatchers.IO) { SkylanderTrapReader.read(figure.installed.path) }
        } else {
            null
        }
    }
    val seriesNumbers = remember(names) { SkylanderVersions.seriesNumbers(names) }
    val figureId = figure.idAndVariant?.first

    // Every version of this figure id: installed ones first, then the rest of Cemu's list.
    val installedVersions = remember(allFigures, figureId) {
        if (figureId == null) {
            listOf(figure)
        } else {
            allFigures.filter { it.idAndVariant?.first == figureId }
                .sortedBy { SkylanderVersions.seriesRank(it.idAndVariant!!.second) }
        }
    }
    val missingVersions = remember(names, installedVersions, figureId) {
        if (figureId == null || !isCharacter) {
            emptyList()
        } else {
            val installedKeys = installedVersions.mapNotNull { it.idAndVariant }.toSet()
            names.filterKeys { it.first == figureId && it !in installedKeys }
                .entries
                .sortedBy { SkylanderVersions.seriesRank(it.key.second) }
                .map { (key, name) ->
                    PortalFigure(
                        installed = NativeEmulatedUSBDevices.InstalledFigure(name, ""),
                        name = name,
                        baseName = null,
                        info = SkylanderCatalog.find(key.first, key.second),
                        idAndVariant = key,
                    )
                }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                // Swallow taps so they don't reach the scrim and close the panel.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = BorderStroke(1.dp, figure.element.color.copy(alpha = 0.6f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    FigureArt(
                        figure = figure,
                        artFile = artIndex.findArt(figure),
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = figure.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = listOfNotNull(
                                figureSubtitle(figure).ifEmpty { null },
                                figure.info?.game?.let { tr(it.label) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (figure.showsFileName) {
                            Text(
                                text = figure.installed.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val loadedSlot = slotPaths.indexOf(figure.installed.path).takeIf { it >= 0 }
                        Spacer(modifier = Modifier.height(6.dp))
                        if (loadedSlot != null) {
                            TextButton(enabled = enabled, onClick = { onRemove(loadedSlot) }) {
                                Text(tr("Remove from {0}", slotLabel(loadedSlot)))
                            }
                        } else {
                            Button(enabled = enabled, onClick = { onPlace(figure) }) {
                                Text(tr("Place on portal"))
                            }
                        }
                        // Favourites are changed here rather than on the cards, so they can't be
                        // changed by a mistaken tap.
                        TextButton(onClick = onToggleFavourite) {
                            Text(if (isFavourite) tr("★ Remove from favourites") else tr("☆ Add to favourites"))
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = tr("Close"),
                        )
                    }
                }

                if (isCharacter) {
                    BaseStatsSection(baseStats)
                }
                if (isTrap) {
                    TrappedVillainSection(trappedVillain)
                }

                if (installedVersions.size + missingVersions.size > 1) {
                    Text(
                        text = tr("Versions"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    installedVersions.forEach { version ->
                        VersionRow(
                            version = version,
                            label = versionLabel(version, seriesNumbers),
                            artFile = artIndex.findArt(version),
                            isCurrent = version.installed.path == figure.installed.path,
                            status = slotPaths.indexOf(version.installed.path).takeIf { it >= 0 }
                                ?.let { tr("On {0}", slotLabel(it)) },
                            actionLabel = tr("Place"),
                            enabled = enabled,
                            onClick = { onShowVersion(version) },
                            onAction = { onPlace(version) },
                        )
                    }
                    missingVersions.forEach { version ->
                        VersionRow(
                            version = version,
                            label = versionLabel(version, seriesNumbers),
                            artFile = null,
                            isCurrent = false,
                            status = tr("Not created yet"),
                            actionLabel = tr("Create"),
                            enabled = enabled,
                            onClick = null,
                            onAction = {
                                val (id, variant) = version.idAndVariant ?: return@VersionRow
                                onCreate(id, variant)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun versionLabel(version: PortalFigure, seriesNumbers: Map<Pair<Int, Int>, Int>): String {
    val key = version.idAndVariant ?: return ""
    return seriesNumbers[key]?.let { tr("Series {0}", it) } ?: tr("Variant")
}

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun InfoNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A character's base stats, from the bundled stats table. */
@Composable
private fun BaseStatsSection(stats: SkylanderBaseStats?) {
    InfoSection(tr("Base stats")) {
        if (stats == null) {
            InfoNote(tr("Base stats for this figure aren't in the stats table yet."))
            return@InfoSection
        }
        stats.maxHealth?.let { StatRow(tr("Max health"), it.toString()) }
        stats.attack?.let { StatRow(tr("Attack"), it.toString()) }
        stats.speed?.let { StatRow(tr("Speed"), it.toString()) }
        stats.armor?.let { StatRow(tr("Armor"), it.toString()) }
        stats.criticalHit?.let { StatRow(tr("Critical hit"), it.toString()) }
        stats.elementalPower?.let { StatRow(tr("Elemental power"), it.toString()) }
        stats.luck?.let { StatRow(tr("Luck"), it.toString()) }
    }
}

/** The villain captured in a Trap Team trap. */
@Composable
private fun TrappedVillainSection(villain: TrappedVillain?) {
    InfoSection(tr("Trapped villain")) {
        when {
            villain == null -> InfoNote(tr("This trap couldn't be read."))
            villain.name.isEmpty() -> InfoNote(tr("This trap is empty."))
            else -> {
                StatRow(tr("Villain"), villain.name)
                if (villain.isEvolved) StatRow(tr("Evolved"), tr("Yes"))
                villain.nickname?.let { StatRow(tr("Nickname"), it) }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

@Composable
private fun VersionRow(
    version: PortalFigure,
    label: String,
    artFile: File?,
    isCurrent: Boolean,
    status: String?,
    actionLabel: String,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FigureArt(
            figure = version,
            artFile = artFile,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = version.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(label.ifEmpty { null }, status).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(enabled = enabled, onClick = onAction) {
            Text(actionLabel)
        }
    }
}

/** The letter a figure is filed under in the A-Z jump bar; '#' for names not starting with A-Z. */
private fun jumpLetter(name: String): Char {
    val first = name.firstOrNull()?.uppercaseChar() ?: '#'
    return if (first in 'A'..'Z') first else '#'
}

private val JUMP_LETTERS = listOf('#') + ('A'..'Z').toList()

/**
 * A strip of letters down the side of the grid. Tapping or dragging over it scrolls to the first
 * figure starting with that letter, since the portal screen can't show a keyboard for searching.
 */
@Composable
private fun AlphabetJumpBar(availableLetters: Set<Char>, onJump: (Char) -> Unit) {
    var height by remember { mutableIntStateOf(1) }
    var activeLetter by remember { mutableStateOf<Char?>(null) }

    fun letterAt(y: Float): Char {
        val index = (y / height * JUMP_LETTERS.size).toInt().coerceIn(0, JUMP_LETTERS.lastIndex)
        return JUMP_LETTERS[index]
    }

    fun jumpTo(y: Float) {
        val letter = letterAt(y)
        if (letter != activeLetter) {
            activeLetter = letter
            if (letter in availableLetters) onJump(letter)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(22.dp)
            .padding(start = 4.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { offset ->
                    jumpTo(offset.y)
                    tryAwaitRelease()
                    activeLetter = null
                })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> jumpTo(offset.y) },
                    onDragEnd = { activeLetter = null },
                    onDragCancel = { activeLetter = null },
                    onVerticalDrag = { change, _ -> jumpTo(change.position.y) },
                )
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JUMP_LETTERS.forEach { letter ->
            val isAvailable = letter in availableLetters
            Text(
                text = letter.toString(),
                fontSize = 10.sp,
                fontWeight = if (letter == activeLetter) FontWeight.Black else FontWeight.Bold,
                color = when {
                    letter == activeLetter -> MaterialTheme.colorScheme.primary
                    isAvailable -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                },
            )
        }
    }
}

/** How the grid is ordered. */
private enum class SortOrder(val label: String) {
    NAME("A-Z"),
    ELEMENT("Element"),
    GAME("Game"),
    RECENT("Recently used"),
}

private fun sortFigures(
    figures: List<PortalFigure>,
    order: SortOrder,
    lastUsed: Map<String, Long>,
): List<PortalFigure> {
    val byName = compareBy<PortalFigure>({ it.name.lowercase() }, { it.installed.name.lowercase() })
    return when (order) {
        SortOrder.NAME -> figures.sortedWith(byName)
        SortOrder.ELEMENT -> figures.sortedWith(compareBy<PortalFigure> { it.element.ordinal }.then(byName))
        SortOrder.GAME -> figures.sortedWith(
            compareBy<PortalFigure> { it.availableFrom?.ordinal ?: Int.MAX_VALUE }.then(byName)
        )
        // Figures never used go last, in name order.
        SortOrder.RECENT -> figures.sortedWith(
            compareByDescending<PortalFigure> { lastUsed[it.installed.path] ?: 0L }.then(byName)
        )
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

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
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
private fun portalChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FigureCard(
    figure: PortalFigure,
    artFile: File?,
    loadedSlot: Int?,
    isFavourite: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isLoaded = loadedSlot != null
    val elementColor = figure.element.color
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Long-pressing is always allowed: it only opens the details panel.
            .combinedClickable(onClick = { if (enabled) onClick() }, onLongClick = onLongClick),
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
            // Favourites are shown with a star but only changed from the long-press panel, so a
            // tap meant for the card can't change them by mistake.
            if (isFavourite) {
                Icon(
                    painter = painterResource(R.drawable.ic_favorite),
                    contentDescription = tr("Favourite"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(3.dp)
                        .size(16.dp),
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

/** Which versions of the figures the grid shows (see [SkylanderVersions]). */
private enum class VersionFilter(val label: String) {
    MAIN("Main roster"),
    VARIANTS("Variants"),
    ALL("All versions"),
}

@Composable
internal fun FigureArt(figure: PortalFigure, artFile: File?, modifier: Modifier) {
    val assets = LocalContext.current.assets
    val cacheKey = skylanderArtCacheKey(figure, artFile)
    // Start from the cache so cards scrolling back into view show their art straight away.
    val art by produceState(cacheKey?.let(::cachedSkylanderArt), cacheKey) {
        if (value == null && cacheKey != null) {
            value = withContext(Dispatchers.IO) {
                // Images in the art folder win over the bundled art.
                runCatching { loadSkylanderArt(assets, figure, artFile) }.getOrNull()
            }
        }
    }

    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    val currentArt = art
    if (currentArt != null) {
        // Figure renders are usually taller than wide, so show them whole on an element-coloured
        // backdrop instead of cropping off heads and weapons.
        Box(
            modifier = modifier
                .clip(shape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            figure.element.color.copy(alpha = 0.55f),
                            lerp(figure.element.color, Color.Black, 0.85f),
                        ),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = currentArt,
                contentDescription = figure.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            )
        }
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

/** Lets the pages be moved up and down. The first page is the one the portal opens on. */
@Composable
private fun PageOrderSetting(pages: List<PortalPage>, onOrderChange: (List<PortalPage>) -> Unit) {
    fun move(from: Int, to: Int) {
        if (to !in pages.indices) return
        onOrderChange(pages.toMutableList().apply { add(to, removeAt(from)) })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = tr("Page order"), style = MaterialTheme.typography.bodyLarge, color = Color.White)
        Text(
            text = tr("The first page is the one the portal opens on."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pages.forEachIndexed { index, page ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}. ${tr(page.label)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = index > 0, onClick = { move(index, index - 1) }) { Text("▲") }
                TextButton(enabled = index < pages.lastIndex, onClick = { move(index, index + 1) }) { Text("▼") }
            }
        }
    }
}
