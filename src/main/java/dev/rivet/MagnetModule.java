package dev.rivet;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;

final class MagnetModule {
    private static final double DEFAULT_RADIUS = 8;
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;
    private final BukkitTask task;

    MagnetModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("magnet");
        data = plugin.data("magnet");
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    boolean command(Player player, String[] args) {
        if (args.length != 0) {
            send(player, "messages.usage", "<white>Usage: /magnet</white>");
            return true;
        }
        String path = "enabled." + player.getUniqueId();
        boolean enabled = !data.getBoolean(path);
        data.set(path, enabled);
        try {
            plugin.saveData("magnet");
        } catch (IOException exception) {
            data.set(path, !enabled);
            plugin.getLogger().severe("Could not save data/magnet.yml: " + exception.getMessage());
            send(player, "messages.save-failed",
                "<white>Your magnet setting could not be saved safely.</white>");
            return true;
        }
        send(player, "messages." + (enabled ? "enabled" : "disabled"), enabled
            ? "<white>Item magnet enabled within <#f72a4c>%radius%</#f72a4c> blocks.</white>"
            : "<white>Item magnet disabled.</white>");
        return true;
    }

    void shutdown() {
        task.cancel();
    }

    private void tick() {
        double radius = radius(settings.getDouble("radius", DEFAULT_RADIUS));
        plugin.getServer().getOnlinePlayers().stream()
            .filter(player -> data.getBoolean("enabled." + player.getUniqueId()))
            .filter(player -> player.hasPermission("rivet.magnet"))
            .forEach(player -> collect(player, radius));
    }

    private void collect(Player player, double radius) {
        player.getNearbyEntities(radius, radius, radius).stream()
            .filter(Item.class::isInstance)
            .map(Item.class::cast)
            .filter(item -> withinRadius(player, item, radius))
            .filter(item -> availableTo(player, item))
            .forEach(item -> collect(player, item));
    }

    private void collect(Player player, Item item) {
        ItemStack stack = item.getItemStack();
        PlayerInventory inventory = player.getInventory();
        if (!canFitFully(inventory.getStorageContents(), inventory.getMaxStackSize(), stack)) {
            return;
        }

        PlayerAttemptPickupItemEvent attempt = new PlayerAttemptPickupItemEvent(player, item, 0);
        plugin.getServer().getPluginManager().callEvent(attempt);
        if (attempt.isCancelled() || !item.isValid()) {
            return;
        }
        EntityPickupItemEvent pickup = new EntityPickupItemEvent(player, item, 0);
        plugin.getServer().getPluginManager().callEvent(pickup);
        if (pickup.isCancelled() || !item.isValid()) {
            return;
        }

        stack = item.getItemStack();
        if (!canFitFully(inventory.getStorageContents(), inventory.getMaxStackSize(), stack)) {
            return;
        }
        ItemStack[] before = cloneContents(inventory.getStorageContents());
        HashMap<Integer, ItemStack> leftovers = inventory.addItem(stack.clone());
        if (!leftovers.isEmpty()) {
            inventory.setStorageContents(before);
            return;
        }
        player.playPickupItemAnimation(item, stack.getAmount());
        item.remove();
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP,
            0.2f, 1.6f);
    }

    private void send(Player player, String path, String fallback) {
        plugin.messageActions().run(player, settings, path, fallback,
            Placeholder.unparsed("radius", number(radius(settings.getDouble(
                "radius", DEFAULT_RADIUS)))));
    }

    static boolean canFitFully(ItemStack[] storage, int inventoryMaxStackSize,
                               ItemStack incoming) {
        if (incoming == null || incoming.getType().isAir() || incoming.getAmount() <= 0) {
            return false;
        }
        int availableCapacity = 0;
        int emptyCapacity = Math.min(inventoryMaxStackSize, incoming.getMaxStackSize());
        for (ItemStack stored : storage) {
            if (stored == null || stored.getType().isAir()) {
                availableCapacity += emptyCapacity;
            } else if (stored.isSimilar(incoming)) {
                int stackLimit = Math.min(inventoryMaxStackSize, stored.getMaxStackSize());
                availableCapacity += Math.max(0, stackLimit - stored.getAmount());
            }
            if (fitsWithin(incoming.getAmount(), availableCapacity)) {
                return true;
            }
        }
        return false;
    }

    static boolean fitsWithin(int incomingAmount, int availableCapacity) {
        return incomingAmount > 0 && incomingAmount <= availableCapacity;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            copy[slot] = contents[slot] == null ? null : contents[slot].clone();
        }
        return copy;
    }

    private static boolean availableTo(Player player, Item item) {
        UUID owner = item.getOwner();
        return item.isValid() && !item.isDead() && item.canPlayerPickup()
            && (owner == null || owner.equals(player.getUniqueId()));
    }

    private static boolean withinRadius(Player player, Item item, double radius) {
        return player.getLocation().distanceSquared(item.getLocation()) <= radius * radius;
    }

    private static double radius(double configured) {
        return Double.isFinite(configured) ? Math.max(1, configured) : DEFAULT_RADIUS;
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value))
            : Double.toString(value);
    }
}
