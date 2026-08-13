# Permissions

Rivet declares 121 permission nodes. Nodes with a default of `true` are available to all players. Nodes with a default of `op` are available to server operators. A default of `false` must be granted explicitly.

The command permission is checked by Paper before Rivet handles the command. Additional `.others`, formatting, bypass, row, and wildcard nodes enable narrower capabilities inside a command.

## Permission reference

| Permission | Default | Commands or purpose |
|---|---:|---|
| `rivet.gamemode` | `op` | [`/gmc`](commands.md#gmc), [`/gms`](commands.md#gms) |
| `rivet.flat` | `op` | [`/flat`](commands.md#flat) |
| `rivet.world` | `op` | [`/flatworld`](commands.md#flatworld), [`/voidworld`](commands.md#voidworld), [`/worldspawn`](commands.md#worldspawn), [`/setworldspawn`](commands.md#setworldspawn), [`/killall`](commands.md#killall) |
| `rivet.home` | `true` | [`/sethome`](commands.md#sethome), [`/home`](commands.md#home), [`/delhome`](commands.md#delhome) |
| `rivet.warp` | `true` | [`/warp`](commands.md#warp) |
| `rivet.warp.set` | `op` | [`/setwarp`](commands.md#setwarp), [`/delwarp`](commands.md#delwarp) |
| `rivet.environment` | `op` | [`/day`](commands.md#day), [`/night`](commands.md#night), [`/noon`](commands.md#noon), [`/midnight`](commands.md#midnight), [`/sun`](commands.md#sun), [`/rain`](commands.md#rain), [`/thunder`](commands.md#thunder) |
| `rivet.inventory` | `op` | [`/clear`](commands.md#clear), [`/i`](commands.md#i) |
| `rivet.inventory.clear.others` | `op` | Allows targeting another player in the related command. |
| `rivet.inventory.scan` | `op` | [`/scan`](commands.md#scan) |
| `rivet.condense` | `true` | [`/condense`](commands.md#condense) |
| `rivet.donate` | `true` | [`/donate`](commands.md#donate) |
| `rivet.giveall` | `op` | [`/giveall`](commands.md#giveall) |
| `rivet.givebreeder` | `op` | [`/givebreeder`](commands.md#givebreeder) |
| `rivet.clearhologram` | `op` | [`/clearhologram`](commands.md#clearhologram) |
| `rivet.restorationcore.give` | `op` | [`/restorationcore`](commands.md#restorationcore) |
| `rivet.message` | `true` | [`/msg`](commands.md#msg), [`/r`](commands.md#r) |
| `rivet.socialspy` | `op` | [`/socialspy`](commands.md#socialspy) |
| `rivet.ignore` | `true` | [`/ignore`](commands.md#ignore) |
| `rivet.ignore.bypass` | `op` | Bypasses the related restriction or cooldown. |
| `rivet.chatcolor` | `true` | [`/chatcolor`](commands.md#chatcolor) |
| `rivet.chatcolor.others` | `op` | Allows targeting another player in the related command. |
| `rivet.chatcolor.advanced` | `op` | Allows additional safe MiniMessage formatting. |
| `rivet.teleport` | `op` | [`/tp`](commands.md#tp) |
| `rivet.tppos` | `op` | [`/tppos`](commands.md#tppos) |
| `rivet.tp.nocooldown` | `op` | Bypasses shared and feature-specific teleport cooldowns. |
| `rivet.vanish` | `op` | [`/vanish`](commands.md#vanish) |
| `rivet.vanish.see` | `op` | Additional capability used by the related feature. |
| `rivet.fly` | `op` | [`/fly`](commands.md#fly) |
| `rivet.flyspeed` | `op` | [`/flyspeed`](commands.md#flyspeed) |
| `rivet.flyspeed.others` | `op` | Allows targeting another player in the related command. |
| `rivet.commandspy` | `op` | [`/commandspy`](commands.md#commandspy) |
| `rivet.heal` | `op` | [`/heal`](commands.md#heal) |
| `rivet.heal.others` | `op` | Allows targeting another player in the related command. |
| `rivet.feed` | `op` | [`/feed`](commands.md#feed) |
| `rivet.feed.others` | `op` | Allows targeting another player in the related command. |
| `rivet.god` | `op` | [`/god`](commands.md#god) |
| `rivet.god.others` | `op` | Allows targeting another player in the related command. |
| `rivet.bossbarmsg` | `op` | [`/bossbarmsg`](commands.md#bossbarmsg) |
| `rivet.permissions.manage` | `op` | [`/perm`](commands.md#perm), [`/group`](commands.md#group) |
| `rivet.holograms` | `op` | [`/hologram`](commands.md#hologram) |
| `rivet.holograms.view.*` | `false` | Wildcard parent for the related permission family. |
| `rivet.eggcapture` | `true` | Additional capability used by the related feature. |
| `rivet.villager-reroll` | `true` | Allows using the reroll offer in eligible villagers' normal trade screens. |
| `rivet.spawn` | `true` | [`/spawn`](commands.md#spawn) |
| `rivet.spawn.set` | `op` | [`/setspawn`](commands.md#setspawn) |
| `rivet.tpa` | `true` | [`/tpa`](commands.md#tpa), [`/tpahere`](commands.md#tpahere), [`/tpaccept`](commands.md#tpaccept), [`/tpdeny`](commands.md#tpdeny) |
| `rivet.kit` | `true` | [`/kit`](commands.md#kit) |
| `rivet.kit.starter` | `true` | Additional capability used by the related feature. |
| `rivet.kit.*` | `op` | Wildcard parent for the related permission family. |
| `rivet.back` | `true` | Uses [`/back`](commands.md#back) to return to the newest valid internal history location. No extra history permission is required. |
| `rivet.afk` | `true` | [`/afk`](commands.md#afk) |
| `rivet.afk.others` | `op` | Allows targeting another player in the related command. |
| `rivet.afk.silent` | `op` | Additional capability used by the related feature. |
| `rivet.afkcheck` | `true` | [`/afkcheck`](commands.md#afkcheck) |
| `rivet.afkcheck.others` | `op` | Allows targeting another player in the related command. |
| `rivet.inventory.invsee` | `op` | [`/invsee`](commands.md#invsee) |
| `rivet.inventory.enderchest` | `true` | [`/enderchest`](commands.md#enderchest) |
| `rivet.inventory.enderchest.others` | `op` | Allows targeting another player in the related command. |
| `rivet.repair` | `op` | [`/repair`](commands.md#repair) |
| `rivet.repair.all` | `op` | Additional capability used by the related feature. |
| `rivet.rename` | `op` | [`/rename`](commands.md#rename) |
| `rivet.rename.format` | `op` | Allows additional safe MiniMessage formatting. |
| `rivet.lore` | `op` | [`/lore`](commands.md#lore) |
| `rivet.lore.format` | `op` | Allows additional safe MiniMessage formatting. |
| `rivet.nick` | `true` | [`/nick`](commands.md#nick) |
| `rivet.nick.format` | `op` | Allows additional safe MiniMessage formatting. |
| `rivet.nick.others` | `op` | Allows targeting another player in the related command. |
| `rivet.stats` | `true` | [`/stats`](commands.md#stats) |
| `rivet.stats.others` | `op` | Allows targeting another player in the related command. |
| `rivet.trash` | `true` | [`/trash`](commands.md#trash) |
| `rivet.utility.craft` | `true` | [`/craft`](commands.md#craft) |
| `rivet.utility.anvil` | `true` | [`/anvil`](commands.md#anvil) |
| `rivet.utility.smithing` | `true` | [`/smithing`](commands.md#smithing) |
| `rivet.utility.stonecutter` | `true` | [`/stonecutter`](commands.md#stonecutter) |
| `rivet.utility.grindstone` | `true` | [`/grindstone`](commands.md#grindstone) |
| `rivet.pose.sit` | `true` | [`/sit`](commands.md#sit) |
| `rivet.pose.lay` | `true` | [`/lay`](commands.md#lay) |
| `rivet.pose.crawl` | `true` | [`/crawl`](commands.md#crawl) |
| `rivet.head` | `op` | [`/head`](commands.md#head) |
| `rivet.backpack` | `true` | [`/backpack`](commands.md#backpack) |
| `rivet.backpack.rows.1` | `false` | Controls the related backpack row count. |
| `rivet.backpack.rows.2` | `false` | Controls the related backpack row count. |
| `rivet.backpack.rows.3` | `false` | Controls the related backpack row count. |
| `rivet.backpack.rows.4` | `false` | Controls the related backpack row count. |
| `rivet.backpack.rows.5` | `false` | Controls the related backpack row count. |
| `rivet.backpack.rows.6` | `false` | Controls the related backpack row count. |
| `rivet.backpack.rows.*` | `op` | Controls the related backpack row count. |
| `rivet.daily` | `true` | [`/daily`](commands.md#daily) |
| `rivet.rtp` | `true` | [`/rtp`](commands.md#rtp) |
| `rivet.rtp.world` | `op` | Additional capability used by the related feature. |
| `rivet.rtp.cooldown.bypass` | `op` | Bypasses the related restriction or cooldown. |
| `rivet.near` | `true` | [`/near`](commands.md#near) |
| `rivet.findbiome` | `true` | [`/findbiome`](commands.md#findbiome) |
| `rivet.filter` | `true` | [`/filter`](commands.md#filter) |
| `rivet.filter.admin` | `op` | Additional capability used by the related feature. |
| `rivet.hat` | `true` | [`/hat`](commands.md#hat) |
| `rivet.jump` | `op` | [`/jump`](commands.md#jump) |
| `rivet.list` | `true` | [`/list`](commands.md#list) |
| `rivet.me` | `true` | [`/me`](commands.md#me) |
| `rivet.me.format` | `op` | Allows additional safe MiniMessage formatting. |
| `rivet.notes` | `op` | [`/note`](commands.md#note) |
| `rivet.ping` | `true` | [`/ping`](commands.md#ping) |
| `rivet.ping.others` | `op` | Allows targeting another player in the related command. |
| `rivet.playtime` | `true` | [`/playtime`](commands.md#playtime) |
| `rivet.playtime.others` | `op` | Allows targeting another player in the related command. |
| `rivet.seen` | `true` | [`/seen`](commands.md#seen) |
| `rivet.ride` | `op` | [`/ride`](commands.md#ride) |
| `rivet.sameip` | `op` | [`/sameip`](commands.md#sameip) |
| `rivet.toast` | `op` | [`/toast`](commands.md#toast) |
| `rivet.top` | `op` | [`/top`](commands.md#top) |
| `rivet.top.others` | `op` | Allows targeting another player in the related command. |
| `rivet.tree` | `op` | [`/tree`](commands.md#tree) |
| `rivet.help` | `true` | [`/help`](commands.md#help) |
| `rivet.lagg` | `op` | [`/lagg clear`](commands.md#lagg), [`/lagg reload`](commands.md#lagg) |
| `rivet.logs.lookup` | `op` | Searches audit history and changes lookup pages with [`/log lookup`](commands.md#log) and [`/log page`](commands.md#log). |
| `rivet.logs.inspect` | `op` | Toggles block and container inspector mode with [`/log inspect`](commands.md#log). |
| `rivet.logs.reload` | `op` | Reloads `settings/logs.yml` with [`/log reload`](commands.md#log). |
| `rivet.logs.commands` | `op` | Includes command records in audit lookup results when command logging is enabled. |
| `rivet.logs.teleport` | `op` | Makes audit coordinates clickable teleport destinations. |
| `rivet.admin` | `op` | [`/rivet`](commands.md#rivet) |

## Rivet permission module

The optional `permissions` module provides local users, groups, inheritance, and wildcard matching. It is disabled by default so Rivet can coexist with an existing permission plugin.

- Use [`/perm`](commands.md#perm) to add, remove, list, check, or reload user permissions.
- Use [`/group`](commands.md#group) to assign users and inspect configured groups.
- Configure groups in `settings/permissions.yml`.
- User assignments and direct nodes are stored under `data/permissions.yml`.

Permission changes are applied to online players immediately when the module is active.
