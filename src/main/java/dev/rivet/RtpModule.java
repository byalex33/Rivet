package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class RtpModule {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final Set<Material> DANGEROUS = Set.of(Material.LAVA, Material.FIRE,
        Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE,
        Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
        Material.WATER);
    private final RivetPlugin plugin;
    private final DelayedTeleport teleports;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;
    private final Set<UUID> searching = new HashSet<>();
    private boolean shutdown;

    RtpModule(RivetPlugin plugin, DelayedTeleport teleports) {
        this.plugin = plugin;
        this.teleports = teleports;
        settings = plugin.settings("rtp");
        data = plugin.data("rtp");
    }

    boolean command(Player player, String[] args) {
        if (args.length > 1 || args.length == 1 && !player.hasPermission("rivet.rtp.world")) {
            player.sendMessage(MM.deserialize("<white>Usage: /rtp" +
                (player.hasPermission("rivet.rtp.world") ? " [world]" : "")));
            return true;
        }
        World world = args.length == 0 ? player.getWorld() : plugin.getServer().getWorld(args[0]);
        if (world == null || !allowedWorld(world)) {
            message(player, "world-disabled", "<white>Random teleport is unavailable in that world.");
            return true;
        }
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, settings.getLong("cooldown-seconds", 300)) * 1000L;
        long remaining = data.getLong("players." + player.getUniqueId() + ".last-use")
            + cooldown - now;
        if (remaining > 0 && !player.hasPermission("rivet.rtp.cooldown.bypass")
            && !player.hasPermission("rivet.tp.nocooldown")) {
            player.sendMessage(MM.deserialize(settings.getString("messages.cooldown",
                    "<white>You can use RTP again in <#f72a4c>%seconds%s</#f72a4c>."),
                Placeholder.unparsed("seconds", Long.toString((remaining + 999) / 1000))));
            return true;
        }
        if (!searching.add(player.getUniqueId())) {
            message(player, "searching", "<white>Already searching for a safe location.");
            return true;
        }

        int minimum = radius(world, "minimum-radius", 500);
        int maximum = radius(world, "maximum-radius", 5000);
        if (!validRadius(minimum, maximum)) {
            searching.remove(player.getUniqueId());
            plugin.getLogger().warning("Invalid RTP radius for world " + world.getName() + ".");
            message(player, "failed", "<white>Could not find a safe location. Try again later.");
            return true;
        }
        message(player, "searching", "<white>Searching for a safe location...</white>");
        search(player, world, minimum, maximum,
            Math.max(1, settings.getInt("maximum-attempts", 24)));
        return true;
    }

    List<String> completions(Player player, String[] args) {
        return args.length == 1 && player.hasPermission("rivet.rtp.world")
            ? plugin.getServer().getWorlds().stream().filter(this::allowedWorld)
                .map(World::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList()
            : List.of();
    }

    void shutdown() {
        shutdown = true;
        searching.clear();
    }

    private void search(Player player, World world, int minimum, int maximum, int attemptsLeft) {
        if (shutdown || !player.isOnline()) {
            searching.remove(player.getUniqueId());
            return;
        }
        if (attemptsLeft <= 0) {
            searching.remove(player.getUniqueId());
            message(player, "failed", "<white>Could not find a safe location. Try again later.");
            return;
        }
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double radius = randomRadius(minimum, maximum, ThreadLocalRandom.current().nextDouble());
        Location center = world.getSpawnLocation();
        int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
        int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
        world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, failure) -> {
            if (shutdown) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (failure != null) {
                    search(player, world, minimum, maximum, attemptsLeft - 1);
                    return;
                }
                Location safe = safeLocation(world, x, z);
                if (safe == null) {
                    search(player, world, minimum, maximum, attemptsLeft - 1);
                    return;
                }
                searching.remove(player.getUniqueId());
                if (!player.isOnline()) {
                    return;
                }
                teleports.start(player, safe, () -> success(player));
            });
        });
    }

    private Location safeLocation(World world, int x, int z) {
        int start = world.getEnvironment() == World.Environment.NETHER
            ? Math.min(world.getMaxHeight() - 2, settings.getInt("nether-maximum-y", 120))
            : Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(x, z));
        int minimumY = world.getMinHeight() + 1;
        for (int groundY = start; groundY >= minimumY; groundY--) {
            Block ground = world.getBlockAt(x, groundY, z);
            Block feet = world.getBlockAt(x, groundY + 1, z);
            Block head = world.getBlockAt(x, groundY + 2, z);
            if (safeGround(ground.getType()) && feet.isPassable() && head.isPassable()
                && !feet.isLiquid() && !head.isLiquid()
                && biomeAllowed(ground.getBiome().getKey().toString(),
                    settings.getStringList("biomes.allowlist"),
                    settings.getStringList("biomes.denylist"))) {
                Location location = new Location(world, x + .5, groundY + 1, z + .5);
                if (world.getWorldBorder().isInside(location)) {
                    return location;
                }
            }
        }
        return null;
    }

    private void success(Player player) {
        if (!player.hasPermission("rivet.rtp.cooldown.bypass")
            && !player.hasPermission("rivet.tp.nocooldown")) {
            data.set("players." + player.getUniqueId() + ".last-use", System.currentTimeMillis());
            try {
                plugin.saveData("rtp");
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not save data/rtp.yml: " + exception.getMessage());
            }
        }
        message(player, "success", "<white>Teleported to a safe random location.");
        plugin.teleportFeedback(player, "Random location");
    }

    private int radius(World world, String key, int fallback) {
        return settings.getInt("worlds." + world.getName() + "." + key,
            settings.getInt(key, fallback));
    }

    private boolean allowedWorld(World world) {
        return settings.getStringList("allowed-worlds").stream()
            .anyMatch(name -> name.equalsIgnoreCase(world.getName()));
    }

    private void message(Player player, String key, String fallback) {
        player.sendMessage(MM.deserialize(settings.getString("messages." + key, fallback)));
    }

    static boolean validRadius(int minimum, int maximum) {
        return minimum >= 0 && maximum >= minimum;
    }

    static double randomRadius(double minimum, double maximum, double unit) {
        double bounded = Math.max(0, Math.min(Math.nextDown(1D), unit));
        return Math.sqrt(minimum * minimum + bounded * (maximum * maximum - minimum * minimum));
    }

    static boolean safeGround(Material material) {
        return safeGround(material.isSolid(), material);
    }

    static boolean safeGround(boolean solid, Material material) {
        return solid && !DANGEROUS.contains(material);
    }

    static boolean biomeAllowed(String biome, List<String> allowlist, List<String> denylist) {
        String normalized = biome.toLowerCase(Locale.ROOT);
        boolean denied = denylist.stream().map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> normalized.equals(value) || normalized.endsWith(":" + value));
        if (denied) {
            return false;
        }
        return allowlist.isEmpty() || allowlist.stream().map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> normalized.equals(value) || normalized.endsWith(":" + value));
    }
}
