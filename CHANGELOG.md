# Changelog

## 0.9.0

- Added Modern LSPosed native entry through `META-INF/xposed/native_init.list` and exported `native_init`.
- Java entry is now compatibility fallback rather than the primary runtime path.
- Replaced ShadowHook shared-mode usage with unique mode so `orig_addr` trampoline calls are valid and deterministic.
- Added transactional hook installation with rollback on partial failure.
- Added explicit native install state machine to prevent duplicate hooks and distinguish retryable/permanent failures.
- Removed hardcoded proxy fallback return values; hooks now require a valid original trampoline.
- Gated `PreferenceUtils::get_int` Rust scalar-pair bridge behind live ARM64 ABI pattern validation.
- Added the latest verified Launcher RVA profile (`1477db56...`) while still live-verifying every address before use.
- Moved runtime status into Launcher-owned cache paths and protected state/file writes with a mutex.
- Added atomic runtime status file replacement and hit-count checkpoints.
- Tightened `.gnu_debugdata` symbol matching and X/Y structural validation.
- Resolver now requires both Java symbol validation and native ARM64 offline validation before persisting offsets.
- Added atomic `resolved.conf` replacement with exit-code checking.
- Added bounded Root command runner with timeout and output cap; removed blocking Root calls from the UI thread.
- Updated diagnostics for Native Entry, module resource packaging, native symbol export and live mappings.
- Removed obsolete split native source/include files; runtime now compiles from one auditable `native_hook.cpp`.
- GitHub Actions now verifies `META-INF/xposed/native_init.list` and exported `native_init` after building the APK.
- Bumped app version to 0.9.0 / versionCode 9.

## 0.7.0

- Added persistent Native runtime state at `/data/adb/desktopgridx/runtime-status.conf`.
- Added hook hit counters for upstream X/Y preferences, getter fallbacks, Hotseat and icon preference paths.
- Added Launcher PID/base address, resolver, pre-init timing, ShadowHook state and last-error reporting.
- Added an in-app `运行时自检` view so locator success and real hook execution can be distinguished.
- Clear stale runtime state when saving/resetting configuration.
- Diagnostic ZIP now includes persistent runtime status plus live DesktopGridX/ShadowHook/Launcher mappings.
- Kept fail-closed hook policy and conditional getter fallback.
- Fixed Native include/scope boundaries exposed by CI while adding runtime tracking.
- Bumped app version to 0.7.0 / versionCode 7.

## 0.6.0

- Added safe icon size control through Launcher-native `Settings.System icon_size_scale`.
- Preserve original icon size setting and restore it on reset.
- Added optional bounded PreferenceUtils integer-key tracing for diagnostics.
- Extended `.gnu_debugdata` resolver with IconSizeProvider and cell-width symbols.
- Extended persisted resolver report with IconSizeProvider/cell-width RVAs.
- Added icon/provider/system-setting diagnostic files.
- Expanded preference scan to grid/icon/hotseat/folder related keys.
- Proved that workspace cell width is already recomputed from `pref_key_cell_x`; deliberately avoided an unnecessary cell-width hook.
- Kept folder-grid changes disabled until a real upstream source/ABI is proven.
