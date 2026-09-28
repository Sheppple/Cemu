package info.cemu.cemu.emulation.emulatedusbdevices

import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Progress a game has saved on a Skylander figure. */
data class SkylanderFigureStats(
    val nickname: String?,
    val gold: Int,
    val playTimeSeconds: Long,
    val heroLevel: Int,
    /** When the figure was last placed on a portal, as "YYYY-MM-DD HH:MM", if set. */
    val lastPlaced: String?,
)

/**
 * Reads the progress saved on a Skylander figure file.
 *
 * Figure data is stored in 64 blocks of 16 bytes. Blocks 8 and up (except sector trailers) are
 * encrypted with AES-128-ECB; each block's key is the MD5 of the first two blocks, the block
 * index and the string " Copyright (C) 2010 Activision. All Rights Reserved. ". Progress is kept
 * in two copies (at 0x80 and 0x240) with a counter byte at offset 9 to tell which is newer.
 *
 * This follows the format as handled by Dolphin (Source/Core/Core/IOS/USB/Emulated/Skylanders/
 * SkylanderFigure.cpp). Only the fields Dolphin reads are shown; the character level is left out
 * because it has to be computed from several per-game experience counters.
 */
object SkylanderFigureStatsReader {
    private const val BLOCK_SIZE = 0x10
    private const val BLOCK_COUNT = 0x40
    private const val FIGURE_SIZE = BLOCK_SIZE * BLOCK_COUNT

    private val HASH_CONST = " Copyright (C) 2010 Activision. All Rights Reserved. "
        .toByteArray(Charsets.US_ASCII)

    /** Returns the figure's saved progress, or null if the file can't be read. Does file IO. */
    fun read(path: String): SkylanderFigureStats? = runCatching {
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
            val isEmpty = (0 until BLOCK_SIZE).all { data[offset + it] == 0.toByte() }
            if (isPlain) {
                System.arraycopy(data, offset, decrypted, offset, BLOCK_SIZE)
                continue
            }
            if (isEmpty) {
                continue
            }
            hashIn[0x20] = block.toByte()
            val key = md5.digest(hashIn)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            val plain = cipher.doFinal(data, offset, BLOCK_SIZE)
            System.arraycopy(plain, 0, decrypted, offset, BLOCK_SIZE)
        }
        return decrypted
    }

    private fun u8(data: ByteArray, offset: Int) = data[offset].toInt() and 0xFF
    private fun u16(data: ByteArray, offset: Int) = u8(data, offset) or (u8(data, offset + 1) shl 8)
    private fun u32(data: ByteArray, offset: Int) =
        u16(data, offset).toLong() or (u16(data, offset + 2).toLong() shl 16)

    private fun parse(data: ByteArray): SkylanderFigureStats {
        // The copy whose counter is one ahead of the other's is the newest.
        val area = if (u8(data, 0x89) + 1 != u8(data, 0x249)) 0x80 else 0x240

        val nickname = buildString {
            // The nickname is 16 UTF-16 characters, split over two blocks.
            for (i in 0 until 16) {
                val offset = area + if (i < 8) 0x20 + i * 2 else 0x40 + (i - 8) * 2
                val char = u16(data, offset)
                if (char == 0) break
                append(char.toChar())
            }
        }.trim()

        val minute = u8(data, area + 0x50)
        val hour = u8(data, area + 0x51)
        val day = u8(data, area + 0x52)
        val month = u8(data, area + 0x53)
        val year = u16(data, area + 0x54)
        val lastPlaced = if (year in 2000..2100 && month in 1..12 && day in 1..31 && hour < 24 && minute < 60) {
            "%04d-%02d-%02d %02d:%02d".format(year, month, day, hour, minute)
        } else {
            null
        }

        return SkylanderFigureStats(
            nickname = nickname.ifEmpty { null },
            gold = u16(data, area + 0x3),
            playTimeSeconds = u32(data, area + 0x5),
            heroLevel = u16(data, area + 0x5A),
            lastPlaced = lastPlaced,
        )
    }
}
