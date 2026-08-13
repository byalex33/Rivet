package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class LaggModule {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final long DEFAULT_INTERVAL_SECONDS = 300;
    private static final long MAX_INTERVAL_SECONDS = Long.MAX_VALUE / 20;
    private static final Set<Material> DEFAULT_CROP_DROPS = Set.of(
        Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO,
        Material.POISONOUS_POTATO, Material.BEETROOT, Material.BEETROOT_SEEDS,
        Material.NETHER_WART, Material.COCOA_BEANS, Material.MELON, Material.MELON_SLICE,
        Material.MELON_SEEDS, Material.PUMPKIN, Material.PUMPKIN_SEEDS,
        Material.SWEET_BERRIES, Material.GLOW_BERRIES, Material.TORCHFLOWER,
        Material.TORCHFLOWER_SEEDS, Material.PITCHER_PLANT, Material.PITCHER_POD,
        Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO, Material.KELP,
        Material.DRIED_KELP, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
        Material.CHORUS_FRUIT, Material.CHORUS_FLOWER);

    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final List<BukkitTask> tasks = new ArrayList<>();

    LaggModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("lagg");
        reload();
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length != 1) {
            message(sender, "messages.usage", "<white>Usage: /lagg clear</white>");
            return true;
        }
        if (args[0].equalsIgnoreCase("clear")) {
            CleanupResult result = clearGroundItems();
            broadcastCleanup(result, true);
            reload();
            return true;
        }
        message(sender, "messages.usage", "<white>Usage: /lagg clear</white>");
        return true;
    }

    void reload() {
        shutdown();
        long interval = cleanupIntervalSeconds(settings.getLong(
            "cleanup-interval-seconds", DEFAULT_INTERVAL_SECONDS));
        long periodTicks = interval * 20;
        for (int warning : warningSeconds(settings.getIntegerList("warning-seconds"), interval)) {
            tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> broadcastWarning(warning), (interval - warning) * 20, periodTicks));
        }
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin,
            () -> broadcastCleanup(clearGroundItems(), false), periodTicks, periodTicks));
    }

    void shutdown() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    private void broadcastWarning(int seconds) {
        plugin.messageActions().run(plugin.getServer().getOnlinePlayers(),
            plugin.getServer().getOnlinePlayers(), settings, "messages.warning", "broadcast",
            "<white>Ground items will be cleared in <#f72a4c>%seconds%</#f72a4c> second%plural%.</white>",
            Placeholder.unparsed("seconds", Integer.toString(seconds)),
            Placeholder.unparsed("plural", seconds == 1 ? "" : "s"));
    }

    private CleanupResult clearGroundItems() {
        boolean ignoreCustomNames = settings.getBoolean("ignore.custom-names", true);
        boolean ignorePersistentData = settings.getBoolean("ignore.persistent-data", true);
        boolean ignoreCrops = settings.getBoolean("ignore.crops", true);
        Set<Material> crops = cropMaterials(settings.getStringList("crop-materials"));
        Map<Material, RemovedItem> removed = new LinkedHashMap<>();
        int entityCount = 0;
        long itemCount = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                ItemStack stack = item.getItemStack();
                ItemMeta meta = stack.getItemMeta();
                boolean customName = item.customName() != null || meta.hasCustomName();
                boolean persistentData = !item.getPersistentDataContainer().isEmpty()
                    || !meta.getPersistentDataContainer().isEmpty();
                if (protectedItem(ignoreCustomNames, ignorePersistentData, ignoreCrops,
                    customName, persistentData, stack.getType(), crops)) {
                    continue;
                }
                int amount = Math.max(0, stack.getAmount());
                recordRemoval(removed, stack.getType(), amount);
                entityCount++;
                itemCount += amount;
                item.remove();
            }
        }
        Map<Material, RemovedItem> sorted = new LinkedHashMap<>();
        removed.entrySet().stream().sorted(Map.Entry.comparingByKey(
            Comparator.comparing(Material::name))).forEach(entry ->
                sorted.put(entry.getKey(), entry.getValue()));
        return new CleanupResult(entityCount, itemCount,
            Collections.unmodifiableMap(new LinkedHashMap<>(sorted)));
    }

    private void broadcastCleanup(CleanupResult result, boolean manual) {
        if (!manual && result.entityCount() == 0
            && !settings.getBoolean("broadcast-empty-cleanups", false)) {
            return;
        }
        Component hover = cleanupHover(result);
        Component details = MM.deserialize(settings.getString("messages.hover-label",
            "<#f72a4c>[Hover for more info]</#f72a4c>"))
            .hoverEvent(HoverEvent.showText(hover));
        plugin.messageActions().run(plugin.getServer().getOnlinePlayers(),
            plugin.getServer().getOnlinePlayers(), settings, "messages.cleanup", "broadcast",
            "<white>Cleared <#f72a4c>%count%</#f72a4c> ground item entities "
                + "(<#f72a4c>%amount%</#f72a4c> items). %details%</white>",
            Placeholder.unparsed("count", Integer.toString(result.entityCount())),
            Placeholder.unparsed("amount", Long.toString(result.itemCount())),
            Placeholder.component("details", details));
    }

    private Component cleanupHover(CleanupResult result) {
        Component hover = MM.deserialize(settings.getString("messages.hover-header",
            "<#f72a4c><bold>Removed ground items</bold></#f72a4c>"));
        if (result.removed().isEmpty()) {
            return hover.append(Component.newline()).append(MM.deserialize(settings.getString(
                "messages.hover-empty", "<white>No eligible item entities were found.</white>")));
        }
        String entryTemplate = settings.getString("messages.hover-entry",
            "<white>%item%:</white> <#f72a4c>%amount%</#f72a4c> items in "
                + "<#f72a4c>%count%</#f72a4c> entities");
        for (Map.Entry<Material, RemovedItem> entry : result.removed().entrySet()) {
            hover = hover.append(Component.newline()).append(MM.deserialize(entryTemplate,
                Placeholder.unparsed("item", display(entry.getKey())),
                Placeholder.unparsed("amount", Long.toString(entry.getValue().items())),
                Placeholder.unparsed("count", Integer.toString(entry.getValue().entities()))));
        }
        return hover;
    }

    private void message(CommandSender sender, String path, String fallback) {
        plugin.messageActions().run(sender, settings, path, fallback);
    }

    static long cleanupIntervalSeconds(long configured) {
        return configured <= 0 ? DEFAULT_INTERVAL_SECONDS
            : Math.min(configured, MAX_INTERVAL_SECONDS);
    }

    static List<Integer> warningSeconds(List<Integer> configured, long interval) {
        return configured.stream().filter(seconds -> seconds != null && seconds > 0
                && seconds < interval).distinct().sorted(Comparator.reverseOrder()).toList();
    }

    static boolean protectedItem(boolean ignoreCustomNames, boolean ignorePersistentData,
                                 boolean ignoreCrops, boolean customName,
                                 boolean persistentData, Material material,
                                 Set<Material> crops) {
        return (ignoreCustomNames && customName) || (ignorePersistentData && persistentData)
            || (ignoreCrops && crops.contains(material));
    }

    static Set<Material> cropMaterials(List<String> configured) {
        if (configured.isEmpty()) {
            return DEFAULT_CROP_DROPS;
        }
        java.util.EnumSet<Material> materials = java.util.EnumSet.noneOf(Material.class);
        configured.stream().map(Material::matchMaterial).filter(java.util.Objects::nonNull)
            .forEach(materials::add);
        return Set.copyOf(materials);
    }

    static void recordRemoval(Map<Material, RemovedItem> removed, Material material, int amount) {
        removed.merge(material, new RemovedItem(1, amount),
            (current, added) -> new RemovedItem(current.entities() + added.entities(),
                current.items() + added.items()));
    }

    private static String display(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        for (int index = 0; index < words.length; index++) {
            words[index] = Character.toUpperCase(words[index].charAt(0)) + words[index].substring(1);
        }
        return String.join(" ", words);
    }

    record RemovedItem(int entities, long items) {
    }

    record CleanupResult(int entityCount, long itemCount, Map<Material, RemovedItem> removed) {
    }
}
