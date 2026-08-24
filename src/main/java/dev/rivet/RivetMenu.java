package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** DeluxeMenus-shaped configuration adapter shared by Rivet's inventory GUIs. */
final class RivetMenu {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final ConfigurationSection section;
    private final String path;
    private final boolean legacyOneBasedSlots;

    RivetMenu(RivetPlugin plugin, ConfigurationSection settings, String path) {
        this(plugin, settings, path, false);
    }

    RivetMenu(RivetPlugin plugin, ConfigurationSection settings, String path,
              boolean legacyOneBasedSlots) {
        this.plugin = plugin;
        this.section = settings == null ? null : settings.getConfigurationSection(path);
        this.path = path;
        this.legacyOneBasedSlots = legacyOneBasedSlots;
    }

    Component title(String fallback, TagResolver... placeholders) {
        return MM.deserialize(string("menu_title", fallback), placeholders);
    }

    Component title(String fallback, String legacyPath, TagResolver... placeholders) {
        String configured = section != null && section.contains("menu_title")
            ? section.getString("menu_title", fallback)
            : legacyPath == null ? fallback : section == null ? fallback
                : section.getRoot().getString(legacyPath, fallback);
        return MM.deserialize(configured, placeholders);
    }

    int size(int fallback) {
        int configured = section == null ? fallback : section.getInt("size",
            section.contains("rows") ? section.getInt("rows") * 9 : fallback);
        if (configured < 9 || configured > 54 || configured % 9 != 0) {
            plugin.getLogger().warning("Invalid " + path + ".size '" + configured
                + "'; using " + fallback + ". Size must be a multiple of 9 from 9 to 54.");
            return fallback;
        }
        return configured;
    }

    List<String> openCommands() {
        return actions(section, "open_commands");
    }

    void open(Player player, TagResolver... placeholders) {
        plugin.guiActions().run(player, openCommands(), TagResolver.resolver(placeholders));
    }

    List<Integer> slots(String key, int inventorySize, List<Integer> fallback) {
        ConfigurationSection item = itemSection(key);
        if (item == null || (!item.contains("slot") && !item.contains("slots"))) {
            return fallback.stream().filter(slot -> slot >= 0 && slot < inventorySize).distinct().toList();
        }
        return configuredSlots(item, inventorySize, legacyOneBasedSlots);
    }

    ItemStack item(String key, Material fallbackMaterial, Component fallbackName,
                   List<Component> fallbackLore, boolean fallbackGlow,
                   TagResolver... placeholders) {
        ConfigurationSection item = itemSection(key);
        Material material = configuredMaterial(item, fallbackMaterial, key);
        if (material == null) {
            return null;
        }
        ItemStack rendered = new ItemStack(material);
        rendered.editMeta(meta -> {
            String name = item == null ? null : firstString(item, "display_name", "name");
            meta.displayName((name == null ? fallbackName : MM.deserialize(name, placeholders))
                .decoration(TextDecoration.ITALIC, false));
            List<String> lore = item == null ? List.of() : configuredLore(item.get("lore"));
            List<Component> renderedLore = lore.isEmpty() ? fallbackLore
                : lore.stream().map(line -> MM.deserialize(line, placeholders)).toList();
            meta.lore(renderedLore.stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
            boolean glow = item != null && item.isBoolean("glow")
                ? item.getBoolean("glow") : fallbackGlow;
            meta.setEnchantmentGlintOverride(glow);
            if (item != null && item.isInt("amount")) {
                rendered.setAmount(Math.clamp(item.getInt("amount"), 1, material.getMaxStackSize()));
            }
        });
        return rendered;
    }

    ItemStack item(String key, Material fallbackMaterial, String fallbackName,
                   List<String> fallbackLore, TagResolver... placeholders) {
        return item(key, fallbackMaterial, MM.deserialize(fallbackName, placeholders),
            fallbackLore.stream().map(line -> MM.deserialize(line, placeholders)).toList(),
            false, placeholders);
    }

    ItemStack item(String key, ItemStack fallback, TagResolver... placeholders) {
        Component name = fallback.getItemMeta().displayName();
        List<Component> lore = fallback.getItemMeta().lore();
        Boolean glow = fallback.getItemMeta().getEnchantmentGlintOverride();
        return item(key, fallback.getType(), name == null ? Component.empty() : name,
            lore == null ? List.of() : lore, glow != null && glow, placeholders);
    }

    List<Integer> place(Inventory inventory, String key, ItemStack item,
                        List<Integer> fallbackSlots, Map<Integer, String> controls) {
        if (item == null) {
            return List.of();
        }
        List<Integer> slots = slots(key, inventory.getSize(), fallbackSlots);
        for (int slot : slots) {
            inventory.setItem(slot, item.clone());
            if (controls != null) {
                controls.put(slot, key);
            }
        }
        return slots;
    }

    void placeStaticItems(Inventory inventory, Set<String> handled,
                          Map<Integer, String> controls, TagResolver... placeholders) {
        ConfigurationSection items = section == null ? null : section.getConfigurationSection("items");
        if (items == null) {
            return;
        }
        for (String key : items.getKeys(false)) {
            if (handled.contains(key)) {
                continue;
            }
            ItemStack item = item(key, null, Component.empty(), List.of(), false, placeholders);
            place(inventory, key, item, List.of(), controls);
        }
    }

    void click(Player player, String key, ClickType click, TagResolver... placeholders) {
        if (key == null) {
            return;
        }
        List<String> configured = new ArrayList<>(actions(itemSection(key), "click_commands"));
        String specific = switch (click) {
            case LEFT -> "left_click_commands";
            case RIGHT -> "right_click_commands";
            case SHIFT_LEFT -> "shift_left_click_commands";
            case SHIFT_RIGHT -> "shift_right_click_commands";
            case MIDDLE -> "middle_click_commands";
            default -> null;
        };
        if (specific != null) {
            configured.addAll(actions(itemSection(key), specific));
        }
        plugin.guiActions().run(player, configured, TagResolver.resolver(placeholders));
    }

    private ConfigurationSection itemSection(String key) {
        return section == null ? null : section.getConfigurationSection("items." + key);
    }

    private Material configuredMaterial(ConfigurationSection item, Material fallback, String key) {
        if (item == null || !item.isString("material")) {
            return fallback;
        }
        String configured = item.getString("material", "").trim();
        if (configured.contains("%") || configured.contains("<")) {
            return fallback;
        }
        Material material = Material.matchMaterial(configured);
        if (material == null || material.isAir() || !material.isItem()) {
            plugin.getLogger().warning("Invalid material at " + path + ".items." + key
                + ".material; " + (fallback == null ? "skipping the item." : "using the default."));
            return fallback;
        }
        return material;
    }

    private String string(String key, String fallback) {
        return section == null ? fallback : section.getString(key, fallback);
    }

    static List<String> actions(ConfigurationSection section, String key) {
        if (section == null) {
            return List.of();
        }
        if (section.isList(key)) {
            return List.copyOf(section.getStringList(key));
        }
        ConfigurationSection configured = section.getConfigurationSection(key);
        if (configured != null && configured.getBoolean("enabled", true)) {
            return List.copyOf(configured.getStringList("actions"));
        }
        return List.of();
    }

    static List<Integer> configuredSlots(ConfigurationSection item, int inventorySize,
                                         boolean oneBased) {
        LinkedHashSet<Integer> slots = new LinkedHashSet<>();
        addSlots(slots, item.get("slot"), inventorySize, oneBased);
        addSlots(slots, item.get("slots"), inventorySize, oneBased);
        return List.copyOf(slots);
    }

    private static void addSlots(Set<Integer> slots, Object configured, int inventorySize,
                                 boolean oneBased) {
        if (configured == null) {
            return;
        }
        if (configured instanceof List<?> values) {
            values.forEach(value -> addSlots(slots, value, inventorySize, oneBased));
            return;
        }
        for (String part : String.valueOf(configured).split(",")) {
            String[] range = part.trim().split("-", 2);
            try {
                int start = Integer.parseInt(range[0].trim());
                int end = range.length == 1 ? start : Integer.parseInt(range[1].trim());
                for (int configuredSlot = Math.min(start, end);
                     configuredSlot <= Math.max(start, end); configuredSlot++) {
                    int slot = oneBased ? configuredSlot - 1 : configuredSlot;
                    if (slot >= 0 && slot < inventorySize) {
                        slots.add(slot);
                    }
                }
            } catch (NumberFormatException ignored) {
                // A malformed entry must not prevent the rest of the menu from opening.
            }
        }
    }

    private static String firstString(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (section.isString(key)) {
                return section.getString(key);
            }
        }
        return null;
    }

    private static List<String> configuredLore(Object configured) {
        if (configured instanceof String line) {
            return line.isEmpty() ? List.of() : List.of(line);
        }
        if (configured instanceof List<?> lines) {
            return lines.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }
}
