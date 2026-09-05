# DesktopGridX v0.10.0

针对 HyperOS 4 `com.miui.home` 的 LSPosed API 102 桌面布局模块。

## v0.10 Loader Compatibility

- **Modern Native Entry 主入口**：`META-INF/xposed/native_init.list -> libdesktopgridx.so -> native_init()`。
- Java `HookEntry` 改为**无条件早加载 Native 的兼容兜底**：只要 LSPosed 进入 Java 生命周期，就立即 `System.loadLibrary("desktopgridx")`，不再依赖 Java 层的 `processName == com.miui.home` 判断。
- Native 侧自行判断/等待 `libapp_launcher.so`，兼容 HyperOS 4 的 `hyos_spawner` 启动模型。
- Native 库改为**未压缩 ZIP_STORED** 打包，便于 Modern Xposed 直接从 APK 映射/加载。
- GitHub Actions 会强制验证 `java_init.list`、`native_init.list`、`module.prop`、`scope.list`、`libdesktopgridx.so`、`native_init` 导出以及 `.so` 的 ZIP_STORED 压缩模式。
- 诊断 logcat 改为固定条数窗口，避免整机日志过大导致超时；LSPosed 日志优先只抓 module/verbose/logcat 文件。

## v0.9 稳定性基础

- ShadowHook 使用 **unique mode**。
- Hook 安装事务化：任一目标失败会回滚本轮已经成功的 Hook。
- 安装状态机：`not_started / installing / waiting_library / installed / failed_retryable / failed_permanent`。
- Getter proxy 不再使用硬编码默认值掩盖异常。
- `PreferenceUtils::get_int` 只有 ARM64 特征完整验证后才启用 Rust scalar-pair ABI bridge，否则自动回退 Getter。
- Runtime 状态线程安全，并写到 Launcher 自己的 cache。

## 布局能力

- 桌面列数：优先 `PreferenceUtils::get_int("pref_key_cell_x")`。
- 桌面行数：优先 `PreferenceUtils::get_int("pref_key_cell_y")`。
- 必要时自动回退 `DeviceConfigs::get_cell_count_x/y`。
- Hotseat：`DeviceConfigs::get_hotseat_max_count()`。
- 图标大小：Launcher 原生 `Settings.System icon_size_scale`。
- Cell width / 横向间距继续交给 Launcher 原生算法按列数重算。

## 自动定位与安全验证

顺序：`.gnu_debugdata` Rust 符号 → X/Y 结构关系验证 → Native ARM64 pattern → Preference ABI gate → Runtime signature fallback → 已验证 RVA profile → 无法唯一证明则 fail closed。

最新分析版本关键 RVA：

- `get_cell_count_x` = `0x61EB2C`
- `get_cell_count_y` = `0x61EBD8`
- `get_hotseat_max_count` = `0x61FC70`
- `PreferenceUtils::get_int` = `0x635564`

## Runtime 自检

Native Entry 状态优先写到 Launcher cache：

- `/data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf`
- `/data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf`
- `/data/data/com.miui.home/cache/desktopgridx-native-runtime.conf`

记录 Native Entry、PID、Launcher base、resolver、安装状态、ShadowHook、各 Hook 状态、命中次数与最后错误。

## GitHub Actions

每次 push 到 `main` 自动执行：JDK 17 + Gradle 9.4.1 → `:app:assembleDebug` → Xposed loader/Native packaging 验证 → 上传 `DesktopGridX-debug` Artifact。

构建环境：AGP 9.2.0，compileSdk/targetSdk 37，arm64-v8a。

## 安全原则

宁可不 Hook，也不使用无法证明的地址或 ABI。文件夹网格等尚未证明真实上游来源的功能继续保持关闭。
