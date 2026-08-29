package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class FishingModule implements Listener {
    private static final Set<Material> FISH = EnumSet.of(
        Material.COD, Material.SALMON, Material.PUFFERFISH, Material.TROPICAL_FISH);

    private final RivetPlugin plugin;

    FishingModule(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
            || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        ItemStack item = caught.getItemStack();
        YamlConfiguration settings = plugin.settings("fishing");
        TagResolver[] common = {
            Placeholder.unparsed("player", event.getPlayer().getName()),
            Placeholder.component("catch", catchName(item))
        };
        if (!isFish(item.getType())) {
            plugin.messageActions().run(plugin.getServer().getOnlinePlayers(),
                plugin.getServer().getOnlinePlayers(), settings, "messages.catch", List.of(
                    "[broadcast] <white>%player% caught <#f72a4c>%catch%</#f72a4c> while fishing!</white>"),
                common);
            return;
        }

        double length = randomLength(item.getType(), settings, ThreadLocalRandom.current().nextDouble());
        int decimalPlaces = Math.clamp(settings.getInt("length.decimal-places", 1), 0, 3);
        plugin.messageActions().run(plugin.getServer().getOnlinePlayers(),
            plugin.getServer().getOnlinePlayers(), settings, "messages.fish", List.of(
                "[broadcast] <white>%player% caught a <#f72a4c>%catch%</#f72a4c> measuring "
                    + "<#f72a4c>%length% %unit%</#f72a4c>!</white>"),
            common[0], common[1],
            Placeholder.unparsed("length", formatLength(length, decimalPlaces)),
            Placeholder.unparsed("unit", settings.getString("length.unit", "cm")));
    }

    static boolean isFish(Material material) {
        return FISH.contains(material);
    }

    static double randomLength(Material material, YamlConfiguration settings, double roll) {
        String path = "length.ranges." + material.name().toLowerCase(Locale.ROOT) + ".";
        double minimum = settings.getDouble(path + "minimum", 10);
        double maximum = settings.getDouble(path + "maximum", 100);
        double low = Math.min(minimum, maximum);
        double high = Math.max(minimum, maximum);
        return low + ((high - low) * Math.clamp(roll, 0, 1));
    }

    static String formatLength(double length, int decimalPlaces) {
        return String.format(Locale.ROOT, "%." + Math.clamp(decimalPlaces, 0, 3) + "f", length);
    }

    private static Component catchName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Component name;
        if (meta != null && meta.hasCustomName()) {
            name = DeathMessagesModule.safeComponent(meta.customName());
        } else if (meta != null && meta.hasItemName()) {
            name = DeathMessagesModule.safeComponent(meta.itemName());
        } else {
            name = Component.translatable(item.translationKey());
        }
        return name.hoverEvent(item.asHoverEvent());
    }
}
