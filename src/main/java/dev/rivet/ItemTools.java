package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class ItemTools {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final MiniMessage FORMATTED = MiniMessage.builder().tags(TagResolver.resolver(
        StandardTags.color(), StandardTags.decorations())).build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final YamlConfiguration settings;

    ItemTools(RivetPlugin plugin) {
        settings = plugin.settings("inventory");
    }

    boolean repair(Player player, String[] args) {
        if (args.length == 0) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (!repair(item)) {
                message(player, "repair-invalid", "<red>Hold a damaged repairable item.");
                return true;
            }
            message(player, "repair-success", "<green>Repaired the item in your hand.");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("all")
            || !player.hasPermission("rivet.repair.all")) {
            player.sendMessage(MM.deserialize("<red>Usage: /repair [all]"));
            return true;
        }
        int repaired = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (repair(item)) {
                repaired++;
            }
        }
        player.sendMessage(MM.deserialize(settings.getString("messages.repair-all",
                "<green>Repaired <white><count></white> items."),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                "count", Integer.toString(repaired))));
        return true;
    }

    boolean rename(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(MM.deserialize("<red>Usage: /rename <name|clear>"));
            return true;
        }
        ItemStack item = held(player);
        if (item == null) {
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            item.editMeta(meta -> meta.displayName(null));
            message(player, "rename-cleared", "<yellow>Item name cleared.");
            return true;
        }
        String input = String.join(" ", args);
        Component name = render(player, input, "rivet.rename.format");
        int maximum = Math.max(1, settings.getInt("item-editing.maximum-name-length", 50));
        if (name == null || !validText(PLAIN.serialize(name), maximum)) {
            invalid(player, maximum);
            return true;
        }
        item.editMeta(meta -> meta.displayName(name));
        message(player, "rename-success", "<green>Item renamed.");
        return true;
    }

    boolean lore(Player player, String[] args) {
        if (args.length == 0) {
            usage(player);
            return true;
        }
        ItemStack item = held(player);
        if (item == null) {
            return true;
        }
        List<Component> lore = item.lore() == null ? new ArrayList<>()
            : new ArrayList<>(item.lore());
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("clear") && args.length == 1) {
            item.lore(null);
            message(player, "lore-cleared", "<yellow>Item lore cleared.");
            return true;
        }
        if (action.equals("remove") && args.length == 2) {
            int line = positiveInt(args[1]);
            if (line < 1 || line > lore.size()) {
                message(player, "lore-line-invalid", "<red>That lore line does not exist.");
                return true;
            }
            lore.remove(line - 1);
        } else if ((action.equals("add") && args.length >= 2)
            || (action.equals("set") && args.length >= 3)) {
            int line = action.equals("set") ? positiveInt(args[1]) : lore.size() + 1;
            String input = String.join(" ", Arrays.copyOfRange(args, action.equals("set") ? 2 : 1,
                args.length));
            Component value = render(player, input, "rivet.lore.format");
            int maximumLength = Math.max(1,
                settings.getInt("item-editing.maximum-lore-line-length", 100));
            int maximumLines = Math.max(1, settings.getInt("item-editing.maximum-lore-lines", 10));
            if (value == null || !validText(PLAIN.serialize(value), maximumLength)
                || line < 1 || line > lore.size() + (action.equals("add") ? 1 : 0)
                || action.equals("add") && lore.size() >= maximumLines) {
                invalid(player, maximumLength);
                return true;
            }
            if (action.equals("add")) {
                lore.add(value);
            } else if (line <= lore.size()) {
                lore.set(line - 1, value);
            } else {
                message(player, "lore-line-invalid", "<red>That lore line does not exist.");
                return true;
            }
        } else {
            usage(player);
            return true;
        }
        item.lore(lore.isEmpty() ? null : lore);
        message(player, "lore-success", "<green>Item lore updated.");
        return true;
    }

    private ItemStack held(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            message(player, "empty-hand", "<red>Hold an item first.");
            return null;
        }
        if (settings.getStringList("item-editing.blocked-materials").stream()
            .anyMatch(material -> material.equalsIgnoreCase(item.getType().name()))) {
            message(player, "blocked-item", "<red>That material cannot be edited.");
            return null;
        }
        return item;
    }

    private Component render(Player player, String input, String permission) {
        try {
            return player.hasPermission(permission) ? FORMATTED.deserialize(input) : Component.text(input);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean repair(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof Damageable damageable)
            || !repairEligible(true, damageable.getDamage())) {
            return false;
        }
        damageable.setDamage(0);
        item.setItemMeta(damageable);
        return true;
    }

    private void invalid(Player player, int maximum) {
        player.sendMessage(MM.deserialize(settings.getString("messages.invalid-text",
                "<red>Text must be 1-<max> visible characters without control characters."),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                "max", Integer.toString(maximum))));
    }

    private static int positiveInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private void usage(Player player) {
        player.sendMessage(MM.deserialize("<red>Usage: /lore <add|set|remove|clear> ..."));
    }

    private void message(Player player, String key, String fallback) {
        player.sendMessage(MM.deserialize(settings.getString("messages." + key, fallback)));
    }

    static boolean repairEligible(boolean damageable, int damage) {
        return damageable && damage > 0;
    }

    static boolean validText(String text, int maximum) {
        return !text.isBlank() && text.length() <= maximum
            && text.chars().noneMatch(Character::isISOControl);
    }
}
