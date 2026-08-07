package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

final class TrashModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RivetPlugin plugin;

    TrashModule(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    boolean command(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<red>Usage: /trash"));
            return true;
        }
        int rows = Math.max(1, Math.min(6, plugin.settings("trash").getInt("rows", 6)));
        TrashHolder holder = new TrashHolder();
        holder.inventory = plugin.getServer().createInventory(holder, rows * 9,
            MM.deserialize(plugin.settings("trash").getString("title", "<dark_red>Trash — items are destroyed")));
        player.openInventory(holder.inventory);
        player.sendMessage(MM.deserialize(plugin.settings("trash").getString("warning",
            "<red>Items left in the trash when it closes cannot be recovered.")));
        return true;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof TrashHolder) {
            event.getInventory().clear();
        }
    }

    private static final class TrashHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
