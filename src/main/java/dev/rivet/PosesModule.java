package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class PosesModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final Map<UUID, Pose> active = new HashMap<>();

    PosesModule(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    boolean command(Player player, String command, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<red>Usage: /" + command));
            return true;
        }
        if (!allowed(player)) {
            player.sendMessage(MM.deserialize(plugin.settings("poses").getString("messages.not-allowed",
                "<red>Poses are not allowed in this world.")));
            return true;
        }
        Pose pose = switch (command) {
            case "sit" -> Pose.SITTING;
            case "lay" -> Pose.SLEEPING;
            default -> Pose.SWIMMING;
        };
        if (active.get(player.getUniqueId()) == pose) {
            clear(player);
            return true;
        }
        active.put(player.getUniqueId(), pose);
        player.setPose(pose, true);
        player.sendMessage(MM.deserialize(plugin.settings("poses").getString("messages.enabled",
            "<green>Pose enabled. Move or run the command again to leave it.")));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (active.containsKey(event.getPlayer().getUniqueId())
            && DelayedTeleport.moved(event.getFrom(), event.getTo())) {
            clear(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            clear(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    void shutdown() {
        plugin.getServer().getOnlinePlayers().forEach(this::clear);
    }

    private boolean allowed(Player player) {
        List<String> worlds = plugin.settings("poses").getStringList("allowed-worlds");
        return worlds.isEmpty() || worlds.stream().map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(player.getWorld().getName().toLowerCase(Locale.ROOT)::equals);
    }

    private void clear(Player player) {
        if (active.remove(player.getUniqueId()) != null) {
            player.setPose(Pose.STANDING, false);
            if (player.isOnline()) {
                player.sendMessage(MM.deserialize(plugin.settings("poses").getString("messages.disabled",
                    "<yellow>Pose disabled.")));
            }
        }
    }
}
