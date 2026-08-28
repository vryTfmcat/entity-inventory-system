package com.obsidiancodx.entityinventory.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.obsidiancodx.entityinventory.MainViewModel
import com.obsidiancodx.entityinventory.core.AuditAction
import com.obsidiancodx.entityinventory.core.AuditScope
import com.obsidiancodx.entityinventory.core.AuditSnapshot
import com.obsidiancodx.entityinventory.core.AuditStatus
import com.obsidiancodx.entityinventory.core.ContainerRecord
import com.obsidiancodx.entityinventory.core.InventoryRecord
import com.obsidiancodx.entityinventory.core.LoadoutRecord
import com.obsidiancodx.entityinventory.core.MissingReason
import com.obsidiancodx.entityinventory.core.PlaceRecord
import com.obsidiancodx.entityinventory.core.TagPayloadCodec
import com.obsidiancodx.entityinventory.scanner.NfcController
import com.obsidiancodx.entityinventory.scanner.QrScannerView
import java.io.ByteArrayOutputStream

private enum class Page(val title: String) { INVENTORY("物品"), ORGANIZE("整理"), AUDIT("盘点"), LOADOUT("携带"), SETTINGS("设置") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityInventoryApp(
    viewModel: MainViewModel,
    nfcController: NfcController,
    playSuccessTone: () -> Unit
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val loadouts by viewModel.loadouts.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val containers by viewModel.containers.collectAsStateWithLifecycle()
    val audit by viewModel.audit.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val scanPulse by viewModel.scanPulse.collectAsStateWithLifecycle()
    val openedItemEntityId by viewModel.openedItemEntityId.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.INVENTORY) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<InventoryRecord?>(null) }
    var tagItem by remember { mutableStateOf<InventoryRecord?>(null) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(scanPulse) {
        if (scanPulse > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            playSuccessTone()
        }
    }

    LaunchedEffect(openedItemEntityId, items) {
        val id = openedItemEntityId ?: return@LaunchedEffect
        items.firstOrNull { it.entityId == id }?.let {
            page = Page.INVENTORY
            selectedItem = it
            viewModel.consumeOpenedItem()
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (showQr) {
                Box(Modifier.fillMaxSize()) {
                    QrScannerView(onRawCode = viewModel::acceptQr)
                    Button(
                        onClick = { showQr = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) { Text("关闭") }
                }
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("实体盘点 · ${page.title}") },
                            actions = {
                                if (busy) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                                IconButton(onClick = { viewModel.refresh() }) { Text("↻") }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(page == Page.INVENTORY, { page = Page.INVENTORY }, { Icon(Icons.Default.Inventory2, null) }, label = { Text("物品") })
                            NavigationBarItem(page == Page.ORGANIZE, { page = Page.ORGANIZE }, { Icon(Icons.Default.Inventory2, null) }, label = { Text("整理") })
                            NavigationBarItem(page == Page.AUDIT, { page = Page.AUDIT }, { Icon(Icons.Default.Checklist, null) }, label = { Text("盘点") })
                            NavigationBarItem(page == Page.LOADOUT, { page = Page.LOADOUT }, { Icon(Icons.Default.Luggage, null) }, label = { Text("携带") })
                            NavigationBarItem(page == Page.SETTINGS, { page = Page.SETTINGS }, { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
                        }
                    },
                    floatingActionButton = {
                        if (page == Page.INVENTORY) {
                            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "新建物品") }
                        }
                    }
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        MessageBar(message)
                        when (page) {
                            Page.INVENTORY -> InventoryPage(items, onSelect = { selectedItem = it })
                            Page.ORGANIZE -> OrganizePage(items, places, containers, viewModel)
                            Page.AUDIT -> AuditPage(items, audit, viewModel, onQr = { showQr = true })
                            Page.LOADOUT -> LoadoutPage(items, loadouts, places, viewModel, onQr = { showQr = true })
                            Page.SETTINGS -> SettingsPage(items, places, containers, viewModel, nfcController)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) AddItemDialog(
        defaultTagId = viewModel.lastUnknownTag.collectAsStateWithLifecycle().value,
        places = places,
        onDismiss = { showAdd = false },
        onCreate = { title, category, batch, quantity, unit, place, container, details, purchaseLink, photoUri ->
            viewModel.createItem(title, category, batch, quantity, unit, place, container, details, purchaseLink, photoUri)
            showAdd = false
        }
    )
    selectedItem?.let { item ->
        ItemPlacementDialog(
            item = item,
            places = places,
            containers = containers,
            onDismiss = { selectedItem = null },
            onSave = { place, container ->
                viewModel.updateItemPlacement(item, place, container)
                selectedItem = null
            },
            onCreatePlace = viewModel::createPlace,
            onCreateContainer = viewModel::createContainer,
            onOpenTag = {
                tagItem = item
                selectedItem = null
            }
        )
    }
    tagItem?.let { item ->
        ItemTagDialog(
            item = item,
            phone = settings.recoveryPhone,
            nfcController = nfcController,
            onDismiss = { tagItem = null },
            onMessage = viewModel::setMessage,
            onPrepareTag = { record ->
                viewModel.prepareTag(record)
                tagItem = null
            },
            onSaveQr = { record, png -> viewModel.saveQr(record, png) }
        )
    }
}

@Composable
private fun MessageBar(message: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun InventoryPage(items: List<InventoryRecord>, onSelect: (InventoryRecord) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("还没有物品。点击右下角开始录入。") }
        return
    }
    val filtered = items.filter { item ->
        query.isBlank() || listOf(item.title, item.category, item.currentPlace, item.container)
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    val grouped = filtered.groupBy { it.category.ifBlank { "未分类" } }.toSortedMap()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索物品、分类、地点或容器") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Text("分类以 50_实体/物品 或 50_实体/批次下的文件夹路径为准", Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (category, categoryItems) ->
                item(key = "category:$category") {
                    Text(category, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                items(categoryItems, key = { it.entityId }) { item ->
                    Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clickable { onSelect(item) }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item.title, fontWeight = FontWeight.SemiBold)
                                Text(item.tag?.status ?: "unbound", color = MaterialTheme.colorScheme.primary)
                            }
                            Text(listOf(item.currentPlace, item.container).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "尚未设置位置" })
                            if (item.quantity != null) Text("${item.quantity} ${item.unit.orEmpty()} · ${item.countMode}")
                        }
                    }
                }
            }
            if (filtered.isEmpty()) item { Text("没有匹配的物品", Modifier.padding(24.dp)) }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

private enum class OrganizeTarget { PLACE, CONTAINER }
private data class OrganizeDropTarget(val key: String, val title: String, val subtitle: String)

@Composable
private fun OrganizePage(
    items: List<InventoryRecord>,
    places: List<PlaceRecord>,
    containers: List<ContainerRecord>,
    viewModel: MainViewModel
) {
    var mode by rememberSaveable { mutableStateOf(OrganizeTarget.PLACE) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedEntityId by rememberSaveable { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val filtered = items.filter {
        query.isBlank() || listOf(it.title, it.category, it.currentPlace, it.container)
            .any { value -> value.contains(query.trim(), ignoreCase = true) }
    }
    val dropTargets = when (mode) {
        OrganizeTarget.PLACE -> places.map {
            OrganizeDropTarget("p:${it.placeId}", it.title, it.parentPlace.ifBlank { "顶层地点" })
        }
        OrganizeTarget.CONTAINER -> containers.map {
            OrganizeDropTarget("c:${it.containerId}", it.title, it.place.ifBlank { "尚未设置地点" })
        }
    }
    val selectedItem = selectedEntityId?.let { entityId -> items.firstOrNull { it.entityId == entityId } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "organize-instructions") {
            Text("长按物品卡将其选中，再滑动到投放区并点击地点或容器。移动到地点会清空原容器；移动到容器会同时采用该容器的地点。")
        }
        item(key = "organize-mode") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { mode = OrganizeTarget.PLACE }, label = { Text(if (mode == OrganizeTarget.PLACE) "✓ 地点" else "地点") })
                AssistChip(onClick = { mode = OrganizeTarget.CONTAINER }, label = { Text(if (mode == OrganizeTarget.CONTAINER) "✓ 容器" else "容器") })
            }
        }
        item(key = "organize-drop-title") {
            Text(
                selectedItem?.let { "已选择“${it.title}”，点击目标区域完成移动" } ?: "投放区（请先长按选择物品）",
                fontWeight = FontWeight.SemiBold,
                color = if (selectedItem == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
            )
        }
        items(
            items = dropTargets.chunked(2),
            key = { row -> "drop-row:${row.joinToString("|") { it.key }}" }
        ) { targetRow ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targetRow.forEach { target ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 88.dp)
                            .clickable(enabled = selectedItem != null) {
                                selectedItem?.let { item ->
                                    when {
                                        target.key.startsWith("p:") -> places.firstOrNull { "p:${it.placeId}" == target.key }
                                            ?.let { viewModel.moveItemToPlace(item, it) }
                                        target.key.startsWith("c:") -> containers.firstOrNull { "c:${it.containerId}" == target.key }
                                            ?.let { viewModel.moveItemToContainer(item, it) }
                                    }
                                    selectedEntityId = null
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedItem != null) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        border = BorderStroke(
                            width = if (selectedItem != null) 2.dp else 1.dp,
                            color = if (selectedItem != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(target.title, fontWeight = FontWeight.SemiBold)
                            Text(target.subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (targetRow.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (dropTargets.isEmpty()) item(key = "organize-no-target") {
            Text("当前没有可用目标，请先在物品详情或设置中新增。")
        }
        item(key = "organize-search") {
            OutlinedTextField(
                query, { query = it }, label = { Text("搜索待整理物品") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        selectedItem?.let { item ->
            item(key = "organize-selection-status") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("已选中：${item.title}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { selectedEntityId = null }) { Text("取消选择") }
                }
            }
        }
        items(filtered, key = { it.entityId }) { item ->
            val isSelected = selectedEntityId == item.entityId
            Card(
                Modifier.fillMaxWidth()
                    .pointerInput(item.entityId) {
                        detectTapGestures(
                            onLongPress = {
                                selectedEntityId = item.entityId
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setMessage("已选择 ${item.title}，请点击目标地点或容器")
                            }
                        )
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                ),
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold)
                    Text("${item.category.ifBlank { "未分类" }} · ${item.currentPlace.ifBlank { "未设置地点" }} · ${item.container.ifBlank { "未设置容器" }}")
                }
            }
        }
        if (filtered.isEmpty()) item(key = "organize-no-items") { Text("没有匹配的物品", Modifier.padding(24.dp)) }
        item(key = "organize-bottom-space") { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ItemPlacementDialog(
    item: InventoryRecord,
    places: List<PlaceRecord>,
    containers: List<ContainerRecord>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onCreatePlace: (String, String, Double?, Double?) -> Unit,
    onCreateContainer: (String, String, String, String) -> Unit,
    onOpenTag: () -> Unit
) {
    var place by remember(item.entityId) { mutableStateOf(item.currentPlace) }
    var container by remember(item.entityId) { mutableStateOf(item.container) }
    var showNewPlace by remember { mutableStateOf(false) }
    var newPlace by remember { mutableStateOf("") }
    var newPlaceParent by remember { mutableStateOf(item.currentPlace) }
    var showNewContainer by remember { mutableStateOf(false) }
    var newContainer by remember { mutableStateOf("") }
    var newContainerType by remember { mutableStateOf("other") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column(Modifier.height(520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分类：${item.category.ifBlank { "未分类" }}（由文件夹路径决定）")
                OutlinedTextField(place, { place = it }, label = { Text("当前位置") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    places.forEach { record -> AssistChip(onClick = { place = record.title }, label = { Text(record.title) }) }
                }
                TextButton(onClick = { showNewPlace = !showNewPlace }) { Text("＋ 在这里添加地点") }
                if (showNewPlace) {
                    OutlinedTextField(newPlace, { newPlace = it }, label = { Text("新地点名称") })
                    OutlinedTextField(newPlaceParent, { newPlaceParent = it }, label = { Text("父地点，可留空") })
                    Button(onClick = {
                        onCreatePlace(newPlace.trim(), newPlaceParent.trim(), null, null)
                        place = newPlace.trim()
                        newPlace = ""
                        showNewPlace = false
                    }, enabled = newPlace.isNotBlank()) { Text("创建并选中地点") }
                }
                OutlinedTextField(container, { container = it }, label = { Text("所在容器") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    containers.forEach { record -> AssistChip(onClick = {
                        container = record.title
                        if (record.place.isNotBlank()) place = record.place
                    }, label = { Text(record.title) }) }
                }
                TextButton(onClick = { showNewContainer = !showNewContainer }) { Text("＋ 在这里添加容器") }
                if (showNewContainer) {
                    OutlinedTextField(newContainer, { newContainer = it }, label = { Text("新容器名称") })
                    OutlinedTextField(newContainerType, { newContainerType = it }, label = { Text("容器类型，如 box / drawer") })
                    Button(onClick = {
                        onCreateContainer(newContainer.trim(), newContainerType.trim(), place.trim(), "")
                        container = newContainer.trim()
                        newContainer = ""
                        showNewContainer = false
                    }, enabled = newContainer.isNotBlank()) { Text("创建并选中容器") }
                }
                OutlinedButton(onClick = onOpenTag, modifier = Modifier.fillMaxWidth()) { Text("二维码与 NFC 标签") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(place.trim(), container.trim()) }) { Text("保存位置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AuditPage(
    items: List<InventoryRecord>,
    audit: AuditSnapshot?,
    viewModel: MainViewModel,
    onQr: () -> Unit
) {
    val pendingQuantityEntityId by viewModel.pendingQuantityEntityId.collectAsStateWithLifecycle()
    if (audit == null) {
        var showAdhoc by rememberSaveable { mutableStateOf(false) }
        val places = items.map { it.currentPlace }.filter { it.isNotBlank() }.distinct().sorted()
        val categories = items.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
        val containers = items.map { it.container }.filter { it.isNotBlank() }.distinct().sorted()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("对全部有效物品冻结快照，之后可在 NFC 与二维码之间切换。")
            Button(onClick = { viewModel.startAllAudit() }, enabled = items.isNotEmpty()) { Text("开始全量盘点") }
            OutlinedButton(onClick = { showAdhoc = true }, enabled = items.isNotEmpty()) { Text("临时选择范围") }
            ScopeChips("按地点", places) { viewModel.startFilteredAudit(AuditScope.PLACE, it) }
            ScopeChips("按类别", categories) { viewModel.startFilteredAudit(AuditScope.CATEGORY, it) }
            ScopeChips("按容器", containers) { viewModel.startFilteredAudit(AuditScope.CONTAINER, it) }
        }
        if (showAdhoc) AdhocAuditDialog(items, { showAdhoc = false }) {
            viewModel.startFilteredAudit(AuditScope.ADHOC, selectedIds = it)
            showAdhoc = false
        }
        return
    }
    val missing = audit.missingEntityIds.mapNotNull { id -> items.firstOrNull { it.entityId == id } }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${audit.observations.size} / ${audit.expectedEntityIds.size} 已确认", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onQr, enabled = audit.status == AuditStatus.IN_PROGRESS) { Icon(Icons.Default.QrCodeScanner, null); Text("二维码") }
            AssistChip(onClick = {}, label = { Text("NFC 持续监听") })
        }
        Text("未确认 ${missing.size} · 位置异常 ${audit.misplacedEntityIds.size} · 数量异常 ${audit.quantityMismatchEntityIds.size} · 额外 ${audit.extraEntityIds.size}")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(missing, key = { it.entityId }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(item.title, fontWeight = FontWeight.Medium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ReasonChip("留在此处", item.entityId, MissingReason.LEFT_HERE, viewModel)
                            ReasonChip("临时不用", item.entityId, MissingReason.NOT_NEEDED, viewModel)
                            ReasonChip("无法扫描", item.entityId, MissingReason.UNSCANNABLE, viewModel)
                            ReasonChip("确认丢失", item.entityId, MissingReason.LOST, viewModel)
                            ReasonChip("其他", item.entityId, MissingReason.OTHER, viewModel)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (audit.status == AuditStatus.PAUSED) {
                Button(onClick = viewModel::resumeAudit) { Text("恢复") }
            } else {
                OutlinedButton(onClick = viewModel::pauseAudit) { Text("暂停") }
                Button(onClick = { viewModel.finishAudit(false) }) { Text("完成并写入") }
            }
            OutlinedButton(onClick = viewModel::cancelAudit) { Text("中止") }
        }
        if (audit.status == AuditStatus.IN_PROGRESS &&
            (audit.misplacedEntityIds.isNotEmpty() || audit.quantityMismatchEntityIds.isNotEmpty())) {
            Button(onClick = { viewModel.finishAudit(true) }, modifier = Modifier.fillMaxWidth()) {
                Text("完成并接受位置 ${audit.misplacedEntityIds.size} / 数量 ${audit.quantityMismatchEntityIds.size} 项变更")
            }
        }
    }
    pendingQuantityEntityId?.let { entityId ->
        items.firstOrNull { it.entityId == entityId }?.let { item ->
            QuantityDialog(
                item = item,
                onDismiss = viewModel::dismissQuantityInput,
                onConfirm = { viewModel.recordQuantity(entityId, it) }
            )
        }
    }
}

@Composable
private fun QuantityDialog(item: InventoryRecord, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var quantity by remember(item.entityId) { mutableStateOf(item.quantity?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认批次数量 · ${item.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("扫描只确认批次存在；请输入现场数量。台账预期为 ${item.quantity ?: "未设置"} ${item.unit.orEmpty()}。")
                OutlinedTextField(quantity, { quantity = it }, label = { Text("当前数量") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(quantity.toDouble()) }, enabled = quantity.toDoubleOrNull()?.let { it >= 0 } == true) {
                Text("记录")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后补录") } }
    )
}

@Composable
private fun ScopeChips(title: String, values: List<String>, onSelect: (String) -> Unit) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { value -> AssistChip(onClick = { onSelect(value) }, label = { Text(value) }) }
        }
    }
}

@Composable
private fun AdhocAuditDialog(items: List<InventoryRecord>, onDismiss: () -> Unit, onStart: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("临时盘点范围") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                items(items, key = { it.entityId }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (item.entityId in selected) selected - item.entityId else selected + item.entityId
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(item.entityId in selected, null)
                        Text(item.title)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(selected) }, enabled = selected.isNotEmpty()) { Text("开始") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ReasonChip(label: String, entityId: String, reason: MissingReason, viewModel: MainViewModel) {
    AssistChip(onClick = { viewModel.addMissingReason(entityId, reason) }, label = { Text(label) })
}

@Composable
private fun LoadoutPage(
    items: List<InventoryRecord>,
    loadouts: List<LoadoutRecord>,
    places: List<PlaceRecord>,
    viewModel: MainViewModel,
    onQr: () -> Unit
) {
    val audit by viewModel.audit.collectAsStateWithLifecycle()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<Pair<LoadoutRecord, AuditAction>?>(null) }
    if (audit != null) {
        AuditPage(items, audit, viewModel, onQr)
        return
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { showCreate = true }, enabled = items.isNotEmpty()) { Text("新建携带清单") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(loadouts, key = { it.loadoutId }) { loadout ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(loadout.title, fontWeight = FontWeight.SemiBold)
                        Text("必带 ${loadout.requiredEntityIds.size} · 可选 ${loadout.optionalEntityIds.size}")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip({ pendingStart = loadout to AuditAction.DEPART }, label = { Text("出发") })
                            AssistChip({ pendingStart = loadout to AuditAction.ARRIVE }, label = { Text("到达") })
                            AssistChip({ pendingStart = loadout to AuditAction.LEAVE }, label = { Text("离开") })
                            AssistChip({ pendingStart = loadout to AuditAction.RECHECK }, label = { Text("中途复核") })
                        }
                    }
                }
            }
        }
    }
    if (showCreate) CreateLoadoutDialog(items, { showCreate = false }) { title, required, optional, defaultContainer ->
        viewModel.createLoadout(title, required, optional, defaultContainer)
        showCreate = false
    }
    pendingStart?.let { (loadout, action) ->
        LoadoutStartDialog(loadout, action, places, onDismiss = { pendingStart = null }) { from, to ->
            viewModel.startLoadout(loadout, action, from, to)
            pendingStart = null
        }
    }
}

@Composable
private fun LoadoutStartDialog(
    loadout: LoadoutRecord,
    action: AuditAction,
    places: List<PlaceRecord>,
    onDismiss: () -> Unit,
    onStart: (String, String) -> Unit
) {
    var fromPlace by remember { mutableStateOf("") }
    var toPlace by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${loadout.title} · ${action.name.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(fromPlace, { fromPlace = it }, label = { Text("起点") })
                if (places.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        places.forEach { place -> AssistChip({ fromPlace = place.title }, label = { Text(place.title) }) }
                    }
                }
                OutlinedTextField(toPlace, { toPlace = it }, label = { Text("终点") })
                if (places.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        places.forEach { place -> AssistChip({ toPlace = place.title }, label = { Text(place.title) }) }
                    }
                }
                Text("地点由你手选；应用不会自动定位或触发盘点。")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(fromPlace.trim(), toPlace.trim()) },
                enabled = fromPlace.isNotBlank() && toPlace.isNotBlank()
            ) { Text("冻结快照并开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SettingsPage(
    items: List<InventoryRecord>,
    places: List<PlaceRecord>,
    containers: List<ContainerRecord>,
    viewModel: MainViewModel,
    nfcController: NfcController
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.selectVault(uri)
    }
    var showPlace by rememberSaveable { mutableStateOf(false) }
    var showContainer by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Obsidian 主档", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { picker.launch(null) }) { Text("选择 Obsidian 库") }
        Text(viewModel.treeUri?.toString() ?: "未选择")
        Text("NFC：${if (!nfcController.available) "设备不支持" else if (!nfcController.enabled) "未开启" else "持续监听"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { showPlace = true }) { Text("新建地点") }
            Text("已有 ${places.size} 个地点")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { showContainer = true }) { Text("新建容器") }
            Text("已有 ${containers.size} 个容器")
        }
        Text("扫描用手机", style = MaterialTheme.typography.titleMedium)
        Text("选择一件物品作为本机后，每次盘点会以 self_device 自动确认。")
        LazyColumn(Modifier.height(240.dp)) {
            items(items, key = { it.entityId }) { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.selfDeviceEntityId = item.entityId }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = viewModel.selfDeviceEntityId == item.entityId, onClick = { viewModel.selfDeviceEntityId = item.entityId })
                    Text(item.title)
                }
            }
        }
    }
    if (showPlace) CreatePlaceDialog(
        places = places,
        onDismiss = { showPlace = false },
        onCreate = { title, parent, lat, lon ->
            viewModel.createPlace(title, parent, lat, lon)
            showPlace = false
        },
        onMessage = viewModel::setMessage
    )
    if (showContainer) CreateContainerDialog(
        places = places,
        containers = containers,
        onDismiss = { showContainer = false },
        onCreate = { title, type, place, parent ->
            viewModel.createContainer(title, type, place, parent)
            showContainer = false
        }
    )
}

@Composable
private fun AddItemDialog(
    defaultTagId: String?,
    places: List<PlaceRecord>,
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean, Double?, String?, String, String, String, String, String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf(false) }
    var quantity by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("件") }
    var currentPlace by remember { mutableStateOf("") }
    var container by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var purchaseLink by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        photoUri = uri?.toString()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (defaultTagId == null) "新建物品" else "绑定未识别标签") },
        text = {
            Column(
                Modifier.height(480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("名称") })
                OutlinedTextField(category, { category = it }, label = { Text("分类，可用 / 分层") })
                OutlinedTextField(currentPlace, { currentPlace = it }, label = { Text("当前位置") })
                if (places.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        places.forEach { place -> AssistChip({ currentPlace = place.title }, label = { Text(place.title) }) }
                    }
                }
                OutlinedTextField(container, { container = it }, label = { Text("所在容器") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(batch, { batch = it }); Text("这是批次")
                }
                if (batch) {
                    OutlinedTextField(quantity, { quantity = it }, label = { Text("数量") })
                    OutlinedTextField(unit, { unit = it }, label = { Text("单位") })
                }
                OutlinedTextField(details, { details = it }, label = { Text("详情/识别信息") })
                OutlinedTextField(purchaseLink, { purchaseLink = it }, label = { Text("购买记录或资料链接") })
                OutlinedButton(onClick = { photoPicker.launch("image/*") }) {
                    Text(if (photoUri == null) "选择照片" else "已选照片，点击更换")
                }
                if (defaultTagId != null) Text("将绑定 $defaultTagId")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        title.trim(), category.trim(), batch, quantity.toDoubleOrNull(), unit,
                        currentPlace.trim(), container.trim(), details.trim(), purchaseLink.trim(), photoUri
                    )
                },
                enabled = title.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ItemTagDialog(
    item: InventoryRecord,
    phone: String,
    nfcController: NfcController,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onPrepareTag: (InventoryRecord) -> Unit,
    onSaveQr: (InventoryRecord, ByteArray) -> Unit
) {
    val tagId = item.tag?.tagId
    val bitmap = remember(tagId, phone) {
        if (tagId == null || phone.isBlank()) null else runCatching {
            TagPayloadCodec.createQrBitmap(TagPayloadCodec.createVCard(tagId, phone), 640)
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), "物品二维码", Modifier.size(260.dp))
                else Text("请先填写联系电话并为物品生成 tagId")
                Text(tagId ?: "未绑定")
                if (bitmap != null) {
                    OutlinedButton(onClick = {
                        val output = ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                        onSaveQr(item, output.toByteArray())
                    }) { Text("保存二维码 PNG") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (tagId == null) onPrepareTag(item)
                    else runCatching { nfcController.armWrite(tagId, phone, item.title) }.onFailure { onMessage(it.message ?: "无法写入 NFC") }
                },
                enabled = tagId == null || phone.isNotBlank()
            ) { Text(if (tagId == null) "生成 tagId" else "写入 NFC") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun CreateLoadoutDialog(
    items: List<InventoryRecord>,
    onDismiss: () -> Unit,
    onCreate: (String, Set<String>, Set<String>, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var required by remember { mutableStateOf(emptySet<String>()) }
    var optional by remember { mutableStateOf(emptySet<String>()) }
    var defaultContainer by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建携带清单") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("名称") })
                OutlinedTextField(defaultContainer, { defaultContainer = it }, label = { Text("默认容器/背包") })
                Text("点击物品依次切换：未选 → 必带 → 可选 → 未选。", Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.height(300.dp)) {
                    items(items, key = { it.entityId }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                when {
                                    item.entityId in required -> {
                                        required -= item.entityId
                                        optional += item.entityId
                                    }
                                    item.entityId in optional -> optional -= item.entityId
                                    else -> required += item.entityId
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(item.entityId in required, null)
                            Text("${item.title} · ${when (item.entityId) { in required -> "必带"; in optional -> "可选"; else -> "未选" }}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title.trim(), required, optional, defaultContainer.trim()) },
                enabled = title.isNotBlank() && required.isNotEmpty()
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CreatePlaceDialog(
    places: List<PlaceRecord>,
    onDismiss: () -> Unit,
    onCreate: (String, String, Double?, Double?) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    val capture: () -> Unit = {
        captureCurrentLocation(context, onResult = { lat, lon ->
            latitude = lat.toString()
            longitude = lon.toString()
        }, onError = onMessage)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) capture() else onMessage("未授予定位权限，仍可创建不含坐标的地点")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建地点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("地点名称") })
                OutlinedTextField(parent, { parent = it }, label = { Text("父地点，可留空") })
                if (places.isNotEmpty()) Text("已有：${places.take(4).joinToString { it.title }}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(latitude, { latitude = it }, label = { Text("纬度") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(longitude, { longitude = it }, label = { Text("经度") }, modifier = Modifier.weight(1f))
                }
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) capture()
                    else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }) { Text("主动读取当前 GPS") }
                Text("房间、宿舍、酒店等地点不需要坐标。")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title.trim(), parent.trim(), latitude.toDoubleOrNull(), longitude.toDoubleOrNull()) },
                enabled = title.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CreateContainerDialog(
    places: List<PlaceRecord>,
    containers: List<ContainerRecord>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("other") }
    var place by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建容器") },
        text = {
            Column(Modifier.height(420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("容器名称") })
                OutlinedTextField(type, { type = it }, label = { Text("类型，如 box / drawer / shelf-level") })
                OutlinedTextField(place, { place = it }, label = { Text("所在地点") })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    places.forEach { record -> AssistChip(onClick = { place = record.title }, label = { Text(record.title) }) }
                }
                OutlinedTextField(parent, { parent = it }, label = { Text("上级容器，可留空") })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    containers.forEach { record -> AssistChip(onClick = { parent = record.title }, label = { Text(record.title) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title.trim(), type.trim(), place.trim(), parent.trim()) }, enabled = title.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun captureCurrentLocation(
    context: Context,
    onResult: (Double, Double) -> Unit,
    onError: (String) -> Unit
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        onError("没有定位权限")
        return
    }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = when {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> return onError("没有可用的定位提供方")
    }
    LocationManagerCompat.getCurrentLocation(
        manager,
        provider,
        CancellationSignal(),
        ContextCompat.getMainExecutor(context)
    ) { location ->
        if (location == null) onError("暂时无法取得当前位置")
        else onResult(location.latitude, location.longitude)
    }
}
