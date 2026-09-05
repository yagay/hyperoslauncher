# Changelog

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
