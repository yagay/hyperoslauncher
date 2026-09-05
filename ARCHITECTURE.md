# DesktopGridX v0.6 Architecture

## Workspace

LSPosed API 102 `onModuleLoaded`
→ Native 初始化
→ 监听 `libapp_launcher.so`
→ ShadowHook PRE init
→ `PreferenceUtils::get_int`
→ 仅覆盖 `pref_key_cell_x/y`
→ Launcher `GridConfig`
→ `DeviceConfigs`
→ Workspace / Widget / cell width 原生计算

若 PRE-init 已错过或上游 Hook 安装失败：
→ `DeviceConfigs::get_cell_count_x/y` getter fallback。

## Cell width / spacing

当前构建 `compute_cell_width_px_by_orientation()` 在 `0x61F5D0`。
反汇编确认它直接调用 `PreferenceUtils::get_int("pref_key_cell_x")`，
随后使用屏幕长短边和 Launcher 原生规则计算 cell width。
因此模块**不 Hook cell width**，避免横竖屏、Pad/Fold 规则失配。

## Icon size

Launcher 自带 `IconSizeProvider`，Manifest authority:
`com.miui.home.launcher.bigicon.iconsize`。

Native 字符串与 Provider 路径确认：
`icon_size_scale`, `iconNewScale`, `iconMaxScale`, `iconMinScale`,
`convertIconSize`, `getIconLocation`，并存在 `Settings.System.putString` 调用路径。

DesktopGridX v0.6 对图标大小采用：
1. 备份 `settings get system icon_size_scale`
2. `settings put system icon_size_scale <1..100>`
3. Native Preference hook 仅对同名 key 做兼容补偿
4. reset 时恢复备份值

不使用 View scale、Resources dimension replacement。

## Hotseat

当前 `get_hotseat_max_count()` 是独立设备规则计算，未证明存在与 cell x/y 同等级的持久化 key。
因此继续单独 Hook getter，这是当前证据下风险最低的实现。

## Folder

当前 mini debug 符号仅看到 `query_folder_size` 等数据查询，没有可证明的
folder rows/columns 配置入口。v0.6 不猜测 ABI/字段，而是通过 Preference trace 与诊断包继续发现。
