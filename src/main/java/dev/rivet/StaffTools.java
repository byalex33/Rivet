package dev.rivet;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class StaffTools implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;
    private final Set<UUID> god = new HashSet<>();
    private final Set<ActiveBar> bossBars = new HashSet<>();

    StaffTools(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("staff");
        data = plugin.data("staff");
        if (persistent()) {
            data.getStringList("god-players").forEach(value -> {
                try {
                    god.add(UUID.fromString(value));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Skipped invalid UUID in data/staff.yml: " + value);
                }
            });
            plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> god.contains(player.getUniqueId()))
                .forEach(player -> player.setInvulnerable(true));
        }
    }

    boolean heal(Player actor, String[] args) {
        Player target = target(actor, args, "rivet.heal.others", "/heal [player]");
        if (target == null) {
            return true;
        }
        var attribute = target.getAttribute(Attribute.MAX_HEALTH);
        target.setHealth(attribute == null ? 20 : attribute.getValue());
        target.setFireTicks(0);
        target.setFreezeTicks(0);
        feedback(actor, target, "heal", "<green>Healed <white><target></white>.");
        return true;
    }

    boolean feed(Player actor, String[] args) {
        Player target = target(actor, args, "rivet.feed.others", "/feed [player]");
        if (target == null) {
            return true;
        }
        target.setFoodLevel(20);
        target.setSaturation(20);
        target.setExhaustion(0);
        feedback(actor, target, "feed", "<green>Fed <white><target></white>.");
        return true;
    }

    boolean god(Player actor, String[] args) {
        GodArguments parsed = parseGodArguments(args);
        if (!parsed.valid()) {
            actor.sendMessage(MM.deserialize("<red>Usage: /god [player] [true|false] [-s]"));
            return true;
        }
        Player target = actor;
        if (parsed.player() != null) {
            if (!actor.hasPermission("rivet.god.others")) {
                actor.sendMessage(MM.deserialize("<red>You cannot change god mode for another player."));
                return true;
            }
            target = plugin.getServer().getPlayerExact(parsed.player());
            if (target == null || !actor.canSee(target)) {
                actor.sendMessage(MM.deserialize("<red>That player is not online.</red>"));
                return true;
            }
        }
        boolean enabled = parsed.state() == null ? !god.contains(target.getUniqueId()) : parsed.state();
        boolean wasEnabled = god.contains(target.getUniqueId());
        if (enabled) {
            god.add(target.getUniqueId());
        } else {
            god.remove(target.getUniqueId());
        }
        target.setInvulnerable(enabled);
        if (persistent() && !save()) {
            if (wasEnabled) {
                god.add(target.getUniqueId());
            } else {
                god.remove(target.getUniqueId());
            }
            data.set("god-players", god.stream().map(UUID::toString).sorted().toList());
            target.setInvulnerable(wasEnabled);
            actor.sendMessage(MM.deserialize("<red>God mode could not be saved safely.</red>"));
            return true;
        }
        if (parsed.silent()) {
            return true;
        }
        String fallback = enabled ? "<green>God mode enabled for <white><target></white>."
            : "<yellow>God mode disabled for <white><target></white>.";
        actor.sendMessage(MM.deserialize(settings.getString("messages.god-" +
                (enabled ? "enabled" : "disabled"), fallback),
            Placeholder.unparsed("target", target.getName())));
        if (actor != target) {
            target.sendMessage(MM.deserialize(settings.getString("messages.god-target-" +
                (enabled ? "enabled" : "disabled"), enabled
                ? "<green>God mode was enabled.</green>" : "<yellow>God mode was disabled.</yellow>")));
        }
        return true;
    }

    boolean flySpeed(Player actor, String[] args) {
        Player target = actor;
        String value;
        if (args.length == 1) {
            value = args[0];
        } else if (args.length == 2 && actor.hasPermission("rivet.flyspeed.others")) {
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !actor.canSee(target)) {
                actor.sendMessage(MM.deserialize("<red>That player is not online.</red>"));
                return true;
            }
            value = args[1];
        } else {
            actor.sendMessage(MM.deserialize("<red>Usage: /flyspeed [player] <amount>"));
            return true;
        }
        Float speed = parseFlySpeed(value);
        if (speed == null) {
            actor.sendMessage(MM.deserialize("<red>Flight speed must be between -1.0 and 1.0."));
            return true;
        }
        target.setFlySpeed(speed);
        actor.sendMessage(MM.deserialize("<green>Flight speed for <white><player></white> set to <white><speed></white>.",
            Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("speed", Float.toString(speed))));
        if (actor != target) {
            target.sendMessage(MM.deserialize("<green>Your flight speed is now <white><speed></white>.",
                Placeholder.unparsed("speed", Float.toString(speed))));
        }
        return true;
    }

    boolean bossBar(Player actor, String[] args) {
        if (args.length < 2) {
            actor.sendMessage(MM.deserialize("<red>Usage: /bossbarmsg <player|all> [-d:seconds] [-c:color] [-s:style] <message>"));
            return true;
        }
        BossBarArguments parsed = parseBossBarArguments(Arrays.copyOfRange(args, 1, args.length),
            settings.getDouble("boss-bar.default-duration-seconds", 10),
            settings.getString("boss-bar.default-color", "purple"),
            settings.getString("boss-bar.default-style", "solid"));
        if (!parsed.valid()) {
            actor.sendMessage(MM.deserialize("<red>" + parsed.error() + "</red>"));
            return true;
        }
        List<Player> targets;
        if (args[0].equalsIgnoreCase("all")) {
            targets = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        } else {
            Player target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !actor.canSee(target)) {
                actor.sendMessage(MM.deserialize("<red>That player is not online.</red>"));
                return true;
            }
            targets = List.of(target);
        }
        if (targets.isEmpty()) {
            actor.sendMessage(MM.deserialize("<yellow>No players are online.</yellow>"));
            return true;
        }
        Component title;
        try {
            title = MM.deserialize(parsed.message());
        } catch (RuntimeException exception) {
            actor.sendMessage(MM.deserialize("<red>That MiniMessage text is invalid.</red>"));
            return true;
        }
        BossBar bar = BossBar.bossBar(title, 1, parsed.color(), parsed.overlay());
        ActiveBar active = new ActiveBar(bar, Set.copyOf(targets));
        targets.forEach(player -> player.showBossBar(bar));
        active.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> remove(active),
            Math.max(1, (long) Math.ceil(parsed.duration() * 20)));
        bossBars.add(active);
        actor.sendMessage(MM.deserialize("<green>Boss bar shown to <white><count></white> player(s).",
            Placeholder.unparsed("count", Integer.toString(targets.size()))));
        return true;
    }

    List<String> completions(Player actor, String[] args, String othersPermission) {
        return args.length == 1 && actor.hasPermission(othersPermission)
            ? plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList()
            : List.of();
    }

    List<String> godCompletions(Player actor, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("true", "false", "-s"));
            if (actor.hasPermission("rivet.god.others")) {
                plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(values::add);
            }
            return values;
        }
        return args.length == 2 ? List.of("true", "false", "-s")
            : args.length == 3 ? List.of("-s") : List.of();
    }

    List<String> flySpeedCompletions(Player actor, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("0.1", "0.5", "1.0"));
            if (actor.hasPermission("rivet.flyspeed.others")) {
                plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(values::add);
            }
            return values;
        }
        return args.length == 2 ? List.of("0.1", "0.5", "1.0") : List.of();
    }

    List<String> bossBarCompletions(Player actor, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("all"));
            plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(values::add);
            return values;
        }
        return args.length == 2 ? List.of("-d:10", "-c:red", "-s:solid") : List.of();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        boolean enabled = persistent() && god.contains(event.getPlayer().getUniqueId());
        event.getPlayer().setInvulnerable(enabled);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.getPlayer().setInvulnerable(false);
        if (!persistent()) {
            god.remove(event.getPlayer().getUniqueId());
        }
    }

    void shutdown() {
        new ArrayList<>(bossBars).forEach(this::remove);
        god.stream().map(plugin.getServer()::getPlayer).filter(java.util.Objects::nonNull)
            .forEach(player -> player.setInvulnerable(false));
        if (!persistent()) {
            god.clear();
        }
    }

    private Player target(Player actor, String[] args, String othersPermission, String usage) {
        if (args.length == 0) {
            return actor;
        }
        if (args.length != 1 || !actor.hasPermission(othersPermission)) {
            actor.sendMessage(MM.deserialize("<red>Usage: " + usage));
            return null;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !actor.canSee(target)) {
            actor.sendMessage(MM.deserialize("<red>That player is not online.</red>"));
            return null;
        }
        return target;
    }

    private void feedback(Player actor, Player target, String key, String fallback) {
        actor.sendMessage(MM.deserialize(settings.getString("messages." + key, fallback),
            Placeholder.unparsed("target", target.getName())));
        if (actor != target) {
            target.sendMessage(MM.deserialize(settings.getString("messages." + key + "-target",
                "<green>You were restored by <white><player></white>."),
                Placeholder.unparsed("player", actor.getName())));
        }
    }

    private boolean persistent() {
        return settings.getBoolean("god.persist-across-reconnects", false);
    }

    private boolean save() {
        data.set("god-players", god.stream().map(UUID::toString).sorted().toList());
        try {
            plugin.saveData("staff");
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/staff.yml: " + exception.getMessage());
            return false;
        }
    }

    private void remove(ActiveBar active) {
        if (!bossBars.remove(active) && active.task == null) {
            return;
        }
        if (active.task != null) {
            active.task.cancel();
            active.task = null;
        }
        active.players.forEach(player -> player.hideBossBar(active.bar));
    }

    static Float parseFlySpeed(String value) {
        try {
            float speed = Float.parseFloat(value);
            return Float.isFinite(speed) && speed >= -1 && speed <= 1 ? speed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static GodArguments parseGodArguments(String[] args) {
        List<String> values = new ArrayList<>();
        boolean silent = false;
        for (String argument : args) {
            if (argument.equalsIgnoreCase("-s")) {
                silent = true;
            } else {
                values.add(argument);
            }
        }
        if (values.size() > 2) {
            return new GodArguments(null, null, silent, false);
        }
        if (values.isEmpty()) {
            return new GodArguments(null, null, silent, true);
        }
        Boolean first = booleanValue(values.get(0));
        if (values.size() == 1) {
            return first == null ? new GodArguments(values.get(0), null, silent, true)
                : new GodArguments(null, first, silent, true);
        }
        Boolean state = booleanValue(values.get(1));
        return new GodArguments(values.get(0), state, silent, state != null);
    }

    static BossBarArguments parseBossBarArguments(String[] args, double defaultDuration,
                                                   String defaultColor, String defaultStyle) {
        double duration = defaultDuration;
        BossBar.Color color = bossBarColor(defaultColor);
        BossBar.Overlay overlay = bossBarOverlay(defaultStyle);
        List<String> message = new ArrayList<>();
        boolean valid = color != null && overlay != null && Double.isFinite(duration)
            && duration > 0 && duration <= 3600;
        String error = valid ? null : "Boss bar defaults are invalid; check settings/staff.yml.";
        for (String argument : args) {
            try {
                if (argument.regionMatches(true, 0, "-d:", 0, 3)) {
                    duration = Double.parseDouble(argument.substring(3));
                    if (!Double.isFinite(duration) || duration <= 0 || duration > 3600) {
                        valid = false;
                        error = "Duration must be greater than 0 and at most 3600 seconds.";
                    }
                } else if (argument.regionMatches(true, 0, "-c:", 0, 3)) {
                    color = bossBarColor(argument.substring(3));
                    if (color == null) {
                        valid = false;
                        error = "Unknown boss bar color.";
                    }
                } else if (argument.regionMatches(true, 0, "-s:", 0, 3)) {
                    overlay = bossBarOverlay(argument.substring(3));
                    if (overlay == null) {
                        valid = false;
                        error = "Unknown boss bar style.";
                    }
                } else {
                    message.add(argument);
                }
            } catch (NumberFormatException exception) {
                valid = false;
                error = "Duration must be a number of seconds.";
            }
        }
        if (message.isEmpty()) {
            valid = false;
            error = "Boss bar message cannot be empty.";
        }
        return new BossBarArguments(duration, color, overlay, String.join(" ", message), error, valid);
    }

    private static BossBar.Color bossBarColor(String value) {
        try {
            return BossBar.Color.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private static BossBar.Overlay bossBarOverlay(String value) {
        try {
            String normalized = value.toUpperCase(Locale.ROOT).replace("SEGMENTED_", "NOTCHED_");
            if (normalized.equals("SOLID")) {
                normalized = "PROGRESS";
            }
            return BossBar.Overlay.valueOf(normalized);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private static Boolean booleanValue(String value) {
        return value.equalsIgnoreCase("true") ? Boolean.TRUE
            : value.equalsIgnoreCase("false") ? Boolean.FALSE : null;
    }

    record GodArguments(String player, Boolean state, boolean silent, boolean valid) {
    }

    record BossBarArguments(double duration, BossBar.Color color, BossBar.Overlay overlay,
                            String message, String error, boolean valid) {
    }

    private static final class ActiveBar {
        private final BossBar bar;
        private final Set<Player> players;
        private BukkitTask task;

        private ActiveBar(BossBar bar, Set<Player> players) {
            this.bar = bar;
            this.players = players;
        }
    }
}
