package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        boolean legacy = settings.contains("gui.title");
        RivetMenu menu = new RivetMenu(plugin, settings, "gui", legacy);
        int size = menu.size(54);
        TrashHolder holder = new TrashHolder();
        String legacyTitle = settings.getString("gui.title", "");
        Component title = legacy && !legacyTitle.equals(
            "<#f72a4c><bold>RIVET</bold></#f72a4c> <dark_gray>•</dark_gray> <white>Trash Bin</white>")
                ? MM.deserialize(legacyTitle) : menu.title("<white>Trash Bin</white>");
        holder.inventory = plugin.getServer().createInventory(holder, size, title);
        menu.placeStaticItems(holder.inventory, Set.of(), holder.controls);
        player.openInventory(holder.inventory);
        menu.open(player);
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof TrashHolder holder)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < holder.inventory.getSize() && holder.controls.containsKey(slot)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                String key = holder.controls.get(slot);
                new RivetMenu(plugin, settings, "gui", settings.contains("gui.title"))
                    .click(player, key, event.getClick());
                if (key.equalsIgnoreCase("closeMenu")
                    && RivetMenu.actions(settings.getConfigurationSection("gui.items." + key),
                        "click_commands").isEmpty()) {
                    player.closeInventory();
                }
            }
        } else if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
            && holder.controls.keySet().stream().map(holder.inventory::getItem)
                .anyMatch(item -> item != null && item.isSimilar(event.getCursor()))) {
            // A double-click may collect trash, but must not collect decorative GUI items.
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof TrashHolder holder
            && event.getRawSlots().stream().anyMatch(holder.controls::containsKey)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof TrashHolder) {
            event.getInventory().clear();
        }
    }

    static List<Integer> configuredSlots(org.bukkit.configuration.ConfigurationSection item,
                                         int inventorySize) {
        return RivetMenu.configuredSlots(item, inventorySize, true);
    }

    private static final class TrashHolder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, String> controls = new HashMap<>();

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
