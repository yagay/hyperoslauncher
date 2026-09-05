# DesktopGridX v0.6.0

针对你上传的 HyperOS 4 `com.miui.home` 桌面构建的 LSPosed API 102 布局模块。

## 已完成

- 桌面列数：优先 `PreferenceUtils::get_int("pref_key_cell_x")` 上游注入。
- 桌面行数：优先 `PreferenceUtils::get_int("pref_key_cell_y")` 上游注入。
- Cell 宽度/横向间距：不额外 Hook。已验证 Launcher 的
  `DeviceConfigs::compute_cell_width_px_by_orientation()` 会读取 `pref_key_cell_x`
  并按屏幕宽度、方向和设备规则自行计算，所以让原生算法工作更稳定。
- Hotseat 最大数量：保留唯一验证过的
  `DeviceConfigs::get_hotseat_max_count()` getter Hook。
- 图标大小：使用 Launcher 自己的 `Settings.System` 键 `icon_size_scale`（1-100），
  首次修改会备份原值，恢复 DesktopGridX 时还原。
- 自动 Hook 点定位：`.gnu_debugdata` Rust 符号 -> ARM64 特征扫描 -> 已知版本 RVA -> fail closed。
- 最早 Hook 时序：LSPosed `onModuleLoaded()` + ShadowHook dl-init pre callback。
- 一键诊断：Root/LSPosed/ShadowHook/logcat/maps/SELinux/Launcher APK & SO 指纹、
  `.gnu_debugdata`、ARM64 扫描、Launcher preferences、IconSizeProvider 与系统图标设置。
- 可选 Preference key trace：只记录最多 400 次整数 key/返回值，不改未知键。

## 自动编译 Debug

仓库已配置 GitHub Actions：

- push 到 `main` 自动编译
- 支持 `workflow_dispatch` 手动编译
- JDK 17
- Gradle 9.4.1
- Android Gradle Plugin 9.2.0
- compileSdk / targetSdk 37
- 执行 `:app:assembleDebug`
- APK 以 `DesktopGridX-debug` Artifact 上传并保留 14 天

## 当前上传桌面验证值

`libapp_launcher.so` SHA-256:

`433232fb60c439e0a2537264c58058212df229a2ffdcbdaf1092f38295e04b4f`

关键 RVA:

- `DeviceConfigs::get_cell_count_x` = `0x61E1F0`
- `DeviceConfigs::get_cell_count_y` = `0x61E29C`
- `DeviceConfigs::get_hotseat_max_count` = `0x61F334`
- `PreferenceUtils::get_int` = `0x634C28`
- `PreferenceUtils::put_int` = `0x634D3C`
- `DeviceConfigs::compute_cell_width_px_by_orientation` = `0x61F5D0`
- `IconSizeProvider::is_parameter_qualified` = `0x62C210`
- IconSizeProvider ContentProvider call = `0x6C3DAC`

Manifest 中的 IconSizeProvider authority:

`content://com.miui.home.launcher.bigicon.iconsize`

## 安全策略

不能唯一证明 Hook 点时不 Hook。不会为了兼容未知版本而盲用旧 RVA。

文件夹内部网格目前**不提供伪 Hook**：当前上传构建没有发现可可靠证明的 folder row/column
配置入口。模块会通过诊断 trace 自动收集未来版本真实 key；只有证明来源后才接入，避免桌面循环崩溃。
