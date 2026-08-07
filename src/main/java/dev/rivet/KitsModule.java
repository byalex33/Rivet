package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class KitsModule {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;

    KitsModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("kits");
        data = plugin.data("kits");
    }

    boolean command(Player player, String[] args) {
        if (args.length == 0) {
            List<String> available = kitNames(player);
            player.sendMessage(MM.deserialize(settings.getString("messages.list",
                    "<gold>Available kits:</gold> <white><kits></white>"),
                Placeholder.unparsed("kits", available.isEmpty() ? "none" : String.join(", ", available))));
            return true;
        }
        if (args.length != 1 || !args[0].matches("[A-Za-z0-9_-]{1,32}")) {
            player.sendMessage(MM.deserialize("<red>Usage: /kit [name]"));
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        ConfigurationSection kit = settings.getConfigurationSection("kits." + name);
        if (kit == null) {
            message(player, "unknown", "<red>That kit does not exist.", name);
            return true;
        }
        if (!hasPermission(player, name)) {
            message(player, "no-permission", "<red>You cannot use the <white><kit></white> kit.", name);
            return true;
        }
        long now = System.currentTimeMillis();
        long remaining = cooldownRemaining(data.getLong(path(player, name)),
            Math.max(0, kit.getLong("cooldown-seconds")), now);
        if (remaining > 0) {
            player.sendMessage(MM.deserialize(settings.getString("messages.cooldown",
                    "<red>Kit <white><kit></white> is available in <seconds> seconds."),
                Placeholder.unparsed("kit", name),
                Placeholder.unparsed("seconds", Long.toString(remaining))));
            return true;
        }

        List<ItemStack> items = new ArrayList<>();
        kit.getMapList("items").stream().map(values -> item(plugin, values))
            .filter(java.util.Objects::nonNull)
            .forEach(items::add);
        ItemStack helmet = item(plugin, kit.getConfigurationSection("armor.helmet"));
        ItemStack chestplate = item(plugin, kit.getConfigurationSection("armor.chestplate"));
        ItemStack leggings = item(plugin, kit.getConfigurationSection("armor.leggings"));
        ItemStack boots = item(plugin, kit.getConfigurationSection("armor.boots"));
        ItemStack offhand = item(plugin, kit.getConfigurationSection("offhand"));

        if (kit.getLong("cooldown-seconds") > 0) {
            data.set(path(player, name), now);
            try {
                plugin.saveData("kits");
            } catch (IOException exception) {
                data.set(path(player, name), null);
                plugin.getLogger().severe("Could not save data/kits.yml: " + exception.getMessage());
                player.sendMessage(MM.deserialize("<red>The kit could not be saved safely. Try again."));
                return true;
            }
        }

        items.forEach(item -> give(player, item));
        equip(player, player.getInventory().getHelmet(), helmet, player.getInventory()::setHelmet);
        equip(player, player.getInventory().getChestplate(), chestplate, player.getInventory()::setChestplate);
        equip(player, player.getInventory().getLeggings(), leggings, player.getInventory()::setLeggings);
        equip(player, player.getInventory().getBoots(), boots, player.getInventory()::setBoots);
        equip(player, player.getInventory().getItemInOffHand(), offhand,
            player.getInventory()::setItemInOffHand);
        message(player, "received", "<green>Received kit <white><kit></white>.", name);
        if (plugin.getConfig().getBoolean("effects.sounds")) {
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, .8f, 1.2f);
        }
        return true;
    }

    List<String> completions(Player player, String[] args) {
        return args.length == 1 ? kitNames(player) : List.of();
    }

    List<String> kitNames(Player player) {
        ConfigurationSection kits = settings.getConfigurationSection("kits");
        return kits == null ? List.of() : kits.getKeys(false).stream()
            .filter(name -> hasPermission(player, name)).sorted().toList();
    }

    static ItemStack item(RivetPlugin plugin, Map<?, ?> values) {
        YamlConfiguration yaml = new YamlConfiguration();
        values.forEach((key, value) -> yaml.set(key.toString(), value));
        return item(plugin, yaml);
    }

    static ItemStack item(RivetPlugin plugin, ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        Material material = Material.matchMaterial(section.getString("material", ""));
        if (material == null || !material.isItem() || material.isAir()) {
            return null;
        }
        ItemStack item = new ItemStack(material,
            Math.max(1, Math.min(material.getMaxStackSize(), section.getInt("amount", 1))));
        item.editMeta(meta -> {
            if (section.isString("name")) {
                meta.displayName(MM.deserialize(section.getString("name", "")));
            }
            if (section.isList("lore")) {
                meta.lore(section.getStringList("lore").stream().map(MM::deserialize).toList());
            }
        });
        ConfigurationSection enchantments = section.getConfigurationSection("enchantments");
        if (enchantments != null) {
            enchantments.getKeys(false).forEach(key -> {
                try {
                    Enchantment enchantment = Registry.ENCHANTMENT.get(
                        NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
                    if (enchantment != null) {
                        item.addUnsafeEnchantment(enchantment, Math.max(1, enchantments.getInt(key)));
                    }
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Skipped invalid kit enchantment " + key + ".");
                }
            });
        }
        return item;
    }

    private void equip(Player player, ItemStack current, ItemStack replacement,
                       java.util.function.Consumer<ItemStack> setter) {
        if (replacement == null) {
            return;
        }
        if (current != null && !current.getType().isAir()) {
            give(player, current);
        }
        setter.accept(replacement);
    }

    private void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
            .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void message(Player player, String key, String fallback, String kit) {
        player.sendMessage(MM.deserialize(settings.getString("messages." + key, fallback),
            Placeholder.unparsed("kit", kit)));
    }

    private static String path(Player player, String kit) {
        return "cooldowns." + player.getUniqueId() + "." + kit;
    }

    private static boolean hasPermission(Player player, String kit) {
        return player.hasPermission("rivet.kit." + kit) || player.hasPermission("rivet.kit.*");
    }

    static long cooldownRemaining(long lastUse, long cooldownSeconds, long now) {
        return Math.max(0, (lastUse + cooldownSeconds * 1000L - now + 999) / 1000);
    }
}
