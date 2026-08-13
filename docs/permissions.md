# Permissions

Rivet declares 147 permission nodes. Nodes with a default of `true` are available to all players. Nodes with a default of `op` are available to server operators. A default of `false` must be granted explicitly.

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
| `rivet.chat.color.red` | `true` | Selects the named red chat style. |
| `rivet.chat.color.gold` | `true` | Selects the named gold chat style. |
| `rivet.chat.color.aqua` | `true` | Selects the named aqua chat style. |
| `rivet.chat.color.pink` | `true` | Selects the named pink chat style. |
| `rivet.chat.color.*` | `op` | Selects every configured named color. |
| `rivet.chat.gradient.sunset` | `op` | Selects the sunset gradient. |
| `rivet.chat.gradient.ocean` | `op` | Selects the ocean gradient. |
| `rivet.chat.gradient.fire` | `op` | Selects the fire gradient. |
| `rivet.chat.gradient.*` | `op` | Selects every configured named gradient. |
| `rivet.chat.style.custom` | `op` | Allows custom hex colors, two-color gradients, and rainbow. |
| `rivet.chat.style.others` | `op` | Allows changing another player's chat style. |
| `rivet.chat.tag` | `true` | [`/tag`](commands.md#tag) |
| `rivet.chat.tag.og` | `false` | Selects the OG tag. |
| `rivet.chat.tag.builder` | `false` | Selects the Builder tag. |
| `rivet.chat.tag.aviation` | `false` | Selects the Aviation tag. |
| `rivet.chat.tag.*` | `op` | Selects every configured tag. |
| `rivet.chat.tag.others` | `op` | Allows changing another player's selected tag. |
| `rivet.chat.mention` | `true` | Allows mentioning players in public chat. |
| `rivet.chat.mention.notify` | `true` | Receives highlighted mentions and their configured sound. |
| `rivet.chat.mention.everyone` | `op` | Allows `@everyone`. |
| `rivet.chat.antispam.bypass` | `op` | Bypasses the lightweight chat cooldown and similarity check. |
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
| `rivet.permissions.manage` | `op` | [`/perm`](commands.md#perm) |
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
| `rivet.seen.location` | `op` | Shows current/last known coordinates and recorded death details in [`/seen`](commands.md#seen). |
| `rivet.snapshots.view` | `op` | Opens your own snapshot browser with [`/snapshot`](commands.md#snapshot). |
| `rivet.snapshots.others` | `op` | Allows [`/snapshot`](commands.md#snapshot) to browse another player's snapshots. |
| `rivet.snapshots.restore` | `op` | Restores a selected snapshot to its online target after the configured safety flow. |
| `rivet.snapshots.teleport` | `op` | Teleports to a selected snapshot's saved location. |
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

The optional `permissions` module provides UUID-based users, multiple groups per user, multiple parents per group, weights, prefix/suffix metadata, and `true`/`false` permission rules. It is disabled by default so Rivet does not add attachments or alter display names on servers using an external permission plugin.

Resolution is deliberately small and deterministic:

1. A matching direct user rule wins over every group rule.
2. Otherwise, the matching rule from the highest-weight assigned or inherited group wins.
3. Within one source, an exact node beats a wildcard; the longer wildcard beats a broader wildcard.
4. Equal-weight, equally specific conflicts prefer `false` so explicit denial is safe.
5. With no configured match, Paper's permission default is used.

Wildcards include `*`, `rivet.*`, and narrower forms such as `rivet.inventory.*`. Rivet expands configured results into Bukkit/Paper permission attachments, including explicit `false` values, so denies override wildcard grants and Paper permissions whose defaults are `true`.

Configure group weights, parents, permissions, prefixes, and suffixes in `settings/permissions.yml`. User memberships and direct permission states are stored in `data/permissions.yml`. Prefixes and suffixes accept safe visual MiniMessage formatting and fill the chat format's independent `%prefix%` and `%suffix%` placeholders while the module is enabled.

Use [`/perm`](commands.md#perm) for all management and debugging. Permission and group changes recalculate affected online players immediately; group-wide changes recalculate every online player.
