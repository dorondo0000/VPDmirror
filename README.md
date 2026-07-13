# VPDMirror

VPDMirror is a Paper/Purpur plugin designed to be used with
[Stable Player Display](https://github.com/bradleyq/stable_player_display) and
[Vivecraft Spigot Extension](https://github.com/Vivecraft/Vivecraft-Spigot-Extension).

It renders VR players' movements using `item_display` entities, allowing players
without the Vivecraft mod installed to see VR players' poses and movements.

## Requirements

- [Stable Player Display](https://github.com/bradleyq/stable_player_display)
- [Vivecraft Spigot Extension](https://github.com/Vivecraft/Vivecraft-Spigot-Extension)

## Structure

- `src/` - Java source code
- `resources/` - Bukkit plugin metadata and default config
- `build.ps1` - local PowerShell build script

## Build

Run the local build script from the project root:

```powershell
.\build.ps1
```

The built jar is written under `build/`.
