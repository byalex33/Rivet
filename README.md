<div align="center">

# 🔩 Rivet

### Everything your Paper server needs, held together.

Survival essentials, staff tools, custom worlds, holograms, graves, automation, and the small details that make a Paper server feel finished.

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net/temurin/releases/?version=21)
[![Paper 1.21.11](https://img.shields.io/badge/Paper-1.21.11-2C2E33?style=for-the-badge&logo=paper&logoColor=white)](https://papermc.io/)
[![Maven](https://img.shields.io/badge/Maven-Build-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](#build-from-source)
[![Tests](https://img.shields.io/badge/tests-38_passing-2EA44F?style=for-the-badge)](#build-from-source)
[![bStats Servers](https://img.shields.io/bstats/servers/33219?style=for-the-badge&label=servers&color=7C3AED)](https://bstats.org/plugin/bukkit/Rivet/33219)
[![bStats Players](https://img.shields.io/bstats/players/33219?style=for-the-badge&label=players&color=2563EB)](https://bstats.org/plugin/bukkit/Rivet/33219)

</div>

---

## Why Rivet?

Rivet replaces a pile of single-purpose plugins with one focused Paper plugin. There are no required runtime dependencies and no database to babysit—drop in the jar and play.

<table>
  <tr>
    <td align="center"><strong>55</strong><br><sub>commands</sub></td>
    <td align="center"><strong>38</strong><br><sub>tests</sub></td>
    <td align="center"><strong>25</strong><br><sub>breeder species</sub></td>
    <td align="center"><strong>3%</strong><br><sub>default head chance</sub></td>
  </tr>
</table>

## Everything included

| | Feature | What it does |
|---|---|---|
| 🌳 | **Tree felling & vein mining** | Break whole trees and connected ore veins with the right tool. |
| 🪦 | **Player graves** | Persistent graves, `/back`, ownership/expiry rules, death coordinates, and tracking compasses. |
| 🥚 | **Egg capture** | Capture mobs with thrown eggs, complete with a polished animation. |
| 🐄 | **Auto breeders** | Craft persistent, species-specific breeders for 25 animal types. |
| 💀 | **Mob & player heads** | Custom-textured mob drops plus cached player profile heads. |
| 💬 | **Modern chat** | MiniMessage formatting, hoverable `[i]` / `[item]` links, private messages, and replies. |
| 🏠 | **Homes & warps** | Named homes, public warps, tab completion, and safe persistence. |
| 🌍 | **Test worlds** | Create, list, enter, and reset tracked flat or void worlds. |
| ✨ | **Holograms** | Persistent text, item, and block displays with visibility controls and animations. |
| 🟨 | **Glow regions** | Select cuboids with a wand and render persistent colored outlines. |
| 🛡️ | **Permissions** | Lightweight users and groups with wildcard permission support. |
| 🕶️ | **Staff tools** | Vanish, flight, teleporting, inventory tools, time, weather, and mob cleanup. |
| 🧭 | **Spawn & TPA** | Warmups, movement cancellation, request expiry, cooldowns, and join spawning. |
| 🎒 | **Kits** | YAML-defined items, armour, offhand gear, per-kit permissions, and persisted cooldowns. |
| 💤 | **AFK & welcome** | Automatic/manual AFK, sleep exclusion, join/leave messages, MOTD, and welcome titles. |
| 📣 | **Announcements** | Rotating MiniMessage announcements with optional sounds and empty-server skipping. |
| 🏷️ | **Nicknames & stats** | Persistent safe-format nicknames and native Bukkit player statistics. |
| 🧰 | **Player utilities** | Inventory inspection, ender chests, trash, portable workstations, and native poses. |

Also included: crop-trample protection, natural-spawn control for flat worlds, visual feedback, sound cues, particles, and useful tab completion throughout.

## Install

1. Run [Paper](https://papermc.io/) `1.21.11` on Java `21`.
2. [Build Rivet from source](#build-from-source).
3. Place the jar in your server's `plugins/` directory.
4. Restart the server.

Rivet creates its configuration and data files under `plugins/Rivet/` on first launch.

## Commands

<details>
<summary><strong>Show all command groups</strong></summary>

| Area | Commands | Permission |
|---|---|---|
| Gamemode | `/gmc`, `/gms` | `rivet.gamemode` |
| Test worlds | `/flat`, `/flatworld`, `/voidworld` | `rivet.flat`, `rivet.world` |
| World tools | `/worldspawn`, `/setworldspawn`, `/killall` | `rivet.world` |
| Homes | `/sethome [name]`, `/home [name]`, `/delhome [name]` | `rivet.home` |
| Warps | `/setwarp`, `/warp`, `/delwarp` | `rivet.warp`, `rivet.warp.set` |
| Inventory | `/clear`, `/i <item> [amount]` | `rivet.inventory` |
| Time | `/day`, `/night`, `/noon`, `/midnight` | `rivet.environment` |
| Weather | `/sun`, `/rain`, `/thunder` | `rivet.environment` |
| Messages | `/msg`, `/r` | `rivet.message` |
| Staff | `/tp`, `/vanish`, `/fly` | `rivet.teleport`, `rivet.vanish`, `rivet.fly` |
| Permissions | `/perm`, `/group` | `rivet.permissions.manage` |
| Holograms | `/hologram` (`/holo`) | `rivet.holograms` |
| Glow regions | `/glow` | `rivet.glow` |
| Spawn | `/spawn`, `/setspawn` | `rivet.spawn`, `rivet.spawn.set` |
| Teleport requests | `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny` | `rivet.tpa` |
| Kits | `/kit [name]` | `rivet.kit`, `rivet.kit.<name>` |
| Death return | `/back` | `rivet.back` |
| AFK | `/afk` | `rivet.afk` |
| Inventory admin | `/invsee`, `/enderchest [player]` | `rivet.inventory.invsee`, `rivet.inventory.enderchest[.others]` |
| Nicknames | `/nick <nickname|off>`, `/nick <player> <nickname|off>` | `rivet.nick`, `rivet.nick.format`, `rivet.nick.others` |
| Statistics | `/stats [player]` | `rivet.stats`, `rivet.stats.others` |
| Trash | `/trash` | `rivet.trash` |
| Portable utilities | `/craft`, `/anvil`, `/smithing`, `/stonecutter`, `/grindstone` | `rivet.utility.<command>` |
| Poses | `/sit`, `/lay`, `/crawl` | `rivet.pose.<command>` |
| Player heads | `/head <player>` | `rivet.head` |

</details>

## Configuration and data

Rivet creates a complete modular layout on first launch:

```text
plugins/Rivet/
├── config.yml
├── modules.yml
├── settings/
│   ├── chat.yml
│   ├── homes.yml
│   ├── warps.yml
│   ├── graves.yml
│   ├── breeders.yml
│   ├── egg-capture.yml
│   ├── tree-feller.yml
│   ├── mob-heads.yml
│   ├── holograms.yml
│   ├── glow.yml
│   ├── permissions.yml
│   ├── worlds.yml
│   ├── staff.yml
│   ├── environment.yml
│   ├── inventory.yml
│   ├── spawn.yml
│   ├── tpa.yml
│   ├── kits.yml
│   ├── afk.yml
│   ├── join-leave.yml
│   ├── announcements.yml
│   ├── nicknames.yml
│   ├── statistics.yml
│   ├── trash.yml
│   ├── utilities.yml
│   └── poses.yml
└── data/
```

- `modules.yml` contains only feature switches. Disabled modules do not register their listeners or start their tasks, and their declared commands return a clean disabled message. Restart after changing a switch.
- `settings/` contains options owned by one module. For example, chat MiniMessage formats live in `settings/chat.yml`, permission groups in `settings/permissions.yml`, and the mob-head drop chance in `settings/mob-heads.yml`.
- `config.yml` contains only Rivet-wide visual feedback settings used by multiple modules.
- `data/` contains generated persistent state such as homes, warps, graves/death locations, spawn, kit cooldowns, nicknames, breeders, glow regions, holograms, permission users, and tracked test worlds. Do not hand-edit these files while the server is running.

The `worlds` module owns test worlds, world spawn, mob cleanup, crop-trample protection, and the flat-world spawn rule. `staff` owns gamemode, teleport, vanish, and flight; `environment` owns time and weather; `inventory` owns clear and item commands.

Kits are defined directly in `settings/kits.yml` with material, amount, display name, lore, enchantments, armour, offhand, and cooldown fields. Offline inventory editing is deliberately excluded: Rivet only opens live Paper inventories, avoiding unsafe direct player-data file writes. Poses use Paper's native fixed-pose API, so they do not create invisible seat entities.

### Upgrading an existing installation

Migration runs before modules start. Rivet moves the old root `chat.yml`, grave, glow, hologram, and permission files into the new layout. It also moves `homes`, `warps`, and `auto-breeders` out of `config.yml` without overwriting values already present in the new data files. Legacy test-world markers are imported into `data/worlds.yml` and retained as a compatibility fallback. New module switches are appended to existing `modules.yml` files without replacing existing choices. If both an old standalone file and its new destination already exist, Rivet keeps both untouched and uses the new destination.

## Build from source

```bash
git clone https://github.com/byalex33/Rivet.git
cd Rivet
mvn clean package
```

The finished plugin is written to `target/rivet-1.0-SNAPSHOT.jar`. `mvn test` runs the 38-test unit suite.

## Privacy-friendly metrics

Rivet uses [bStats](https://bstats.org/plugin/bukkit/Rivet/33219) for anonymous server and player counts. Server owners retain the global opt-out in `plugins/bStats/config.yml`.

---

<div align="center">
  <sub>Built for Paper. Kept intentionally lean.</sub>
</div>
