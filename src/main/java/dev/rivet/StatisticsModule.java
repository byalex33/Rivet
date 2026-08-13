package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class StatisticsModule {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;

    StatisticsModule(RivetPlugin plugin) {
        this.plugin = plugin;
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
                "<white>Usage: /seen &lt;player&gt;</white>");
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
        long seenAt = online == null ? target.getLastSeen() : target.getLastLogin();
        if (seenAt <= 0) {
            seenAt = target.getLastPlayed();
        }
        TagResolver placeholders = TagResolver.resolver(
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("duration", seenAt <= 0
                ? "unknown" : relativeDuration(now - seenAt)),
            Placeholder.unparsed("timestamp", timestamp(seenAt)));
        String path = online == null ? "seen.offline" : "seen.online";
        String fallback = online == null
            ? "<white><#f72a4c>%player%</#f72a4c> was last seen <#f72a4c>%duration%</#f72a4c> ago (%timestamp%).</white>"
            : "<white><#f72a4c>%player%</#f72a4c> is currently online (joined <#f72a4c>%duration%</#f72a4c> ago).</white>";
        plugin.messageActions().run(sender, plugin.settings("statistics"), path, fallback,
            placeholders);
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
        return args.length == 1
            ? plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList()
            : List.of();
    }

    private void notFound(CommandSender sender) {
        plugin.messageActions().run(sender, plugin.settings("statistics"), "seen.not-found",
            "<white>That player could not be found.</white>");
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
}
