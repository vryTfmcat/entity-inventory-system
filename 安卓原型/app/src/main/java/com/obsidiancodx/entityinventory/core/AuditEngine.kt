package com.obsidiancodx.entityinventory.core

object AuditEngine {
    fun observe(
        snapshot: AuditSnapshot,
        observation: ScanObservation,
        entityByTagId: Map<String, InventoryRecord>
    ): Pair<AuditSnapshot, ScanResolution> {
        check(snapshot.status == AuditStatus.IN_PROGRESS) { "盘点未处于进行中" }
        val entity = entityByTagId[observation.tagId]
            ?: return snapshot.copy(unexpectedTagIds = snapshot.unexpectedTagIds + observation.tagId) to
                ScanResolution.Unknown(observation.tagId)
        val duplicate = entity.entityId in snapshot.observations
        val existingResult = snapshot.observationResults[entity.entityId] ?: ObservationResult.VERIFIED
        if (duplicate) return snapshot to ScanResolution.Matched(entity, true, existingResult)
        val result = when {
            entity.entityId !in snapshot.expectedEntityIds -> ObservationResult.UNEXPECTED
            snapshot.expectedPlace.isNotBlank() && entity.currentPlace.isNotBlank() && entity.currentPlace != snapshot.expectedPlace ->
                ObservationResult.MISPLACED
            else -> ObservationResult.VERIFIED
        }
        return snapshot.copy(
            observations = snapshot.observations + (entity.entityId to observation),
            observationResults = snapshot.observationResults + (entity.entityId to result)
        ) to ScanResolution.Matched(entity, false, result)
    }

    fun recordQuantity(snapshot: AuditSnapshot, entity: InventoryRecord, quantity: Double): AuditSnapshot {
        require(entity.entityId in snapshot.observations) { "请先扫描批次标签" }
        require(quantity >= 0) { "数量不能为负数" }
        val mismatch = entity.quantity?.let { expected -> kotlin.math.abs(expected - quantity) > 0.000001 } ?: false
        val existing = snapshot.observationResults[entity.entityId] ?: ObservationResult.VERIFIED
        val result = if (mismatch) ObservationResult.QUANTITY_MISMATCH else if (existing == ObservationResult.QUANTITY_MISMATCH) {
            ObservationResult.VERIFIED
        } else existing
        return snapshot.copy(
            quantityObservations = snapshot.quantityObservations + (entity.entityId to quantity),
            observationResults = snapshot.observationResults + (entity.entityId to result)
        )
    }

    fun addException(snapshot: AuditSnapshot, exception: AuditException): AuditSnapshot {
        require(exception.entityId in snapshot.missingEntityIds) { "只能为未确认物品填写例外" }
        return snapshot.copy(exceptions = snapshot.exceptions + (exception.entityId to exception))
    }

    fun pause(snapshot: AuditSnapshot): AuditSnapshot = snapshot.copy(status = AuditStatus.PAUSED)
    fun resume(snapshot: AuditSnapshot): AuditSnapshot {
        check(snapshot.status == AuditStatus.PAUSED)
        return snapshot.copy(status = AuditStatus.IN_PROGRESS)
    }

    fun finish(snapshot: AuditSnapshot): AuditSnapshot {
        check(snapshot.unresolvedRequiredIds.isEmpty()) {
            "仍有必带物品未扫描且未填写原因: ${snapshot.unresolvedRequiredIds.joinToString()}"
        }
        check(snapshot.unresolvedQuantityIds.isEmpty()) {
            "仍有已扫描批次未确认数量: ${snapshot.unresolvedQuantityIds.joinToString()}"
        }
        val hasDifferences = snapshot.exceptions.isNotEmpty() ||
            snapshot.missingEntityIds.isNotEmpty() ||
            snapshot.misplacedEntityIds.isNotEmpty() ||
            snapshot.quantityMismatchEntityIds.isNotEmpty() ||
            snapshot.extraEntityIds.isNotEmpty() ||
            snapshot.unexpectedTagIds.isNotEmpty()
        val finalStatus = if (!hasDifferences) {
            AuditStatus.COMPLETED
        } else {
            AuditStatus.COMPLETED_WITH_EXCEPTIONS
        }
        return snapshot.copy(status = finalStatus)
    }
}
