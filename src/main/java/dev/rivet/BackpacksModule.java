package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;

final class BackpacksModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;
    private final Map<UUID, Inventory> open = new HashMap<>();

    BackpacksModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("backpacks");
        data = plugin.data("backpacks");
    }

    boolean command(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /backpack"));
            return true;
        }
        if (settings.getStringList("blocked-worlds").stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .anyMatch(player.getWorld().getName().toLowerCase(Locale.ROOT)::equals)) {
            message(player, "blocked-world", "<white>Backpacks are disabled in this world.");
            return true;
        }
        Inventory inventory = open.computeIfAbsent(player.getUniqueId(), ignored -> load(player));
        player.openInventory(inventory);
        menu().open(player);
        return true;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        Inventory inventory = open.get(player);
        if (inventory == event.getInventory()) {
            save(player, inventory);
            open.remove(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Inventory inventory = open.remove(event.getPlayer().getUniqueId());
        if (inventory != null) {
            save(event.getPlayer().getUniqueId(), inventory);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof BackpackHolder holder
            && event.getRawSlot() >= 0 && holder.controls.containsKey(event.getRawSlot())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                menu().click(player, holder.controls.get(event.getRawSlot()), event.getClick());
            }
            return;
        }
        if (event.getView().getTopInventory().getHolder(false) instanceof BackpackHolder holder
            && event.getAction() == InventoryAction.COLLECT_TO_CURSOR
            && holder.controls.keySet().stream().map(holder.inventory::getItem)
                .anyMatch(item -> item != null && item.isSimilar(event.getCursor()))) {
            event.setCancelled(true);
            return;
        }
        saveNextTick(event.getWhoClicked().getUniqueId(), event.getView().getTopInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof BackpackHolder holder
            && event.getRawSlots().stream().anyMatch(holder.controls::containsKey)) {
            event.setCancelled(true);
            return;
        }
        saveNextTick(event.getWhoClicked().getUniqueId(), event.getView().getTopInventory());
    }

    void shutdown() {
        new HashMap<>(open).forEach((uuid, inventory) -> {
            save(uuid, inventory);
            open.remove(uuid);
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.getOpenInventory().getTopInventory() == inventory) {
                player.closeInventory();
            }
        });
    }

    void reloadGui() {
        new HashMap<>(open).forEach((uuid, inventory) -> {
            save(uuid, inventory);
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.getOpenInventory().getTopInventory() == inventory) {
                player.closeInventory();
            }
            open.remove(uuid);
        });
    }

    private Inventory load(Player player) {
        RivetMenu menu = menu();
        int configuredRows = settings.contains("default-rows")
            ? Math.clamp(settings.getInt("default-rows", 3), 1, 6) : menu.size(27) / 9;
        int rows = resolvedRows(configuredRows,
            row -> player.hasPermission("rivet.backpack.rows." + row));
        String legacyTitle = settings.getString("title", "");
        Component title = !legacyTitle.isBlank()
            && !legacyTitle.equals("<#f72a4c><bold>RIVET</bold></#f72a4c> <dark_gray>•</dark_gray> <white>Backpack</white>")
                ? MM.deserialize(legacyTitle) : menu.title("<white>Backpack</white>");
        BackpackHolder holder = new BackpackHolder();
        Inventory inventory = plugin.getServer().createInventory(holder, rows * 9, title);
        holder.inventory = inventory;
        List<?> stored = data.getList(path(player.getUniqueId()), List.of());
        for (int slot = 0; slot < Math.min(inventory.getSize(), stored.size()); slot++) {
            if (stored.get(slot) instanceof ItemStack item) {
                inventory.setItem(slot, item.clone());
            }
        }
        if (stored.subList(Math.min(inventory.getSize(), stored.size()), stored.size()).stream()
            .anyMatch(ItemStack.class::isInstance)) {
            message(player, "overflow", "<white>Items above your current backpack size are safely retained until you regain more rows.</white>");
        }
        menu.placeStaticItems(inventory, java.util.Set.of(), holder.controls);
        return inventory;
    }

    private void saveNextTick(UUID player, Inventory inventory) {
        if (open.get(player) != inventory) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (open.get(player) == inventory) {
                save(player, inventory);
            }
        });
    }

    private void save(UUID player, Inventory inventory) {
        List<?> stored = data.getList(path(player), List.of());
        List<ItemStack> previous = new ArrayList<>(java.util.Collections.nCopies(54, null));
        for (int slot = 0; slot < Math.min(54, stored.size()); slot++) {
            if (stored.get(slot) instanceof ItemStack item) {
                previous.set(slot, item.clone());
            }
        }
        List<ItemStack> visible = java.util.Arrays.stream(inventory.getContents())
            .map(item -> item == null ? null : item.clone()).toList();
        if (inventory.getHolder(false) instanceof BackpackHolder holder) {
            visible = new ArrayList<>(visible);
            for (int slot : holder.controls.keySet()) {
                if (slot < visible.size()) {
                    visible.set(slot, slot < previous.size() ? previous.get(slot) : null);
                }
            }
        }
        List<ItemStack> contents = mergeContents(previous, visible, 54);
        data.set(path(player), contents);
        try {
            plugin.saveData("backpacks");
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/backpacks.yml: " + exception.getMessage());
        }
    }

    private void message(Player player, String key, String fallback) {
        plugin.messageActions().run(player, settings, "messages." + key, fallback);
    }

    private RivetMenu menu() {
        return new RivetMenu(plugin, settings, "gui");
    }

    private static String path(UUID player) {
        return "players." + player + ".contents";
    }

    static int resolvedRows(int defaultRows, IntPredicate permission) {
        int rows = Math.max(1, Math.min(6, defaultRows));
        for (int candidate = 1; candidate <= 6; candidate++) {
            if (permission.test(candidate)) {
                rows = Math.max(rows, candidate);
            }
        }
        return rows;
    }

    static <T> List<T> mergeContents(List<? extends T> stored, List<? extends T> visible,
                                     int capacity) {
        List<T> merged = new ArrayList<>(java.util.Collections.nCopies(capacity, null));
        for (int slot = 0; slot < Math.min(capacity, stored.size()); slot++) {
            merged.set(slot, stored.get(slot));
        }
        for (int slot = 0; slot < Math.min(capacity, visible.size()); slot++) {
            merged.set(slot, visible.get(slot));
        }
        return merged;
    }

    private static final class BackpackHolder implements InventoryHolder {
        private final Map<Integer, String> controls = new HashMap<>();
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
