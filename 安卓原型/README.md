# 实体盘点安卓原型

原生 Android 应用，包名 `com.obsidiancodx.entityinventory`。应用把 `50_实体/` 中的 Markdown 视为唯一主档，Room 只保存可重建索引和未完成盘点。

## APK 发布位置

APK 与构建缓存不进入 Git。使用下面的构建命令生成调试安装包，
并将需要长期保存的发布文件单独存放。

## 已实现范围

- Android Storage Access Framework 选择 Obsidian 库；
- 递归索引 `50_实体/`，解析物品、批次和携带清单；
- 新建物品/批次 Markdown，并在写入前检查内容哈希；
- 录入名称、照片、分类、地点、容器、数量、详情和购买/资料链接；
- 物品页支持按名称、文件夹分类、地点和容器搜索；分类以 `物品/`、`批次/` 下的 Markdown 文件夹路径为准；
- 点击物品可选择或就地新增地点/容器；“整理”页使用单一连续纵向列表，两列宫格投放区与物品区随整个页面共同滚动；长按物品卡后保持选中，滑动到目标地点或容器并点击即可移动；
- 使用开箱物品插画作为传统与自适应 Android 启动器图标；
- 正式读取 `entityType: container` 的容器主档，并可在设置页新增容器；
- NFC NDEF 读写：第一条 `tel:`，第二条为共用 `tagId`；
- NFC 追加物品名称文本记录和 Android Application Record；安装本 App 的 Android 手机扫描后会打开对应物品详情；
- 二维码 vCard 生成与相机连续扫描；
- 二维码/NFC 统一观测、跨方式去重；
- 全量、地点、类别、容器、临时范围和携带清单冻结快照；
- 缺件例外、批次数量、差异分类、草稿暂停/恢复和追加式盘点记录；
- 扫描用手机的 `self_device` 观测入口；
- 为未来 UHF 保留 `ScanMethod.UHF`。

## 构建条件

- JDK 17；
- Android SDK Platform 35；
- Android Build Tools 35；
- 使用仓库内 Gradle Wrapper。

```powershell
.\scripts\build-windows.ps1
```

项目位于中文路径时，Android/Gradle 的 Windows 测试 worker 可能无法载入已经编译的测试类。`build-windows.ps1` 会临时映射一个纯 ASCII 盘符，完成测试与 APK 构建后自动还原 `local.properties` 并解除盘符。也可分别运行 `-Target test` 或 `-Target assembleDebug`。

当前电脑原先只有 JDK 11 且没有 Android SDK，因此项目同时提供 `scripts/bootstrap-android.ps1`，把命令行工具安装在本项目的 `.tooling/` 内，不修改系统级 Android Studio 配置。

## 首次使用

1. 安装 debug APK；
2. 在“设置”中选择 Obsidian 库根目录；
3. 确认能读取 `50_实体/README.md`；
4. 在 Obsidian 的 `50_实体/_系统/联系方式设置.md` 填写电话号码；
5. 新建物品后生成二维码，并进入 NFC 写入模式；写入内容包括联系电话、`tagId`、物品名称和 App 唤起记录；
6. 点击已有物品可修改地点和容器，也可直接新增地点/容器；批量整理可进入底部“整理”，长按物品卡使其保持选中，再滑动到顶部宫格投放区并点击目标；
7. 读回两种载荷一致后再激活标签。
