# Feature overview

## Player and survival features

| Feature | Module | Default | Description |
|---|---|---:|---|
| Homes | `homes` | Enabled | Named player homes with configurable limits and effects. |
| Warps | `warps` | Enabled | Named public server destinations. |
| Graves | `graves` | Enabled | Persistent death graves, recovery rules, tracking compasses, and `/back`. |
| Spawn | `spawn` | Enabled | Server spawn, join spawning, warmups, and movement cancellation. |
| Chat | `chat` | Enabled | MiniMessage chat, private messages, ignore lists, social spy, chat colors, and item links. |
| Kits | `kits` | Disabled | Configured item kits with permissions and persistent cooldowns. |
| AFK | `afk` | Enabled | Manual and automatic AFK state, reasons, sleep exclusion, and status checks. |
| Backpacks | `backpacks` | Disabled | Persistent personal storage with permission-controlled rows. |
| Daily rewards | `daily` | Disabled | Timestamp-based rewards, streaks, milestone rewards, experience, and commands. |
| Random teleport | `rtp` | Disabled | Asynchronous searches for safe random destinations. |
| Nearby players | `near` | Disabled | Visibility-aware nearby-player listing. |
| Pickup filters | `filter` | Enabled | Persistent per-player item pickup allowlists or denylists. |
| Statistics | `statistics` | Enabled | Native statistics, playtime, and last-seen information. |
| Nicknames | `nicknames` | Enabled | Persistent nicknames with controlled MiniMessage formatting. |
| Trash | `trash` | Enabled | A configurable disposable inventory. |
| Utilities | `utilities` | Enabled | Portable workstations, player lists, ping, jump, and riding tools. |
| Poses | `poses` | Disabled | Native Paper sitting, laying, and crawling poses. |

## World and gameplay features

| Feature | Module | Default | Description |
|---|---|---:|---|
| Auto breeders | `breeders` | Enabled | Persistent, species-specific automated breeders with a live next-breed hologram countdown, stored breeding XP, and chicken-egg collection. |
| Egg capture | `egg-capture` | Enabled | Capture supported mobs with thrown eggs and animated effects. |
| Creeper restoration | `creeper-restoration` | Enabled | Visual creeper debris and throwable Restoration Cores that reconstruct saved craters. |
| Tree felling | `tree-feller` | Enabled | Whole-tree cutting and connected ore vein mining with drops and XP clumped at the initially mined block. |
| Mob heads | `mob-heads` | Enabled | Configurable, Looting-aware mob-head drops and cached player heads, with vanilla Wither Skeleton skull drops preserved by default. |
| Villager reroll | `villager-reroll` | Enabled | Adds a non-consuming reroll offer to the vanilla trade screen using Paper's native trade generation. Untraded villagers with workstations are eligible by default. |
| Environment | `environment` | Enabled | Animated time changes and weather controls. |
| Worlds | `worlds` | Disabled | Tracked flat and void test worlds, biome search, world tools, and spawn controls. |
| Inventory tools | `inventory` | Disabled | Inventory administration, item creation, repair, editing, donation, and scanning. |

## Server administration features

| Feature | Module | Default | Description |
|---|---|---:|---|
| Staff tools | `staff` | Disabled | Gamemode, teleportation, vanish, flight, moderation notes, boss bars, toasts, and status tools. |
| Permissions | `permissions` | Disabled | Lightweight UUID-based users, groups, and wildcard nodes. |
| Holograms | `holograms` | Enabled | Persistent text, item, and block displays with animation and visibility controls. |
| Announcements | `announcements` | Disabled | Rotating MiniMessage announcements with sound and empty-server controls. |
| Join and leave | `join-leave` | Enabled | Join/leave messages, welcome titles, MOTD, and first-join behavior. |
| Help | `help` | Enabled | Permission-aware interactive command pages. |

## Creeper restoration

When a creeper destroys blocks, Rivet records their block data for the lifetime of the current server process. The explosion produces harmless visual debris and suppresses ordinary drops to prevent duplication.

An authorized administrator can create a Restoration Core with [`/restorationcore`](commands.md#restorationcore). A player right-clicks the core to throw it like a projectile. If it lands near a saved crater, one core is consumed and empty spaces are rebuilt with reverse-flight block animation. A missed core drops as an item and can be recovered.

Container restoration, container contents, other block-entity data, activation distance, timing, sounds, and particles are configurable in `settings/creeper-restoration.yml`. Crater snapshots are intentionally not persisted across restarts.

## Data and privacy

Rivet stores gameplay state in local YAML files under `plugins/Rivet/data/`. The [`/sameip`](commands.md#sameip) command compares current session addresses without saving or displaying the raw address. Rivet uses bStats for anonymous usage metrics; server owners can use the global bStats opt-out.
