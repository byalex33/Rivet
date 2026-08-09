# Configuration

## Directory layout

```text
plugins/Rivet/
|-- config.yml
|-- modules.yml
|-- settings/
|   |-- afk.yml
|   |-- announcements.yml
|   |-- backpacks.yml
|   |-- breeders.yml
|   |-- chat.yml
|   |-- creeper-restoration.yml
|   |-- daily.yml
|   |-- egg-capture.yml
|   |-- environment.yml
|   |-- filter.yml
|   |-- graves.yml
|   |-- help.yml
|   |-- holograms.yml
|   |-- homes.yml
|   |-- inventory.yml
|   |-- join-leave.yml
|   |-- kits.yml
|   |-- mob-heads.yml
|   |-- near.yml
|   |-- nicknames.yml
|   |-- permissions.yml
|   |-- poses.yml
|   |-- rtp.yml
|   |-- spawn.yml
|   |-- staff.yml
|   |-- statistics.yml
|   |-- tpa.yml
|   |-- trash.yml
|   |-- tree-feller.yml
|   |-- utilities.yml
|   |-- warps.yml
|   `-- worlds.yml
`-- data/
    `-- generated runtime state
```

## `config.yml`

This file contains Rivet-wide sound and particle switches plus the internal message-palette migration version. Module-specific values belong in `settings/`.

## `modules.yml`

This file contains feature switches only. Every value must be `true` or `false`. A module switch change requires a server restart.

| Module | Default |
|---|---:|
| `afk` | Enabled |
| `announcements` | Disabled |
| `backpacks` | Disabled |
| `breeders` | Enabled |
| `chat` | Enabled |
| `creeper-restoration` | Enabled |
| `daily` | Disabled |
| `egg-capture` | Enabled |
| `environment` | Enabled |
| `filter` | Enabled |
| `graves` | Enabled |
| `help` | Enabled |
| `holograms` | Enabled |
| `homes` | Enabled |
| `inventory` | Disabled |
| `join-leave` | Enabled |
| `kits` | Disabled |
| `mob-heads` | Enabled |
| `near` | Disabled |
| `nicknames` | Enabled |
| `permissions` | Disabled |
| `poses` | Disabled |
| `rtp` | Disabled |
| `spawn` | Enabled |
| `staff` | Disabled |
| `statistics` | Enabled |
| `tpa` | Disabled |
| `trash` | Enabled |
| `tree-feller` | Enabled |
| `utilities` | Enabled |
| `warps` | Enabled |
| `worlds` | Disabled |

## Settings files

Each module owns one file under `settings/`. On startup, Rivet adds newly introduced default keys without replacing existing values. Messages use MiniMessage formatting and `%placeholder%` variables.

Sound and particle fields accept Minecraft registry keys with or without the `minecraft:` prefix. Invalid configured effects fall back to safe defaults and produce a warning in the server log.

## Runtime data

Files under `data/` contain generated state such as homes, warps, graves, breeders, holograms, permission users, ignored players, filters, nicknames, backpacks, reward claims, cooldowns, staff state, and tracked test worlds.

Do not hand-edit runtime data while the server is running. Rivet may overwrite an external change the next time it saves that module.

## Reloading

[`/rivet reload`](commands.md#rivet) validates and reloads `config.yml`, `modules.yml`, and all module settings. Invalid YAML leaves the current in-memory configuration active and identifies the failing file.

Settings changes apply immediately where supported. Changes to `modules.yml` are reported but do not take effect until restart.

## MiniMessage safety

Rivet uses white for primary copy and `#f72a4c` for emphasized names and values by default. Placeholders use `%name%` syntax so angle brackets remain reserved for MiniMessage tags.

Rivet also accepts `<lime>` as an alias for MiniMessage's bright-green `<green>` color.

Commands that accept player-supplied formatting limit which tags can be used. Formatting permissions do not grant click, hover, command, insertion, or other unsafe interactive tags.

## Upgrading

Rivet migrates supported legacy files before modules start. Existing values in the new destination take priority, and conflicting legacy files are retained rather than overwritten. Missing module switches and settings keys are added without replacing administrator choices.
