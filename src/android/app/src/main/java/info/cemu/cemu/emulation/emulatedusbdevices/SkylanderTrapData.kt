package info.cemu.cemu.emulation.emulatedusbdevices

import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** The villain captured in a Trap Team trap. */
data class TrappedVillain(
    val name: String,
    val isEvolved: Boolean,
    /** The nickname given to the villain in game, if any. */
    val nickname: String?,
)

/**
 * Reads which villain is captured in a Trap Team trap figure.
 *
 * Figure data is 64 blocks of 16 bytes. Blocks 8 and up (except the sector trailers, every fourth
 * block) are encrypted with AES-128-ECB; each block's key is the MD5 of the first two blocks, the
 * block index and the string " Copyright (C) 2010 Activision. All Rights Reserved. " (as handled by
 * Dolphin's SkylanderFigure.cpp). A trap keeps two copies of its data, starting at block 0x08 and
 * block 0x24, and a counter byte at offset 9 of the first block tells which copy is newer. In that
 * copy, the next block starts with the villain id and whether it's evolved, followed by the
 * villain's nickname split over three blocks (skipping the sector trailer).
 *
 * The trap layout and villain ids follow the figure format documented by the Runes figure editor
 * (github.com/NefariousTechSupport/Runes, Docs/SkylanderFormat.md).
 */
object SkylanderTrapReader {
    private const val BLOCK_SIZE = 0x10
    private const val BLOCK_COUNT = 0x40
    private const val FIGURE_SIZE = BLOCK_SIZE * BLOCK_COUNT

    private val HASH_CONST = " Copyright (C) 2010 Activision. All Rights Reserved. "
        .toByteArray(Charsets.US_ASCII)

    /** Villain names by id, as stored in trap figures. 0 means the trap is empty. */
    private val VILLAINS = listOf(
        null, "Chompy Mage", "Dr. Krankcase", "Wolfgang", "Chef Pepper Jack", "Nightshade",
        "Luminous", "Golden Queen", "Dreamcatcher", "The Gulper", "Kaos", "Cuckoo Clocker",
        "Buzzer Beak", "Shield Shredder", "Cross Crow", "Bone Chompy", "Brawl and Chain",
        "Bomb Shell", "Masker Mind", "Chill Bill", "Sheep Creep", "Shrednaught", "Chomp Chest",
        "Broccoli Guy", "Rage Mage", "Lob Goblin", "Chompy", "Fisticuffs", "Trolling Thunder",
        "Hood Sickle", "Bruiser Cruiser", "Brawlrus", "Tussle Sprout", "Krankenstein",
        "Scrap Shooter", "Slobber Trap", "Grinnade", "Bad Juju", "Blaster-Tron", "Tae Kwon Crow",
        "Pain-Yatta", "Smoke Scream", "Eye Five", "Grave Clobber", "Threatpack", "Mab Lobs",
        "Eye Scream",
    )

    /**
     * Returns the villain in the trap at [path], a [TrappedVillain] with an empty name if the trap
     * is empty, or null if the file can't be read. Does file IO.
     */
    fun read(path: String): TrappedVillain? = runCatching {
        val data = File(path).readBytes()
        if (data.size < FIGURE_SIZE) return null
        parse(decrypt(data))
    }.getOrNull()

    private fun decrypt(data: ByteArray): ByteArray {
        val hashIn = ByteArray(0x56)
        System.arraycopy(data, 0, hashIn, 0, 0x20)
        System.arraycopy(HASH_CONST, 0, hashIn, 0x21, HASH_CONST.size)

        val md5 = MessageDigest.getInstance("MD5")
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val decrypted = ByteArray(FIGURE_SIZE)

        for (block in 0 until BLOCK_COUNT) {
            val offset = block * BLOCK_SIZE
            val isPlain = (block + 1) % 4 == 0 || block < 8
            if (isPlain) {
                System.arraycopy(data, offset, decrypted, offset, BLOCK_SIZE)
                continue
            }
            if ((0 until BLOCK_SIZE).all { data[offset + it] == 0.toByte() }) {
                continue
            }
            hashIn[0x20] = block.toByte()
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(md5.digest(hashIn), "AES"))
            System.arraycopy(cipher.doFinal(data, offset, BLOCK_SIZE), 0, decrypted, offset, BLOCK_SIZE)
        }
        return decrypted
    }

    private fun u8(data: ByteArray, offset: Int) = data[offset].toInt() and 0xFF
    private fun u16(data: ByteArray, offset: Int) = u8(data, offset) or (u8(data, offset + 1) shl 8)

    private fun parse(data: ByteArray): TrappedVillain {
        // The copy whose counter is one ahead of the other's is the newest.
        val firstBlock = if (u8(data, 0x89) + 1 != u8(data, 0x249)) 0x08 else 0x24
        val villainBlock = (firstBlock + 1) * BLOCK_SIZE

        val id = u8(data, villainBlock)
        val isEvolved = u8(data, villainBlock + 1) == 1

        // Nickname: 6 characters in the villain block, 8 in the next, 2 after the sector trailer.
        val nicknameParts = listOf(
            villainBlock + 4 to 6,
            (firstBlock + 2) * BLOCK_SIZE to 8,
            (firstBlock + 4) * BLOCK_SIZE to 2,
        )
        val nickname = buildString {
            for ((start, length) in nicknameParts) {
                for (i in 0 until length) {
                    val char = u16(data, start + i * 2)
                    if (char == 0) return@buildString
                    append(char.toChar())
                }
            }
        }.trim()

        return TrappedVillain(
            name = VILLAINS.getOrNull(id) ?: if (id == 0) "" else "Villain #$id",
            isEvolved = isEvolved,
            nickname = nickname.ifEmpty { null },
        )
    }
}
