package com.obsidiancodx.entityinventory.data

import com.obsidiancodx.entityinventory.core.AuditAction
import com.obsidiancodx.entityinventory.core.AuditException
import com.obsidiancodx.entityinventory.core.AuditScope
import com.obsidiancodx.entityinventory.core.AuditSnapshot
import com.obsidiancodx.entityinventory.core.AuditStatus
import com.obsidiancodx.entityinventory.core.MissingReason
import com.obsidiancodx.entityinventory.core.ObservationResult
import com.obsidiancodx.entityinventory.core.ScanMethod
import com.obsidiancodx.entityinventory.core.ScanObservation
import org.yaml.snakeyaml.Yaml

object AuditDraftCodec {
    private val yaml = Yaml()

    fun encode(snapshot: AuditSnapshot): String = yaml.dump(
        linkedMapOf(
            "auditId" to snapshot.auditId,
            "scope" to snapshot.scope.name,
            "action" to snapshot.action.name,
            "expectedEntityIds" to snapshot.expectedEntityIds.sorted(),
            "requiredEntityIds" to snapshot.requiredEntityIds.sorted(),
            "quantityRequiredEntityIds" to snapshot.quantityRequiredEntityIds.sorted(),
            "scopeValue" to snapshot.scopeValue,
            "fromPlace" to snapshot.fromPlace,
            "toPlace" to snapshot.toPlace,
            "loadoutId" to snapshot.loadoutId,
            "startedAt" to snapshot.startedAt,
            "status" to snapshot.status.name,
            "observations" to snapshot.observations.map { (entityId, observation) ->
                linkedMapOf(
                    "entityId" to entityId,
                    "tagId" to observation.tagId,
                    "method" to observation.method.name,
                    "observedAt" to observation.observedAt,
                    "auditId" to observation.auditId,
                    "deviceId" to observation.deviceId,
                    "rawHardwareIdHash" to observation.rawHardwareIdHash
                )
            },
            "unexpectedTagIds" to snapshot.unexpectedTagIds.sorted(),
            "observationResults" to snapshot.observationResults.mapValues { it.value.name },
            "quantityObservations" to snapshot.quantityObservations,
            "exceptions" to snapshot.exceptions.values.map {
                linkedMapOf("entityId" to it.entityId, "reason" to it.reason.name, "note" to it.note)
            }
        )
    )

    fun decode(raw: String): AuditSnapshot {
        val root = yaml.load<Any?>(raw) as? Map<*, *> ?: error("无效盘点草稿")
        fun value(key: String): String = root[key]?.toString().orEmpty()
        fun stringSet(key: String): Set<String> = (root[key] as? Iterable<*>)
            ?.mapNotNull { it?.toString() }?.toSet().orEmpty()
        val auditId = value("auditId").ifBlank { error("草稿缺少 auditId") }
        val observations = (root["observations"] as? Iterable<*>)?.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val entityId = map["entityId"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            entityId to ScanObservation(
                tagId = map["tagId"]?.toString().orEmpty(),
                method = enumValueOf<ScanMethod>(map["method"]?.toString().orEmpty()),
                observedAt = map["observedAt"]?.toString().orEmpty(),
                auditId = map["auditId"]?.toString()?.ifBlank { auditId } ?: auditId,
                deviceId = map["deviceId"]?.toString()?.takeIf(String::isNotBlank),
                rawHardwareIdHash = map["rawHardwareIdHash"]?.toString()?.takeIf(String::isNotBlank)
            )
        }?.toMap().orEmpty()
        val exceptions = (root["exceptions"] as? Iterable<*>)?.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val entityId = map["entityId"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            entityId to AuditException(
                entityId = entityId,
                reason = enumValueOf<MissingReason>(map["reason"]?.toString().orEmpty()),
                note = map["note"]?.toString().orEmpty()
            )
        }?.toMap().orEmpty()
        val observationResults = (root["observationResults"] as? Map<*, *>)?.mapNotNull { (key, value) ->
            val entityId = key?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            entityId to enumValueOf<ObservationResult>(value?.toString().orEmpty())
        }?.toMap().orEmpty()
        val quantityObservations = (root["quantityObservations"] as? Map<*, *>)?.mapNotNull { (key, value) ->
            val entityId = key?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val quantity = (value as? Number)?.toDouble() ?: value?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            entityId to quantity
        }?.toMap().orEmpty()
        return AuditSnapshot(
            auditId = auditId,
            scope = enumValueOf(value("scope")),
            action = enumValueOf(value("action")),
            expectedEntityIds = stringSet("expectedEntityIds"),
            requiredEntityIds = stringSet("requiredEntityIds"),
            quantityRequiredEntityIds = stringSet("quantityRequiredEntityIds"),
            scopeValue = value("scopeValue"),
            fromPlace = value("fromPlace"),
            toPlace = value("toPlace"),
            loadoutId = value("loadoutId"),
            startedAt = value("startedAt"),
            status = enumValueOf<AuditStatus>(value("status")),
            observations = observations,
            observationResults = observationResults,
            quantityObservations = quantityObservations,
            unexpectedTagIds = stringSet("unexpectedTagIds"),
            exceptions = exceptions
        )
    }
}
