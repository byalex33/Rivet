package dev.rivet;

import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class HopperModule implements Listener {
    private static final int DEFAULT_TRANSFER_COOLDOWN = 2;

    private final RivetPlugin plugin;
    private final YamlConfiguration settings;

    HopperModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("gameplay");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        accelerate(event.getInitiator());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(InventoryPickupItemEvent event) {
        accelerate(event.getInventory());
    }

    private void accelerate(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (!(holder instanceof Hopper hopper)) {
            return;
        }
        Block block = hopper.getBlock();
        plugin.getServer().getScheduler().runTask(plugin, () -> shortenCooldown(block));
    }

    private void shortenCooldown(Block block) {
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
            || !(block.getState(false) instanceof Hopper hopper)) {
            return;
        }
        int configured = transferCooldown(settings.getInt(
            "hoppers.transfer-cooldown-ticks", DEFAULT_TRANSFER_COOLDOWN));
        hopper.setTransferCooldown(Math.min(hopper.getTransferCooldown(), configured));
    }

    static int transferCooldown(int configured) {
        return Math.max(1, configured);
    }
}
