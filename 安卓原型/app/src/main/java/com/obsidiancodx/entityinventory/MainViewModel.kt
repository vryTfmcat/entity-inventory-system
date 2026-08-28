package com.obsidiancodx.entityinventory

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obsidiancodx.entityinventory.core.AuditAction
import com.obsidiancodx.entityinventory.core.AuditEngine
import com.obsidiancodx.entityinventory.core.AuditException
import com.obsidiancodx.entityinventory.core.AuditScope
import com.obsidiancodx.entityinventory.core.AuditSnapshot
import com.obsidiancodx.entityinventory.core.AuditStatus
import com.obsidiancodx.entityinventory.core.ContainerRecord
import com.obsidiancodx.entityinventory.core.EntityType
import com.obsidiancodx.entityinventory.core.IdGenerator
import com.obsidiancodx.entityinventory.core.InventoryRecord
import com.obsidiancodx.entityinventory.core.InventorySettings
import com.obsidiancodx.entityinventory.core.LoadoutRecord
import com.obsidiancodx.entityinventory.core.MissingReason
import com.obsidiancodx.entityinventory.core.PlaceRecord
import com.obsidiancodx.entityinventory.core.ScanMethod
import com.obsidiancodx.entityinventory.core.ScanObservation
import com.obsidiancodx.entityinventory.core.ScanResolution
import com.obsidiancodx.entityinventory.core.TagPayloadCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EntityInventoryApplication
    private val repository = app.repository
    private val prefs = application.getSharedPreferences("inventory", 0)

    private val _items = MutableStateFlow<List<InventoryRecord>>(emptyList())
    val items: StateFlow<List<InventoryRecord>> = _items.asStateFlow()
    private val _loadouts = MutableStateFlow<List<LoadoutRecord>>(emptyList())
    val loadouts: StateFlow<List<LoadoutRecord>> = _loadouts.asStateFlow()
    private val _settings = MutableStateFlow(InventorySettings())
    val settings: StateFlow<InventorySettings> = _settings.asStateFlow()
    private val _places = MutableStateFlow<List<PlaceRecord>>(emptyList())
    val places: StateFlow<List<PlaceRecord>> = _places.asStateFlow()
    private val _containers = MutableStateFlow<List<ContainerRecord>>(emptyList())
    val containers: StateFlow<List<ContainerRecord>> = _containers.asStateFlow()
    private val _audit = MutableStateFlow<AuditSnapshot?>(null)
    val audit: StateFlow<AuditSnapshot?> = _audit.asStateFlow()
    private val _message = MutableStateFlow("请选择 Obsidian 库")
    val message: StateFlow<String> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _lastUnknownTag = MutableStateFlow<String?>(null)
    val lastUnknownTag: StateFlow<String?> = _lastUnknownTag.asStateFlow()
    private val _scanPulse = MutableStateFlow(0)
    val scanPulse: StateFlow<Int> = _scanPulse.asStateFlow()
    private val _pendingQuantityEntityId = MutableStateFlow<String?>(null)
    val pendingQuantityEntityId: StateFlow<String?> = _pendingQuantityEntityId.asStateFlow()
    private val _openedItemEntityId = MutableStateFlow<String?>(null)
    val openedItemEntityId: StateFlow<String?> = _openedItemEntityId.asStateFlow()
    private var pendingOpenTagId: String? = null
    private var draftPersistJob: Job? = null
    private var draftRestoreAttempted = false

    val treeUri: Uri? get() = prefs.getString("treeUri", null)?.let(Uri::parse)
    var selfDeviceEntityId: String?
        get() = prefs.getString("selfDeviceEntityId", null)
        set(value) { prefs.edit().putString("selfDeviceEntityId", value).apply() }

    init { treeUri?.let(::refresh) }

    fun selectVault(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString("treeUri", uri.toString()).apply()
        refresh(uri)
    }

    fun refresh(uri: Uri? = treeUri) {
        if (uri == null) return
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.index(uri) }
                .onSuccess {
                    _items.value = it.items
                    _loadouts.value = it.loadouts
                    _places.value = it.places
                    _containers.value = it.containers
                    _settings.value = it.settings
                    pendingOpenTagId?.let { tagId ->
                        it.items.firstOrNull { record -> record.tag?.tagId == tagId }?.let { record ->
                            _openedItemEntityId.value = record.entityId
                            pendingOpenTagId = null
                        }
                    }
                    _message.value = "已索引 ${it.items.size} 件物品、${it.places.size} 个地点、${it.containers.size} 个容器、${it.loadouts.size} 份携带清单"
                    if (!draftRestoreAttempted) {
                        draftRestoreAttempted = true
                        repository.loadAuditDraft()?.let { draft ->
                            _audit.value = draft
                            _message.value = "已恢复未完成盘点：${draft.auditId}"
                        }
                    }
                }
                .onFailure { _message.value = "索引失败：${it.message}" }
            _busy.value = false
        }
    }

    fun createItem(
        title: String,
        category: String,
        batch: Boolean,
        quantity: Double?,
        unit: String?,
        currentPlace: String,
        container: String,
        details: String,
        purchaseLink: String,
        photoUri: String?
    ) {
        val uri = treeUri ?: return setMessage("请先选择 Obsidian 库")
        val tagId = _lastUnknownTag.value ?: IdGenerator.tagId()
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                repository.createItem(
                    uri, title, category, batch, tagId, quantity, unit,
                    currentPlace, container, details, purchaseLink, photoUri
                )
            }
                .onSuccess {
                    _lastUnknownTag.value = null
                    _message.value = "已创建 ${it.title}，标签待写入和验证"
                    refresh(uri)
                }
                .onFailure { _message.value = "创建失败：${it.message}" }
            _busy.value = false
        }
    }

    fun saveQr(item: InventoryRecord, png: ByteArray) {
        val uri = treeUri ?: return setMessage("请先选择 Obsidian 库")
        viewModelScope.launch {
            runCatching { repository.saveQrPng(uri, item, png) }
                .onSuccess { setMessage("二维码已保存到 50_实体/_附件/二维码/${item.tag?.tagId}.png") }
                .onFailure { setMessage("二维码保存失败：${it.message}") }
        }
    }

    fun prepareTag(item: InventoryRecord) {
        val uri = treeUri ?: return setMessage("请先选择 Obsidian 库")
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                repository.prepareTag(uri, item, IdGenerator.tagId(), _settings.value.contactVersion)
            }.onSuccess {
                setMessage("已为 ${it.title} 生成 tagId；重新点开物品即可保存二维码并写入 NFC")
                refresh(uri)
            }.onFailure { setMessage("准备标签失败：${it.message}") }
            _busy.value = false
        }
    }

    fun createLoadout(title: String, requiredIds: Set<String>, optionalIds: Set<String>, defaultContainer: String) {
        val uri = treeUri ?: return setMessage("请先选择 Obsidian 库")
        viewModelScope.launch {
            runCatching { repository.createLoadout(uri, title, requiredIds, optionalIds, defaultContainer) }
                .onSuccess {
                    setMessage("已创建携带清单：${it.title}")
                    refresh(uri)
                }
                .onFailure { setMessage("创建携带清单失败：${it.message}") }
        }
    }

    fun createPlace(title: String, parentPlace: String, latitude: Double?, longitude: Double?) {
        val uri = treeUri ?: return setMessage("请先选择 Obsidian 库")
        viewModelScope.launch {
            runCatching { repository.createPlace(uri, title, parentPlace, latitude, longitude) }
                .onSuccess {
                    setMessage("已创建地点：${it.title}")
                    refresh(uri)
                }
                .onFailure { setMessage("创建地点失败：${it.message}") }
        }
    }

    fun createContainer(title: String, containerType: String, place: String, parentContainer: String) {
        val uri = treeUri ?: return setMessage("请先选择 Obsidian 库")
        if (title.isBlank()) return setMessage("容器名称不能为空")
        viewModelScope.launch {
            runCatching { repository.createContainer(uri, title, containerType, place, parentContainer) }
                .onSuccess {
                    setMessage("已创建容器：${it.title}")
                    refresh(uri)
                }
                .onFailure { setMessage("创建容器失败：${it.message}") }
        }
    }

    fun updateItemPlacement(item: InventoryRecord, currentPlace: String, container: String) {
        val uri = treeUri ?: return setMessage("请先选择 Obsidian 库")
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.updateItemPlacement(uri, item, currentPlace, container) }
                .onSuccess {
                    setMessage("已更新 ${it.title}：${currentPlace.ifBlank { "未设置地点" }} / ${container.ifBlank { "未设置容器" }}")
                    refresh(uri)
                }
                .onFailure { setMessage("位置更新失败：${it.message}") }
            _busy.value = false
        }
    }

    fun moveItemToPlace(item: InventoryRecord, place: PlaceRecord) {
        updateItemPlacement(item, place.title, "")
    }

    fun moveItemToContainer(item: InventoryRecord, container: ContainerRecord) {
        updateItemPlacement(item, container.place.ifBlank { item.currentPlace }, container.title)
    }

    fun startAllAudit(action: AuditAction = AuditAction.INVENTORY) {
        val candidates = _items.value.filter { it.status in setOf("active", "stored", "lent", "repair") }
        val expected = candidates.map { it.entityId }.toSet()
        val quantityRequired = candidates.filter { it.entityType == EntityType.ITEM_BATCH && it.countMode != "presence" }
            .map { it.entityId }.toSet()
        updateAudit(AuditSnapshot(
            auditId = IdGenerator.auditId(), scope = AuditScope.ALL, action = action,
            expectedEntityIds = expected, quantityRequiredEntityIds = quantityRequired
        ))
        observeSelfDevice()
        _message.value = "已冻结 ${expected.size} 件物品的盘点快照"
    }

    fun startFilteredAudit(scope: AuditScope, value: String = "", selectedIds: Set<String> = emptySet()) {
        require(scope != AuditScope.LOADOUT) { "携带清单请使用 startLoadout" }
        val candidates = _items.value.filter { it.status in setOf("active", "stored", "lent", "repair") }
        val scopedItems = when (scope) {
            AuditScope.ALL -> candidates
            AuditScope.PLACE -> candidates.filter { it.currentPlace == value }
            AuditScope.CATEGORY -> candidates.filter { it.category == value || it.category.startsWith("$value/") }
            AuditScope.CONTAINER -> candidates.filter { it.container == value }
            AuditScope.ADHOC -> candidates.filter { it.entityId in selectedIds }
            AuditScope.LOADOUT -> emptyList()
        }
        val expected = scopedItems.map { it.entityId }.toSet()
        val quantityRequired = scopedItems.filter { it.entityType == EntityType.ITEM_BATCH && it.countMode != "presence" }
            .map { it.entityId }.toSet()
        require(expected.isNotEmpty()) { "所选范围没有有效物品" }
        updateAudit(AuditSnapshot(
            auditId = IdGenerator.auditId(), scope = scope, action = AuditAction.INVENTORY,
            expectedEntityIds = expected, quantityRequiredEntityIds = quantityRequired, scopeValue = value
        ))
        observeSelfDevice()
        _message.value = "已冻结 ${expected.size} 件物品的 ${scope.name.lowercase()} 盘点快照"
    }

    fun startLoadout(loadout: LoadoutRecord, action: AuditAction, fromPlace: String, toPlace: String) {
        require(fromPlace.isNotBlank() && toPlace.isNotBlank()) { "请选择起点和终点" }
        val expected = loadout.requiredEntityIds + loadout.optionalEntityIds
        val quantityRequired = _items.value.filter {
            it.entityId in expected && it.entityType == EntityType.ITEM_BATCH && it.countMode != "presence"
        }.map { it.entityId }.toSet()
        updateAudit(AuditSnapshot(
            auditId = IdGenerator.auditId(),
            scope = AuditScope.LOADOUT,
            action = action,
            expectedEntityIds = expected,
            requiredEntityIds = loadout.requiredEntityIds,
            quantityRequiredEntityIds = quantityRequired,
            fromPlace = fromPlace,
            toPlace = toPlace,
            loadoutId = loadout.loadoutId
        ))
        observeSelfDevice()
        _message.value = "已开始携带清单：${loadout.title}"
    }

    fun acceptQr(raw: String) {
        val tagId = TagPayloadCodec.decodeQr(raw)
        if (tagId == null) setMessage("二维码不是实体盘点标签") else acceptTag(tagId, ScanMethod.QR, null)
    }

    fun acceptNfc(tagId: String, uidHash: String) {
        val item = _items.value.firstOrNull { it.tag?.tagId == tagId }
        if (item?.tag?.status == "active" && item.tag.nfcUidHash != null && item.tag.nfcUidHash != uidHash) {
            setMessage("NFC UID 与已激活绑定不一致，已阻止计入：${item.title}")
            return
        }
        if (item?.tag?.status == "pending") {
            verifyNfcBinding(tagId, uidHash)
            return
        }
        acceptTag(tagId, ScanMethod.NFC, uidHash)
    }

    fun verifyNfcBinding(tagId: String, uidHash: String) {
        val uri = treeUri ?: return setMessage("请先选择 Obsidian 库")
        val item = _items.value.firstOrNull { it.tag?.tagId == tagId }
            ?: return setMessage("NFC 已读回，但找不到对应物品卡：$tagId")
        val phone = _settings.value.recoveryPhone
        val qrTagId = runCatching { TagPayloadCodec.decodeQr(TagPayloadCodec.createVCard(tagId, phone)) }.getOrNull()
        if (qrTagId != tagId) return setMessage("二维码与 NFC 的 tagId 不一致，禁止激活")
        viewModelScope.launch {
            runCatching { repository.activateTag(uri, item, uidHash, _settings.value.contactVersion) }
                .onSuccess {
                    setMessage("NFC 与二维码 tagId 一致，已激活：${it.title}")
                    refresh(uri)
                }
                .onFailure { setMessage("标签读回成功，但激活失败：${it.message}") }
        }
    }

    private fun acceptTag(tagId: String, method: ScanMethod, hardwareHash: String?) {
        val active = _audit.value
        val allByTag = _items.value.mapNotNull { item -> item.tag?.tagId?.let { it to item } }.toMap()
        if (active == null) {
            val item = allByTag[tagId]
            if (item == null) {
                pendingOpenTagId = tagId
                _lastUnknownTag.value = tagId
                setMessage("发现未绑定标签，可直接新建物品：$tagId")
            } else {
                _openedItemEntityId.value = item.entityId
                setMessage("已识别并打开：${item.title}")
            }
            return
        }
        if (active.status != AuditStatus.IN_PROGRESS) {
            setMessage("盘点已暂停，请先恢复再扫描")
            return
        }
        val entityByTag = allByTag.filterValues { it.tag?.status == "active" }
        val observation = ScanObservation(tagId, method, auditId = active.auditId, rawHardwareIdHash = hardwareHash)
        val (updated, resolution) = AuditEngine.observe(active, observation, entityByTag)
        updateAudit(updated)
        when (resolution) {
            is ScanResolution.Matched -> {
                if (!resolution.duplicate) _scanPulse.value += 1
                if (!resolution.duplicate && resolution.entity.entityId in updated.quantityRequiredEntityIds) {
                    _pendingQuantityEntityId.value = resolution.entity.entityId
                }
                setMessage(if (resolution.duplicate) "重复扫描：${resolution.entity.title}" else "已确认：${resolution.entity.title}")
            }
            is ScanResolution.Unknown -> {
                _lastUnknownTag.value = resolution.tagId
                setMessage("本次发现额外未绑定标签：${resolution.tagId}")
            }
        }
    }

    fun addMissingReason(entityId: String, reason: MissingReason) {
        val current = _audit.value ?: return
        val updated = runCatching { AuditEngine.addException(current, AuditException(entityId, reason)) }
            .getOrElse { setMessage(it.message ?: "无法添加例外"); current }
        updateAudit(updated)
    }

    fun recordQuantity(entityId: String, quantity: Double) {
        val current = _audit.value ?: return
        val item = _items.value.firstOrNull { it.entityId == entityId } ?: return setMessage("找不到批次")
        val updated = runCatching { AuditEngine.recordQuantity(current, item, quantity) }
            .getOrElse { return setMessage(it.message ?: "无法记录数量") }
        updateAudit(updated)
        _pendingQuantityEntityId.value = null
        setMessage(if (entityId in updated.quantityMismatchEntityIds) "数量与预期不一致：${item.title}" else "已确认数量：${item.title}")
    }

    fun dismissQuantityInput() {
        _pendingQuantityEntityId.value = null
        setMessage("批次数量尚未确认，完成盘点前仍需补录")
    }

    fun pauseAudit() {
        val current = _audit.value ?: return
        updateAudit(runCatching { AuditEngine.pause(current) }.getOrElse { return setMessage(it.message ?: "无法暂停") })
        setMessage("盘点已暂停并保存，可稍后恢复")
    }

    fun resumeAudit() {
        val current = _audit.value ?: return
        updateAudit(runCatching { AuditEngine.resume(current) }.getOrElse { return setMessage(it.message ?: "无法恢复") })
        setMessage("盘点已恢复")
    }

    fun finishAudit(applyCorrections: Boolean = false) {
        val uri = treeUri ?: return setMessage("请先选择 Obsidian 库")
        val current = _audit.value ?: return
        val finished = runCatching { AuditEngine.finish(current) }
            .getOrElse { return setMessage(it.message ?: "无法完成盘点") }
        draftPersistJob?.cancel()
        _audit.value = finished
        viewModelScope.launch {
            runCatching { repository.appendAudit(uri, finished) }
                .onSuccess {
                    var updatedCards = 0
                    var failedCards = 0
                    finished.observedEntityIds.forEach { entityId ->
                        val item = _items.value.firstOrNull { record -> record.entityId == entityId }
                        if (item != null) {
                            runCatching { repository.applyAuditResult(uri, item, finished, applyCorrections) }
                                .onSuccess { updatedCards += 1 }
                                .onFailure { failedCards += 1 }
                        }
                    }
                    setMessage(buildString {
                        append("盘点已写入 ${it.relativePath}")
                        append("；核对状态回写 $updatedCards，失败 $failedCards")
                        if (applyCorrections) append("；已统一接受位置/数量变更候选")
                    })
                    _audit.value = null
                    _pendingQuantityEntityId.value = null
                    repository.deleteAuditDraft(finished.auditId)
                    refresh(uri)
                }
                .onFailure { setMessage("盘点写入失败：${it.message}") }
        }
    }

    fun cancelAudit() {
        val current = _audit.value ?: return
        draftPersistJob?.cancel()
        _audit.value = null
        viewModelScope.launch { repository.deleteAuditDraft(current.auditId) }
        setMessage("盘点已中止，未写入完成记录")
    }

    fun setMessage(value: String) { _message.value = value }

    fun consumeOpenedItem() { _openedItemEntityId.value = null }

    private fun updateAudit(snapshot: AuditSnapshot) {
        _audit.value = snapshot
        draftPersistJob?.cancel()
        draftPersistJob = viewModelScope.launch {
            runCatching { repository.saveAuditDraft(snapshot) }
                .onFailure { setMessage("盘点仍在内存中，但草稿保存失败：${it.message}") }
        }
    }

    private fun observeSelfDevice() {
        val id = selfDeviceEntityId ?: return
        val item = _items.value.firstOrNull { it.entityId == id } ?: return
        val tagId = item.tag?.tagId ?: return
        acceptTag(tagId, ScanMethod.SELF_DEVICE, null)
    }
}
