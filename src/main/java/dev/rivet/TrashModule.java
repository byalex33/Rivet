package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TrashModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final GuiActions guiActions;

    TrashModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("trash");
        guiActions = plugin.guiActions();
    }

    boolean command(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /trash"));
            return true;
        }
        int rows = Math.max(1, Math.min(6, settings.getInt("gui.rows", 6)));
        TrashHolder holder = new TrashHolder();
        String configuredTitle = settings.getString("gui.title",
            "<#f72a4c>Trash — items are destroyed</#f72a4c>");
        Component title = configuredTitle.equals("<#f72a4c>Trash — items are destroyed</#f72a4c>")
            ? RivetGui.title("Trash Bin") : MM.deserialize(configuredTitle);
        holder.inventory = plugin.getServer().createInventory(holder, rows * 9, title);
        addConfiguredItems(holder);
        player.openInventory(holder.inventory);
        plugin.messageActions().run(player, settings, "gui.open_commands", List.of());
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof TrashHolder holder)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < holder.inventory.getSize() && holder.actions.containsKey(slot)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                guiActions.run(player, holder.actions.get(slot));
            }
        } else if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
            && holder.actions.keySet().stream().map(holder.inventory::getItem)
                .anyMatch(item -> item != null && item.isSimilar(event.getCursor()))) {
            // A double-click may collect trash, but must not collect decorative GUI items.
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof TrashHolder holder
            && event.getRawSlots().stream().anyMatch(holder.actions::containsKey)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof TrashHolder) {
            event.getInventory().clear();
        }
    }

    private void addConfiguredItems(TrashHolder holder) {
        ConfigurationSection items = settings.getConfigurationSection("gui.items");
        if (items == null) {
            return;
        }
        for (String key : items.getKeys(false)) {
            ConfigurationSection configured = items.getConfigurationSection(key);
            if (configured == null) {
                continue;
            }
            Material material = Material.matchMaterial(configured.getString("material", ""));
            if (material == null || material.isAir() || !material.isItem()) {
                plugin.getLogger().warning("Invalid trash GUI material at gui.items." + key
                    + ".material; skipping the item.");
                continue;
            }
            ItemStack item = new ItemStack(material);
            item.editMeta(meta -> {
                if (configured.contains("name")) {
                    meta.displayName(MM.deserialize(configured.getString("name", "")));
                }
                List<String> lore = configuredLore(configured.get("lore"));
                if (!lore.isEmpty()) {
                    meta.lore(lore.stream().map(MM::deserialize).toList());
                }
            });
            List<String> actions = new ArrayList<>(configured.getStringList("click_commands"));
            if (key.equalsIgnoreCase("closeMenu")
                && actions.stream().noneMatch(action -> action.trim().equalsIgnoreCase("[close]"))) {
                actions.add("[close]");
            }
            for (int slot : configuredSlots(configured, holder.inventory.getSize())) {
                holder.inventory.setItem(slot, item.clone());
                holder.actions.put(slot, List.copyOf(actions));
            }
        }
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

    static List<Integer> configuredSlots(ConfigurationSection item, int inventorySize) {
        List<Integer> slots = new ArrayList<>();
        if (item.contains("slot")) {
            addSlots(slots, item.get("slot"), inventorySize);
        }
        if (item.contains("slots")) {
            addSlots(slots, item.get("slots"), inventorySize);
        }
        return slots.stream().distinct().toList();
    }

    private static void addSlots(List<Integer> slots, Object configured, int inventorySize) {
        if (configured instanceof List<?> values) {
            values.forEach(value -> addSlots(slots, value, inventorySize));
            return;
        }
        String value = String.valueOf(configured).trim();
        for (String part : value.split(",")) {
            String[] range = part.trim().split("-", 2);
            try {
                int start = Integer.parseInt(range[0].trim());
                int end = range.length == 1 ? start : Integer.parseInt(range[1].trim());
                for (int slot = Math.min(start, end); slot <= Math.max(start, end); slot++) {
                    if (slot >= 1 && slot <= inventorySize) {
                        slots.add(slot - 1);
                    }
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries while keeping the rest of the GUI usable.
            }
        }
    }

    private static final class TrashHolder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, List<String>> actions = new HashMap<>();

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
