package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;

final class SpawnModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final DelayedTeleport teleports;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;

    SpawnModule(RivetPlugin plugin, DelayedTeleport teleports) {
        this.plugin = plugin;
        this.teleports = teleports;
        settings = plugin.settings("spawn");
        data = plugin.data("spawn");
    }

    boolean command(Player player, String command, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /" + command));
            return true;
        }
        if (command.equals("setspawn")) {
            set(player);
        } else {
            teleport(player);
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (settings.getBoolean("join.teleport-every-join")
            || settings.getBoolean("join.teleport-first-join") && !event.getPlayer().hasPlayedBefore()) {
            teleport(event.getPlayer());
        }
    }

    private void set(Player player) {
        Location location = player.getLocation();
        data.set("spawn.world", location.getWorld().getName());
        data.set("spawn.x", location.getX());
        data.set("spawn.y", location.getY());
        data.set("spawn.z", location.getZ());
        data.set("spawn.yaw", location.getYaw());
        data.set("spawn.pitch", location.getPitch());
        try {
            plugin.saveData("spawn");
            player.sendMessage(MM.deserialize(settings.getString("messages.set",
                "<white>Spawn set to your location.")));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/spawn.yml: " + exception.getMessage());
            player.sendMessage(MM.deserialize("<white>Could not save the spawn. Check the console."));
        }
    }

    private void teleport(Player player) {
        Location destination = location();
        if (destination == null) {
            player.sendMessage(MM.deserialize(settings.getString("messages.not-set",
                "<white>The server spawn has not been set.")));
            return;
        }
        int delay = Math.max(0, settings.getInt("teleport-delay-seconds", 3));
        teleports.start(player, destination, delay, settings.getBoolean("cancel-on-move", true),
            MM.deserialize(settings.getString("messages.wait",
                    "<white>Teleporting to spawn in <#f72a4c>%seconds%</#f72a4c> seconds. Do not move.</white>"),
                Placeholder.unparsed("seconds", Integer.toString(delay))),
            MM.deserialize(settings.getString("messages.cancelled", "<white>Teleport cancelled because you moved.")),
            () -> {
                player.sendMessage(MM.deserialize(settings.getString("messages.teleported",
                    "<white>Teleported to spawn.")));
                plugin.teleportFeedback(player, "Spawn");
            });
    }

    private Location location() {
        String worldName = data.getString("spawn.world");
        World world = worldName == null ? null : plugin.getServer().getWorld(worldName);
        double x = data.getDouble("spawn.x");
        double y = data.getDouble("spawn.y");
        double z = data.getDouble("spawn.z");
        float yaw = (float) data.getDouble("spawn.yaw");
        float pitch = (float) data.getDouble("spawn.pitch");
        return world == null || !RivetPlugin.validCoordinates(x, y, z, yaw, pitch)
            ? null : new Location(world, x, y, z, yaw, pitch);
    }
}
