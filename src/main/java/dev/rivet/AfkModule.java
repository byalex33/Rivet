package dev.rivet;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class AfkModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final Set<UUID> afk = new HashSet<>();
    private final Map<UUID, Long> lastActivity = new HashMap<>();
    private final Map<UUID, Long> afkSince = new HashMap<>();
    private final Map<UUID, String> reasons = new HashMap<>();
    private final BukkitTask task;

    AfkModule(RivetPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getOnlinePlayers().forEach(player ->
            lastActivity.put(player.getUniqueId(), System.currentTimeMillis()));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkAutomatic, 20, 20);
    }

    boolean command(Player player, String[] args) {
        AfkArguments parsed = parseArguments(args);
        if (!parsed.valid()) {
            player.sendMessage(MM.deserialize("<red>Usage: /afk [reason] [-p:<player>] [-s]"));
            return true;
        }
        if (parsed.silent() && !player.hasPermission("rivet.afk.silent")) {
            player.sendMessage(MM.deserialize("<red>You cannot use silent AFK changes."));
            return true;
        }
        Player target = player;
        if (parsed.player() != null) {
            if (!player.hasPermission("rivet.afk.others")) {
                player.sendMessage(MM.deserialize("<red>You cannot change another player's AFK state."));
                return true;
            }
            target = plugin.getServer().getPlayerExact(parsed.player());
            if (target == null || !player.canSee(target)) {
                player.sendMessage(MM.deserialize("<red>That player is not online."));
                return true;
            }
        }
        boolean value = !isAfk(target.getUniqueId());
        set(target, value, parsed.reason(), parsed.silent());
        if (parsed.silent()) {
            player.sendMessage(MM.deserialize(value
                    ? "<green>AFK enabled for <white><player></white>."
                    : "<yellow>AFK disabled for <white><player></white>.",
                Placeholder.unparsed("player", target.getName())));
        }
        return true;
    }

    boolean check(Player viewer, String[] args) {
        if (args.length > 1) {
            viewer.sendMessage(MM.deserialize("<red>Usage: /afkcheck [player|all]"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("all")) {
            if (!viewer.hasPermission("rivet.afkcheck.others")) {
                viewer.sendMessage(MM.deserialize("<red>You cannot inspect other players."));
                return true;
            }
            List<? extends Player> players = plugin.getServer().getOnlinePlayers().stream()
                .filter(viewer::canSee).filter(player -> isAfk(player.getUniqueId()))
                .sorted(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
            if (players.isEmpty()) {
                viewer.sendMessage(MM.deserialize("<gray>No visible online players are AFK."));
                return true;
            }
            viewer.sendMessage(MM.deserialize("<gold>AFK players:</gold>"));
            players.forEach(player -> status(viewer, player));
            return true;
        }
        Player target = viewer;
        if (args.length == 1) {
            if (!viewer.hasPermission("rivet.afkcheck.others")) {
                viewer.sendMessage(MM.deserialize("<red>You cannot inspect other players."));
                return true;
            }
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !viewer.canSee(target)) {
                viewer.sendMessage(MM.deserialize("<red>That player is not online."));
                return true;
            }
        }
        status(viewer, target);
        return true;
    }

    List<String> completions(Player player, String command, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        if (command.equals("afk")) {
            List<String> values = new java.util.ArrayList<>();
            if (player.hasPermission("rivet.afk.silent")) {
                values.add("-s");
            }
            if (player.hasPermission("rivet.afk.others")) {
                plugin.getServer().getOnlinePlayers().stream().filter(player::canSee)
                    .map(other -> "-p:" + other.getName()).sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(values::add);
            }
            return values;
        }
        if (!player.hasPermission("rivet.afkcheck.others")) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>(List.of("all"));
        plugin.getServer().getOnlinePlayers().stream().filter(player::canSee)
            .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(values::add);
        return values;
    }

    boolean isAfk(UUID player) {
        return afk.contains(player);
    }

    Component indicator() {
        return MM.deserialize(plugin.settings("afk").getString("indicator.format", " <gray>[AFK]</gray>"));
    }

    boolean showIndicator() {
        return plugin.settings("afk").getBoolean("indicator.enabled", true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        afk.remove(id);
        afkSince.remove(id);
        reasons.remove(id);
        lastActivity.remove(id);
        event.getPlayer().setSleepingIgnored(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (DelayedTeleport.moved(event.getFrom(), event.getTo())) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInput(PlayerInputEvent event) {
        if (event.getInput().isForward() || event.getInput().isBackward()
            || event.getInput().isLeft() || event.getInput().isRight()
            || event.getInput().isJump() || event.getInput().isSneak()
            || event.getInput().isSprint()) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase(Locale.ROOT);
        if (!command.equals("/afk") && !command.startsWith("/afk ")
            && !command.equals("/afkcheck") && !command.startsWith("/afkcheck ")) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> activity(event.getPlayer()));
    }

    void shutdown() {
        task.cancel();
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            player.setSleepingIgnored(false);
            if (afk.contains(player.getUniqueId())) {
                afk.remove(player.getUniqueId());
                afkSince.remove(player.getUniqueId());
                reasons.remove(player.getUniqueId());
                plugin.refreshDisplayName(player);
            }
        });
        lastActivity.clear();
    }

    private void activity(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (isAfk(player.getUniqueId())) {
            set(player, false, null, false);
        }
    }

    private void checkAutomatic() {
        long minutes = Math.max(0, plugin.settings("afk").getLong("automatic-after-minutes", 10));
        if (minutes == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        plugin.getServer().getOnlinePlayers().stream()
            .filter(player -> !isAfk(player.getUniqueId()))
            .filter(player -> shouldAutoAfk(lastActivity.getOrDefault(player.getUniqueId(), now),
                minutes * 60_000L, now))
            .forEach(player -> set(player, true, null, false));
    }

    private void set(Player player, boolean value, String reason, boolean silent) {
        if (value) {
            afk.add(player.getUniqueId());
            afkSince.put(player.getUniqueId(), System.currentTimeMillis());
            if (reason != null) {
                reasons.put(player.getUniqueId(), reason);
            } else {
                reasons.remove(player.getUniqueId());
            }
        } else {
            afk.remove(player.getUniqueId());
            afkSince.remove(player.getUniqueId());
            reasons.remove(player.getUniqueId());
            lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        }
        player.setSleepingIgnored(value
            && plugin.settings("afk").getBoolean("exclude-from-sleep", true));
        plugin.refreshDisplayName(player);
        if (silent) {
            return;
        }
        String path = value ? "messages.afk" : "messages.return";
        String fallback = value ? "<yellow><player> is now AFK.</yellow>"
            : "<green><player> is no longer AFK.</green>";
        if (value && reason != null) {
            path = "messages.afk-with-reason";
            fallback = "<yellow><player> is now AFK: <white><reason></white></yellow>";
        }
        Component message = MM.deserialize(plugin.settings("afk").getString(path, fallback),
            Placeholder.unparsed("player", player.getName()),
            Placeholder.unparsed("reason", reason == null ? "" : reason));
        plugin.getServer().broadcast(message);
    }

    private void status(Player viewer, Player target) {
        boolean value = isAfk(target.getUniqueId());
        String reason = reasons.getOrDefault(target.getUniqueId(), "none");
        String duration = value ? duration(System.currentTimeMillis()
            - afkSince.getOrDefault(target.getUniqueId(), System.currentTimeMillis())) : "0s";
        viewer.sendMessage(MM.deserialize(value
                ? "<yellow><player> is AFK</yellow> <gray>(<duration>)</gray><white> — <reason></white>"
                : "<green><player> is not AFK.</green>",
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("duration", duration), Placeholder.unparsed("reason", reason)));
    }

    static AfkArguments parseArguments(String[] args) {
        String player = null;
        boolean silent = false;
        List<String> reason = new java.util.ArrayList<>();
        boolean valid = true;
        for (String argument : args) {
            if (argument.equalsIgnoreCase("-s")) {
                silent = true;
            } else if (argument.regionMatches(true, 0, "-p:", 0, 3)) {
                String value = argument.substring(3);
                if (player != null || value.isBlank()) {
                    valid = false;
                } else {
                    player = value;
                }
            } else {
                reason.add(argument);
            }
        }
        String joined = String.join(" ", reason).trim();
        return new AfkArguments(player, joined.isEmpty() ? null : joined, silent, valid);
    }

    static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long minutes = seconds / 60;
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + minutes % 60 + "m"
            : minutes > 0 ? minutes + "m " + seconds % 60 + "s" : seconds + "s";
    }

    record AfkArguments(String player, String reason, boolean silent, boolean valid) {
    }

    static boolean shouldAutoAfk(long lastActivity, long thresholdMillis, long now) {
        return thresholdMillis > 0 && now - lastActivity >= thresholdMillis;
    }
}
