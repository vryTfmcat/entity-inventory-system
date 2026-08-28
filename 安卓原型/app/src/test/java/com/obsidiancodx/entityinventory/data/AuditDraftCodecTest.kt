package com.obsidiancodx.entityinventory.data

import com.obsidiancodx.entityinventory.core.AuditAction
import com.obsidiancodx.entityinventory.core.AuditEngine
import com.obsidiancodx.entityinventory.core.AuditScope
import com.obsidiancodx.entityinventory.core.AuditSnapshot
import com.obsidiancodx.entityinventory.core.ScanMethod
import com.obsidiancodx.entityinventory.core.ScanObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditDraftCodecTest {
    @Test
    fun pausedAuditRoundTripKeepsFrozenSnapshotAndObservations() {
        val original = AuditEngine.pause(
            AuditSnapshot(
                auditId = "aud_draft",
                scope = AuditScope.LOADOUT,
                action = AuditAction.LEAVE,
                expectedEntityIds = setOf("ent_a", "ent_b"),
                requiredEntityIds = setOf("ent_a"),
                fromPlace = "酒店",
                toPlace = "车站",
                loadoutId = "loa_test",
                observations = mapOf(
                    "ent_a" to ScanObservation("tag_01arz3ndektsv4rrffq69g5fav", ScanMethod.QR, auditId = "aud_draft")
                )
            )
        )
        val decoded = AuditDraftCodec.decode(AuditDraftCodec.encode(original))
        assertEquals(original, decoded)
    }
}
