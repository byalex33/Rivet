package dev.rivet;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent, internal location history used by the simple /back command. */
final class TeleportHistory {
    static final int MAX_ENTRIES = 8;
    static final double MIN_DISTANCE_SQUARED = 4d;

    private final RivetPlugin plugin;
    private final YamlConfiguration data;
    private final Map<UUID, PlayerHistory> histories = new HashMap<>();

    TeleportHistory(RivetPlugin plugin) {
        this.plugin = plugin;
        data = plugin.data("teleports");
        load();
    }

    void record(Player player, Location origin, String source) {
        Snapshot snapshot = snapshot(origin);
        if (snapshot == null) {
            return;
        }
        PlayerHistory history = histories.computeIfAbsent(player.getUniqueId(),
            ignored -> new PlayerHistory());
        push(history.entries, new Entry(UUID.randomUUID(), snapshot,
            System.currentTimeMillis(), source));
        save();
    }

    BackDestination destination(Player player) {
        PlayerHistory history = histories.get(player.getUniqueId());
        if (history == null || history.entries.isEmpty()) {
            return null;
        }
        boolean changed = false;
        for (Entry entry : List.copyOf(history.entries)) {
            Location destination = location(entry.location);
            if (destination == null) {
                continue;
            }
            if (!meaningful(player.getLocation(), destination)) {
                history.entries.remove(entry);
                changed = true;
                continue;
            }
            if (changed) {
                save();
            }
            return new BackDestination(entry.id, destination);
        }
        if (changed) {
            save();
        }
        return null;
    }

    long lastBack(UUID player) {
        PlayerHistory history = histories.get(player);
        return history == null ? 0 : history.lastBack;
    }

    void completeBack(Player player, BackDestination destination, boolean recordCooldown) {
        PlayerHistory history = histories.get(player.getUniqueId());
        if (history == null) {
            return;
        }
        consume(history.entries, destination.id);
        if (recordCooldown) {
            history.lastBack = System.currentTimeMillis();
        }
        save();
    }

    boolean importLegacyDeath(UUID player, UUID world, double x, double y, double z,
                              float yaw, float pitch, long lastBack) {
        PlayerHistory history = histories.computeIfAbsent(player, ignored -> new PlayerHistory());
        if (!history.entries.isEmpty()) {
            return false;
        }
        World loaded = plugin.getServer().getWorld(world);
        String worldName = loaded == null ? "" : loaded.getName();
        Snapshot snapshot = new Snapshot(world, worldName, x, y, z, yaw, pitch);
        if (!snapshot.valid()) {
            return false;
        }
        history.entries.add(new Entry(UUID.randomUUID(), snapshot, 0, "death"));
        history.lastBack = Math.max(history.lastBack, lastBack);
        return true;
    }

    void saveImportedHistory() {
        save();
    }

    void shutdown() {
        save();
    }

    private void load() {
        ConfigurationSection section = data.getConfigurationSection("history");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID player = UUID.fromString(key);
                String path = "history." + key;
                PlayerHistory history = new PlayerHistory();
                history.lastBack = data.getLong(path + ".last-back");
                for (Map<?, ?> values : data.getMapList(path + ".entries")) {
                    Entry entry = entry(values);
                    if (entry != null) {
                        history.entries.add(entry);
                    }
                    if (history.entries.size() == MAX_ENTRIES) {
                        break;
                    }
                }
                if (!history.entries.isEmpty() || history.lastBack > 0) {
                    histories.put(player, history);
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipped invalid teleport history for " + key + ".");
            }
        }
    }

    private void save() {
        data.set("history", null);
        for (Map.Entry<UUID, PlayerHistory> player : histories.entrySet()) {
            String path = "history." + player.getKey();
            PlayerHistory history = player.getValue();
            data.set(path + ".last-back", history.lastBack);
            data.set(path + ".entries", history.entries.stream().map(TeleportHistory::values).toList());
        }
        try {
            plugin.saveData("teleports");
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save teleport history: " + exception.getMessage());
        }
    }

    private Location location(Snapshot snapshot) {
        World world = plugin.getServer().getWorld(snapshot.world);
        if (world == null && !snapshot.worldName.isBlank()) {
            world = plugin.getServer().getWorld(snapshot.worldName);
        }
        if (world == null || snapshot.y < world.getMinHeight() || snapshot.y >= world.getMaxHeight()) {
            return null;
        }
        return new Location(world, snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch);
    }

    static boolean meaningful(Location from, Location to) {
        Snapshot first = snapshot(from);
        Snapshot second = snapshot(to);
        return first != null && second != null && meaningful(first, second);
    }

    static boolean meaningful(Snapshot from, Snapshot to) {
        if (!from.world.equals(to.world)) {
            return true;
        }
        double x = from.x - to.x;
        double y = from.y - to.y;
        double z = from.z - to.z;
        return x * x + y * y + z * z >= MIN_DISTANCE_SQUARED;
    }

    static void push(List<Entry> entries, Entry entry) {
        entries.removeIf(existing -> samePlace(existing.location, entry.location));
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    static boolean consume(List<Entry> entries, UUID id) {
        return entries.removeIf(entry -> entry.id.equals(id));
    }

    private static boolean samePlace(Snapshot first, Snapshot second) {
        if (!first.world.equals(second.world)) {
            return false;
        }
        double x = first.x - second.x;
        double y = first.y - second.y;
        double z = first.z - second.z;
        return x * x + y * y + z * z < MIN_DISTANCE_SQUARED;
    }

    private static Snapshot snapshot(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        Snapshot snapshot = new Snapshot(location.getWorld().getUID(), location.getWorld().getName(),
            location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        return snapshot.valid() ? snapshot : null;
    }

    static Entry entry(Map<?, ?> values) {
        try {
            UUID id = UUID.fromString(string(values, "id"));
            UUID world = UUID.fromString(string(values, "world"));
            Snapshot snapshot = new Snapshot(world, string(values, "world-name"),
                number(values, "x"), number(values, "y"), number(values, "z"),
                (float) number(values, "yaw"), (float) number(values, "pitch"));
            return snapshot.valid() ? new Entry(id, snapshot,
                (long) number(values, "created-at"), string(values, "source")) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static Map<String, Object> values(Entry entry) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", entry.id.toString());
        values.put("world", entry.location.world.toString());
        values.put("world-name", entry.location.worldName);
        values.put("x", entry.location.x);
        values.put("y", entry.location.y);
        values.put("z", entry.location.z);
        values.put("yaw", entry.location.yaw);
        values.put("pitch", entry.location.pitch);
        values.put("created-at", entry.createdAt);
        values.put("source", entry.source);
        return values;
    }

    private static String string(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private static double number(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("Missing number " + key);
    }

    record BackDestination(UUID id, Location location) {
    }

    record Entry(UUID id, Snapshot location, long createdAt, String source) {
    }

    record Snapshot(UUID world, String worldName, double x, double y, double z,
                    float yaw, float pitch) {
        boolean valid() {
            return world != null && worldName != null && Double.isFinite(x) && Double.isFinite(y)
                && Double.isFinite(z) && Float.isFinite(yaw) && Float.isFinite(pitch);
        }
    }

    private static final class PlayerHistory {
        private final List<Entry> entries = new ArrayList<>();
        private long lastBack;
    }
}
