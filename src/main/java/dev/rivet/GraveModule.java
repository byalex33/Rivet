package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class GraveModule implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RivetPlugin plugin;
    private final DelayedTeleport teleports;
    private final NamespacedKey graveKey;
    private final File file;
    private final YamlConfiguration settings;
    private final Map<UUID, Grave> graves = new HashMap<>();
    private final Map<UUID, DeathState> deaths = new HashMap<>();
    private final BukkitTask expiryTask;

    GraveModule(RivetPlugin plugin, DelayedTeleport teleports) {
        this.plugin = plugin;
        this.teleports = teleports;
        graveKey = new NamespacedKey(plugin, "grave");
        file = plugin.dataFile("graves");
        settings = plugin.settings("graves");
        load();
        plugin.getServer().getScheduler().runTask(plugin, this::ensureLoadedVisuals);
        expiryTask = settings.getLong("expiry.seconds") > 0
            ? plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 20, 20)
            : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Location death = player.getLocation();
        DeathState previous = deaths.get(player.getUniqueId());
        deaths.put(player.getUniqueId(), new DeathState(death.getWorld().getUID(), death.getX(),
            death.getY(), death.getZ(), death.getYaw(), death.getPitch(),
            previous == null ? 0 : previous.lastBack));
        player.sendMessage(MM.deserialize(settings.getString("death-message",
                "<red>You died at <white><x>, <y>, <z></white> in <gray><world></gray>.</red>"),
            Placeholder.unparsed("x", Integer.toString(death.getBlockX())),
            Placeholder.unparsed("y", Integer.toString(death.getBlockY())),
            Placeholder.unparsed("z", Integer.toString(death.getBlockZ())),
            Placeholder.unparsed("world", death.getWorld().getName())));
        try {
            save();
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save " + player.getName() + "'s death location: "
                + exception.getMessage());
        }

        if (event.getKeepInventory() || event.getDrops().isEmpty()) {
            return;
        }

        Location location = graveLocation(death);
        Component deathMessage = event.deathMessage();
        String cause = deathMessage == null ? player.getName() + " died"
            : PLAIN.serialize(deathMessage);
        Grave grave = new Grave(UUID.randomUUID(), player.getUniqueId(), player.getName(),
            location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(), cause,
            event.getDrops().stream().map(ItemStack::clone).toList(), System.currentTimeMillis());
        graves.put(grave.id(), grave);
        try {
            save();
        } catch (IOException exception) {
            graves.remove(grave.id());
            plugin.getLogger().severe("Could not save " + player.getName() + "'s grave: " + exception.getMessage());
            player.sendMessage(Component.text("Your grave could not be saved; your items dropped normally.",
                NamedTextColor.RED));
            return;
        }

        event.getDrops().clear();
        spawnVisuals(grave, location);
        player.sendMessage(Component.text("Your grave is waiting at ", NamedTextColor.GOLD)
            .append(Component.text(coordinates(location), NamedTextColor.YELLOW))
            .append(Component.text(".", NamedTextColor.GOLD)));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!settings.getBoolean("tracking-compass.enabled", true)) {
            return;
        }
        Grave grave = graves.values().stream()
            .filter(candidate -> candidate.owner().equals(event.getPlayer().getUniqueId()))
            .max(java.util.Comparator.comparingLong(Grave::createdAt)).orElse(null);
        Location location = grave == null ? null : location(grave);
        if (location == null) {
            return;
        }
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        meta.setLodestone(location);
        meta.setLodestoneTracked(false);
        meta.displayName(MM.deserialize(settings.getString("tracking-compass.name",
            "<gold>Grave Tracker</gold>")));
        List<String> lore = settings.getStringList("tracking-compass.lore");
        if (lore.isEmpty()) {
            lore = List.of("<gray>Points to your latest active grave.</gray>");
        }
        meta.lore(lore.stream()
            .map(MM::deserialize).toList());
        compass.setItemMeta(meta);
        event.getPlayer().getInventory().addItem(compass).values().forEach(item ->
            event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), item));
    }

    boolean back(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<red>Usage: /back"));
            return true;
        }
        DeathState state = deaths.get(player.getUniqueId());
        Location destination = state == null ? null : location(state);
        if (destination == null) {
            player.sendMessage(MM.deserialize(settings.getString("back.messages.none",
                "<red>No death location is saved.</red>")));
            return true;
        }
        long now = System.currentTimeMillis();
        long remaining = cooldownRemaining(state.lastBack,
            Math.max(0, settings.getLong("back.cooldown-seconds", 30)), now);
        if (remaining > 0) {
            player.sendMessage(MM.deserialize(settings.getString("back.messages.cooldown",
                    "<red>You can use /back again in <seconds> seconds.</red>"),
                Placeholder.unparsed("seconds", Long.toString(remaining))));
            return true;
        }
        int delay = Math.max(0, settings.getInt("back.teleport-delay-seconds", 3));
        teleports.start(player, destination, delay,
            settings.getBoolean("back.cancel-on-move", true),
            MM.deserialize(settings.getString("back.messages.wait",
                    "<yellow>Returning in <white><seconds></white> seconds. Do not move.</yellow>"),
                Placeholder.unparsed("seconds", Integer.toString(delay))),
            MM.deserialize(settings.getString("back.messages.cancelled",
                "<red>Back teleport cancelled because you moved.</red>")),
            () -> {
                deaths.put(player.getUniqueId(), state.withLastBack(System.currentTimeMillis()));
                try {
                    save();
                } catch (IOException exception) {
                    plugin.getLogger().severe("Could not save /back cooldown: " + exception.getMessage());
                }
                player.sendMessage(MM.deserialize(settings.getString("back.messages.teleported",
                    "<green>Returned to your latest death location.</green>")));
                plugin.teleportFeedback(player, "Death location");
            });
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        claim(event);
    }

    @EventHandler
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        claim(event);
    }

    private void claim(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String value = event.getRightClicked().getPersistentDataContainer()
            .get(graveKey, PersistentDataType.STRING);
        if (value == null) {
            return;
        }
        event.setCancelled(true);

        UUID id;
        try {
            id = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            event.getRightClicked().remove();
            return;
        }
        Grave grave = graves.get(id);
        if (grave == null) {
            removeVisuals(id, event.getRightClicked().getLocation());
            return;
        }

        Player player = event.getPlayer();
        if (!canOpen(grave.owner(), player.getUniqueId(), grave.createdAt(),
            settings.getBoolean("ownership.owner-only", true),
            Math.max(0, settings.getLong("ownership.allow-others-after-seconds", 0)),
            System.currentTimeMillis())) {
            player.sendMessage(Component.text("This grave belongs to " + grave.name() + ".", NamedTextColor.RED));
            return;
        }

        graves.remove(id);
        try {
            save();
        } catch (IOException exception) {
            graves.put(id, grave);
            plugin.getLogger().severe("Could not claim " + player.getName() + "'s grave: " + exception.getMessage());
            player.sendMessage(Component.text("Your grave could not be opened safely. Try again.", NamedTextColor.RED));
            return;
        }

        Location location = location(grave);
        removeVisuals(id, location);
        ItemStack[] items = grave.items().stream().map(ItemStack::clone).toArray(ItemStack[]::new);
        player.getInventory().addItem(items).values()
            .forEach(item -> location.getWorld().dropItemNaturally(location, item));
        player.sendMessage(Component.text(grave.owner().equals(player.getUniqueId())
            ? "Your items have been returned." : "The grave's items have been claimed.", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1, .8f);
        player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0),
            18, .5, .6, .5);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        List<Grave> inChunk = graves.values().stream()
            .filter(grave -> grave.world().equals(event.getWorld().getUID())
                && ((int) Math.floor(grave.x()) >> 4) == event.getChunk().getX()
                && ((int) Math.floor(grave.z()) >> 4) == event.getChunk().getZ())
            .toList();
        if (!inChunk.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin,
                () -> inChunk.forEach(this::ensureVisuals));
        }
    }

    private void spawnVisuals(Grave grave, Location location) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        Player owner = plugin.getServer().getPlayer(grave.owner());
        meta.setPlayerProfile(owner == null
            ? plugin.getServer().getOfflinePlayer(grave.owner()).getPlayerProfile()
            : owner.getPlayerProfile());
        head.setItemMeta(meta);

        location.getWorld().spawn(location.clone().add(0, .7, 0), ItemDisplay.class, display -> {
            tag(display, grave.id());
            display.setItemStack(head);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            display.setBillboard(Display.Billboard.CENTER);
            display.setGlowing(true);
        });
        location.getWorld().spawn(location.clone().add(0, 1.7, 0), TextDisplay.class, display -> {
            tag(display, grave.id());
            display.text(hologram(grave.name(), grave.cause(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
            display.setShadowed(true);
            display.setSeeThrough(true);
            display.setLineWidth(240);
        });
        location.getWorld().spawn(location, Interaction.class, interaction -> {
            tag(interaction, grave.id());
            interaction.setInteractionWidth(1.2f);
            interaction.setInteractionHeight(1.6f);
            interaction.setResponsive(true);
        });
    }

    private void tag(Entity entity, UUID id) {
        entity.setPersistent(true);
        entity.setInvulnerable(true);
        entity.getPersistentDataContainer().set(graveKey, PersistentDataType.STRING, id.toString());
    }

    private void ensureLoadedVisuals() {
        graves.values().forEach(grave -> {
            World world = plugin.getServer().getWorld(grave.world());
            if (world != null && world.isChunkLoaded((int) Math.floor(grave.x()) >> 4,
                (int) Math.floor(grave.z()) >> 4)) {
                ensureVisuals(grave);
            }
        });
    }

    private void expire() {
        long seconds = Math.max(0, settings.getLong("expiry.seconds"));
        if (seconds == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Grave> expired = graves.values().stream()
            .filter(grave -> expired(grave.createdAt(), seconds, now))
            .filter(grave -> location(grave) != null).toList();
        if (expired.isEmpty()) {
            return;
        }
        expired.forEach(grave -> graves.remove(grave.id()));
        try {
            save();
        } catch (IOException exception) {
            expired.forEach(grave -> graves.put(grave.id(), grave));
            plugin.getLogger().severe("Could not save expired graves: " + exception.getMessage());
            return;
        }
        for (Grave grave : expired) {
            Location location = location(grave);
            if (settings.getBoolean("expiry.drop-items", true)) {
                grave.items().stream().map(ItemStack::clone)
                    .forEach(item -> location.getWorld().dropItemNaturally(location, item));
            }
            removeVisuals(grave.id(), location);
        }
    }

    void shutdown() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
    }

    private void ensureVisuals(Grave grave) {
        Location location = location(grave);
        if (location == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4,
            location.getBlockZ() >> 4)) {
            return;
        }
        List<Entity> visuals = visuals(grave.id(), location);
        if (visuals.size() == 3) {
            return;
        }
        visuals.forEach(Entity::remove);
        spawnVisuals(grave, location);
    }

    private List<Entity> visuals(UUID id, Location location) {
        if (location == null || !location.isWorldLoaded()) {
            return List.of();
        }
        String value = id.toString();
        return new ArrayList<>(location.getWorld().getNearbyEntities(location, 2, 3, 2,
            entity -> value.equals(entity.getPersistentDataContainer().get(graveKey, PersistentDataType.STRING))));
    }

    private void removeVisuals(UUID id, Location location) {
        visuals(id, location).forEach(Entity::remove);
    }

    private Location graveLocation(Location death) {
        World world = death.getWorld();
        if (death.getY() < world.getMinHeight() || death.getY() >= world.getMaxHeight()) {
            return world.getSpawnLocation().toCenterLocation();
        }
        int x = death.getBlockX();
        int y = death.getBlockY();
        int z = death.getBlockZ();
        while (y < world.getMaxHeight() - 2 && world.getBlockAt(x, y, z).isLiquid()) {
            y++;
        }
        for (int attempts = 0; attempts < 8 && !world.getBlockAt(x, y, z).isPassable(); attempts++) {
            y++;
        }
        if (!world.getBlockAt(x, y, z).isPassable()) {
            y = world.getHighestBlockYAt(x, z) + 1;
        }
        return new Location(world, x + .5, y + .1, z + .5);
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            ConfigurationSection section = yaml.getConfigurationSection("graves");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        String path = "graves." + key;
                        List<ItemStack> items = yaml.getList(path + ".items", List.of()).stream()
                            .filter(ItemStack.class::isInstance).map(ItemStack.class::cast).toList();
                        Grave grave = new Grave(UUID.fromString(key),
                            UUID.fromString(yaml.getString(path + ".owner", "")),
                            yaml.getString(path + ".name", "Unknown"),
                            UUID.fromString(yaml.getString(path + ".world", "")),
                            yaml.getDouble(path + ".x"), yaml.getDouble(path + ".y"), yaml.getDouble(path + ".z"),
                            yaml.getString(path + ".cause", "Died"), items,
                            yaml.getLong(path + ".created-at", System.currentTimeMillis()));
                        graves.put(grave.id(), grave);
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning("Skipped invalid grave " + key + ": " + exception.getMessage());
                    }
                }
            }
            ConfigurationSection deathSection = yaml.getConfigurationSection("deaths");
            if (deathSection != null) {
                for (String key : deathSection.getKeys(false)) {
                    try {
                        String path = "deaths." + key;
                        deaths.put(UUID.fromString(key), new DeathState(
                            UUID.fromString(yaml.getString(path + ".world", "")),
                            yaml.getDouble(path + ".x"), yaml.getDouble(path + ".y"),
                            yaml.getDouble(path + ".z"), (float) yaml.getDouble(path + ".yaw"),
                            (float) yaml.getDouble(path + ".pitch"), yaml.getLong(path + ".last-back")));
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning("Skipped invalid death location " + key + ".");
                    }
                }
            }
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not load data/graves.yml: " + exception.getMessage());
        }
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Grave grave : graves.values()) {
            String path = "graves." + grave.id();
            yaml.set(path + ".owner", grave.owner().toString());
            yaml.set(path + ".name", grave.name());
            yaml.set(path + ".world", grave.world().toString());
            yaml.set(path + ".x", grave.x());
            yaml.set(path + ".y", grave.y());
            yaml.set(path + ".z", grave.z());
            yaml.set(path + ".cause", grave.cause());
            yaml.set(path + ".items", grave.items());
            yaml.set(path + ".created-at", grave.createdAt());
        }
        for (Map.Entry<UUID, DeathState> entry : deaths.entrySet()) {
            String path = "deaths." + entry.getKey();
            DeathState state = entry.getValue();
            yaml.set(path + ".world", state.world.toString());
            yaml.set(path + ".x", state.x);
            yaml.set(path + ".y", state.y);
            yaml.set(path + ".z", state.z);
            yaml.set(path + ".yaw", state.yaw);
            yaml.set(path + ".pitch", state.pitch);
            yaml.set(path + ".last-back", state.lastBack);
        }
        Files.createDirectories(file.toPath().getParent());
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        yaml.save(temporary);
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Location location(Grave grave) {
        World world = plugin.getServer().getWorld(grave.world());
        return world == null ? null : new Location(world, grave.x(), grave.y(), grave.z());
    }

    private Location location(DeathState state) {
        World world = plugin.getServer().getWorld(state.world);
        return world == null ? null : new Location(world, state.x, state.y, state.z, state.yaw, state.pitch);
    }

    private static String coordinates(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    static Component hologram(String username, String cause, int x, int y, int z) {
        return Component.text(username + "'s Grave", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.newline())
            .append(Component.text(cause, NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("X " + x + "  Y " + y + "  Z " + z, NamedTextColor.YELLOW))
            .append(Component.newline())
            .append(Component.text("Right-click to reclaim", NamedTextColor.GREEN));
    }

    static boolean canOpen(UUID owner, UUID player, long createdAt, boolean ownerOnly,
                           long allowOthersAfterSeconds, long now) {
        return owner.equals(player) || !ownerOnly || allowOthersAfterSeconds > 0
            && now - createdAt >= allowOthersAfterSeconds * 1000L;
    }

    static boolean expired(long createdAt, long expirySeconds, long now) {
        return expirySeconds > 0 && now - createdAt >= expirySeconds * 1000L;
    }

    static long cooldownRemaining(long lastUse, long cooldownSeconds, long now) {
        return Math.max(0, (lastUse + cooldownSeconds * 1000L - now + 999) / 1000);
    }

    private record Grave(UUID id, UUID owner, String name, UUID world, double x, double y, double z,
                         String cause, List<ItemStack> items, long createdAt) {
    }

    private record DeathState(UUID world, double x, double y, double z, float yaw, float pitch,
                              long lastBack) {
        private DeathState withLastBack(long value) {
            return new DeathState(world, x, y, z, yaw, pitch, value);
        }
    }
}
