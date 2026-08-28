package com.obsidiancodx.entityinventory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditScaleTest {
    @Test
    fun fiveThousandCardsKeepStableSnapshotAndDeduplicateAcrossMethods() {
        val items = (0 until 5_000).map { index ->
            val tagId = "tag_${index.toString(32).padStart(26, '0')}"
            InventoryRecord(
                entityId = "ent_$index",
                title = "合成物品 $index",
                entityType = EntityType.ITEM,
                category = "合成/性能",
                tag = TagBinding(tagId),
                sourcePath = "物品/合成/$index.md",
                contentHash = "hash-$index"
            )
        }
        val byTag = items.associateBy { requireNotNull(it.tag).tagId }
        var snapshot = AuditSnapshot(
            auditId = "aud_scale",
            scope = AuditScope.ALL,
            action = AuditAction.INVENTORY,
            expectedEntityIds = items.mapTo(linkedSetOf()) { it.entityId }
        )

        items.forEach { item ->
            val tagId = requireNotNull(item.tag).tagId
            snapshot = AuditEngine.observe(
                snapshot,
                ScanObservation(tagId, ScanMethod.QR, auditId = snapshot.auditId),
                byTag
            ).first
        }
        val firstTag = requireNotNull(items.first().tag).tagId
        val (deduplicated, resolution) = AuditEngine.observe(
            snapshot,
            ScanObservation(firstTag, ScanMethod.NFC, auditId = snapshot.auditId),
            byTag
        )

        assertEquals(5_000, deduplicated.expectedEntityIds.size)
        assertEquals(5_000, deduplicated.observations.size)
        assertTrue((resolution as ScanResolution.Matched).duplicate)
        assertEquals(AuditStatus.COMPLETED, AuditEngine.finish(deduplicated).status)
    }
}
