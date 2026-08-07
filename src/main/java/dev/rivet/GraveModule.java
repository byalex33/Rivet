package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

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

    private final RivetPlugin plugin;
    private final NamespacedKey graveKey;
    private final File file;
    private final Map<UUID, Grave> graves = new HashMap<>();

    GraveModule(RivetPlugin plugin) {
        this.plugin = plugin;
        graveKey = new NamespacedKey(plugin, "grave");
        file = new File(plugin.getDataFolder(), "graves.yml");
        load();
        plugin.getServer().getScheduler().runTask(plugin, this::ensureLoadedVisuals);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Location death = player.getLocation();
        String coordinates = coordinates(death);
        player.sendMessage(Component.text("You died at ", NamedTextColor.RED)
            .append(Component.text(coordinates, NamedTextColor.WHITE))
            .append(Component.text(" in " + death.getWorld().getName() + ".", NamedTextColor.GRAY)));

        if (event.getKeepInventory() || event.getDrops().isEmpty()) {
            return;
        }

        Location location = graveLocation(death);
        Component deathMessage = event.deathMessage();
        String cause = deathMessage == null ? player.getName() + " died"
            : PLAIN.serialize(deathMessage);
        Grave grave = new Grave(UUID.randomUUID(), player.getUniqueId(), player.getName(),
            location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(), cause,
            event.getDrops().stream().map(ItemStack::clone).toList());
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
        if (!grave.owner().equals(player.getUniqueId())) {
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
        player.sendMessage(Component.text("Your items have been returned.", NamedTextColor.GREEN));
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
            if (section == null) {
                return;
            }
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
                        yaml.getString(path + ".cause", "Died"), items);
                    graves.put(grave.id(), grave);
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Skipped invalid grave " + key + ": " + exception.getMessage());
                }
            }
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not load graves.yml: " + exception.getMessage());
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

    private record Grave(UUID id, UUID owner, String name, UUID world, double x, double y, double z,
                         String cause, List<ItemStack> items) {
    }
}
