package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class DelayedTeleport implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;
    private final Map<UUID, Pending> pending = new HashMap<>();

    DelayedTeleport(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("teleports");
        data = plugin.data("teleports");
    }

    boolean start(Player player, Location destination, Runnable success) {
        boolean bypass = player.hasPermission("rivet.tp.nocooldown");
        long cooldownSeconds = Math.max(0, settings.getLong("cooldown-seconds", 10));
        long remaining = cooldownRemaining(data.getLong(cooldownPath(player)),
            cooldownSeconds, System.currentTimeMillis());
        if (!bypass && remaining > 0) {
            player.sendMessage(MM.deserialize(settings.getString("messages.cooldown",
                    "<white>You can teleport again in <#f72a4c>%seconds%</#f72a4c> seconds.</white>"),
                Placeholder.unparsed("seconds", Long.toString(remaining))));
            return false;
        }
        int warmup = Math.max(0, settings.getInt("warmup-seconds", 3));
        Component waiting = MM.deserialize(settings.getString("messages.warmup",
                "<white>Teleporting in <#f72a4c>%seconds%</#f72a4c> seconds. Do not move.</white>"),
            Placeholder.unparsed("seconds", Integer.toString(warmup)));
        Component cancelled = MM.deserialize(settings.getString("messages.cancelled",
            "<white>Teleport cancelled because you moved.</white>"));
        begin(player, destination, warmup, settings.getBoolean("cancel-on-move", true),
            waiting, cancelled, !bypass && cooldownSeconds > 0, success);
        return true;
    }

    void startImmediate(Player player, Location destination, Runnable success) {
        begin(player, destination, 0, false, null, null, false, success);
    }

    private void begin(Player player, Location destination, int delaySeconds, boolean cancelOnMove,
                       Component waiting, Component cancelled, boolean recordCooldown,
                       Runnable success) {
        cancel(player, null);
        if (delaySeconds <= 0) {
            complete(player, destination, recordCooldown, success);
            return;
        }
        if (waiting != null) {
            player.sendMessage(waiting);
        }
        Pending entry = new Pending(player.getLocation().clone(), cancelOnMove, cancelled);
        pending.put(player.getUniqueId(), entry);
        entry.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(player.getUniqueId()) == entry && player.isOnline()) {
                complete(player, destination, recordCooldown, success);
            }
        }, Math.max(1L, delaySeconds * 20L));
    }

    private void complete(Player player, Location destination, boolean recordCooldown,
                          Runnable success) {
        if (!player.teleport(destination)) {
            player.sendMessage(MM.deserialize(settings.getString("messages.failed",
                "<white>Teleport failed.</white>")));
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), null);
    }

    void shutdown() {
        pending.values().forEach(entry -> entry.task.cancel());
        pending.clear();
    }

    private void cancel(Player player, Component message) {
        Pending entry = pending.remove(player.getUniqueId());
        if (entry == null) {
            return;
        }
        entry.task.cancel();
        if (message != null && player.isOnline()) {
            player.sendMessage(message);
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

    private static String cooldownPath(Player player) {
        return "cooldowns." + player.getUniqueId();
    }

    private static final class Pending {
        private final Location origin;
        private final boolean cancelOnMove;
        private final Component cancelled;
        private BukkitTask task;

        private Pending(Location origin, boolean cancelOnMove, Component cancelled) {
            this.origin = origin;
            this.cancelOnMove = cancelOnMove;
            this.cancelled = cancelled;
        }
    }
}
