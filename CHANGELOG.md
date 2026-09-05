# Changelog

## 0.6.0

- Added safe icon size control through Launcher-native `Settings.System icon_size_scale`.
- Preserve original icon size setting and restore it on reset.
- Added optional bounded PreferenceUtils integer-key tracing for diagnostics.
- Extended `.gnu_debugdata` resolver with IconSizeProvider and cell-width symbols.
- Extended persisted resolver report with IconSizeProvider/cell-width RVAs.
- Added icon/provider/system-setting diagnostic files.
- Expanded preference scan to grid/icon/hotseat/folder related keys.
- Proved that workspace cell width is already recomputed from `pref_key_cell_x`; deliberately
  avoided an unnecessary cell-width hook.
- Kept folder-grid changes disabled until a real upstream source/ABI is proven.
