package com.obsidiancodx.entityinventory.core

import java.security.SecureRandom
import java.time.Instant

object IdGenerator {
    private const val alphabet = "0123456789abcdefghjkmnpqrstvwxyz"
    private val random = SecureRandom()

    fun entityId(): String = "ent_${ulid()}"
    fun tagId(): String = "tag_${ulid()}"
    fun auditId(): String = "aud_${ulid()}"
    fun loadoutId(): String = "loa_${ulid()}"
    fun placeId(): String = "plc_${ulid()}"
    fun containerId(): String = "con_${ulid()}"

    private fun ulid(): String {
        var time = Instant.now().toEpochMilli()
        val chars = CharArray(26)
        for (i in 9 downTo 0) {
            chars[i] = alphabet[(time and 31).toInt()]
            time = time ushr 5
        }
        val bytes = ByteArray(10).also(random::nextBytes)
        var buffer = 0
        var bits = 0
        var index = 10
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5 && index < chars.size) {
                bits -= 5
                chars[index++] = alphabet[(buffer shr bits) and 31]
            }
        }
        while (index < chars.size) chars[index++] = alphabet[random.nextInt(32)]
        return String(chars)
    }
}
