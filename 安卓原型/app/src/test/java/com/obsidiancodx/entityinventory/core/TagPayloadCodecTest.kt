package com.obsidiancodx.entityinventory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPayloadCodecTest {
    private val tagId = "tag_01arz3ndektsv4rrffq69g5fav"

    @Test
    fun vCardRoundTripKeepsSharedTagId() {
        val raw = TagPayloadCodec.createVCard(tagId, "+8613812345678")
        assertEquals(tagId, TagPayloadCodec.decodeQr(raw))
        assertTrue(raw.contains("TEL:+8613812345678"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun phoneMustIncludeCountryCode() {
        TagPayloadCodec.createVCard(tagId, "13812345678")
    }

    @Test
    fun itemNameIsSafeForNdefTextRecord() {
        assertEquals("米家养生壶 S1 测试", TagPayloadCodec.normalizeItemName("  米家养生壶 S1\n测试  "))
        assertTrue(TagPayloadCodec.normalizeItemName("物".repeat(100)).length == 80)
    }
}
