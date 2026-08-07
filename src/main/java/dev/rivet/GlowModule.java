package dev.rivet;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class GlowModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final float THICKNESS = .04f;
    private static final List<String> COLORS = List.of(
        "aqua", "blue", "green", "yellow", "gold", "red", "light_purple", "white", "gray");

    private final RivetPlugin plugin;
    private final NamespacedKey wandKey;
    private final NamespacedKey outlineKey;
    private final File file;
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<String, Region> regions = new HashMap<>();

    GlowModule(RivetPlugin plugin) {
        this.plugin = plugin;
        wandKey = new NamespacedKey(plugin, "glow_wand");
        outlineKey = new NamespacedKey(plugin, "glow_outline");
        file = new File(plugin.getDataFolder(), "glows.yml");
        load();
        plugin.getServer().getScheduler().runTask(plugin, this::refreshLoaded);
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("wand")) {
            return giveWand(sender);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return create(sender, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            return remove(sender, args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("color")) {
            return color(sender, args[0], args[2]);
        }
        send(sender, "<red>Usage: /glow wand | create <name> | <name> color <color> | remove <name>");
        return true;
    }

    List<String> completions(String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(List.of("wand", "create", "remove"));
            choices.addAll(names());
            return choices;
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("remove")) {
                return names();
            }
            if (regions.containsKey(key(args[0]))) {
                return List.of("color");
            }
        }
        return args.length == 3 && args[1].equalsIgnoreCase("color") ? COLORS : List.of();
    }

    private boolean giveWand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>This command is only available to players.");
            return true;
        }
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        wand.editMeta(meta -> {
            meta.displayName(MM.deserialize("<aqua><bold>Glow Wand"));
            meta.lore(List.of(
                MM.deserialize("<gray>Left-click a block: <white>pos1"),
                MM.deserialize("<gray>Right-click a block: <white>pos2")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        });
        player.getInventory().addItem(wand).values()
            .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(player, "<green>Glow wand added to your inventory.");
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onWandUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
            || event.getItem() == null
            || !event.getItem().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE)) {
            return;
        }
        boolean first = event.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!first && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Selection selection = selections.computeIfAbsent(
            event.getPlayer().getUniqueId(), ignored -> new Selection());
        Location location = event.getClickedBlock().getLocation();
        if (first) {
            selection.first = location;
        } else {
            selection.second = location;
        }
        send(event.getPlayer(), "<aqua>Position " + (first ? "1" : "2") + ":</aqua> <white>"
            + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());
    }

    private boolean create(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>This command is only available to players.");
            return true;
        }
        if (!RivetPlugin.validWorldName(name) || regions.containsKey(key(name))) {
            send(sender, "<red>Use a unique name containing only letters, numbers, _ or -.");
            return true;
        }
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.first == null || selection.second == null
            || !selection.first.getWorld().equals(selection.second.getWorld())) {
            send(sender, "<red>Select two positions in the same world first.");
            return true;
        }
        Region region = new Region(name, selection.first, selection.second);
        // ponytail: 128-block sides keep display rendering sane; split larger regions if needed.
        if (region.longestSide() > 128) {
            send(sender, "<red>Glow outlines can be at most 128 blocks along each side.");
            return true;
        }
        regions.put(key(name), region);
        if (!save(sender)) {
            regions.remove(key(name));
            return true;
        }
        spawn(region);
        send(sender, "<green>Created glow outline <white>" + name + "</white>.");
        return true;
    }

    private boolean color(CommandSender sender, String name, String value) {
        Region region = regions.get(key(name));
        if (region == null) {
            send(sender, "<red>That glow outline does not exist.");
            return true;
        }
        Color color;
        try {
            color = parseColor(value);
        } catch (IllegalArgumentException exception) {
            send(sender, "<red>Use a named MiniMessage color or #RRGGBB.");
            return true;
        }
        Color previous = region.color;
        region.color = color;
        if (!save(sender)) {
            region.color = previous;
            return true;
        }
        displays(region).forEach(display -> display.setGlowColorOverride(color));
        send(sender, "<green>Set <white>" + region.name + "</white> to <" + hex(color) + ">"
            + value + "</" + hex(color) + ">.");
        return true;
    }

    private boolean remove(CommandSender sender, String name) {
        Region region = regions.remove(key(name));
        if (region == null) {
            send(sender, "<red>That glow outline does not exist.");
            return true;
        }
        if (!save(sender)) {
            regions.put(key(name), region);
            return true;
        }
        displays(region).forEach(Entity::remove);
        send(sender, "<green>Removed glow outline <white>" + region.name + "</white>.");
        return true;
    }

    private void spawn(Region region) {
        World world = plugin.getServer().getWorld(region.world);
        if (world == null) {
            return;
        }
        double x0 = region.minX;
        double y0 = region.minY;
        double z0 = region.minZ;
        double x1 = region.maxX + 1d;
        double y1 = region.maxY + 1d;
        double z1 = region.maxZ + 1d;
        for (double y : new double[]{y0, y1}) {
            for (double z : new double[]{z0, z1}) {
                edge(region, world, x0, y - THICKNESS / 2, z - THICKNESS / 2,
                    (float) (x1 - x0), THICKNESS, THICKNESS);
            }
        }
        for (double x : new double[]{x0, x1}) {
            for (double z : new double[]{z0, z1}) {
                edge(region, world, x - THICKNESS / 2, y0, z - THICKNESS / 2,
                    THICKNESS, (float) (y1 - y0), THICKNESS);
            }
        }
        for (double x : new double[]{x0, x1}) {
            for (double y : new double[]{y0, y1}) {
                edge(region, world, x - THICKNESS / 2, y - THICKNESS / 2, z0,
                    THICKNESS, THICKNESS, (float) (z1 - z0));
            }
        }
    }

    private void edge(Region region, World world, double x, double y, double z,
                      float scaleX, float scaleY, float scaleZ) {
        world.spawn(new Location(world, x, y, z), BlockDisplay.class, display -> {
            display.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
            display.setPersistent(true);
            display.setInvulnerable(true);
            display.setGlowing(true);
            display.setGlowColorOverride(region.color);
            display.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            display.setViewRange(Math.max(1, region.longestSide() / 64f + 1));
            Transformation transformation = display.getTransformation();
            transformation.getScale().set(scaleX, scaleY, scaleZ);
            display.setTransformation(transformation);
            display.getPersistentDataContainer().set(
                outlineKey, PersistentDataType.STRING, key(region.name));
        });
    }

    private List<BlockDisplay> displays(Region region) {
        String name = key(region.name);
        List<BlockDisplay> displays = new ArrayList<>();
        plugin.getServer().getWorlds().forEach(world -> world.getEntities().forEach(entity -> {
            if (entity instanceof BlockDisplay display && name.equals(entity.getPersistentDataContainer()
                .get(outlineKey, PersistentDataType.STRING))) {
                displays.add(display);
            }
        }));
        return displays;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        refresh(event.getChunk().getEntities());
    }

    private void refreshLoaded() {
        plugin.getServer().getWorlds().forEach(world ->
            refresh(world.getEntities().toArray(Entity[]::new)));
    }

    private void refresh(Entity[] entities) {
        for (Entity entity : entities) {
            String name = entity.getPersistentDataContainer().get(outlineKey, PersistentDataType.STRING);
            if (name == null) {
                continue;
            }
            Region region = regions.get(name);
            if (!(entity instanceof BlockDisplay display) || region == null
                || !region.world.equals(entity.getWorld().getUID())) {
                entity.remove();
                continue;
            }
            display.setGlowing(true);
            display.setGlowColorOverride(region.color);
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            ConfigurationSection root = yaml.getConfigurationSection("glows");
            if (root == null) {
                return;
            }
            for (String name : root.getKeys(false)) {
                String path = "glows." + name;
                Region region = new Region(name);
                region.world = UUID.fromString(required(yaml.getString(path + ".world")));
                region.minX = yaml.getInt(path + ".min-x");
                region.minY = yaml.getInt(path + ".min-y");
                region.minZ = yaml.getInt(path + ".min-z");
                region.maxX = yaml.getInt(path + ".max-x");
                region.maxY = yaml.getInt(path + ".max-y");
                region.maxZ = yaml.getInt(path + ".max-z");
                region.color = Color.fromRGB(yaml.getInt(path + ".color", 0x55FFFF));
                regions.put(key(name), region);
            }
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            plugin.getLogger().severe("Could not load glows.yml: " + exception.getMessage());
        }
    }

    private boolean save(CommandSender sender) {
        YamlConfiguration yaml = new YamlConfiguration();
        regions.values().forEach(region -> {
            String path = "glows." + region.name;
            yaml.set(path + ".world", region.world.toString());
            yaml.set(path + ".min-x", region.minX);
            yaml.set(path + ".min-y", region.minY);
            yaml.set(path + ".min-z", region.minZ);
            yaml.set(path + ".max-x", region.maxX);
            yaml.set(path + ".max-y", region.maxY);
            yaml.set(path + ".max-z", region.maxZ);
            yaml.set(path + ".color", region.color.asRGB());
        });
        try {
            Files.createDirectories(file.toPath().getParent());
            File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save glows.yml: " + exception.getMessage());
            send(sender, "<red>The glow change could not be saved.");
            return false;
        }
    }

    static Color parseColor(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        NamedTextColor named = NamedTextColor.NAMES.value(normalized);
        if (named != null) {
            return Color.fromRGB(named.value());
        }
        String hex = normalized.startsWith("#") ? normalized.substring(1) : normalized;
        if (hex.length() != 6 || !hex.matches("[0-9a-f]{6}")) {
            throw new IllegalArgumentException("invalid color");
        }
        return Color.fromRGB(Integer.parseInt(hex, 16));
    }

    private List<String> names() {
        return regions.values().stream().map(region -> region.name)
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%06X", color.asRGB());
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing value");
        }
        return value;
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(MM.deserialize(message));
    }

    private static final class Selection {
        private Location first;
        private Location second;
    }

    private static final class Region {
        private final String name;
        private UUID world;
        private int minX;
        private int minY;
        private int minZ;
        private int maxX;
        private int maxY;
        private int maxZ;
        private Color color = Color.AQUA;

        private Region(String name) {
            this.name = name;
        }

        private Region(String name, Location first, Location second) {
            this(name);
            world = first.getWorld().getUID();
            minX = Math.min(first.getBlockX(), second.getBlockX());
            minY = Math.min(first.getBlockY(), second.getBlockY());
            minZ = Math.min(first.getBlockZ(), second.getBlockZ());
            maxX = Math.max(first.getBlockX(), second.getBlockX());
            maxY = Math.max(first.getBlockY(), second.getBlockY());
            maxZ = Math.max(first.getBlockZ(), second.getBlockZ());
        }

        private int longestSide() {
            return Math.max(maxX - minX + 1,
                Math.max(maxY - minY + 1, maxZ - minZ + 1));
        }
    }
}
