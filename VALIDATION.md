# Validation — DesktopGridX v0.6.0

Target: user supplied `系统桌面(1).apk`.

## Verified native symbols

- X: 0x61E1F0
- Y: 0x61E29C
- Hotseat: 0x61F334
- PreferenceUtils::get_int: 0x634C28
- PreferenceUtils::put_int: 0x634D3C
- compute_cell_width_px_by_orientation: 0x61F5D0
- IconSizeProvider::is_parameter_qualified: 0x62C210
- IconSizeProvider ContentProvider call: 0x6C3DAC

## Important machine-code finding

`compute_cell_width_px_by_orientation` calls `PreferenceUtils::get_int`
with the literal `pref_key_cell_x`, then derives width from the current display dimensions.
This proves that a separate cell-width hook is unnecessary for normal workspace spacing.

## Icon source

Binary AndroidManifest contains:
- class `com.miui.home.launcher.bigicon.IconSizeProvider`
- authority `com.miui.home.launcher.bigicon.iconsize`

Native provider data contains:
- `convertIconSize`
- `getIconLocation`
- `iconMaxScale`
- `iconMinScale`
- `cellWidth`
- `iconNewScale`
- `icon_size_scale`
- `isIconNewScaleApplied`
- `Settings.System.putString`

Therefore v0.6 uses the Launcher's own `icon_size_scale` system setting instead of visual scaling hacks.

## Fail-closed

Unresolved or ambiguous native targets are never hooked.
