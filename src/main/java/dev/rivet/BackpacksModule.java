package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

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
        plugin.messageActions().run(player, settings, "gui.open_commands", List.of());
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
        saveNextTick(event.getWhoClicked().getUniqueId(), event.getView().getTopInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
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

    private Inventory load(Player player) {
        int rows = resolvedRows(settings.getInt("default-rows", 3),
            row -> player.hasPermission("rivet.backpack.rows." + row));
        Inventory inventory = plugin.getServer().createInventory(null, rows * 9,
            MM.deserialize(settings.getString("title", "<#f72a4c>Backpack")));
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
}
