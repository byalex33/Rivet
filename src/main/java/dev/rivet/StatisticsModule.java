package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class StatisticsModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration data;

    StatisticsModule(RivetPlugin plugin) {
        this.plugin = plugin;
        data = plugin.data("statistics");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String base = playerPath(player.getUniqueId());
        data.set(base + ".name", player.getName());
        data.set(base + ".last-logout", System.currentTimeMillis());
        storeLocation(data, base + ".last-location", new StoredLocation(
            player.getWorld().getName(), player.getX(), player.getY(), player.getZ()));
        saveData();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        String base = playerPath(player.getUniqueId());
        data.set(base + ".name", player.getName());
        data.set(base + ".last-death.time", System.currentTimeMillis());
        storeLocation(data, base + ".last-death.location", new StoredLocation(
            player.getWorld().getName(), player.getX(), player.getY(), player.getZ()));
        saveData();
    }

    boolean command(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length == 0 && sender instanceof Player player) {
            target = player;
        } else if (args.length == 1 && sender.hasPermission("rivet.stats.others")) {
            Player online = plugin.getServer().getPlayerExact(args[0]);
            target = online == null ? plugin.getServer().getOfflinePlayerIfCached(args[0]) : online;
        } else {
            sender.sendMessage(MM.deserialize("<white>Usage: /stats [player]"));
            return true;
        }
        if (target == null || target.getName() == null) {
            sender.sendMessage(MM.deserialize("<white>That player could not be found."));
            return true;
        }

        long mined = Arrays.stream(Material.values()).filter(Material::isBlock)
            .mapToLong(material -> statistic(target, Statistic.MINE_BLOCK, material)).sum();
        long placed = Arrays.stream(Material.values()).filter(Material::isBlock)
            .mapToLong(material -> statistic(target, Statistic.USE_ITEM, material)).sum();
        long distance = target.getStatistic(Statistic.WALK_ONE_CM)
            + target.getStatistic(Statistic.SPRINT_ONE_CM)
            + target.getStatistic(Statistic.SWIM_ONE_CM)
            + target.getStatistic(Statistic.FLY_ONE_CM)
            + target.getStatistic(Statistic.AVIATE_ONE_CM);
        long firstPlayed = target.getFirstPlayed();
        TagResolver placeholders = TagResolver.resolver(
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("playtime", duration(target.getStatistic(Statistic.PLAY_ONE_MINUTE) * 50L)),
            Placeholder.unparsed("deaths", Integer.toString(target.getStatistic(Statistic.DEATHS))),
            Placeholder.unparsed("player_kills", Integer.toString(target.getStatistic(Statistic.PLAYER_KILLS))),
            Placeholder.unparsed("mob_kills", Integer.toString(target.getStatistic(Statistic.MOB_KILLS))),
            Placeholder.unparsed("blocks_broken", Long.toString(mined)),
            Placeholder.unparsed("blocks_placed", Long.toString(placed)),
            Placeholder.unparsed("distance", String.format(java.util.Locale.ROOT, "%.1f km", distance / 100_000D)),
            Placeholder.unparsed("jumps", Integer.toString(target.getStatistic(Statistic.JUMP))),
            Placeholder.unparsed("since_first_join", firstPlayed <= 0 ? "unknown"
                : duration(System.currentTimeMillis() - firstPlayed)));
        plugin.messageActions().run(sender, plugin.settings("statistics"), "statistics", List.of(
            "[message] <#f72a4c><bold>%player%'s statistics</bold></#f72a4c>"), placeholders);
        return true;
    }

    boolean playtime(CommandSender sender, String[] args) {
        UtilitiesModule.TargetArgument parsed = UtilitiesModule.parseOptionalTarget(args,
            sender.hasPermission("rivet.playtime.others"));
        if (!parsed.valid() || parsed.name() == null && !(sender instanceof Player)) {
            sender.sendMessage(MM.deserialize("<white>Usage: /playtime"
                + (sender.hasPermission("rivet.playtime.others") ? " [player]" : "")));
            return true;
        }
        OfflinePlayer target;
        if (parsed.name() == null) {
            target = (Player) sender;
        } else {
            Player online = plugin.getServer().getPlayerExact(parsed.name());
            if (online != null && sender instanceof Player viewer && !viewer.canSee(online)) {
                sender.sendMessage(MM.deserialize("<white>That player could not be found."));
                return true;
            }
            target = online == null ? plugin.getServer().getOfflinePlayerIfCached(parsed.name()) : online;
        }
        if (target == null || target.getName() == null || !target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(MM.deserialize("<white>That player could not be found."));
            return true;
        }
        plugin.messageActions().run(sender, plugin.settings("statistics"), "playtime",
            "<#f72a4c>%player%'s playtime:</#f72a4c> <#f72a4c>%playtime%</#f72a4c>",
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("playtime", duration(target.getStatistic(Statistic.PLAY_ONE_MINUTE) * 50L)));
        return true;
    }

    boolean seen(CommandSender sender, String[] args) {
        if (args.length != 1) {
            plugin.messageActions().run(sender, plugin.settings("statistics"), "seen.usage",
                "<white>%tag% Usage: /seen &lt;player&gt;</white>", tag());
            return true;
        }

        Player online = plugin.getServer().getPlayerExact(args[0]);
        if (online != null && sender instanceof Player viewer && !viewer.canSee(online)) {
            notFound(sender);
            return true;
        }
        OfflinePlayer target = online == null
            ? plugin.getServer().getOfflinePlayerIfCached(args[0]) : online;
        if (target == null || target.getName() == null
            || online == null && !target.hasPlayedBefore()) {
            notFound(sender);
            return true;
        }

        long now = System.currentTimeMillis();
        long firstJoin = target.getFirstPlayed();
        long lastLogin = target.getLastLogin();
        long storedLogout = data.getLong(playerPath(target.getUniqueId()) + ".last-logout");
        long lastLogout = storedLogout > 0 ? storedLogout
            : online == null ? positive(target.getLastSeen(), target.getLastPlayed()) : 0;
        long playtime = target.getStatistic(Statistic.PLAY_ONE_MINUTE) * 50L;
        TagResolver placeholders = TagResolver.resolver(
            tag(),
            Placeholder.unparsed("player", target.getName()),
            Placeholder.component("first_join", relativeTime(firstJoin, now)),
            Placeholder.component("last_login", relativeTime(lastLogin, now)),
            Placeholder.component("last_logout", relativeTime(lastLogout, now)),
            Placeholder.unparsed("playtime", duration(playtime)),
            Placeholder.component("session", elapsedTime(lastLogin, now)));
        String path = online == null ? "seen.offline" : "seen.online";
        plugin.messageActions().run(sender, plugin.settings("statistics"), path,
            defaultSeenActions(online != null), placeholders);
        if (sender.hasPermission("rivet.seen.location")) {
            sendStaffDetails(sender, target, online, now);
        }
        return true;
    }

    List<String> completions(CommandSender sender, String[] args) {
        return args.length == 1 && sender.hasPermission("rivet.stats.others")
            ? plugin.getServer().getOnlinePlayers().stream().map(Player::getName).sorted().toList()
            : List.of();
    }

    List<String> playtimeCompletions(CommandSender sender, String[] args) {
        return args.length == 1 && sender.hasPermission("rivet.playtime.others")
            ? plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList()
            : List.of();
    }

    List<String> seenCompletions(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        plugin.getServer().getOnlinePlayers().stream()
            .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
            .map(Player::getName).forEach(names::add);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null) {
            players.getKeys(false).stream().map(uuid -> data.getString("players." + uuid + ".name"))
                .filter(java.util.Objects::nonNull)
                .filter(name -> {
                    Player online = plugin.getServer().getPlayerExact(name);
                    return online == null || !(sender instanceof Player viewer) || viewer.canSee(online);
                }).forEach(names::add);
        }
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void notFound(CommandSender sender) {
        plugin.messageActions().run(sender, plugin.settings("statistics"), "seen.not-found",
            "<white>%tag% That player could not be found.</white>", tag());
    }

    private void sendStaffDetails(CommandSender sender, OfflinePlayer target, Player online,
                                  long now) {
        String base = playerPath(target.getUniqueId());
        StoredLocation location = online == null
            ? storedLocation(data, base + ".last-location")
            : new StoredLocation(online.getWorld().getName(), online.getX(), online.getY(), online.getZ());
        plugin.messageActions().run(sender, plugin.settings("statistics"), "seen.staff-location",
            defaultStaffLocationActions(),
            Placeholder.unparsed("world", location == null ? "unknown" : location.world()),
            Placeholder.unparsed("coordinates", location == null ? "unknown" : location.coordinates()));

        long deathTime = data.getLong(base + ".last-death.time");
        StoredLocation death = storedLocation(data, base + ".last-death.location");
        if (deathTime > 0 && death != null) {
            plugin.messageActions().run(sender, plugin.settings("statistics"), "seen.staff-death",
                defaultStaffDeathActions(),
                Placeholder.component("last_death", relativeTime(deathTime, now)),
                Placeholder.unparsed("death_location", death.world() + " " + death.coordinates()));
        }
    }

    private TagResolver tag() {
        return Placeholder.component("tag", RivetMessages.tag(plugin));
    }

    static List<String> defaultSeenActions(boolean online) {
        List<String> actions = new java.util.ArrayList<>();
        actions.add("[message] %tag% <white><bold>%player%</bold></white> "
            + (online ? "<green><bold>ONLINE</bold></green>"
                : "<red><bold>OFFLINE</bold></red>"));
        actions.add(online
            ? "[message] <gray>Current session:</gray> <white>%session%</white>"
            : "[message] <gray>Last seen:</gray> <white>%last_logout%</white>");
        actions.add("[message] <gray>First joined:</gray> <white>%first_join%</white>");
        actions.add("[message] <gray>Last login:</gray> <white>%last_login%</white>");
        actions.add("[message] <gray>Total playtime:</gray> <white>%playtime%</white>");
        return List.copyOf(actions);
    }

    static List<String> defaultStaffLocationActions() {
        return List.of("[message] <gray>Location:</gray> <white>%world%</white> "
            + "<gray>at</gray> <white>%coordinates%</white>");
    }

    static List<String> defaultStaffDeathActions() {
        return List.of("[message] <gray>Last death:</gray> <white>%last_death%</white> "
            + "<gray>at</gray> <white>%death_location%</white>");
    }

    static boolean migrateSeenV2(YamlConfiguration settings) {
        List<String> oldOnline = List.of("[message] <white><#f72a4c>%player%</#f72a4c> is currently online (joined <#f72a4c>%duration%</#f72a4c> ago).</white>");
        List<String> oldOffline = List.of("[message] <white><#f72a4c>%player%</#f72a4c> was last seen <#f72a4c>%duration%</#f72a4c> ago (%timestamp%).</white>");
        boolean changed = false;
        List<String> previousOnline = List.of(
            "[message] %tag% <#f72a4c><bold>%player%</bold></#f72a4c> <green>Online</green> <dark_gray>•</dark_gray> <white>Online for %session%</white>",
            "[message] <dark_gray>First join:</dark_gray> <white>%first_join%</white> <dark_gray>• Last login:</dark_gray> <white>%last_login%</white>",
            "[message] <dark_gray>Last logout:</dark_gray> <white>%last_logout%</white> <dark_gray>• Playtime:</dark_gray> <white>%playtime%</white>");
        List<String> previousOffline = List.of(
            "[message] %tag% <#f72a4c><bold>%player%</bold></#f72a4c> <gray>Offline</gray>",
            "[message] <dark_gray>First join:</dark_gray> <white>%first_join%</white> <dark_gray>• Last login:</dark_gray> <white>%last_login%</white>",
            "[message] <dark_gray>Last logout:</dark_gray> <white>%last_logout%</white> <dark_gray>• Playtime:</dark_gray> <white>%playtime%</white>");
        if (settings.getStringList("seen.online.actions").equals(oldOnline)
            || settings.getStringList("seen.online.actions").equals(previousOnline)) {
            settings.set("seen.online.actions", defaultSeenActions(true));
            changed = true;
        }
        if (settings.getStringList("seen.offline.actions").equals(oldOffline)
            || settings.getStringList("seen.offline.actions").equals(previousOffline)) {
            settings.set("seen.offline.actions", defaultSeenActions(false));
            changed = true;
        }
        List<String> previousLocation = List.of(
            "[message] <dark_gray>Location:</dark_gray> <white>%world%</white> <dark_gray>•</dark_gray> <white>%coordinates%</white>");
        if (settings.getStringList("seen.staff-location.actions").equals(previousLocation)) {
            settings.set("seen.staff-location.actions", defaultStaffLocationActions());
            changed = true;
        }
        List<String> previousDeath = List.of(
            "[message] <dark_gray>Last death:</dark_gray> <white>%last_death%</white> <dark_gray>•</dark_gray> <white>%death_location%</white>");
        if (settings.getStringList("seen.staff-death.actions").equals(previousDeath)) {
            settings.set("seen.staff-death.actions", defaultStaffDeathActions());
            changed = true;
        }
        return changed;
    }

    private String timestamp(long millis) {
        if (millis <= 0) {
            return "unknown";
        }
        String pattern = plugin.settings("statistics").getString(
            "seen.date-format", "d MMM uuuu 'at' HH:mm z");
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.UK)
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid statistics seen.date-format '" + pattern
                + "'; using the default format.");
            return DateTimeFormatter.ofPattern("d MMM uuuu 'at' HH:mm z", Locale.UK)
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
        }
    }

    private Component relativeTime(long millis, long now) {
        return millis <= 0 ? Component.text("unknown")
            : timeComponent(friendlyElapsed(now - millis) + " ago", timestamp(millis));
    }

    private Component elapsedTime(long startedAt, long now) {
        return startedAt <= 0 ? Component.text("unknown")
            : timeComponent(friendlyElapsed(now - startedAt), timestamp(startedAt));
    }

    static Component timeComponent(String visible, String exact) {
        return Component.text(visible).hoverEvent(HoverEvent.showText(
            Component.text("Exact time: " + exact)));
    }

    static String friendlyElapsed(long millis) {
        Duration elapsed = Duration.ofMillis(Math.max(0, millis));
        long days = elapsed.toDays();
        if (days > 0) {
            return days + (days == 1 ? " day" : " days");
        }
        long hours = elapsed.toHours();
        long minutes = elapsed.toMinutesPart();
        if (hours > 0) {
            return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        long totalMinutes = elapsed.toMinutes();
        if (totalMinutes > 0) {
            return totalMinutes + "m";
        }
        long seconds = elapsed.toSeconds();
        return seconds < 5 ? "just now" : seconds + "s";
    }

    static void storeLocation(YamlConfiguration data, String path, StoredLocation location) {
        data.set(path + ".world", location.world());
        data.set(path + ".x", location.x());
        data.set(path + ".y", location.y());
        data.set(path + ".z", location.z());
    }

    static StoredLocation storedLocation(YamlConfiguration data, String path) {
        String world = data.getString(path + ".world");
        return world == null || world.isBlank() ? null : new StoredLocation(world,
            data.getDouble(path + ".x"), data.getDouble(path + ".y"),
            data.getDouble(path + ".z"));
    }

    private void saveData() {
        try {
            plugin.saveData("statistics");
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save Seen v2 data: " + exception.getMessage());
        }
    }

    private static long positive(long preferred, long fallback) {
        return preferred > 0 ? preferred : Math.max(0, fallback);
    }

    private static String playerPath(UUID player) {
        return "players." + player;
    }

    private static int statistic(OfflinePlayer player, Statistic statistic, Material material) {
        try {
            return player.getStatistic(statistic, material);
        } catch (IllegalArgumentException exception) {
            return 0;
        }
    }

    static String duration(long millis) {
        Duration duration = Duration.ofMillis(Math.max(0, millis));
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        return days > 0 ? days + "d " + hours + "h " + minutes + "m"
            : hours > 0 ? hours + "h " + minutes + "m"
            : minutes + "m";
    }

    static String relativeDuration(long millis) {
        long safeMillis = Math.max(0, millis);
        return safeMillis < 60_000 ? safeMillis / 1_000 + "s" : duration(safeMillis);
    }

    record StoredLocation(String world, double x, double y, double z) {
        String coordinates() {
            return (long) Math.floor(x) + ", " + (long) Math.floor(y) + ", "
                + (long) Math.floor(z);
        }
    }
}
