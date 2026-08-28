package com.obsidiancodx.entityinventory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditEngineTest {
    private val item = InventoryRecord(
        entityId = "ent_item",
        title = "钥匙",
        entityType = EntityType.ITEM,
        tag = TagBinding("tag_01arz3ndektsv4rrffq69g5fav"),
        sourcePath = "物品/钥匙.md",
        contentHash = "hash"
    )

    @Test
    fun qrAndNfcForSameTagCountOnce() {
        val start = AuditSnapshot("aud_test", AuditScope.ALL, AuditAction.INVENTORY, setOf(item.entityId))
        val tagId = requireNotNull(item.tag).tagId
        val byTag = mapOf(tagId to item)
        val first = AuditEngine.observe(
            start,
            ScanObservation(tagId, ScanMethod.QR, auditId = start.auditId),
            byTag
        ).first
        val (second, resolution) = AuditEngine.observe(
            first,
            ScanObservation(tagId, ScanMethod.NFC, auditId = start.auditId),
            byTag
        )
        assertEquals(1, second.observations.size)
        assertTrue((resolution as ScanResolution.Matched).duplicate)
    }

    @Test(expected = IllegalStateException::class)
    fun requiredMissingCannotFinishSilently() {
        AuditEngine.finish(AuditSnapshot("aud_test", AuditScope.LOADOUT, AuditAction.DEPART, setOf("ent_missing")))
    }

    @Test
    fun missingWithReasonCanFinish() {
        val start = AuditSnapshot("aud_test", AuditScope.LOADOUT, AuditAction.DEPART, setOf("ent_missing"))
        val explained = AuditEngine.addException(start, AuditException("ent_missing", MissingReason.LEFT_HERE))
        assertEquals(AuditStatus.COMPLETED_WITH_EXCEPTIONS, AuditEngine.finish(explained).status)
    }

    @Test
    fun batchNeedsQuantityAndRecordsMismatch() {
        val batch = item.copy(
            entityId = "ent_batch",
            entityType = EntityType.ITEM_BATCH,
            quantity = 10.0,
            countMode = "quantity"
        )
        val tagId = requireNotNull(batch.tag).tagId
        val start = AuditSnapshot(
            auditId = "aud_batch",
            scope = AuditScope.ALL,
            action = AuditAction.INVENTORY,
            expectedEntityIds = setOf(batch.entityId),
            quantityRequiredEntityIds = setOf(batch.entityId)
        )
        val observed = AuditEngine.observe(
            start,
            ScanObservation(tagId, ScanMethod.NFC, auditId = start.auditId),
            mapOf(tagId to batch)
        ).first
        assertTrue(runCatching { AuditEngine.finish(observed) }.isFailure)
        val counted = AuditEngine.recordQuantity(observed, batch, 8.0)
        assertTrue(batch.entityId in counted.quantityMismatchEntityIds)
        assertEquals(AuditStatus.COMPLETED_WITH_EXCEPTIONS, AuditEngine.finish(counted).status)
    }

    @Test
    fun placeAuditSeparatesMisplacedAndUnexpectedItems() {
        val expected = item.copy(currentPlace = "家")
        val unexpected = item.copy(entityId = "ent_other", title = "耳机", currentPlace = "公司")
        val start = AuditSnapshot(
            auditId = "aud_place",
            scope = AuditScope.PLACE,
            action = AuditAction.INVENTORY,
            expectedEntityIds = setOf(expected.entityId),
            scopeValue = "酒店"
        )
        val expectedTag = requireNotNull(expected.tag).tagId
        val unexpectedTag = "tag_01arz3ndektsv4rrffq69g5fb0"
        val byTag = mapOf(expectedTag to expected, unexpectedTag to unexpected)
        val misplaced = AuditEngine.observe(
            start,
            ScanObservation(expectedTag, ScanMethod.QR, auditId = start.auditId),
            byTag
        ).first
        val extra = AuditEngine.observe(
            misplaced,
            ScanObservation(unexpectedTag, ScanMethod.NFC, auditId = start.auditId),
            byTag
        ).first
        assertTrue(expected.entityId in extra.misplacedEntityIds)
        assertTrue(unexpected.entityId in extra.extraEntityIds)
    }
}
