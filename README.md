# DesktopGridX v0.7.0

针对 HyperOS 4 `com.miui.home` 桌面构建的 LSPosed API 102 布局模块。

## 已完成

- 桌面列数：优先 `PreferenceUtils::get_int("pref_key_cell_x")` 上游注入。
- 桌面行数：优先 `PreferenceUtils::get_int("pref_key_cell_y")` 上游注入。
- Cell 宽度/横向间距：不额外 Hook；Launcher 原生算法会根据 `pref_key_cell_x`、屏幕方向和设备规则自行重算。
- Hotseat 最大数量：保留唯一验证过的 `DeviceConfigs::get_hotseat_max_count()` getter Hook。
- 图标大小：使用 Launcher 自己的 `Settings.System` 键 `icon_size_scale`（1-100），首次修改备份原值，恢复时还原。
- 自动 Hook 点定位：`.gnu_debugdata` Rust 符号 -> ARM64 特征扫描 -> 已知版本 RVA -> fail closed。
- 最早 Hook 时序：LSPosed `onModuleLoaded()` + ShadowHook dl-init pre callback。
- 可选 Preference key trace：只记录整数 key/返回值，不改未知键。

## v0.7.0 运行时自检

为了解决“自动定位成功，但无法证明 Runtime Hook 是否真正执行”的问题，Native 层新增持久化状态：

`/data/adb/desktopgridx/runtime-status.conf`

它会记录：

- Launcher PID / `libapp_launcher.so` base address
- 当前 resolver 来源
- 是否在 `.init/.init_array` 之前安装
- ShadowHook 初始化结果
- Preference Hook / X/Y fallback / Hotseat Hook 安装状态
- `pref_key_cell_x/y`、getter X/Y、Hotseat 和 icon preference 的实际命中次数
- 最近一次错误和当前执行阶段

App 首页新增 **运行时自检**，可直接读取并显示这些状态。保存新配置时会删除旧 runtime 状态，避免把上一个 Launcher 进程的结果误认为当前结果。

## 一键诊断

诊断 ZIP 现在固定包含 `03-runtime-status.txt`，同时收集 live `/proc/<launcher-pid>/maps` 中：

- `libdesktopgridx.so`
- `libshadowhook.so`
- `libapp_launcher.so`
- DesktopGridX APK 映射

并继续包含 Root/LSPosed/logcat/SELinux、Launcher APK/SO 指纹、`.gnu_debugdata`、ARM64 扫描、preferences、IconSizeProvider 与系统图标设置。

## 自动编译 Debug

GitHub Actions 已配置：push 到 `main` 自动编译，也支持手动触发；使用 JDK 17、Gradle 9.4.1、AGP 9.2.0、compileSdk/targetSdk 37，执行 `:app:assembleDebug`，APK 以 `DesktopGridX-debug` Artifact 上传并保留 14 天。

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

IconSizeProvider authority:

`content://com.miui.home.launcher.bigicon.iconsize`

## 推荐测试

不要用桌面当前原值做首次验证。建议暂时设置明显不同的值，例如 **6 × 8**，Hotseat 可设 6，图标大小可设 90；保存后强制停止桌面，返回桌面启动一次，再打开 DesktopGridX 点“运行时自检”。若仍异常，直接导出诊断 ZIP。

## 安全策略

不能唯一证明 Hook 点时不 Hook，不会盲用旧 RVA。文件夹内部网格当前仍不提供未经证明的 Hook；后续依靠 Preference trace 和诊断证据找到真实配置来源后再接入。
