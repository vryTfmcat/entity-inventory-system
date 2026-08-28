package com.obsidiancodx.entityinventory.core

import android.graphics.Bitmap
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.nio.charset.StandardCharsets

object TagPayloadCodec {
    private const val externalDomain = "obsidiancodx.local"
    private const val externalType = "tag"
    private val tagPattern = Regex("(?im)^X-OBSIDIAN-CODX-TAG:(tag_[0-9a-hjkmnp-tv-z]{26})\\s*$")
    private val jsonPattern = Regex("\\\"tagId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")

    fun createNdef(tagId: String, phone: String, itemName: String = "", appPackage: String = ""): NdefMessage {
        requireValidTagId(tagId)
        val normalizedPhone = normalizePhone(phone)
        val contact = NdefRecord.createUri(Uri.parse("tel:$normalizedPhone"))
        val json = "{\"v\":1,\"tagId\":\"$tagId\"}"
        val identity = NdefRecord.createExternal(
            externalDomain,
            externalType,
            json.toByteArray(StandardCharsets.UTF_8)
        )
        val normalizedName = normalizeItemName(itemName)
        val records = mutableListOf(contact, identity)
        if (normalizedName.isNotBlank()) {
            records += NdefRecord.createTextRecord("zh-CN", normalizedName)
        }
        if (appPackage.isNotBlank()) {
            records += NdefRecord.createApplicationRecord(appPackage)
        }
        return NdefMessage(records.toTypedArray())
    }

    fun decodeNdef(message: NdefMessage): String? = message.records.firstNotNullOfOrNull { record ->
        val type = record.type.toString(StandardCharsets.US_ASCII)
        if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE && type == "$externalDomain:$externalType") {
            jsonPattern.find(record.payload.toString(StandardCharsets.UTF_8))?.groupValues?.get(1)
        } else null
    }

    fun decodeItemName(message: NdefMessage): String? = message.records.firstNotNullOfOrNull { record ->
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN || !record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            return@firstNotNullOfOrNull null
        }
        val payload = record.payload
        if (payload.isEmpty()) return@firstNotNullOfOrNull null
        val languageLength = payload[0].toInt() and 0x3f
        val textStart = 1 + languageLength
        if (textStart > payload.size) null else payload.copyOfRange(textStart, payload.size).toString(StandardCharsets.UTF_8)
    }

    fun createVCard(tagId: String, phone: String): String {
        requireValidTagId(tagId)
        val normalizedPhone = normalizePhone(phone)
        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("FN:失物联系")
            appendLine("TEL:$normalizedPhone")
            appendLine("NOTE:这是遗失物品，请联系失主")
            appendLine("X-OBSIDIAN-CODX-TAG:$tagId")
            append("END:VCARD")
        }
    }

    fun decodeQr(raw: String): String? = tagPattern.find(raw)?.groupValues?.get(1)

    fun createQrBitmap(raw: String, size: Int = 768): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(raw, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    fun normalizePhone(value: String): String {
        val normalized = value.filter { it.isDigit() || it == '+' }
        require(Regex("^\\+[1-9][0-9]{6,14}$").matches(normalized)) {
            "电话号码必须包含国家代码，例如 +86..."
        }
        return normalized
    }

    fun normalizeItemName(value: String): String = value.trim().replace(Regex("[\\r\\n]+"), " ").take(80)

    private fun requireValidTagId(tagId: String) {
        require(Regex("^tag_[0-9a-hjkmnp-tv-z]{26}$").matches(tagId)) { "无效 tagId" }
    }
}
