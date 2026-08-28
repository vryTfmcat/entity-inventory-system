package com.obsidiancodx.entityinventory.core

import java.time.OffsetDateTime

enum class EntityType(val wire: String) {
    ITEM("item"),
    ITEM_BATCH("item-batch"),
    PLACE("place"),
    CONTAINER("container"),
    LOADOUT("loadout"),
    AUDIT("audit");

    companion object {
        fun fromWire(value: String?): EntityType? = entries.firstOrNull { it.wire == value }
    }
}

enum class ScanMethod { NFC, QR, SELF_DEVICE, MANUAL, UHF }
enum class AuditScope { ALL, PLACE, CATEGORY, CONTAINER, LOADOUT, ADHOC }
enum class AuditAction { INVENTORY, DEPART, ARRIVE, LEAVE, RECHECK }
enum class AuditStatus { IN_PROGRESS, PAUSED, COMPLETED, COMPLETED_WITH_EXCEPTIONS, ABORTED }
enum class ObservationResult { VERIFIED, MISPLACED, QUANTITY_MISMATCH, UNEXPECTED }
enum class MissingReason(val wire: String) {
    LEFT_HERE("left_here"),
    NOT_NEEDED("not_needed"),
    UNSCANNABLE("unscannable"),
    LOST("lost"),
    OTHER("other")
}

data class TagBinding(
    val tagId: String,
    val status: String = "active",
    val nfcUidHash: String? = null,
    val contactVersion: Int = 1
)

data class InventoryRecord(
    val entityId: String,
    val title: String,
    val entityType: EntityType,
    val category: String = "",
    val owner: String = "我",
    val status: String = "active",
    val homePlace: String = "",
    val currentPlace: String = "",
    val container: String = "",
    val quantity: Double? = null,
    val unit: String? = null,
    val countMode: String = "presence",
    val tag: TagBinding? = null,
    val lastChecked: String? = null,
    val sourcePath: String,
    val contentHash: String
)

data class LoadoutRecord(
    val loadoutId: String,
    val title: String,
    val requiredEntityIds: Set<String>,
    val optionalEntityIds: Set<String>,
    val defaultContainer: String = "",
    val sourcePath: String,
    val contentHash: String
)

data class PlaceRecord(
    val placeId: String,
    val title: String,
    val placeType: String = "custom",
    val parentPlace: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sourcePath: String,
    val contentHash: String
)

data class ContainerRecord(
    val entityId: String,
    val containerId: String,
    val title: String,
    val containerType: String = "other",
    val parentContainer: String = "",
    val place: String = "",
    val owner: String = "我",
    val status: String = "active",
    val sourcePath: String,
    val contentHash: String
)

data class ScanObservation(
    val tagId: String,
    val method: ScanMethod,
    val observedAt: String = OffsetDateTime.now().toString(),
    val auditId: String,
    val deviceId: String? = null,
    val rawHardwareIdHash: String? = null
)

data class AuditException(
    val entityId: String,
    val reason: MissingReason,
    val note: String = ""
)

data class AuditSnapshot(
    val auditId: String,
    val scope: AuditScope,
    val action: AuditAction,
    val expectedEntityIds: Set<String>,
    val requiredEntityIds: Set<String> = expectedEntityIds,
    val quantityRequiredEntityIds: Set<String> = emptySet(),
    val scopeValue: String = "",
    val fromPlace: String = "",
    val toPlace: String = "",
    val loadoutId: String = "",
    val startedAt: String = OffsetDateTime.now().toString(),
    val status: AuditStatus = AuditStatus.IN_PROGRESS,
    val observations: Map<String, ScanObservation> = emptyMap(),
    val observationResults: Map<String, ObservationResult> = emptyMap(),
    val quantityObservations: Map<String, Double> = emptyMap(),
    val unexpectedTagIds: Set<String> = emptySet(),
    val exceptions: Map<String, AuditException> = emptyMap()
) {
    val observedEntityIds: Set<String> get() = observations.keys
    val missingEntityIds: Set<String> get() = expectedEntityIds - observedEntityIds
    val misplacedEntityIds: Set<String> get() = observationResults.filterValues { it == ObservationResult.MISPLACED }.keys
    val quantityMismatchEntityIds: Set<String> get() = observationResults.filterValues { it == ObservationResult.QUANTITY_MISMATCH }.keys
    val extraEntityIds: Set<String> get() = observationResults.filterValues { it == ObservationResult.UNEXPECTED }.keys
    val unresolvedQuantityIds: Set<String> get() = (quantityRequiredEntityIds intersect observedEntityIds) - quantityObservations.keys
    val expectedPlace: String get() = when {
        scope == AuditScope.PLACE -> scopeValue
        scope == AuditScope.LOADOUT && action in setOf(AuditAction.DEPART, AuditAction.LEAVE) -> fromPlace
        scope == AuditScope.LOADOUT -> toPlace
        else -> ""
    }
    val unresolvedRequiredIds: Set<String>
        get() = (requiredEntityIds - observedEntityIds) - exceptions.keys
}

data class VaultSnapshot(
    val items: List<InventoryRecord>,
    val loadouts: List<LoadoutRecord>,
    val places: List<PlaceRecord>,
    val containers: List<ContainerRecord>,
    val settings: InventorySettings,
    val indexedAt: String = OffsetDateTime.now().toString()
)

data class InventorySettings(
    val recoveryPhone: String = "",
    val contactVersion: Int = 1
)

sealed interface ScanResolution {
    data class Matched(
        val entity: InventoryRecord,
        val duplicate: Boolean,
        val result: ObservationResult = ObservationResult.VERIFIED
    ) : ScanResolution
    data class Unknown(val tagId: String) : ScanResolution
}
