package dev.rivet;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

record SnapshotState(ItemStack[] inventory, ItemStack[] armour, ItemStack offhand,
                     int xpLevel, float xpProgress, double health, int hunger,
                     float saturation) {
    SnapshotState {
        inventory = cloneItems(inventory);
        armour = cloneItems(armour);
        offhand = cloneItem(offhand);
    }

    @Override
    public ItemStack[] inventory() {
        return cloneItems(inventory);
    }

    @Override
    public ItemStack[] armour() {
        return cloneItems(armour);
    }

    @Override
    public ItemStack offhand() {
        return cloneItem(offhand);
    }

    static ItemStack[] cloneItems(ItemStack[] items) {
        if (items == null) {
            return new ItemStack[0];
        }
        return Arrays.stream(items).map(SnapshotState::cloneItem).toArray(ItemStack[]::new);
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
