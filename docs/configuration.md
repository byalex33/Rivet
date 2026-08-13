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
|   |-- gameplay.yml
|   |-- graves.yml
|   |-- help.yml
|   |-- holograms.yml
|   |-- homes.yml
|   |-- inventory.yml
|   |-- join-leave.yml
|   |-- kits.yml
|   |-- lagg.yml
|   |-- logs.yml
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
|   |-- teleports.yml
|   |-- trash.yml
|   |-- tree-feller.yml
|   |-- utilities.yml
|   |-- villager-reroll.yml
|   |-- warps.yml
|   `-- worlds.yml
|-- data/
|   `-- generated runtime state
`-- logs.db
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
| `lagg` | Enabled |
| `logs` | Enabled |
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
| `villager-reroll` | Enabled |
| `warps` | Enabled |
| `worlds` | Disabled |

## Settings files

Substantial modules own a file under `settings/`. Small mechanics are grouped in `gameplay.yml`, while cross-feature rules such as teleport timing live in a dedicated policy file. On startup, Rivet adds newly introduced default keys without replacing existing values. Messages use MiniMessage formatting and `%placeholder%` variables.

### Gameplay mechanics

`settings/gameplay.yml` contains small switches that do not need full module lifecycle management: crop-trample protection, water-harvest replanting, Iron Golem poppy drops, and faster hoppers. Hopper transfers use a 2-tick cooldown by default; vanilla uses 8 ticks.

### Teleport policy

`settings/teleports.yml` applies one policy to `/home`, `/warp`, `/spawn`, `/back`, accepted TPA requests, and `/rtp`. The defaults are a 3-second movement-cancellable warmup and a shared 10-second cooldown. `/back`, TPA requests, and `/rtp` retain their feature-specific cooldowns. Staff and test-world movement commands and automatic join spawning remain immediate.

Operators receive `rivet.tp.nocooldown` by default. It bypasses shared and feature-specific teleport cooldowns, but not the warmup.

### Ground-item cleanup

`settings/lagg.yml` controls the cleanup interval, warning times, protected-item rules, and every cleanup message. Rivet scans loaded worlds only when a cleanup runs. By default, dropped items with custom names or PersistentDataContainer data are protected. The cleanup result reports both removed entity stacks and their combined item amount; its configurable hover label shows the complete breakdown by material.

### Gameplay audit log

`settings/logs.yml` controls individual event categories, the retention period, excluded worlds and materials, and compact lookup page sizes. Commands are disabled by default. Enabling command logging never records chat, `/me`, private messages, or replies.

High-volume entries are written asynchronously in batches to `plugins/Rivet/logs.db`. SQLite WAL mode and location, player, action, and time indexes keep gameplay writes and inspector queries lightweight. Records include before/after data and item metadata so rollback and restore can be added later without changing the basic schema; those operations are not currently exposed.

Sound and particle fields accept Minecraft registry keys with or without the `minecraft:` prefix. Sound fields also accept legacy Bukkit names such as `ENTITY_ENDERMAN_TELEPORT` or `entity_enderman_teleport`. Invalid configured effects fall back to safe defaults and produce a warning in the server log.

## Runtime data

Files under `data/` contain generated state such as homes, warps, graves, breeders, holograms, permission users, ignored players, filters, nicknames, backpacks, reward claims, cooldowns, staff state, and tracked test worlds. The audit module stores its high-volume records separately in `logs.db`, not YAML.

Do not hand-edit runtime data while the server is running. Rivet may overwrite an external change the next time it saves that module.

## Reloading

[`/rivet reload`](commands.md#rivet) validates and reloads `config.yml`, `modules.yml`, and all module settings. Invalid YAML leaves the current in-memory configuration active and identifies the failing file.

Settings changes apply immediately where supported. Changes to `modules.yml` are reported but do not take effect until restart.

Use [`/lagg reload`](commands.md#lagg) to reload only `settings/lagg.yml` and restart its warning schedule. `/rivet reload` also refreshes the active cleanup schedule.

Use [`/log reload`](commands.md#log) to validate and reload only `settings/logs.yml`. Exclusions and event switches apply to new records immediately; lowering retention also schedules an immediate purge.

`settings/gameplay.yml` includes the `iron-golem-poppy-drops` switch. It is enabled by default to preserve vanilla behavior; set it to `false` and run `/rivet reload` to stop Iron Golems from dropping poppies (formerly roses). Iron ingot drops are unaffected.

## MiniMessage safety

Rivet uses white for primary copy and `#f72a4c` for emphasized names and values by default. Placeholders use `%name%` syntax so angle brackets remain reserved for MiniMessage tags.

Tagged module output uses the shared `messages.tag` MiniMessage value in `config.yml`. Audit headings, errors, reload feedback, and inspector state all use this shared tag rather than a logs-specific prefix.

Rivet also accepts `<lime>` as an alias for MiniMessage's bright-green `<green>` color.

Commands that accept player-supplied formatting limit which tags can be used. Formatting permissions do not grant click, hover, command, insertion, or other unsafe interactive tags.

## Upgrading

Rivet migrates supported legacy files before modules start. Existing values in the new destination take priority, and conflicting legacy files are retained rather than overwritten. Missing module switches and settings keys are added without replacing administrator choices.

When upgrading to the grouped gameplay settings, Rivet copies supported values from `settings/worlds.yml`, a legacy `settings/hoppers.yml`, and a legacy `hoppers` module switch. Those old entries are deliberately left untouched for recoverability but are no longer read after migration.
