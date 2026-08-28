package com.obsidiancodx.entityinventory.data

import android.content.Context
import android.net.Uri
import com.obsidiancodx.entityinventory.core.AuditSnapshot
import com.obsidiancodx.entityinventory.core.ContainerRecord
import com.obsidiancodx.entityinventory.core.EntityType
import com.obsidiancodx.entityinventory.core.IdGenerator
import com.obsidiancodx.entityinventory.core.InventoryRecord
import com.obsidiancodx.entityinventory.core.InventorySettings
import com.obsidiancodx.entityinventory.core.LoadoutRecord
import com.obsidiancodx.entityinventory.core.PlaceRecord
import com.obsidiancodx.entityinventory.core.TagBinding
import com.obsidiancodx.entityinventory.core.VaultSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime

class InventoryRepository(private val context: Context) {
    private val store = VaultDocumentStore(context)
    private val cache = CacheDatabase.get(context).cacheDao()

    suspend fun index(treeUri: Uri): VaultSnapshot = withContext(Dispatchers.IO) {
        val files = store.scanMarkdown(treeUri)
        val items = mutableListOf<InventoryRecord>()
        val loadouts = mutableListOf<LoadoutRecord>()
        val places = mutableListOf<PlaceRecord>()
        val containers = mutableListOf<ContainerRecord>()
        var settings = InventorySettings()
        val cacheRows = mutableListOf<FileCacheEntity>()

        files.forEach { file ->
            val document = runCatching { MarkdownDocument.parse(file.raw) }.getOrNull() ?: return@forEach
            val fm = document.frontmatter
            when (EntityType.fromWire(fm.string("entityType"))) {
                EntityType.ITEM, EntityType.ITEM_BATCH -> parseItem(file, fm)?.let(items::add)
                EntityType.LOADOUT -> parseLoadout(file, fm)?.let(loadouts::add)
                EntityType.PLACE -> parsePlace(file, fm)?.let(places::add)
                EntityType.CONTAINER -> parseContainer(file, fm)?.let(containers::add)
                else -> if (fm.string("type") == "inventory-settings") {
                    settings = InventorySettings(
                        recoveryPhone = fm.string("recoveryPhone"),
                        contactVersion = fm.int("contactVersion", 1)
                    )
                }
            }
            val stableId = fm.string("entityId").ifBlank {
                fm.string("loadoutId").ifBlank { fm.string("auditId") }
            }
            cacheRows += FileCacheEntity(
                documentUri = file.document.uri.toString(),
                relativePath = file.relativePath,
                modifiedAt = file.modifiedAt,
                contentHash = file.hash,
                entityType = fm.string("entityType"),
                stableId = stableId
            )
        }

        cache.upsertFiles(cacheRows)
        if (cacheRows.isEmpty()) cache.clearFiles() else cache.deleteMissing(cacheRows.map { it.documentUri })
        VaultSnapshot(
            items.sortedWith(compareBy<InventoryRecord> { it.category }.thenBy { it.title }),
            loadouts.sortedBy { it.title },
            places.sortedBy { it.title },
            containers.sortedBy { it.title },
            settings
        )
    }

    suspend fun saveAuditDraft(snapshot: AuditSnapshot) = withContext(Dispatchers.IO) {
        cache.saveDraft(DraftAuditEntity(snapshot.auditId, AuditDraftCodec.encode(snapshot), System.currentTimeMillis()))
    }

    suspend fun loadAuditDraft(): AuditSnapshot? = withContext(Dispatchers.IO) {
        cache.activeDraft()?.let { runCatching { AuditDraftCodec.decode(it.json) }.getOrNull() }
    }

    suspend fun deleteAuditDraft(auditId: String) = withContext(Dispatchers.IO) {
        cache.deleteDraft(auditId)
    }

    suspend fun createItem(
        treeUri: Uri,
        title: String,
        category: String,
        batch: Boolean,
        tagId: String = IdGenerator.tagId(),
        quantity: Double? = null,
        unit: String? = null,
        currentPlace: String = "",
        container: String = "",
        details: String = "",
        purchaseLink: String = "",
        photoUri: String? = null
    ): InventoryRecord = withContext(Dispatchers.IO) {
        val now = OffsetDateTime.now().toString()
        val entityId = IdGenerator.entityId()
        val entityType = if (batch) EntityType.ITEM_BATCH else EntityType.ITEM
        val frontmatter = linkedMapOf<String, Any?>(
            "title" to title,
            "entityType" to entityType.wire,
            "schemaVersion" to 1,
            "entityId" to entityId,
            "category" to category,
            "owner" to "我",
            "status" to "active",
            "homePlace" to currentPlace,
            "currentPlace" to currentPlace,
            "container" to container,
            "tagId" to tagId,
            "tagStatus" to "pending",
            "nfcUidHash" to "",
            "qrStatus" to "generated",
            "contactVersion" to 1,
            "lastChecked" to "",
            "purchaseLink" to purchaseLink,
            "photo" to "",
            "created" to now,
            "updated" to now,
            "tags" to listOf(if (batch) "实体/批次" else "实体/物品")
        )
        if (batch) {
            frontmatter["quantity"] = quantity ?: 0.0
            frontmatter["unit"] = unit ?: "件"
            frontmatter["countMode"] = "quantity"
            frontmatter["quantityConfidence"] = "exact"
        }
        val body = buildString {
            appendLine("# $title")
            appendLine()
            appendLine("## 识别信息")
            appendLine()
            if (details.isNotBlank()) appendLine(details)
            appendLine()
            appendLine("## 位置与用途")
            appendLine()
            appendLine("- 地点：$currentPlace")
            appendLine("- 容器：$container")
            appendLine()
            appendLine("## 购买与资料")
            appendLine()
            if (purchaseLink.isNotBlank()) appendLine("- $purchaseLink")
            appendLine()
            appendLine("## 标签与维护")
            appendLine()
            appendLine("## 备注")
        }
        val categoryPath = category.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val root = if (batch) "批次" else "物品"
        var created = store.createMarkdown(
            treeUri,
            listOf(root) + categoryPath,
            "${VaultDocumentStore.safeFileName(title)}.md",
            MarkdownDocument(frontmatter, body).render()
        )
        if (!photoUri.isNullOrBlank()) {
            val source = Uri.parse(photoUri)
            val mime = context.contentResolver.getType(source) ?: "image/jpeg"
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
            val fileName = "$entityId.$extension"
            val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                ?: error("无法读取所选照片")
            store.writeAttachment(treeUri, listOf("_附件", "物品照片"), fileName, mime, bytes)
            frontmatter["photo"] = "[[_附件/物品照片/$fileName]]"
            created = store.updateMarkdown(created, MarkdownDocument(frontmatter, body).render())
        }
        parseItem(created, frontmatter) ?: error("新建物品无法解析")
    }

    suspend fun saveQrPng(treeUri: Uri, item: InventoryRecord, png: ByteArray): Uri = withContext(Dispatchers.IO) {
        val tagId = item.tag?.tagId ?: error("物品没有 tagId")
        store.writeAttachment(treeUri, listOf("_附件", "二维码"), "$tagId.png", "image/png", png)
    }

    suspend fun prepareTag(
        treeUri: Uri,
        item: InventoryRecord,
        tagId: String,
        contactVersion: Int
    ): InventoryRecord = withContext(Dispatchers.IO) {
        check(item.tag == null) { "物品已经有标签绑定" }
        val current = store.findMarkdown(treeUri, item.sourcePath)
        check(current.hash == item.contentHash) {
            "${item.sourcePath} 已在 Obsidian 中修改，停止准备标签"
        }
        val markdown = MarkdownDocument.parse(current.raw)
        check(markdown.frontmatter.string("tagId").isBlank()) { "物品卡已经有 tagId" }
        val now = OffsetDateTime.now().toString()
        markdown.frontmatter["tagId"] = tagId
        markdown.frontmatter["tagStatus"] = "pending"
        markdown.frontmatter["nfcUidHash"] = ""
        markdown.frontmatter["qrStatus"] = "generated"
        markdown.frontmatter["contactVersion"] = contactVersion
        markdown.frontmatter["updated"] = now
        val updated = store.updateMarkdown(current, markdown.render())
        parseItem(updated, markdown.frontmatter) ?: error("准备标签后的物品卡无法解析")
    }

    suspend fun updateItemLocation(
        file: VaultFile,
        placeLink: String,
        checkedAt: String = OffsetDateTime.now().toString()
    ): VaultFile = withContext(Dispatchers.IO) {
        val markdown = MarkdownDocument.parse(file.raw)
        markdown.frontmatter["currentPlace"] = placeLink
        markdown.frontmatter["lastChecked"] = checkedAt
        markdown.frontmatter["updated"] = checkedAt
        store.updateMarkdown(file, markdown.render())
    }

    suspend fun updateItemLocation(
        treeUri: Uri,
        item: InventoryRecord,
        placeLink: String,
        checkedAt: String = OffsetDateTime.now().toString()
    ): InventoryRecord = withContext(Dispatchers.IO) {
        val current = store.findMarkdown(treeUri, item.sourcePath)
        check(current.hash == item.contentHash) {
            "${item.sourcePath} 已在 Obsidian 中修改，停止位置更新"
        }
        val markdown = MarkdownDocument.parse(current.raw)
        markdown.frontmatter["currentPlace"] = placeLink
        markdown.frontmatter["lastChecked"] = checkedAt
        markdown.frontmatter["updated"] = checkedAt
        val updated = store.updateMarkdown(current, markdown.render())
        parseItem(updated, markdown.frontmatter) ?: error("位置更新后的物品卡无法解析")
    }

    suspend fun updateItemPlacement(
        treeUri: Uri,
        item: InventoryRecord,
        currentPlace: String,
        container: String,
        checkedAt: String = OffsetDateTime.now().toString()
    ): InventoryRecord = withContext(Dispatchers.IO) {
        val current = store.findMarkdown(treeUri, item.sourcePath)
        check(current.hash == item.contentHash) {
            "${item.sourcePath} 已在 Obsidian 中修改，停止位置更新"
        }
        val markdown = MarkdownDocument.parse(current.raw)
        markdown.frontmatter["currentPlace"] = currentPlace
        if (markdown.frontmatter.string("homePlace").isBlank() && currentPlace.isNotBlank()) {
            markdown.frontmatter["homePlace"] = currentPlace
        }
        markdown.frontmatter["container"] = container
        markdown.frontmatter["lastChecked"] = checkedAt
        markdown.frontmatter["updated"] = checkedAt
        val updated = store.updateMarkdown(current, markdown.render())
        parseItem(updated, markdown.frontmatter) ?: error("位置更新后的物品卡无法解析")
    }

    suspend fun activateTag(
        treeUri: Uri,
        item: InventoryRecord,
        nfcUidHash: String,
        contactVersion: Int
    ): InventoryRecord = withContext(Dispatchers.IO) {
        val current = store.findMarkdown(treeUri, item.sourcePath)
        check(current.hash == item.contentHash) {
            "${item.sourcePath} 已在 Obsidian 中修改，停止激活标签"
        }
        val markdown = MarkdownDocument.parse(current.raw)
        check(markdown.frontmatter.string("tagId") == item.tag?.tagId) { "物品卡 tagId 已改变" }
        val now = OffsetDateTime.now().toString()
        markdown.frontmatter["tagStatus"] = "active"
        markdown.frontmatter["qrStatus"] = "verified"
        markdown.frontmatter["nfcUidHash"] = nfcUidHash
        markdown.frontmatter["contactVersion"] = contactVersion
        markdown.frontmatter["tagActivatedAt"] = now
        markdown.frontmatter["updated"] = now
        val updated = store.updateMarkdown(current, markdown.render())
        parseItem(updated, markdown.frontmatter) ?: error("激活后的物品卡无法解析")
    }

    suspend fun applyAuditResult(
        treeUri: Uri,
        item: InventoryRecord,
        snapshot: AuditSnapshot,
        applyCorrections: Boolean,
        checkedAt: String = OffsetDateTime.now().toString()
    ): InventoryRecord = withContext(Dispatchers.IO) {
        require(item.entityId in snapshot.observations) { "物品不在本次扫描结果中" }
        val current = store.findMarkdown(treeUri, item.sourcePath)
        check(current.hash == item.contentHash) {
            "${item.sourcePath} 已在 Obsidian 中修改，停止盘点结果回写"
        }
        val markdown = MarkdownDocument.parse(current.raw)
        markdown.frontmatter["lastChecked"] = checkedAt
        markdown.frontmatter["lastAuditId"] = snapshot.auditId
        snapshot.quantityObservations[item.entityId]?.let { observed ->
            val mismatch = item.entityId in snapshot.quantityMismatchEntityIds
            markdown.frontmatter["lastObservedQuantity"] = observed
            markdown.frontmatter["quantityStatus"] = if (mismatch && !applyCorrections) "mismatch" else "verified"
            if (applyCorrections || !mismatch) markdown.frontmatter["quantity"] = observed
        }
        if (applyCorrections && item.entityId in snapshot.misplacedEntityIds && snapshot.expectedPlace.isNotBlank()) {
            markdown.frontmatter["currentPlace"] = snapshot.expectedPlace
        }
        markdown.frontmatter["updated"] = checkedAt
        val updated = store.updateMarkdown(current, markdown.render())
        parseItem(updated, markdown.frontmatter) ?: error("盘点回写后的物品卡无法解析")
    }

    suspend fun createLoadout(
        treeUri: Uri,
        title: String,
        requiredIds: Set<String>,
        optionalIds: Set<String> = emptySet(),
        defaultContainer: String = ""
    ): LoadoutRecord = withContext(Dispatchers.IO) {
        require(requiredIds.isNotEmpty()) { "携带清单至少需要一个必带物品" }
        require((requiredIds intersect optionalIds).isEmpty()) { "同一物品不能同时必带和可选" }
        val now = OffsetDateTime.now().toString()
        val id = IdGenerator.loadoutId()
        val frontmatter = linkedMapOf<String, Any?>(
            "title" to title,
            "entityType" to "loadout",
            "schemaVersion" to 1,
            "loadoutId" to id,
            "loadoutKind" to "template",
            "status" to "active",
            "defaultContainer" to defaultContainer,
            "requiredEntityIds" to requiredIds.sorted(),
            "optionalEntityIds" to optionalIds.sorted(),
            "created" to now,
            "updated" to now,
            "tags" to listOf("实体/携带清单")
        )
        val body = buildString {
            appendLine("# $title")
            appendLine()
            appendLine("## 必带")
            appendLine()
            requiredIds.sorted().forEach { appendLine("- `$it`") }
            appendLine()
            appendLine("## 可选")
            appendLine()
            optionalIds.sorted().forEach { appendLine("- `$it`") }
        }
        val created = store.createMarkdown(
            treeUri,
            listOf("携带清单"),
            "${VaultDocumentStore.safeFileName(title)}.md",
            MarkdownDocument(frontmatter, body).render()
        )
        parseLoadout(created, frontmatter) ?: error("新建携带清单无法解析")
    }

    suspend fun createPlace(
        treeUri: Uri,
        title: String,
        parentPlace: String = "",
        latitude: Double? = null,
        longitude: Double? = null
    ): PlaceRecord = withContext(Dispatchers.IO) {
        val now = OffsetDateTime.now().toString()
        val id = IdGenerator.placeId()
        val frontmatter = linkedMapOf<String, Any?>(
            "title" to title,
            "entityType" to "place",
            "schemaVersion" to 1,
            "placeId" to id,
            "placeType" to if (latitude != null && longitude != null) "geo" else "custom",
            "parentPlace" to parentPlace,
            "latitude" to (latitude ?: ""),
            "longitude" to (longitude ?: ""),
            "status" to "active",
            "created" to now,
            "updated" to now,
            "tags" to listOf("实体/地点")
        )
        val body = "# $title\n\n## 范围\n\n这个地点可以是地图位置，也可以是房间、酒店或临时区域。\n"
        val created = store.createMarkdown(
            treeUri,
            listOf("地点"),
            "${VaultDocumentStore.safeFileName(title)}.md",
            MarkdownDocument(frontmatter, body).render()
        )
        parsePlace(created, frontmatter) ?: error("新建地点无法解析")
    }

    suspend fun createContainer(
        treeUri: Uri,
        title: String,
        containerType: String = "other",
        place: String = "",
        parentContainer: String = ""
    ): ContainerRecord = withContext(Dispatchers.IO) {
        val now = OffsetDateTime.now().toString()
        val frontmatter = linkedMapOf<String, Any?>(
            "title" to title,
            "entityType" to "container",
            "schemaVersion" to 1,
            "entityId" to IdGenerator.entityId(),
            "containerId" to IdGenerator.containerId(),
            "containerType" to containerType.ifBlank { "other" },
            "parentContainer" to parentContainer,
            "place" to place,
            "owner" to "我",
            "status" to "active",
            "identityStatus" to "confirmed",
            "created" to now,
            "updated" to now,
            "tags" to listOf("实体/容器")
        )
        val body = buildString {
            appendLine("# $title")
            appendLine()
            appendLine("- 所在地点：$place")
            appendLine("- 上级容器：$parentContainer")
        }
        val created = store.createMarkdown(
            treeUri,
            listOf("空间与容器", "应用创建"),
            "${VaultDocumentStore.safeFileName(title)}.md",
            MarkdownDocument(frontmatter, body).render()
        )
        parseContainer(created, frontmatter) ?: error("新建容器无法解析")
    }

    suspend fun appendAudit(treeUri: Uri, snapshot: AuditSnapshot): VaultFile = withContext(Dispatchers.IO) {
        val completedAt = OffsetDateTime.now().toString()
        val frontmatter = linkedMapOf<String, Any?>(
            "title" to "盘点 ${snapshot.auditId}",
            "entityType" to "audit",
            "schemaVersion" to 1,
            "auditId" to snapshot.auditId,
            "auditScope" to snapshot.scope.name.lowercase(),
            "auditAction" to snapshot.action.name.lowercase(),
            "scopeValue" to snapshot.scopeValue,
            "status" to snapshot.status.name.lowercase(),
            "fromPlace" to snapshot.fromPlace,
            "toPlace" to snapshot.toPlace,
            "loadoutId" to snapshot.loadoutId,
            "expectedEntityIds" to snapshot.expectedEntityIds.sorted(),
            "observations" to snapshot.observations.values.map {
                mapOf("tagId" to it.tagId, "method" to it.method.name.lowercase(), "observedAt" to it.observedAt)
            },
            "observationResults" to snapshot.observationResults.mapValues { it.value.name.lowercase() },
            "quantityObservations" to snapshot.quantityObservations,
            "missingEntityIds" to snapshot.missingEntityIds.sorted(),
            "misplacedEntityIds" to snapshot.misplacedEntityIds.sorted(),
            "quantityMismatchEntityIds" to snapshot.quantityMismatchEntityIds.sorted(),
            "extraEntityIds" to snapshot.extraEntityIds.sorted(),
            "unexpectedTagIds" to snapshot.unexpectedTagIds.sorted(),
            "exceptions" to snapshot.exceptions.values.map {
                mapOf("entityId" to it.entityId, "reason" to it.reason.wire, "note" to it.note)
            },
            "startedAt" to snapshot.startedAt,
            "completedAt" to completedAt,
            "created" to completedAt,
            "updated" to completedAt,
            "tags" to listOf("实体/盘点记录")
        )
        val body = buildString {
            appendLine("# 盘点 ${snapshot.auditId}")
            appendLine()
            appendLine("- 预期：${snapshot.expectedEntityIds.size}")
            appendLine("- 已确认：${snapshot.observations.size}")
            appendLine("- 未发现：${snapshot.missingEntityIds.size}")
            appendLine("- 位置异常：${snapshot.misplacedEntityIds.size}")
            appendLine("- 数量异常：${snapshot.quantityMismatchEntityIds.size}")
            appendLine("- 额外物品：${snapshot.extraEntityIds.size}")
            appendLine("- 未绑定标签：${snapshot.unexpectedTagIds.size}")
            appendLine()
            appendLine("## 未发现与例外")
            appendLine()
            appendLine("| entityId | 原因 | 备注 |")
            appendLine("|---|---|---|")
            snapshot.missingEntityIds.sorted().forEach { id ->
                val exception = snapshot.exceptions[id]
                appendLine("| $id | ${exception?.reason?.wire.orEmpty()} | ${exception?.note.orEmpty()} |")
            }
            appendLine()
            appendLine("## 扫描结果")
            appendLine()
            appendLine("| entityId | 结果 | 当前数量 |")
            appendLine("|---|---|---:|")
            snapshot.observations.keys.sorted().forEach { id ->
                appendLine("| $id | ${snapshot.observationResults[id]?.name?.lowercase().orEmpty()} | ${snapshot.quantityObservations[id]?.toString().orEmpty()} |")
            }
        }
        store.createMarkdown(
            treeUri,
            listOf("盘点记录", completedAt.take(10)),
            "${snapshot.auditId}.md",
            MarkdownDocument(frontmatter, body).render()
        )
    }

    private fun parseItem(file: VaultFile, fm: Map<String, Any?>): InventoryRecord? {
        val type = EntityType.fromWire(fm.string("entityType")) ?: return null
        val id = fm.string("entityId").takeIf { it.isNotBlank() } ?: return null
        val tagId = fm.string("tagId").takeIf { it.isNotBlank() }
        return InventoryRecord(
            entityId = id,
            title = fm.string("title").ifBlank { file.document.name?.removeSuffix(".md").orEmpty() },
            entityType = type,
            category = categoryFromPath(file, type).ifBlank { fm.string("category") },
            owner = fm.string("owner").ifBlank { "我" },
            status = fm.string("status").ifBlank { "active" },
            homePlace = fm.string("homePlace"),
            currentPlace = fm.string("currentPlace"),
            container = fm.string("container"),
            quantity = fm.doubleOrNull("quantity"),
            unit = fm.string("unit").takeIf { it.isNotBlank() },
            countMode = fm.string("countMode").ifBlank { "presence" },
            tag = tagId?.let {
                TagBinding(
                    tagId = it,
                    status = fm.string("tagStatus").ifBlank { "pending" },
                    nfcUidHash = fm.string("nfcUidHash").takeIf(String::isNotBlank),
                    contactVersion = fm.int("contactVersion", 1)
                )
            },
            lastChecked = fm.string("lastChecked").takeIf { it.isNotBlank() },
            sourcePath = file.relativePath,
            contentHash = file.hash
        )
    }

    private fun parseLoadout(file: VaultFile, fm: Map<String, Any?>): LoadoutRecord? {
        val id = fm.string("loadoutId").takeIf { it.isNotBlank() } ?: return null
        return LoadoutRecord(
            loadoutId = id,
            title = fm.string("title").ifBlank { file.document.name?.removeSuffix(".md").orEmpty() },
            requiredEntityIds = fm.stringSet("requiredEntityIds"),
            optionalEntityIds = fm.stringSet("optionalEntityIds"),
            defaultContainer = fm.string("defaultContainer"),
            sourcePath = file.relativePath,
            contentHash = file.hash
        )
    }

    private fun parsePlace(file: VaultFile, fm: Map<String, Any?>): PlaceRecord? {
        val id = fm.string("placeId").takeIf { it.isNotBlank() } ?: return null
        return PlaceRecord(
            placeId = id,
            title = fm.string("title").ifBlank { file.document.name?.removeSuffix(".md").orEmpty() },
            placeType = fm.string("placeType").ifBlank { "custom" },
            parentPlace = fm.string("parentPlace"),
            latitude = fm.doubleOrNull("latitude"),
            longitude = fm.doubleOrNull("longitude"),
            sourcePath = file.relativePath,
            contentHash = file.hash
        )
    }

    private fun parseContainer(file: VaultFile, fm: Map<String, Any?>): ContainerRecord? {
        val entityId = fm.string("entityId").takeIf { it.isNotBlank() } ?: return null
        val containerId = fm.string("containerId").takeIf { it.isNotBlank() } ?: return null
        return ContainerRecord(
            entityId = entityId,
            containerId = containerId,
            title = fm.string("title").ifBlank { file.document.name?.removeSuffix(".md").orEmpty() },
            containerType = fm.string("containerType").ifBlank { "other" },
            parentContainer = linkLabel(fm.string("parentContainer")),
            place = linkLabel(fm.string("place")),
            owner = fm.string("owner").ifBlank { "我" },
            status = fm.string("status").ifBlank { "active" },
            sourcePath = file.relativePath,
            contentHash = file.hash
        )
    }

    private fun categoryFromPath(file: VaultFile, type: EntityType): String {
        val root = if (type == EntityType.ITEM_BATCH) "批次" else "物品"
        val parts = file.relativePath.replace('\\', '/').split('/').filter(String::isNotBlank)
        val rootIndex = parts.indexOf(root)
        if (rootIndex < 0 || rootIndex >= parts.lastIndex) return ""
        return parts.subList(rootIndex + 1, parts.lastIndex).joinToString("/")
    }

    private fun linkLabel(value: String): String {
        val raw = value.removePrefix("[[").removeSuffix("]]" )
        val alias = raw.substringAfter('|', missingDelimiterValue = "")
        return if (alias.isNotBlank()) alias else raw.substringAfterLast('/')
    }
}
