package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Shared visual language for Rivet's read-only inventory menus. */
final class RivetGui {
    static final int[] CONTENT_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    private RivetGui() {
    }

    static Component title(String name) {
        return Component.text("RIVET", RivetPalette.SECONDARY)
            .decorate(TextDecoration.BOLD)
            .append(Component.text("  •  ", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.BOLD, false))
            .append(Component.text(name, NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, false));
    }

    static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    static ItemStack button(Material material, String name, String... lore) {
        return item(material, Component.text(name, NamedTextColor.WHITE),
            java.util.Arrays.stream(lore)
                .<Component>map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
    }

    static ItemStack pane(Material material) {
        return item(material, Component.empty(), List.of());
    }

    static void frame(Inventory inventory) {
        ItemStack border = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < 45; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || column == 0 || column == 8) {
                inventory.setItem(slot, border);
            }
        }
        ItemStack footer = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 45; slot < Math.min(54, inventory.getSize()); slot++) {
            inventory.setItem(slot, footer);
        }
    }

    static int contentIndex(int slot) {
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            if (CONTENT_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }
}
