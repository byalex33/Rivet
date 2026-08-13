package dev.rivet;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DelayedTeleport implements Listener {
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;
    private final TeleportHistory history;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> suppressedHistory = new HashSet<>();

    DelayedTeleport(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("teleports");
        data = plugin.data("teleports");
        history = new TeleportHistory(plugin);
    }

    boolean start(Player player, Location destination, Runnable success) {
        return start(player, destination, true, success);
    }

    boolean startBack(Player player, Location destination, Runnable success) {
        return start(player, destination, false, success);
    }

    private boolean start(Player player, Location destination, boolean recordHistory,
                          Runnable success) {
        boolean bypass = bypassesCooldown(player);
        long cooldownSeconds = Math.max(0, settings.getLong("cooldown-seconds", 10));
        long remaining = cooldownRemaining(data.getLong(cooldownPath(player)),
            cooldownSeconds, System.currentTimeMillis());
        if (!bypass && remaining > 0) {
            plugin.messageActions().run(player, settings, "messages.cooldown",
                "<white>You can teleport again in <#f72a4c>%seconds%</#f72a4c> seconds.</white>",
                Placeholder.unparsed("seconds", Long.toString(remaining)));
            return false;
        }
        int warmup = effectiveWarmup(settings.getInt("warmup-seconds", 3), bypass);
        Runnable waiting = () -> plugin.messageActions().run(player, settings, "messages.warmup",
            "<white>Teleporting in <#f72a4c>%seconds%</#f72a4c> seconds. Do not move.</white>",
            Placeholder.unparsed("seconds", Integer.toString(warmup)));
        Runnable cancelled = () -> plugin.messageActions().run(player, settings, "messages.cancelled",
            "<white>Teleport cancelled because you moved.</white>");
        begin(player, destination, warmup, settings.getBoolean("cancel-on-move", true),
            waiting, cancelled, !bypass && cooldownSeconds > 0, recordHistory, success);
        return true;
    }

    void startImmediate(Player player, Location destination, Runnable success) {
        begin(player, destination, 0, false, null, null, false, false, success);
    }

    private void begin(Player player, Location destination, int delaySeconds, boolean cancelOnMove,
                       Runnable waiting, Runnable cancelled, boolean recordCooldown,
                       boolean recordHistory, Runnable success) {
        cancel(player, null);
        if (delaySeconds <= 0) {
            complete(player, destination, recordCooldown, recordHistory, success);
            return;
        }
        if (waiting != null) {
            waiting.run();
        }
        Pending entry = new Pending(player.getLocation().clone(), cancelOnMove, cancelled);
        pending.put(player.getUniqueId(), entry);
        entry.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(player.getUniqueId()) == entry && player.isOnline()) {
                complete(player, destination, recordCooldown, recordHistory, success);
            }
        }, Math.max(1L, delaySeconds * 20L));
    }

    private void complete(Player player, Location destination, boolean recordCooldown,
                          boolean recordHistory, Runnable success) {
        UUID playerId = player.getUniqueId();
        if (!recordHistory) {
            suppressedHistory.add(playerId);
        }
        boolean teleported;
        try {
            teleported = player.teleport(destination);
        } finally {
            if (!recordHistory) {
                suppressedHistory.remove(playerId);
            }
        }
        if (!teleported) {
            plugin.messageActions().run(player, settings, "messages.failed",
                "<white>Teleport failed.</white>");
            return;
        }
        if (recordCooldown) {
            data.set(cooldownPath(player), System.currentTimeMillis());
            try {
                plugin.saveData("teleports");
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not save teleport cooldown: " + exception.getMessage());
            }
        }
        success.run();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending entry = pending.get(event.getPlayer().getUniqueId());
        if (entry != null && entry.cancelOnMove && moved(entry.origin, event.getTo())) {
            cancel(event.getPlayer(), entry.cancelled);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (pending.containsKey(event.getPlayer().getUniqueId())) {
            cancel(event.getPlayer(), pending.get(event.getPlayer().getUniqueId()).cancelled);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void rememberTeleport(PlayerTeleportEvent event) {
        if (suppressedHistory.contains(event.getPlayer().getUniqueId())
            || !trackedCause(event.getCause())
            || !TeleportHistory.meaningful(event.getFrom(), event.getTo())) {
            return;
        }
        history.record(event.getPlayer(), event.getFrom(),
            event.getCause().name().toLowerCase(java.util.Locale.ROOT));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), null);
    }

    void shutdown() {
        pending.values().forEach(entry -> entry.task.cancel());
        pending.clear();
        suppressedHistory.clear();
        history.shutdown();
    }

    void recordDeath(Player player, Location location) {
        history.record(player, location, "death");
    }

    TeleportHistory.BackDestination backDestination(Player player) {
        return history.destination(player);
    }

    long lastBack(Player player) {
        return history.lastBack(player.getUniqueId());
    }

    void completeBack(Player player, TeleportHistory.BackDestination destination,
                      boolean recordCooldown) {
        history.completeBack(player, destination, recordCooldown);
    }

    boolean importLegacyDeath(UUID player, UUID world, double x, double y, double z,
                              float yaw, float pitch, long lastBack) {
        return history.importLegacyDeath(player, world, x, y, z, yaw, pitch, lastBack);
    }

    void saveImportedHistory() {
        history.saveImportedHistory();
    }

    private void cancel(Player player, Runnable message) {
        Pending entry = pending.remove(player.getUniqueId());
        if (entry == null) {
            return;
        }
        entry.task.cancel();
        if (message != null && player.isOnline()) {
            message.run();
        }
    }

    static boolean moved(Location from, Location to) {
        if (to == null || from.getWorld() != to.getWorld()) {
            return true;
        }
        double x = from.getX() - to.getX();
        double y = from.getY() - to.getY();
        double z = from.getZ() - to.getZ();
        return x * x + y * y + z * z > .01;
    }

    static long cooldownRemaining(long lastUse, long cooldownSeconds, long now) {
        return Math.max(0, (lastUse + cooldownSeconds * 1000L - now + 999) / 1000);
    }

    static boolean bypassesCooldown(Player player) {
        return player.hasPermission("rivet.tp.nocooldown");
    }

    static int effectiveWarmup(int configuredSeconds, boolean bypass) {
        return bypass ? 0 : Math.max(0, configuredSeconds);
    }

    static boolean trackedCause(PlayerTeleportEvent.TeleportCause cause) {
        return cause == PlayerTeleportEvent.TeleportCause.COMMAND
            || cause == PlayerTeleportEvent.TeleportCause.PLUGIN
            || cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
            || cause == PlayerTeleportEvent.TeleportCause.END_PORTAL
            || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY;
    }

    private static String cooldownPath(Player player) {
        return "cooldowns." + player.getUniqueId();
    }

    private static final class Pending {
        private final Location origin;
        private final boolean cancelOnMove;
        private final Runnable cancelled;
        private BukkitTask task;

        private Pending(Location origin, boolean cancelOnMove, Runnable cancelled) {
            this.origin = origin;
            this.cancelOnMove = cancelOnMove;
            this.cancelled = cancelled;
        }
    }
}
