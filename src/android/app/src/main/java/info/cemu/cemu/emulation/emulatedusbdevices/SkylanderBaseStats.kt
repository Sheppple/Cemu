package info.cemu.cemu.emulation.emulatedusbdevices

import android.content.res.AssetManager
import org.json.JSONObject

/** A Skylander's base stats, as shown in the game's stats screen before any upgrades. */
data class SkylanderBaseStats(
    val maxHealth: Int?,
    val speed: Int?,
    val armor: Int?,
    val criticalHit: Int?,
    val elementalPower: Int?,
    /** Imaginators only: that game has Attack and Luck instead of Critical hit and Elemental power. */
    val attack: Int? = null,
    val luck: Int? = null,
)

/**
 * Base stats bundled in `assets/skylanders_stats.json`. The file maps a figure id in 4-digit hex
 * (e.g. "0010" for Spyro) to its stats, and can also have entries for one variant as
 * "<id>_<variant>" (e.g. "000E_3810" for Eon's Elite Gill Grunt), which win over the figure id.
 * The numbers below only show the format:
 *
 * ```
 * { "000E": { "health": 270, "speed": 43, "armor": 18, "critical": 30, "elemental": 25 } }
 * ```
 *
 * Imaginators figures use that game's stats instead: "health", "attack", "armor", "speed", "luck".
 *
 * Figures without an entry show no base stats.
 */
object SkylanderBaseStatsTable {
    private const val FILE = "skylanders_stats.json"

    @Volatile
    private var table: Map<String, SkylanderBaseStats>? = null

    /** Loads the table once. Does IO. */
    private fun load(assets: AssetManager): Map<String, SkylanderBaseStats> {
        table?.let { return it }
        val loaded = runCatching {
            val json = JSONObject(assets.open(FILE).bufferedReader().use { it.readText() })
            json.keys().asSequence().associate { key ->
                val entry = json.getJSONObject(key)
                fun stat(name: String) = if (entry.has(name)) entry.optInt(name) else null
                key.uppercase() to SkylanderBaseStats(
                    maxHealth = stat("health"),
                    speed = stat("speed"),
                    armor = stat("armor"),
                    criticalHit = stat("critical"),
                    elementalPower = stat("elemental"),
                    attack = stat("attack"),
                    luck = stat("luck"),
                )
            }
        }.getOrDefault(emptyMap())
        table = loaded
        return loaded
    }

    /** The base stats for [figure]'s exact variant, else for its figure id. Does IO the first time. */
    fun find(assets: AssetManager, figure: PortalFigure): SkylanderBaseStats? {
        val (id, variant) = figure.idAndVariant ?: return null
        val stats = load(assets)
        return stats["%04X_%04X".format(id, variant)] ?: stats["%04X".format(id)]
    }
}
