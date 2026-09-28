package info.cemu.cemu.emulation.emulatedusbdevices

/**
 * Decides which release of a Skylander is the "main" one, so the roster shows one entry per
 * character instead of a separate entry for each Series.
 *
 * A figure's variant number encodes its release:
 * - bits 12-15: the year (game) it was released in: 0 = Spyro's Adventure, 1 = Giants, 2 = Swap
 *   Force, 3 = Trap Team, and so on;
 * - bit 11: a repose, i.e. a new Series of an existing character (Series 2, 3, 4);
 * - bit 10: an alternate deco (Legendary, Polar, Springtime, Dark, Royal, ...);
 * - bit 9: a LightCore figure;
 * - bit 8: an in-game variant;
 * - bits 0-7: the deco. Normal Series releases use 0x01 or 0x05 (and 0x09 in some dumps), while
 *   other decos are special editions: 0x10 is Eon's Elite, others are Stone, Metallic and so on.
 *
 * Rules:
 * - Normal releases are the original figure and its normal Series reposes. Everything else
 *   (Legendary, Dark, LightCore, Eon's Elite, Chase, Holiday, alternate decos, ...) is a variant.
 *   Eon's Elite is a variant because its stats are much stronger than the normal releases.
 * - For characters, the main version is the normal release from the latest Series: S4 > S3 > S2 >
 *   S1. Other versions of the character are variants.
 * - Traps, magic items, vehicles and other toys are never merged, because one id can cover several
 *   different toys (for example every Magic trap shares one id). Their normal releases are all main.
 */
object SkylanderVersions {
    private const val REPOSE = 0x0800
    private const val ALT_DECO = 0x0400
    private const val LIGHT_CORE = 0x0200
    private const val IN_GAME_VARIANT = 0x0100
    private val NORMAL_REPOSE_DECOS = setOf(0x01, 0x05, 0x09)

    // Special editions of characters that have their own figure id instead of a variant flag, e.g.
    // Legendary Bash or Dark Spyro. Only checked for characters: traps and items with these words
    // in their names (Dark Dagger, Volcanic Vault) are normal releases.
    private val SPECIAL_NAME_PREFIXES = listOf(
        "Legendary ", "Dark ", "LightCore ", "Light Core ", "Eon's Elite ", "Chase ", "Holiday ",
        "Polar ", "Springtime ", "Royal ", "Volcanic ", "Stone ", "Metallic ", "Nitro ", "Jolly ",
    )

    private val CHARACTER_TYPES = setOf(
        SkylanderType.SKYLANDER,
        SkylanderType.GIANT,
        SkylanderType.SWAPPER,
        SkylanderType.TRAP_MASTER,
        SkylanderType.MINI,
    )

    fun isCharacter(info: SkylanderInfo?): Boolean = info?.type in CHARACTER_TYPES

    /** Whether this is the original release or a normal Series repose, rather than a variant. */
    fun isNormalRelease(variant: Int, name: String, isCharacter: Boolean): Boolean {
        if (isCharacter &&
            (SPECIAL_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) } ||
                name.contains("Eon's Elite", ignoreCase = true))
        ) {
            return false
        }
        if (variant and (ALT_DECO or LIGHT_CORE or IN_GAME_VARIANT) != 0) {
            return false
        }
        val deco = variant and 0xFF
        return if (variant and REPOSE == 0) deco == 0 else deco in NORMAL_REPOSE_DECOS
    }

    /** Orders normal releases: the original is 0, and later Series reposes rank higher. */
    fun seriesRank(variant: Int): Int = if (variant and REPOSE == 0) 0 else (variant ushr 12) + 1

    /**
     * Series numbers (1 for the original, 2, 3, 4 for reposes) of every normal character release in
     * [names], keyed by id and variant.
     */
    fun seriesNumbers(names: Map<Pair<Int, Int>, String>): Map<Pair<Int, Int>, Int> =
        names.entries
            .filter { (key, name) ->
                isCharacter(SkylanderCatalog.find(key.first, key.second)) &&
                    isNormalRelease(key.second, name, isCharacter = true)
            }
            .groupBy { it.key.first }
            .flatMap { (_, releases) ->
                releases
                    .map { it.key }
                    .sortedBy { seriesRank(it.second) }
                    .mapIndexed { index, key -> key to index + 1 }
            }
            .toMap()

    /**
     * The versions from Cemu's figure list that make up the main roster: for each character its
     * latest normal Series, and for other toys every normal release.
     */
    fun mainVersions(names: Map<Pair<Int, Int>, String>): List<Pair<Int, Int>> {
        val (allCharacters, allOthers) = names.entries.partition { (key, _) ->
            isCharacter(SkylanderCatalog.find(key.first, key.second))
        }
        val characters = allCharacters.filter { (key, name) -> isNormalRelease(key.second, name, isCharacter = true) }
        val others = allOthers.filter { (key, name) -> isNormalRelease(key.second, name, isCharacter = false) }
        val latestCharacters = characters
            .groupBy { it.key.first }
            .map { (_, releases) -> releases.maxBy { seriesRank(it.key.second) }.key }
        return (latestCharacters + others.map { it.key }).distinct()
    }
}
