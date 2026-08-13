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
|   |-- death-messages.yml
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
|   |-- snapshots.yml
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
|-- logs.db
`-- snapshots.db
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
| `death-messages` | Enabled |
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
| `snapshots` | Enabled |
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

### Chat

`settings/chat.yml` keeps public chat in one compact file. The `format` value supports `%prefix%`, `%suffix%`, `%tag%`, `%player%`, and `%message%`; the first two are filled from Rivet group metadata only when the optional permissions module is active. Tags remain independent cosmetic selections. A selected color, gradient, or rainbow style wraps `%message%` only.

Named colors and gradients under `chat-styles` become permission names such as `rivet.chat.color.red` and `rivet.chat.gradient.sunset`. Custom six-digit hex colors, custom two-color gradients, and rainbow share `rivet.chat.style.custom` and can each be disabled. Tags follow `rivet.chat.tag.<name>`. Player selectors show only choices the player may use.

Mentions are rendered per viewer: a matching player sees their own highlighted `@Name`, while other viewers keep the ordinary message style. The optional sound is resolved like other Rivet sounds. The anti-spam section intentionally contains only a cooldown and a similarity percentage; `rivet.chat.antispam.bypass` skips both checks.

Style and tag displays use a visual-only MiniMessage parser. Colors, gradients, rainbow, reset, and safe decorations are supported, but player-controlled cosmetics cannot create clicks, hovers, commands, URLs, insertions, NBT, fonts, or selectors. Rivet-generated `[item]` hover data remains intact.

### Death messages

`settings/death-messages.yml` contains a short list for each supported cause. Rivet chooses randomly within the matching list. `rare` is a global optional pool selected according to `rare-chance`; set the chance to `0` or leave the list empty to disable it. `killer-health` appends the remaining health of a player killer.

Messages accept MiniMessage plus `%player%`, `%killer%`, `%mob%`, `%weapon%`, and `%world%`. Player, entity, world, and item names are inserted safely rather than parsed as formatting. Weapons preserve custom names and include a real-item hover. Empty cause lists fall back to `generic`; when both are empty, the original vanilla death message is retained.

### Creeper restoration

`settings/creeper-restoration.yml` controls container and block-entity restoration,
animation timing, Restoration Core behavior, messages, sounds, and particles. With
`restore-container-contents` enabled, Rivet snapshots every affected container before
clearing its live block inventory. This escrow prevents the same contents from dropping
during the explosion and then being restored as a second copy. Double-chest halves are
cleared independently; if live contents cannot be cleared safely, Rivet discards their
saved copy rather than risk duplication.

### Gameplay mechanics

`settings/gameplay.yml` contains small switches that do not need full module lifecycle management: crop-trample protection, water-harvest replanting, Iron Golem poppy drops, and faster hoppers. Hopper transfers use a 2-tick cooldown by default; vanilla uses 8 ticks.

### Teleport policy

`settings/teleports.yml` applies one policy to `/home`, `/warp`, `/spawn`, `/back`, accepted TPA requests, and `/rtp`. The defaults are a 3-second movement-cancellable warmup and a shared 10-second cooldown. `/back`, TPA requests, and `/rtp` retain their feature-specific cooldowns. Staff and test-world movement commands and automatic join spawning remain immediate.

Operators receive `rivet.tp.nocooldown` by default. It makes player-facing teleports immediate by bypassing the shared warmup, movement-cancellation wait, shared cooldown, `/back` cooldown, TPA request cooldown, and RTP cooldown.

`/back` keeps up to eight recent, distinct locations internally in `data/teleports.yml`. Deaths and meaningful command, plugin, or portal teleports are recorded; tiny moves, bed exits, dismounts, spectator movement, chorus fruit, and automatic join spawning are ignored. Players only use `/back`: there are no numbered history commands. Existing saved death locations are imported automatically when no newer history exists.

### Ground-item cleanup

`settings/lagg.yml` controls the cleanup interval, warning times, protected-item rules, and every cleanup message, including the `/lagg timer` response. Rivet scans loaded worlds only when a cleanup runs. By default, dropped items with custom names, PersistentDataContainer data, or a material in `crop-materials` are protected. The crop list includes ordinary farmland crops, seeds, nether wart, cocoa, berries, melon and pumpkin products, sugar cane, cactus, bamboo, kelp, mushrooms, and chorus harvests. The cleanup result reports both removed entity stacks and their combined item amount; its configurable hover label shows the complete breakdown by material.

### Item pickup filter

`settings/filter.yml` controls the maximum saved materials, worlds where filtering is
unavailable, feedback, and the filter GUI. A new player's filter is enabled by default.
Adding an item through `/filter add` or the GUI also enables the filter automatically;
players can use `/filter toggle` when they intentionally want to retain but temporarily
disable their saved list.

### Statistics and Seen v2

`settings/statistics.yml` controls the configurable `/stats`, `/playtime`, and `/seen`
message actions. Seen v2 provides separate online, offline, staff-location, and staff-death
outputs. Its relative-time placeholders retain an exact timestamp on hover, formatted by
`seen.date-format`.

Paper supplies first join, last login, and total playtime. Rivet stores only the last
logout location and most recent observed death in `data/statistics.yml`; older players
show `unknown` for fields that have not yet been observed by Seen v2.

### Gameplay audit log

`settings/logs.yml` controls individual event categories, the retention period, excluded worlds and materials, and compact lookup page sizes. Commands are disabled by default. Enabling command logging never records chat, `/me`, private messages, or replies.

High-volume entries are written asynchronously in batches to `plugins/Rivet/logs.db`. SQLite WAL mode and location, player, action, and time indexes keep gameplay writes and inspector queries lightweight. Records include before/after data and item metadata so rollback and restore can be added later without changing the basic schema; those operations are not currently exposed.

### Inventory snapshots

`settings/snapshots.yml` controls per-player maximums, retention, binary-payload
deduplication, death capture, restore confirmation, `PRE_RESTORE` safety snapshots,
creation auditing, and all command messages. Defaults keep 10 snapshots per player for 30
days, deduplicate identical state, save only on death, and require both confirmation and a
successful safety snapshot before restore.

Snapshot rows and compressed binary payloads live in `plugins/Rivet/snapshots.db`; no
inventory contents are stored in YAML or copied into `logs.db`. Database serialization,
writes, reads, and cleanup run on one dedicated worker. Bukkit state is captured and
restored on the server thread.

### Built-in permissions

When the `permissions` module is enabled, `settings/permissions.yml` stores human-readable group definitions with integer weights, `parents` lists, boolean permission maps, and MiniMessage `prefix`/`suffix` metadata. `data/permissions.yml` stores UUID-keyed user `groups` lists and direct boolean permission maps. A missing permission key means unset and therefore inherited or handled by Paper's default.

Sound and particle fields accept Minecraft registry keys with or without the `minecraft:` prefix. Sound fields also accept legacy Bukkit names such as `ENTITY_ENDERMAN_TELEPORT` or `entity_enderman_teleport`. Invalid configured effects fall back to safe defaults and produce a warning in the server log.

`settings/utilities.yml` contains the shared message actions used when `/nv` enables or
disables Night Vision, alongside portable-interface, jump, ride, list, and ping settings.

## Runtime data

Files under `data/` contain generated state such as homes, warps, graves, teleport history, Seen v2 logout/location and death details, breeders, holograms, permission users, ignored players, chat styles and tags, filters, nicknames, backpacks, reward claims, cooldowns, staff state, and tracked test worlds. The audit module stores its high-volume records separately in `logs.db`, and inventory snapshots use `snapshots.db`; neither uses YAML for payload data.

Do not hand-edit runtime data while the server is running. Rivet may overwrite an external change the next time it saves that module.

## Reloading

[`/rivet reload`](commands.md#rivet) validates and reloads `config.yml`, `modules.yml`, and all module settings. Invalid YAML leaves the current in-memory configuration active and identifies the failing file.

Settings changes apply immediately where supported. Changes to `modules.yml` are reported but do not take effect until restart.

`/rivet reload` reloads `settings/lagg.yml` and restarts the active cleanup and warning schedule.

Use [`/log reload`](commands.md#log) to validate and reload only `settings/logs.yml`. Exclusions and event switches apply to new records immediately; lowering retention also schedules an immediate purge.

`/rivet reload` refreshes `settings/snapshots.yml` immediately. Lower retention or maximum
values schedule cleanup on the snapshot storage worker. The `snapshots` switch in
`modules.yml` still requires a server restart.

`settings/gameplay.yml` includes the `iron-golem-poppy-drops` switch. It is enabled by default to preserve vanilla behavior; set it to `false` and run `/rivet reload` to stop Iron Golems from dropping poppies (formerly roses). Iron ingot drops are unaffected.

## MiniMessage safety

Rivet uses white for primary copy and `#f72a4c` for emphasized names and values by default. Placeholders use `%name%` syntax so angle brackets remain reserved for MiniMessage tags.

Tagged module output uses the shared `messages.tag` MiniMessage value in `config.yml`. Audit headings, errors, reload feedback, and inspector state all use this shared tag rather than a logs-specific prefix.

Rivet also accepts `<lime>` as an alias for MiniMessage's bright-green `<green>` color.

## Message actions

Player-facing output is configured as an event with an `enabled` switch and an ordered
`actions` list. This lets the same event send more than one kind of feedback:

```yaml
messages:
  received:
    enabled: true
    actions:
      - '[message] <white>Reward received.</white>'
      - '[sound] entity.player.levelup 0.8 1.2'
```

Supported actions are `[message]`, `[broadcast]`, `[actionbar]`, `[title]`, `[sound]`,
`[particle]`, `[firework]`, `[bossbar]`, `[toast]`, `[lightning]`, and `[close]`.
Titles use `title | subtitle | fade-in ticks | stay ticks | fade-out ticks`. Fireworks use
`#RRGGBB,#RRGGBB | type | power | count | gap ticks`. Rivet upgrades legacy message strings
and action lists to this structure on startup without replacing customized text.

Commands that accept player-supplied formatting limit which tags can be used. Formatting permissions do not grant click, hover, command, insertion, or other unsafe interactive tags.

## Upgrading

Rivet migrates supported legacy files before modules start. Existing values in the new destination take priority, and conflicting legacy files are retained rather than overwritten. Missing module switches and settings keys are added without replacing administrator choices.

Legacy `chat-color` selections are copied to the new `chat-style` storage on startup and remain available in memory even if saving fails. Their original keys are retained for recovery. Existing `chat-colors` feature switches are copied to the corresponding `chat-styles` switches only when the new value is absent, and an unchanged old default chat format is upgraded to include the new placeholders.

When upgrading to the grouped gameplay settings, Rivet copies supported values from `settings/worlds.yml`, a legacy `settings/hoppers.yml`, and a legacy `hoppers` module switch. Those old entries are deliberately left untouched for recoverability but are no longer read after migration.

When the built-in permissions module first reads legacy permission files, it converts each user `group` value to a `groups` list, each group `parent` to a `parents` list, and permission grant lists to boolean maps with `true` values. Existing names, UUID assignments, grants, and inheritance are retained; the same files are upgraded in place and no live configuration file needs to be removed.
