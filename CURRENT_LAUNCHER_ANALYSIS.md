# CURRENT_LAUNCHER_ANALYSIS — v0.6.0

Target APK: `系统桌面(1).apk`

`libapp_launcher.so` SHA-256:
`433232fb60c439e0a2537264c58058212df229a2ffdcbdaf1092f38295e04b4f`

## Confirmed symbols

- `DeviceConfigs::get_cell_count_x` — 0x61E1F0
- `DeviceConfigs::get_cell_count_y` — 0x61E29C
- `DeviceConfigs::get_hotseat_max_count` — 0x61F334
- `DeviceConfigs::get_cell_width_for_provider` — 0x61F428
- `DeviceConfigs::compute_cell_width_px_by_orientation` — 0x61F5D0
- `DeviceConfigs::pick_cell_width_for_current_orientation` — 0x61F758
- `IconSizeProvider::is_parameter_qualified` — 0x62C210
- `PreferenceUtils::get_int` — 0x634C28
- `PreferenceUtils::put_int` — 0x634D3C
- `IconSizeProvider ContentProvider::call` — 0x6C3DAC

## Key findings

1. `compute_cell_width_px_by_orientation` passes literal `pref_key_cell_x` to
   `PreferenceUtils::get_int`, proving that workspace cell width is downstream of column count.
2. AndroidManifest contains `com.miui.home.launcher.bigicon.IconSizeProvider` with authority
   `com.miui.home.launcher.bigicon.iconsize`.
3. Native provider strings contain `convertIconSize`, `getIconLocation`, `iconMaxScale`,
   `iconMinScale`, `cellWidth`, `iconNewScale`, `icon_size_scale`,
   `isIconNewScaleApplied`, and `Settings.System.putString`.
4. No equally-proven folder row/column preference or stable native ABI was found. v0.6 therefore
   refuses to invent a folder-grid hook and uses diagnostic tracing to discover future real keys.
