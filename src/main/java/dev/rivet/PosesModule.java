package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
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
    private final Map<UUID, ActivePose> active = new HashMap<>();

    PosesModule(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    boolean command(Player player, String command, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /" + command));
            return true;
        }
        if (!allowed(player)) {
            player.sendMessage(MM.deserialize(plugin.settings("poses").getString("messages.not-allowed",
                "<white>Poses are not allowed in this world.")));
            return true;
        }
        PoseMode mode = modeFor(command);
        ActivePose current = active.get(player.getUniqueId());
        if (current != null && current.mode() == mode) {
            clear(player);
            return true;
        }
        if (current != null) {
            clear(player, false);
        }
        if (!activate(player, mode)) {
            player.sendMessage(MM.deserialize("<white>Could not enable that pose here.</white>"));
            return true;
        }
        player.sendMessage(MM.deserialize(plugin.settings("poses").getString("messages.enabled",
            "<white>Pose enabled. Move, dismount, or run the command again to leave it.")));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        ActivePose pose = active.get(event.getPlayer().getUniqueId());
        if (pose != null && pose.seat() == null
            && DelayedTeleport.moved(event.getFrom(), event.getTo())) {
            clear(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ActivePose pose = active.get(player.getUniqueId());
        if (pose != null && pose.seat() != null && pose.seat().equals(event.getDismounted())) {
            clear(player);
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
        clear(player, true);
    }

    private void clear(Player player, boolean notify) {
        ActivePose pose = active.remove(player.getUniqueId());
        if (pose == null) {
            return;
        }
        if (pose.seat() != null) {
            if (player.getVehicle() != null && player.getVehicle().equals(pose.seat())) {
                player.leaveVehicle();
            }
            pose.seat().remove();
        }
        player.setPose(Pose.STANDING, false);
        if (notify && player.isOnline()) {
            player.sendMessage(MM.deserialize(plugin.settings("poses").getString("messages.disabled",
                "<white>Pose disabled.")));
        }
    }

    private boolean activate(Player player, PoseMode mode) {
        ArmorStand seat = null;
        if (mode == PoseMode.SIT) {
            seat = player.getWorld().spawn(player.getLocation().subtract(0, .45, 0),
                ArmorStand.class, stand -> {
                    stand.setInvisible(true);
                    stand.setMarker(true);
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.setPersistent(false);
                    stand.setSilent(true);
                    stand.setCollidable(false);
                    stand.addScoreboardTag("rivet-pose-seat");
                });
            if (!seat.addPassenger(player)) {
                seat.remove();
                return false;
            }
        }
        player.setPose(mode.pose(), true);
        active.put(player.getUniqueId(), new ActivePose(mode, seat));
        return true;
    }

    static PoseMode modeFor(String command) {
        return switch (command) {
            case "sit" -> PoseMode.SIT;
            case "lay" -> PoseMode.LAY;
            default -> PoseMode.CRAWL;
        };
    }

    enum PoseMode {
        SIT(Pose.SITTING),
        // Minecraft's client immediately overrides SLEEPING when no real bed is involved.
        // SWIMMING is the stable horizontal pose available through Paper's public API.
        LAY(Pose.SWIMMING),
        CRAWL(Pose.SWIMMING);

        private final Pose pose;

        PoseMode(Pose pose) {
            this.pose = pose;
        }

        Pose pose() {
            return pose;
        }
    }

    private record ActivePose(PoseMode mode, ArmorStand seat) {
    }
}
