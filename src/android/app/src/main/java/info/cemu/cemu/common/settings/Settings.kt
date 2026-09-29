package info.cemu.cemu.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import info.cemu.cemu.common.ui.localization.DEFAULT_LANGUAGE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class EmulationSettings(
    val gamePadPosition: GamePadPosition = GamePadPosition.RIGHT,
    val isPadVisible: Boolean = false,
    val isPadOnExternalDisplay: Boolean = false,
    val isExternalScreenRotatedLeft: Boolean = false,
    val isSkylanderPortalOnExternalDisplay: Boolean = true,
)

@Serializable
data class GuiSettings(
    val language: String = DEFAULT_LANGUAGE,
)

@Serializable
data class StorageSettings(
    val dataRootPath: String? = null,
    val customRootUri: String? = null,
    val mirrorRootPath: String? = null,
    val pendingDeleteDataRootPath: String? = null,
    val isSaveMirrorDirty: Boolean = false,
    val lastSaveSyncAtMillis: Long? = null,
    val lastManualSyncAtMillis: Long? = null,
    @Deprecated("Kept only to decode settings written by older data-storage prototypes.")
    val isMirrorDirty: Boolean = false,
    val lastStorageError: String? = null,
)

@Serializable
data class InputOverlayRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class InputOverlaySettings(
    val isVibrateOnTouchEnabled: Boolean = false,
    val isOverlayEnabled: Boolean = false,
    val controllerIndex: Int = 0,
    val alpha: Int = 64,
    val inputVisibilityMap: Map<OverlayInputConfig, Boolean> = emptyMap(),
    val inputOverlayRectMap: Map<OverlayInputConfig, InputOverlayRect> = emptyMap(),
)

@Serializable
data class SkylanderTeamFigure(
    val slot: Int,
    val path: String,
    val name: String,
)

/** A saved set of Skylanders figures and the portal slots they go on. */
@Serializable
data class SkylanderTeam(
    val figures: List<SkylanderTeamFigure>,
)

@Serializable
data class AppSettings(
    val guiSettings: GuiSettings = GuiSettings(),
    val emulationSettings: EmulationSettings = EmulationSettings(),
    val storageSettings: StorageSettings = StorageSettings(),
    val inputOverlaySettings: InputOverlaySettings = InputOverlaySettings(),
    val hotkeySettings: Map<HotkeyAction, HotkeyCombo> = emptyMap(),
    val skylanderTeams: List<SkylanderTeam> = emptyList(),
    /** Favourite Skylanders, by [info.cemu.cemu.emulation.emulatedusbdevices.PortalFigure.favouriteKey]. */
    val skylanderFavourites: Set<String> = emptySet(),
    /**
     * No longer used: replaced by [skylanderLastUsedByGame]. Kept because settings are decoded
     * strictly, so removing a field that existing settings files still contain would make them
     * fail to load and reset every setting.
     */
    @Deprecated("Replaced by skylanderLastUsedByGame.")
    val skylanderLastUsed: Map<String, Long> = emptyMap(),
    /**
     * When each figure file was last placed on the portal, in milliseconds since the epoch, per
     * Skylanders game (keyed by game name, or "ANY" when the game isn't known).
     */
    val skylanderLastUsedByGame: Map<String, Map<String, Long>> = emptyMap(),
    val skylanderPortalSettings: SkylanderPortalSettings = SkylanderPortalSettings(),
    /**
     * Skylanders game chosen by hand for a title, keyed by title id in hex, for titles whose game
     * isn't detected from their name. The value is a SkylanderGame name, or "ANY" for all figures.
     */
    val skylanderGameOverrides: Map<String, String> = emptyMap(),
)

/** How the Skylanders portal screen looks. */
@Serializable
data class SkylanderPortalSettings(
    /** Whether the portal's centre glows and shimmers. Off draws an unlit portal. */
    val isGlowEnabled: Boolean = true,
    /** Whether the drawn portal is shown on the portal page, above the slots. */
    val isPortalVisible: Boolean = true,
    /**
     * The order of the portal screen's pages, by page name ("PORTAL", "COLLECTION", "RECENT"). The
     * first page is the one shown when the portal opens. Unknown names are ignored and missing
     * pages are added at the end.
     */
    val pageOrder: List<String> = listOf("PORTAL", "COLLECTION", "RECENT"),
    /** Size of the figure cards: "SMALL", "MEDIUM" or "LARGE". */
    val cardSize: String = "MEDIUM",
    /** Size of the text on the portal screen: "SMALL", "MEDIUM" or "LARGE". */
    val textSize: String = "MEDIUM",
    /** Whether pages can be changed by swiping, as well as with the arrows and page names. */
    val isSwipeEnabled: Boolean = true,
    /** How many times the tip has been shown; it's only shown for the first few launches. */
    val tipShownCount: Int = 0,
)

object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        Json.decodeFromString<AppSettings>(input.readBytes().decodeToString())
    } catch (_: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(Json.encodeToString(t).encodeToByteArray())
    }
}

object AppSettingsStore {
    private lateinit var _dataStore: DataStore<AppSettings>
    val dataStore: DataStore<AppSettings>
        get() = _dataStore

    fun init(context: Context) {
        _dataStore = MultiProcessDataStoreFactory.create(
            serializer = AppSettingsSerializer,
            corruptionHandler = null,
            produceFile = { context.dataStoreFile("appSettings.json") },
        )
    }
}
