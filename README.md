# DesktopGridX v0.9.0

针对 HyperOS 4 `com.miui.home` 的 LSPosed API 102 桌面布局模块。

## v0.9 架构

- **Modern Native Entry 主入口**：`META-INF/xposed/native_init.list -> libdesktopgridx.so -> native_init()`。
- Java `HookEntry` 仅保留兼容兜底，不再是核心运行路径。
- ShadowHook 改为 **unique mode**，避免 shared mode 下直接使用 `orig_addr` 的语义风险。
- Hook 安装改为**事务化**：任意目标失败会回滚本轮已经成功的 Hook，不允许留下半修改状态。
- 安装状态机：`not_started / installing / waiting_library / installed / failed_retryable / failed_permanent`，防止重复 Hook。
- Getter proxy 只有在原 trampoline 已验证存在时才允许安装；不再用 5 / -1 等硬编码默认值掩盖异常。
- `PreferenceUtils::get_int` 只有 ARM64 特征完整验证后才启用 Rust scalar-pair ABI 桥接，否则自动退回 Getter 路径。
- Runtime 状态加锁并写入 Launcher 自己 cache，不依赖 `/data/adb` 的进程写权限。

## 布局能力

- 桌面列数：优先拦截 `PreferenceUtils::get_int("pref_key_cell_x")`。
- 桌面行数：优先拦截 `PreferenceUtils::get_int("pref_key_cell_y")`。
- 如果安装太晚或 Preference ABI 未验证，自动启用 `DeviceConfigs::get_cell_count_x/y` Getter fallback。
- Hotseat：`DeviceConfigs::get_hotseat_max_count()`。
- 图标大小：使用 Launcher 自己的 `Settings.System icon_size_scale`。
- Cell width / 横向间距继续交给 Launcher 原生算法根据列数重算。

## 自动定位与安全验证

优先级：

1. `.gnu_debugdata` Rust 符号
2. X/Y 结构关系校验（`Y-X = 0xAC`）
3. Native ARM64 live pattern 校验
4. Preference ABI pattern 校验
5. Runtime ARM64 signature fallback
6. 已验证 RVA profile（每个 RVA 仍必须 live pattern 验证）
7. 无法唯一证明则 fail closed，不 Hook

当前已记录并 live-verify 的 Launcher RVA profile 包括：

- `1c8ad848...`
- `433232fb...`
- `1477db56...`

最新分析版本关键 RVA：

- `get_cell_count_x` = `0x61EB2C`
- `get_cell_count_y` = `0x61EBD8`
- `get_hotseat_max_count` = `0x61FC70`
- `PreferenceUtils::get_int` = `0x635564`

## Runtime 自检

Native Entry 状态写到 Launcher 自己的 cache：

- `/data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf`
- `/data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf`
- `/data/data/com.miui.home/cache/desktopgridx-native-runtime.conf`

记录：Native Entry 是否进入、PID、Launcher base、resolver、安装状态、ShadowHook、各 Hook 状态、命中次数与最后错误。

App 的“运行时自检”会同时显示 Native Entry 和 Java fallback 状态。

## Resolver / Root 稳定性

- Root 命令统一使用有 timeout、输出上限和 exit code 的 runner。
- `resolved.conf` 使用临时文件 + 校验 + 原子替换。
- 只有 `.gnu_debugdata` 结构验证和 Native offline ARM64 验证同时成功才持久化 Hook 点。
- UI 上保存、恢复、强制停止桌面等 Root 操作全部移出主线程，避免 ANR。

## GitHub Actions

每次 push 到 `main` 自动：

1. JDK 17 + Gradle 9.4.1
2. `:app:assembleDebug`
3. 解包 APK 验证 `META-INF/xposed/native_init.list`
4. 验证 `libdesktopgridx.so` 导出全局 `native_init`
5. 上传 `DesktopGridX-debug` Artifact

构建环境：AGP 9.2.0，compileSdk/targetSdk 37，arm64-v8a。

## 安全原则

宁可不 Hook，也不使用无法证明的地址或 ABI。文件夹网格等尚未证明真实上游来源的功能继续保持关闭，避免 Launcher 循环崩溃。
