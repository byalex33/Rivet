package dev.rivet;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class DelayedTeleport implements Listener {
    private final RivetPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    DelayedTeleport(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    void start(Player player, Location destination, int delaySeconds, boolean cancelOnMove,
               Component waiting, Component cancelled, Runnable success) {
        cancel(player, null);
        if (delaySeconds <= 0) {
            if (player.teleport(destination)) {
                success.run();
            }
            return;
        }
        if (waiting != null) {
            player.sendMessage(waiting);
        }
        Pending entry = new Pending(player.getLocation().clone(), cancelOnMove, cancelled);
        pending.put(player.getUniqueId(), entry);
        entry.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(player.getUniqueId()) == entry && player.isOnline()
                && player.teleport(destination)) {
                success.run();
            }
        }, Math.max(1L, delaySeconds * 20L));
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
