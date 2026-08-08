<div align="center">

# 🔩 Rivet

### Everything your Paper server needs, held together.

Survival essentials, staff tools, custom worlds, holograms, graves, automation, and the small details that make a Paper server feel finished.

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net/temurin/releases/?version=21)
[![Paper 1.21.11](https://img.shields.io/badge/Paper-1.21.11-2C2E33?style=for-the-badge&logo=paper&logoColor=white)](https://papermc.io/)
[![Maven](https://img.shields.io/badge/Maven-Build-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](#build-from-source)
[![Tests](https://img.shields.io/badge/tests-69_passing-2EA44F?style=for-the-badge)](#build-from-source)
[![bStats Servers](https://img.shields.io/bstats/servers/33219?style=for-the-badge&label=servers&color=7C3AED)](https://bstats.org/plugin/bukkit/Rivet/33219)
[![bStats Players](https://img.shields.io/bstats/players/33219?style=for-the-badge&label=players&color=2563EB)](https://bstats.org/plugin/bukkit/Rivet/33219)

</div>

---

## Why Rivet?

Rivet replaces a pile of single-purpose plugins with one focused Paper plugin. There are no required runtime dependencies and no database to babysit—drop in the jar and play.

<table>
  <tr>
    <td align="center"><strong>90</strong><br><sub>commands</sub></td>
    <td align="center"><strong>69</strong><br><sub>tests</sub></td>
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
| 💬 | **Modern chat** | MiniMessage formatting, item links, private messages, persistent ignore lists, and social spy. |
| 🏠 | **Homes & warps** | Named homes, public warps, tab completion, and safe persistence. |
| 🌍 | **Test worlds** | Create, list, enter, and reset tracked flat or void worlds. |
| ✨ | **Holograms** | Persistent text, item, and block displays with visibility controls and animations. |
| 🌈 | **Player glows** | Give players persistent colored outlines, including a smoothly animated rainbow. |
| 🛡️ | **Permissions** | Lightweight users and groups with wildcard permission support. |
| 🕶️ | **Staff tools** | Vanish, flight, teleporting, inventory tools, time, weather, and mob cleanup. |
| 🧭 | **Spawn & TPA** | Warmups, movement cancellation, request expiry, cooldowns, and join spawning. |
| 🎒 | **Kits** | YAML-defined items, armour, offhand gear, per-kit permissions, and persisted cooldowns. |
| 💤 | **AFK & welcome** | Automatic/manual AFK, sleep exclusion, join/leave messages, MOTD, and welcome titles. |
| 📣 | **Announcements** | Rotating MiniMessage announcements with optional sounds and empty-server skipping. |
| 🏷️ | **Nicknames & stats** | Persistent safe-format nicknames and native Bukkit player statistics. |
| 🧰 | **Player utilities** | Inventory inspection, ender chests, trash, portable workstations, and native poses. |
| 🎒 | **Backpacks** | Persistent personal storage with 1-6 permission-based rows and shrink-safe overflow retention. |
| 🎁 | **Daily rewards** | Timestamp-based claims, streaks, cycling YAML rewards, and milestone bonuses. |
| 🧭 | **RTP & near** | Async chunk searches for safe random teleports plus visibility-aware nearby-player lists. |
| 🛠️ | **Item & staff tools** | Heal, feed, god mode, repair, and safe item name/lore editing. |
| 📖 | **Interactive text help** | Permission-aware command pages with clickable first, previous, next, and last navigation. |
| 📝 | **Notes & staff UI** | UUID-keyed player notes, safe session-address matching, and temporary advancement toasts. |

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
| World tools | `/worldspawn`, `/setworldspawn`, `/killall`, `/top [player]`, `/tree <treeType>` | `rivet.world`, `rivet.top[.others]`, `rivet.tree` |
| Homes | `/sethome [name]`, `/home [name]`, `/delhome [name]` | `rivet.home` |
| Warps | `/setwarp`, `/warp`, `/delwarp` | `rivet.warp`, `rivet.warp.set` |
| Inventory | `/clear [player] [item[:amount][;plain]] [-s]`, `/i`, `/condense`, `/donate`, `/giveall`, `/hat` | `rivet.inventory`, `rivet.condense`, `rivet.donate`, `rivet.giveall`, `rivet.hat` |
| Time | `/day`, `/night`, `/noon`, `/midnight` | `rivet.environment` |
| Weather | `/sun`, `/rain`, `/thunder` | `rivet.environment` |
| Messages | `/msg`, `/r`, `/ignore`, `/socialspy` (`/ss`), `/chatcolor [player] <color|reset>`, `/me <message>` | `rivet.message`, `rivet.ignore`, `rivet.socialspy`, `rivet.chatcolor[.others|.advanced]`, `rivet.me[.format]` |
| Staff | `/tp`, `/vanish`, `/fly`, `/flyspeed [player] <amount>`, `/heal`, `/feed`, `/god`, `/bossbarmsg`, `/note <player> <add <text>\|remove <id>\|clear\|list>`, `/sameip [player]`, `/toast <player\|all> [flags] <message>` | Existing staff permissions plus `rivet.notes`, `rivet.sameip`, `rivet.toast` |
| Permissions | `/perm`, `/group` | `rivet.permissions.manage` |
| Holograms | `/hologram` (`/holo`) | `rivet.holograms` |
| Player glows | `/glow add <player> <color> [-s]`, `/glow remove <player> [-s]`, `/glow color` | `rivet.glow` |
| Spawn | `/spawn`, `/setspawn` | `rivet.spawn`, `rivet.spawn.set` |
| Teleport requests | `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny` | `rivet.tpa` |
| Kits | `/kit [name]` | `rivet.kit`, `rivet.kit.<name>` |
| Death return | `/back` | `rivet.back` |
| AFK | `/afk [reason] [-p:player] [-s]`, `/afkcheck [player|all]` | `rivet.afk[.others|.silent]`, `rivet.afkcheck[.others]` |
| Inventory admin | `/invsee`, `/enderchest [player]` | `rivet.inventory.invsee`, `rivet.inventory.enderchest[.others]` |
| Nicknames | `/nick <nickname|off>`, `/nick <player> <nickname|off>` | `rivet.nick`, `rivet.nick.format`, `rivet.nick.others` |
| Statistics | `/stats [player]`, `/playtime [player]` | `rivet.stats[.others]`, `rivet.playtime[.others]` |
| Trash | `/trash` | `rivet.trash` |
| Portable utilities | `/craft`, `/anvil`, `/smithing`, `/stonecutter`, `/grindstone` | `rivet.utility.<command>` |
| Player & movement utilities | `/list`, `/ping [player]`, `/jump`, `/ride` | `rivet.list`, `rivet.ping[.others]`, `rivet.jump`, `rivet.ride` |
| Poses | `/sit`, `/lay`, `/crawl` | `rivet.pose.<command>` |
| Player heads | `/head <player>` | `rivet.head` |
| Backpacks | `/backpack` (`/bp`) | `rivet.backpack`, `rivet.backpack.rows.1` through `.6` |
| Daily rewards | `/daily` | `rivet.daily` |
| Random teleport | `/rtp [world]` | `rivet.rtp`, `rivet.rtp.world`, `rivet.rtp.cooldown.bypass` |
| Nearby players | `/near` | `rivet.near` |
| Item filter | `/filter`, `/filter add [item]`, `/filter remove`, `/filter list`, `/filter clear`, `/filter toggle` | `rivet.filter`, `rivet.filter.admin` |
| Biome search | `/findbiome <biome>` | `rivet.findbiome` |
| Item editing | `/repair [all]`, `/rename`, `/lore` | `rivet.repair[.all]`, `rivet.rename[.format]`, `rivet.lore[.format]` |
| Help | `/help [page]` (`/rivethelp`, `/rhelp`) | `rivet.help` |
| Administration | `/rivet`, `/rivet reload` | `rivet.admin` |

</details>

### New feature examples

```text
/filter add diamond
/filter remove
/chatcolor <gradient:red:gold>
/chatcolor Alex <rainbow>
/bossbarmsg all -d:10 -c:red -s:segmented_10 <red><bold>Server restarting soon
/toast all -t:challenge -icon:diamond <gold><bold>Server event started!
/note Alex add Repeatedly ignored the build rules
/clear Alex diamond:32;plain -s
```

Chat colors accept one safe color tag. Gradients and rainbow formatting require `rivet.chatcolor.advanced`; command, click, hover, insertion, and other MiniMessage tags are rejected. The optional `;plain` clear selector matches only items without custom metadata and replaces obsolete legacy item-data matching.

Player notes are stored by UUID in `data/notes.yml`; every note records its ID, text, staff identity, and timestamp. Clearing notes requires the explicit `confirm` argument. `/sameip` is staff-only, checks current online sessions only, never stores or displays the raw address, filters vanished players, and reports that a match does not prove common ownership. Proxies and shared networks can produce matches.

Staff-scoped permissions include `rivet.inventory.clear.others`, `rivet.afk.others`, `rivet.afk.silent`, `rivet.afkcheck.others`, `rivet.chatcolor.others`, `rivet.flyspeed.others`, `rivet.bossbarmsg`, `rivet.notes`, `rivet.sameip`, `rivet.toast`, `rivet.jump`, `rivet.ride`, `rivet.top.others`, and `rivet.giveall`. Player defaults include `rivet.hat`, `rivet.list`, `rivet.me`, `rivet.ping`, and `rivet.playtime`; formatting in `/me` requires `rivet.me.format`.

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
│   ├── poses.yml
│   ├── backpacks.yml
│   ├── daily.yml
│   ├── rtp.yml
│   ├── near.yml
│   ├── filter.yml
│   └── help.yml
└── data/
    └── notes.yml  # generated after the first saved note
```

- `modules.yml` contains only feature switches. Disabled modules do not register their listeners or start their tasks, and their declared commands return a clean disabled message. Restart after changing a switch.
- `settings/` contains options owned by one module. For example, chat MiniMessage formats live in `settings/chat.yml`, permission groups in `settings/permissions.yml`, and the mob-head drop chance in `settings/mob-heads.yml`.
- `config.yml` contains only Rivet-wide visual feedback settings used by multiple modules.
- `data/` contains generated persistent state such as `notes.yml`, `filters.yml`, chat colors, homes, warps, graves/death locations, spawn, kit cooldowns, nicknames, backpacks, daily claims, RTP cooldowns, ignore/social-spy preferences, staff state, breeders, player glows, holograms, permission users, and tracked test worlds. Do not hand-edit these files while the server is running.

The new batch extends existing settings owners: `/me` uses `settings/chat.yml`; notes, same-IP text, and toast defaults use `settings/staff.yml`; hat feedback uses `settings/inventory.yml`; jump, list, ping, and ride use `settings/utilities.yml`; playtime uses `settings/statistics.yml`; and tree range uses `settings/worlds.yml`. `/rivet reload` applies these settings without reading or rewriting `data/notes.yml`.

The `worlds` module owns test worlds, biome search, world spawn, mob cleanup, crop-trample protection, and the flat-world spawn rule. `staff` owns gamemode, teleport, vanish, flight speed, boss bars, heal, feed, and god mode; `environment` owns time and weather; `inventory` owns inventory administration, condensing, donation, give-all, repair, rename, and lore editing. Social spy, ignore, and persisted chat colors extend `chat` instead of creating competing message handlers.

`/rivet reload` validates and reloads `config.yml`, `modules.yml`, and every file under `settings/` without reading or rewriting runtime files under `data/`. Invalid YAML leaves the active configuration untouched and reports the source file. Settings apply immediately where safe; module switch changes are listed and require a restart so listeners and tasks are never half-loaded.

Daily reward items use the same YAML item schema as kits. Rewards can also contain `experience` points and console `commands`; `<player>` is replaced with the claimant's exact name. Backpacks retain all 54 serialized slots internally, so reducing a player's visible row permission never deletes overflow items. RTP generates chunks asynchronously, then validates solid footing, two-block clearance, liquids, dangerous blocks, configured biomes, and Nether ceiling bounds on the server thread.

Kits are defined directly in `settings/kits.yml` with material, amount, display name, lore, enchantments, armour, offhand, and cooldown fields. Offline inventory editing is deliberately excluded: Rivet only opens live Paper inventories, avoiding unsafe direct player-data file writes. Poses use Paper's native fixed-pose API, so they do not create invisible seat entities.

### Upgrading an existing installation

Migration runs before modules start. Rivet moves the old root `chat.yml`, grave, glow, hologram, and permission files into the new layout. It also moves `homes`, `warps`, and `auto-breeders` out of `config.yml` without overwriting values already present in the new data files. Legacy test-world markers are imported into `data/worlds.yml` and retained as a compatibility fallback. New module switches are appended to existing `modules.yml` files without replacing existing choices. If both an old standalone file and its new destination already exist, Rivet keeps both untouched and uses the new destination.

## Build from source

```bash
git clone https://github.com/byalex33/Rivet.git
cd Rivet
mvn package
```

The complete Maven package build runs the 69-test suite and writes the shaded plugin to `/Users/alex/Documents/1mill crops/plugins/rivet-1.0-SNAPSHOT.jar` as configured in `pom.xml`. Maven's `clean` goal is intentionally not used in this workspace.

## Privacy-friendly metrics

Rivet uses [bStats](https://bstats.org/plugin/bukkit/Rivet/33219) for anonymous server and player counts. Server owners retain the global opt-out in `plugins/bStats/config.yml`.

---

<div align="center">
  <sub>Built for Paper. Kept intentionally lean.</sub>
</div>
