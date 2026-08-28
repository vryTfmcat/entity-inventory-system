package com.obsidiancodx.entityinventory.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentTest {
    @Test
    fun roundTripPreservesBodyAndNestedLists() {
        val raw = """---
title: 钥匙
entityType: item
entityId: ent_01arz3ndektsv4rrffq69g5fav
tags:
  - 实体/物品
---
# 钥匙

用户正文不能丢。
"""
        val parsed = MarkdownDocument.parse(raw)
        parsed.frontmatter["currentPlace"] = "[[家]]"
        val rendered = parsed.render()
        assertTrue(rendered.contains("用户正文不能丢。"))
        assertEquals("[[家]]", MarkdownDocument.parse(rendered).frontmatter["currentPlace"])
    }
}
