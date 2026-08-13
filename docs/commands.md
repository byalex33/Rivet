# Command reference

Rivet registers 99 commands. Every command below has a stable link used by the documentation sidebar.

## Syntax conventions

- `<value>` is required.
- `[value]` is optional.
- `a|b` means choose one value.
- Commands are case-insensitive. Player names may remain case-sensitive where an exact online lookup is required.
- A permission default of `true` is available to all players; `op` is available to operators by default.

## Command index

| Category | Commands |
|---|---|
| Homes and warps | [`/sethome`](#sethome), [`/home`](#home), [`/delhome`](#delhome), [`/setwarp`](#setwarp), [`/warp`](#warp), [`/delwarp`](#delwarp) |
| Teleportation | [`/spawn`](#spawn), [`/setspawn`](#setspawn), [`/tpa`](#tpa), [`/tpahere`](#tpahere), [`/tpaccept`](#tpaccept), [`/tpdeny`](#tpdeny), [`/rtp`](#rtp), [`/near`](#near), [`/back`](#back), [`/tp`](#tp), [`/tppos`](#tppos) |
| Chat and identity | [`/msg`](#msg), [`/r`](#r), [`/socialspy`](#socialspy), [`/ignore`](#ignore), [`/chatcolor`](#chatcolor), [`/tag`](#tag), [`/me`](#me), [`/nick`](#nick), [`/afk`](#afk), [`/afkcheck`](#afkcheck) |
| Inventory and items | [`/clear`](#clear), [`/i`](#i), [`/condense`](#condense), [`/donate`](#donate), [`/giveall`](#giveall), [`/scan`](#scan), [`/invsee`](#invsee), [`/enderchest`](#enderchest), [`/repair`](#repair), [`/rename`](#rename), [`/lore`](#lore), [`/hat`](#hat), [`/trash`](#trash), [`/backpack`](#backpack) |
| World and environment | [`/flat`](#flat), [`/flatworld`](#flatworld), [`/voidworld`](#voidworld), [`/worldspawn`](#worldspawn), [`/setworldspawn`](#setworldspawn), [`/killall`](#killall), [`/findbiome`](#findbiome), [`/top`](#top), [`/tree`](#tree), [`/day`](#day), [`/night`](#night), [`/noon`](#noon), [`/midnight`](#midnight), [`/sun`](#sun), [`/rain`](#rain), [`/thunder`](#thunder) |
| Gameplay systems | [`/givebreeder`](#givebreeder), [`/restorationcore`](#restorationcore), [`/kit`](#kit), [`/daily`](#daily), [`/filter`](#filter), [`/head`](#head) |
| Player information and utilities | [`/stats`](#stats), [`/playtime`](#playtime), [`/seen`](#seen), [`/craft`](#craft), [`/anvil`](#anvil), [`/smithing`](#smithing), [`/stonecutter`](#stonecutter), [`/grindstone`](#grindstone), [`/jump`](#jump), [`/list`](#list), [`/ping`](#ping), [`/ride`](#ride), [`/sit`](#sit), [`/lay`](#lay), [`/crawl`](#crawl) |
| Staff and moderation | [`/gmc`](#gmc), [`/gms`](#gms), [`/vanish`](#vanish), [`/fly`](#fly), [`/flyspeed`](#flyspeed), [`/commandspy`](#commandspy), [`/heal`](#heal), [`/feed`](#feed), [`/god`](#god), [`/bossbarmsg`](#bossbarmsg), [`/note`](#note), [`/sameip`](#sameip), [`/toast`](#toast) |
| Server administration | [`/perm`](#perm), [`/hologram`](#hologram), [`/clearhologram`](#clearhologram), [`/lagg`](#lagg), [`/log`](#log), [`/snapshot`](#snapshot), [`/help`](#help), [`/rivet`](#rivet) |

## Homes and warps

<a id="sethome"></a>

### `/sethome`

- **Syntax:** `/sethome [name]`
- **Permission:** `rivet.home`
- **Default:** `true`
- **Aliases:** None

Set your home.


<a id="home"></a>

### `/home`

- **Syntax:** `/home [name]`
- **Permission:** `rivet.home`
- **Default:** `true`
- **Aliases:** None

Teleport home.


<a id="delhome"></a>

### `/delhome`

- **Syntax:** `/delhome [name]`
- **Permission:** `rivet.home`
- **Default:** `true`
- **Aliases:** None

Delete a home.


<a id="setwarp"></a>

### `/setwarp`

- **Syntax:** `/setwarp <name>`
- **Permission:** `rivet.warp.set`
- **Default:** `op`
- **Aliases:** None

Set a public warp.


<a id="warp"></a>

### `/warp`

- **Syntax:** `/warp <name>`
- **Permission:** `rivet.warp`
- **Default:** `true`
- **Aliases:** None

Teleport to a public warp.


<a id="delwarp"></a>

### `/delwarp`

- **Syntax:** `/delwarp <name>`
- **Permission:** `rivet.warp.set`
- **Default:** `op`
- **Aliases:** None

Delete a public warp.


## Teleportation

Player-facing destination commands use the warmup, movement cancellation, and shared cooldown configured in `settings/teleports.yml`. `/back`, TPA requests, and `/rtp` also keep their feature-specific cooldowns. `rivet.tp.nocooldown`, granted to operators by default, bypasses the warmup and every shared or feature-specific teleport cooldown. Staff and test-world movement commands remain immediate.

<a id="spawn"></a>

### `/spawn`

- **Syntax:** `/spawn`
- **Permission:** `rivet.spawn`
- **Default:** `true`
- **Aliases:** None

Teleport to the server spawn.


<a id="setspawn"></a>

### `/setspawn`

- **Syntax:** `/setspawn`
- **Permission:** `rivet.spawn.set`
- **Default:** `op`
- **Aliases:** None

Set the server spawn.


<a id="tpa"></a>

### `/tpa`

- **Syntax:** `/tpa <player>`
- **Permission:** `rivet.tpa`
- **Default:** `true`
- **Aliases:** None

Request to teleport to a player.

Request expiry and request throttling use `settings/tpa.yml`; an accepted teleport uses the shared policy in `settings/teleports.yml`.

<a id="tpahere"></a>

### `/tpahere`

- **Syntax:** `/tpahere <player>`
- **Permission:** `rivet.tpa`
- **Default:** `true`
- **Aliases:** None

Request that a player teleports to you.

Requests that another player teleport to your location.

<a id="tpaccept"></a>

### `/tpaccept`

- **Syntax:** `/tpaccept [player]`
- **Permission:** `rivet.tpa`
- **Default:** `true`
- **Aliases:** None

Accept a teleport request.


<a id="tpdeny"></a>

### `/tpdeny`

- **Syntax:** `/tpdeny [player]`
- **Permission:** `rivet.tpa`
- **Default:** `true`
- **Aliases:** None

Deny a teleport request.


<a id="rtp"></a>

### `/rtp`

- **Syntax:** `/rtp [world]`
- **Permission:** `rivet.rtp`
- **Default:** `true`
- **Aliases:** None

Teleport to a safe random location.

Specifying a world requires `rivet.rtp.world`. Safe destination checks and the RTP-specific cooldown use `settings/rtp.yml`; teleport timing uses `settings/teleports.yml`.

<a id="near"></a>

### `/near`

- **Syntax:** `/near`
- **Permission:** `rivet.near`
- **Default:** `true`
- **Aliases:** None

List nearby visible players.


<a id="back"></a>

### `/back`

- **Syntax:** `/back`
- **Permission:** `rivet.back`
- **Default:** `true`
- **Aliases:** None

Return to your most recent useful location. Rivet records deaths and the origin of meaningful teleports such as homes, warps, spawn, accepted TPA requests, RTP, portals, and direct staff or utility teleports.

The history is internal and is never addressed by number. A successful `/back` consumes the chosen entry instead of recording another return trip, which prevents the command from endlessly bouncing between two places. Tiny or internal movement corrections are ignored. History and the command's feature-specific cooldown survive restarts.


<a id="tp"></a>

### `/tp`

- **Syntax:** `/tp <player>`
- **Permission:** `rivet.teleport`
- **Default:** `op`
- **Aliases:** None

Teleport to a player.


<a id="tppos"></a>

### `/tppos`

- **Syntax:** `/tppos <x> <y> <z>`
- **Permission:** `rivet.tppos`
- **Default:** `op`
- **Aliases:** None

Teleport to coordinates in the current world.

Accepts decimals and relative coordinates such as `~`, `~10`, and `~-5`. The current world and facing direction are preserved.

## Chat and identity

<a id="msg"></a>

### `/msg`

- **Syntax:** `/msg <player> <message>`
- **Permission:** `rivet.message`
- **Default:** `true`
- **Aliases:** None

Privately message a player.


<a id="r"></a>

### `/r`

- **Syntax:** `/r <message>`
- **Permission:** `rivet.message`
- **Default:** `true`
- **Aliases:** None

Reply to your last private message.


<a id="socialspy"></a>

### `/socialspy`

- **Syntax:** `/socialspy`
- **Permission:** `rivet.socialspy`
- **Default:** `op`
- **Aliases:** `/ss`

Toggle private-message social spy.


<a id="ignore"></a>

### `/ignore`

- **Syntax:** `/ignore <player|list|clear>`
- **Permission:** `rivet.ignore`
- **Default:** `true`
- **Aliases:** None

Manage ignored private-message senders.

Use a player name to toggle an ignore, `list` to view ignored players, or `clear` to reset the list.

<a id="chatcolor"></a>

### `/chatcolor`

- **Syntax:** `/chatcolor [player] <color|#hex|gradient start end|rainbow|reset>`
- **Permission:** `rivet.chatcolor`
- **Default:** `true`
- **Aliases:** None

Choose a persistent style for the message body. With no arguments, the command opens a simple inventory selector. Named colors and gradients use their matching `rivet.chat.color.<name>` or `rivet.chat.gradient.<name>` permission. Custom hex colors, custom two-color gradients, and rainbow use `rivet.chat.style.custom`; targeting another player uses `rivet.chat.style.others`.

The legacy `rivet.chatcolor.advanced` and `rivet.chatcolor.others` nodes remain accepted for existing installations.

<a id="tag"></a>

### `/tag`

- **Syntax:** `/tag <set [player] tag|reset [player]|list>`
- **Permission:** `rivet.chat.tag`
- **Default:** `true`
- **Aliases:** None

Choose a cosmetic tag kept separate from permission-group prefixes and suffixes. `/tag` opens a GUI containing only tags the player may use; `/tag list` provides the text equivalent. A tag requires `rivet.chat.tag.<name>` or `rivet.chat.tag.*`, and changing another player's tag requires `rivet.chat.tag.others`.

<a id="me"></a>

### `/me`

- **Syntax:** `/me <message>`
- **Permission:** `rivet.me`
- **Default:** `true`
- **Aliases:** None

Broadcast an action message.


<a id="nick"></a>

### `/nick`

- **Syntax:** `/nick <nickname|off>`
- **Permission:** `rivet.nick`
- **Default:** `true`
- **Aliases:** None

Set or remove a nickname.


<a id="afk"></a>

### `/afk`

- **Syntax:** `/afk [reason] [-p:player] [-s]`
- **Permission:** `rivet.afk`
- **Default:** `true`
- **Aliases:** None

Toggle AFK status with an optional reason.

Use `-p:<player>` when permitted to change another player's state and `-s` for a silent change.

<a id="afkcheck"></a>

### `/afkcheck`

- **Syntax:** `/afkcheck [player|all]`
- **Permission:** `rivet.afkcheck`
- **Default:** `true`
- **Aliases:** None

Inspect online AFK status and duration.


## Inventory and items

<a id="clear"></a>

### `/clear`

- **Syntax:** `/clear [player] [item[:amount][;plain]] [-s]`
- **Permission:** `rivet.inventory`
- **Default:** `op`
- **Aliases:** None

Clear all or selected items from an inventory.

The optional item selector supports `material:amount`; append `;plain` to match only items without custom metadata. Use `-s` for silent operation.

<a id="i"></a>

### `/i`

- **Syntax:** `/i <item> [amount]`
- **Permission:** `rivet.inventory`
- **Default:** `op`
- **Aliases:** None

Give yourself an item.


<a id="condense"></a>

### `/condense`

- **Syntax:** `/condense`
- **Permission:** `rivet.condense`
- **Default:** `true`
- **Aliases:** None

Condense plain materials into storage blocks.


<a id="donate"></a>

### `/donate`

- **Syntax:** `/donate <player> [amount]`
- **Permission:** `rivet.donate`
- **Default:** `true`
- **Aliases:** None

Donate held items to an online player.


<a id="giveall"></a>

### `/giveall`

- **Syntax:** `/giveall <item> [amount]`
- **Permission:** `rivet.giveall`
- **Default:** `op`
- **Aliases:** None

Give an item to every online player.


<a id="scan"></a>

### `/scan`

- **Syntax:** `/scan <item> [world|all]`
- **Permission:** `rivet.inventory.scan`
- **Default:** `op`
- **Aliases:** None

Scan saved world containers for an item.

Use `/scan status` to inspect a running scan or `/scan cancel` to stop it. Scans inspect saved region data and nested storage without generating unexplored chunks.

<a id="invsee"></a>

### `/invsee`

- **Syntax:** `/invsee <player>`
- **Permission:** `rivet.inventory.invsee`
- **Default:** `op`
- **Aliases:** None

Open an online player's inventory.


<a id="enderchest"></a>

### `/enderchest`

- **Syntax:** `/enderchest [player]`
- **Permission:** `rivet.inventory.enderchest`
- **Default:** `true`
- **Aliases:** `/ec`

Open your or another online player's ender chest.


<a id="repair"></a>

### `/repair`

- **Syntax:** `/repair [all]`
- **Permission:** `rivet.repair`
- **Default:** `op`
- **Aliases:** None

Repair a held item or all carried equipment.

Use `all` to repair carried equipment when the additional `rivet.repair.all` permission is granted.

<a id="rename"></a>

### `/rename`

- **Syntax:** `/rename <name|clear>`
- **Permission:** `rivet.rename`
- **Default:** `op`
- **Aliases:** None

Rename the held item.


<a id="lore"></a>

### `/lore`

- **Syntax:** `/lore <add|set|remove|clear>`
- **Permission:** `rivet.lore`
- **Default:** `op`
- **Aliases:** None

Edit lore on the held item.

Supports `add <text>`, `set <line> <text>`, `remove <line>`, and `clear`.

<a id="hat"></a>

### `/hat`

- **Syntax:** `/hat`
- **Permission:** `rivet.hat`
- **Default:** `true`
- **Aliases:** None

Wear the item in your main hand.


<a id="trash"></a>

### `/trash`

- **Syntax:** `/trash`
- **Permission:** `rivet.trash`
- **Default:** `true`
- **Aliases:** None

Open a disposable item inventory.


<a id="backpack"></a>

### `/backpack`

- **Syntax:** `/backpack`
- **Permission:** `rivet.backpack`
- **Default:** `true`
- **Aliases:** `/bp`

Open your persistent personal backpack.

Alias: `/bp`. Visible rows are selected by `rivet.backpack.rows.1` through `.6`; hidden overflow is retained.

## World and environment

<a id="flat"></a>

### `/flat`

- **Syntax:** `/flat`
- **Permission:** `rivet.flat`
- **Default:** `op`
- **Aliases:** None

Create or teleport to the flat test world.

Uses Rivet's legacy flat test-world shortcut.

<a id="flatworld"></a>

### `/flatworld`

- **Syntax:** `/flatworld <create|tp|reset|list> [name]`
- **Permission:** `rivet.world`
- **Default:** `op`
- **Aliases:** None

Manage flat testing worlds.

Use `create`, `tp`, `reset`, or `list`. Tracked test worlds are managed by the worlds module.

<a id="voidworld"></a>

### `/voidworld`

- **Syntax:** `/voidworld create <name>`
- **Permission:** `rivet.world`
- **Default:** `op`
- **Aliases:** None

Create void testing worlds.

Creates a tracked empty test world.

<a id="worldspawn"></a>

### `/worldspawn`

- **Syntax:** `/worldspawn`
- **Permission:** `rivet.world`
- **Default:** `op`
- **Aliases:** None

Teleport to the current world's spawn.


<a id="setworldspawn"></a>

### `/setworldspawn`

- **Syntax:** `/setworldspawn`
- **Permission:** `rivet.world`
- **Default:** `op`
- **Aliases:** None

Set the current world's spawn.


<a id="killall"></a>

### `/killall`

- **Syntax:** `/killall`
- **Permission:** `rivet.world`
- **Default:** `op`
- **Aliases:** None

Remove all mobs from the current world.


<a id="findbiome"></a>

### `/findbiome`

- **Syntax:** `/findbiome <biome>`
- **Permission:** `rivet.findbiome`
- **Default:** `true`
- **Aliases:** None

Find the nearest matching biome.


<a id="top"></a>

### `/top`

- **Syntax:** `/top [player]`
- **Permission:** `rivet.top`
- **Default:** `op`
- **Aliases:** None

Teleport to the highest safe location.

Targets yourself by default. Targeting another player requires `rivet.top.others`.

<a id="tree"></a>

### `/tree`

- **Syntax:** `/tree <treeType>`
- **Permission:** `rivet.tree`
- **Default:** `op`
- **Aliases:** None

Generate a vanilla tree at the targeted block.


<a id="day"></a>

### `/day`

- **Syntax:** `/day`
- **Permission:** `rivet.environment`
- **Default:** `op`
- **Aliases:** None

Fast-forward the current world to day.

Smoothly advances time to day using the configured transition.

<a id="night"></a>

### `/night`

- **Syntax:** `/night`
- **Permission:** `rivet.environment`
- **Default:** `op`
- **Aliases:** None

Fast-forward the current world to night.

Smoothly advances time to night using the configured transition.

<a id="noon"></a>

### `/noon`

- **Syntax:** `/noon`
- **Permission:** `rivet.environment`
- **Default:** `op`
- **Aliases:** None

Set the current world to noon.


<a id="midnight"></a>

### `/midnight`

- **Syntax:** `/midnight`
- **Permission:** `rivet.environment`
- **Default:** `op`
- **Aliases:** None

Set the current world to midnight.


<a id="sun"></a>

### `/sun`

- **Syntax:** `/sun`
- **Permission:** `rivet.environment`
- **Default:** `op`
- **Aliases:** None

Clear the current world's weather.


<a id="rain"></a>

### `/rain`

- **Syntax:** `/rain`
- **Permission:** `rivet.environment`
- **Default:** `op`
- **Aliases:** None

Start rain in the current world.


<a id="thunder"></a>

### `/thunder`

- **Syntax:** `/thunder`
- **Permission:** `rivet.environment`
- **Default:** `op`
- **Aliases:** None

Start a thunderstorm in the current world.


## Gameplay systems

<a id="givebreeder"></a>

### `/givebreeder`

- **Syntax:** `/givebreeder <animal> [amount] or /givebreeder <player> <animal> [amount]`
- **Permission:** `rivet.givebreeder`
- **Default:** `op`
- **Aliases:** `/giveautobreeder`

Give an animal-specific auto breeder.

The first form gives a breeder to yourself. The second form targets another online player. Supported animals are available through tab completion. Each placed breeder's hologram includes a live countdown to the next breeding check.

<a id="restorationcore"></a>

### `/restorationcore`

- **Syntax:** `/restorationcore [player] [amount]`
- **Permission:** `rivet.restorationcore.give`
- **Default:** `op`
- **Aliases:** `/givecore`

Give one or more throwable Restoration Cores.

Creates throwable Restoration Cores. Right-clicking a core launches it; impact near a saved creeper crater starts reconstruction. Missed cores drop for recovery.

<a id="kit"></a>

### `/kit`

- **Syntax:** `/kit [name]`
- **Permission:** `rivet.kit`
- **Default:** `true`
- **Aliases:** None

List or receive a configured kit.


<a id="daily"></a>

### `/daily`

- **Syntax:** `/daily`
- **Permission:** `rivet.daily`
- **Default:** `true`
- **Aliases:** None

Claim your daily reward.


<a id="filter"></a>

### `/filter`

- **Syntax:** `/filter [add|remove|list|clear|toggle|help]`
- **Permission:** `rivet.filter`
- **Default:** `true`
- **Aliases:** None

Manage your persistent item pickup filter.

Supports `add [item]`, `remove`, `list`, `clear`, `toggle`, and `help`.

<a id="head"></a>

### `/head`

- **Syntax:** `/head <player>`
- **Permission:** `rivet.head`
- **Default:** `op`
- **Aliases:** None

Give yourself a cached player's head.


## Player information and utilities

<a id="stats"></a>

### `/stats`

- **Syntax:** `/stats [player]`
- **Permission:** `rivet.stats`
- **Default:** `true`
- **Aliases:** None

Show player statistics.


<a id="playtime"></a>

### `/playtime`

- **Syntax:** `/playtime [player]`
- **Permission:** `rivet.playtime`
- **Default:** `true`
- **Aliases:** None

Show native player playtime.


<a id="seen"></a>

### `/seen`

- **Syntax:** `/seen <player>`
- **Permission:** `rivet.seen`
- **Default:** `true`
- **Aliases:** None

Show a compact player activity summary: online/offline state, first join, last login,
last logout, total playtime, and the current session duration when online. Relative
times keep the output readable; hover them to see the exact configured date and time.

Viewers with `rivet.seen.location` also see the current or last known world and block
coordinates, plus the most recently recorded death time and location when available.
Rivet stores only logout/location and death details that Paper does not expose reliably;
join and playtime values come from Paper's native player data and statistics.

Vanished online players appear unavailable to viewers who cannot see them and are also
excluded from those viewers' tab completions.

<a id="craft"></a>

### `/craft`

- **Syntax:** `/craft`
- **Permission:** `rivet.utility.craft`
- **Default:** `true`
- **Aliases:** None

Open a portable crafting table.


<a id="anvil"></a>

### `/anvil`

- **Syntax:** `/anvil`
- **Permission:** `rivet.utility.anvil`
- **Default:** `true`
- **Aliases:** None

Open a portable anvil.


<a id="smithing"></a>

### `/smithing`

- **Syntax:** `/smithing`
- **Permission:** `rivet.utility.smithing`
- **Default:** `true`
- **Aliases:** None

Open a portable smithing table.


<a id="stonecutter"></a>

### `/stonecutter`

- **Syntax:** `/stonecutter`
- **Permission:** `rivet.utility.stonecutter`
- **Default:** `true`
- **Aliases:** None

Open a portable stonecutter.


<a id="grindstone"></a>

### `/grindstone`

- **Syntax:** `/grindstone`
- **Permission:** `rivet.utility.grindstone`
- **Default:** `true`
- **Aliases:** None

Open a portable grindstone.


<a id="jump"></a>

### `/jump`

- **Syntax:** `/jump`
- **Permission:** `rivet.jump`
- **Default:** `op`
- **Aliases:** None

Teleport safely to the block you are looking at.


<a id="list"></a>

### `/list`

- **Syntax:** `/list`
- **Permission:** `rivet.list`
- **Default:** `true`
- **Aliases:** None

List visible online players.


<a id="ping"></a>

### `/ping`

- **Syntax:** `/ping [player]`
- **Permission:** `rivet.ping`
- **Default:** `true`
- **Aliases:** None

Show network latency.


<a id="ride"></a>

### `/ride`

- **Syntax:** `/ride`
- **Permission:** `rivet.ride`
- **Default:** `op`
- **Aliases:** None

Ride the entity you are looking at.


<a id="sit"></a>

### `/sit`

- **Syntax:** `/sit`
- **Permission:** `rivet.pose.sit`
- **Default:** `true`
- **Aliases:** None

Toggle a sitting pose.


<a id="lay"></a>

### `/lay`

- **Syntax:** `/lay`
- **Permission:** `rivet.pose.lay`
- **Default:** `true`
- **Aliases:** None

Toggle a laying pose.


<a id="crawl"></a>

### `/crawl`

- **Syntax:** `/crawl`
- **Permission:** `rivet.pose.crawl`
- **Default:** `true`
- **Aliases:** None

Toggle a crawling pose.


## Staff and moderation

<a id="gmc"></a>

### `/gmc`

- **Syntax:** `/gmc`
- **Permission:** `rivet.gamemode`
- **Default:** `op`
- **Aliases:** None

Switch to creative mode.


<a id="gms"></a>

### `/gms`

- **Syntax:** `/gms`
- **Permission:** `rivet.gamemode`
- **Default:** `op`
- **Aliases:** None

Switch to survival mode.


<a id="vanish"></a>

### `/vanish`

- **Syntax:** `/vanish`
- **Permission:** `rivet.vanish`
- **Default:** `op`
- **Aliases:** `/v`

Toggle staff vanish.


<a id="fly"></a>

### `/fly`

- **Syntax:** `/fly`
- **Permission:** `rivet.fly`
- **Default:** `op`
- **Aliases:** None

Toggle flight.


<a id="flyspeed"></a>

### `/flyspeed`

- **Syntax:** `/flyspeed [player] <amount>`
- **Permission:** `rivet.flyspeed`
- **Default:** `op`
- **Aliases:** None

Set your or another player's flight speed.

Values must be within Paper's supported flight-speed range. Targeting another player requires `rivet.flyspeed.others`.

<a id="commandspy"></a>

### `/commandspy`

- **Syntax:** `/commandspy`
- **Permission:** `rivet.commandspy`
- **Default:** `op`
- **Aliases:** `/cspy`

Toggle staff command monitoring.


<a id="heal"></a>

### `/heal`

- **Syntax:** `/heal [player]`
- **Permission:** `rivet.heal`
- **Default:** `op`
- **Aliases:** None

Restore health for yourself or another player.


<a id="feed"></a>

### `/feed`

- **Syntax:** `/feed [player]`
- **Permission:** `rivet.feed`
- **Default:** `op`
- **Aliases:** None

Restore hunger for yourself or another player.


<a id="god"></a>

### `/god`

- **Syntax:** `/god [player] [true|false] [-s]`
- **Permission:** `rivet.god`
- **Default:** `op`
- **Aliases:** None

Toggle invulnerability for yourself or another player.

Use `true` or `false` to set an explicit state. Use `-s` to suppress target feedback.

<a id="bossbarmsg"></a>

### `/bossbarmsg`

- **Syntax:** `/bossbarmsg <player|all> [flags] <message>`
- **Permission:** `rivet.bossbarmsg`
- **Default:** `op`
- **Aliases:** None

Show a timed MiniMessage boss bar.

Optional flags are `-d:seconds`, `-c:color`, and `-s:style`.

<a id="note"></a>

### `/note`

- **Syntax:** `/note <player> <add|remove|clear|list>`
- **Permission:** `rivet.notes`
- **Default:** `op`
- **Aliases:** None

Manage UUID-keyed staff notes.

Supports `add <note>`, `remove <id>`, `list`, and `clear confirm`. Notes are stored by player UUID.

<a id="sameip"></a>

### `/sameip`

- **Syntax:** `/sameip [player]`
- **Permission:** `rivet.sameip`
- **Default:** `op`
- **Aliases:** None

Find visible online players sharing a session address.

Checks current visible online sessions. It does not store or display the raw address.

<a id="toast"></a>

### `/toast`

- **Syntax:** `/toast <player|all> i:<icon> t:<title> <message> [type:<type>]`
- **Permission:** `rivet.toast`
- **Default:** `op`
- **Aliases:** None

Show a temporary advancement toast.

Requires `i:<icon>` and `t:<title>`. The optional `type:<task|goal|challenge>` controls the toast frame.

## Server administration

<a id="perm"></a>

### `/perm`

- **Syntax:** `/perm <user|group|check|tree|listgroups|reload>`
- **Permission:** `rivet.permissions.manage`
- **Default:** `op`
- **Aliases:** None

Manage Rivet's built-in users, groups, inheritance, metadata, and tri-state permissions.

```text
/perm user <player> info
/perm user <player> group add <group>
/perm user <player> group remove <group>
/perm user <player> group set <group>
/perm user <player> permission set <node> <true|false>
/perm user <player> permission unset <node>

/perm group <group> create
/perm group <group> delete
/perm group <group> info
/perm group <group> members
/perm group <group> parent add <group>
/perm group <group> parent remove <group>
/perm group <group> weight set <number>
/perm group <group> permission set <node> <true|false>
/perm group <group> permission unset <node>
/perm group <group> meta prefix set <MiniMessage>
/perm group <group> meta suffix set <MiniMessage>

/perm check <player> <node>
/perm tree <player>
/perm listgroups
/perm reload
```

`/perm check` shows the final result, winning direct or group rule, wildcard used, relevant group weights, inherited memberships, and Paper-default fallback. `/perm tree` prints each directly assigned group and its parent hierarchy. All changes recalculate affected online players immediately.

<a id="hologram"></a>

### `/hologram`

- **Syntax:** `/hologram help`
- **Permission:** `rivet.holograms`
- **Default:** `op`
- **Aliases:** `/holo`

Manage holograms.

Run `/hologram help` for create, copy, edit, delete, list, nearby, teleport, and visibility operations.

<a id="clearhologram"></a>

### `/clearhologram`

- **Syntax:** `/clearhologram`
- **Permission:** `rivet.clearhologram`
- **Default:** `op`
- **Aliases:** None

Clear loaded auto-breeder holograms and recreate displays for active breeders. This includes displays using Rivet's former `core:` tags and legacy invisible ArmorStand hologram stacks. Holograms created with `/holo` are not changed.

<a id="lagg"></a>

### `/lagg`

- **Syntax:** `/lagg <clear|reload>`
- **Permission:** `rivet.lagg`
- **Default:** `op`
- **Aliases:** None

Clear eligible dropped item entities or reload `settings/lagg.yml`.

`/lagg clear` scans loaded worlds immediately and resets the automatic cleanup countdown. It never targets mobs, minecarts, armor stands, projectiles, or any entity other than dropped `Item` entities. `/lagg reload` validates and reloads only this module's settings while keeping the current configuration active if loading fails.

<a id="log"></a>

### `/log`

- **Syntax:** `/log <inspect|lookup|page|reload>`
- **Permissions:** `rivet.logs.inspect`, `rivet.logs.lookup`, or `rivet.logs.reload`, depending on the subcommand
- **Default:** `op`
- **Aliases:** `/logs`

Inspect and search Rivet's SQLite gameplay audit history.

```text
/log inspect
/log lookup
/log lookup <player>
/log lookup <player> <time>
/log lookup <player> <time> radius:<radius>
/log lookup <time> radius:<radius>
/log page <number>
/log reload
```

`/log inspect` toggles inspector mode. Left- or right-click a block for its recent history; clicking a container shows inventory additions and removals. Inspector and lookup output is newest-first. Times use compact values such as `30m`, `2h`, `7d`, or `1w`; radius is limited to 1,000 blocks and is centered on the viewer.

A bare player lookup covers the configured default time globally. A bare `/log lookup` uses the configured default time and radius around the player. Previous and next controls run `/log page`, and coordinates show the exact world and position on hover. With `rivet.logs.teleport`, clicking coordinates teleports the viewer to that location. `/log reload` validates only `settings/logs.yml`.

<a id="snapshot"></a>

### `/snapshot`

- **Syntax:** `/snapshot <player>`
- **Permissions:** `rivet.snapshots.view`; `rivet.snapshots.others` for another player
- **Default:** `op`
- **Aliases:** None

Browse a player's recent inventory snapshots newest first.

Death snapshots include their reason, relative and exact time, death cause, world,
coordinates, and snapshot ID. Clicking an entry opens a read-only preview with the exact
saved storage-slot layout, armour, offhand, health, hunger, saturation, XP, and location.
The GUI cancels all clicks and drags so saved items cannot be taken.

`rivet.snapshots.teleport` enables the location button. `rivet.snapshots.restore` enables
restore for an online, visible target. Restore replaces the target's inventory and state
instead of merging; the default confirmation screen warns about replacement and creates a
`PRE_RESTORE` safety snapshot before any change is applied.

<a id="help"></a>

### `/help`

- **Syntax:** `/help [page]`
- **Permission:** `rivet.help`
- **Default:** `true`
- **Aliases:** `/rivethelp`, `/rhelp`

Browse the commands available to you.

Displays interactive, permission-aware command pages with clickable navigation.

<a id="rivet"></a>

### `/rivet`

- **Syntax:** `/rivet [reload]`
- **Permission:** `rivet.admin`
- **Default:** `op`
- **Aliases:** None

Show Rivet information or reload configuration.

Run `/rivet reload` to validate and reload global and module settings. Module switch changes still require a restart.
