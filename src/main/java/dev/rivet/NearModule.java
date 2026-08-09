package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;

final class NearModule {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final YamlConfiguration settings;

    NearModule(RivetPlugin plugin) {
        settings = plugin.settings("near");
    }

    boolean command(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /near"));
            return true;
        }
        double configuredRadius = settings.getDouble("radius", 100);
        double radius = Double.isFinite(configuredRadius) ? Math.max(1, configuredRadius) : 100;
        int maximum = Math.max(1, settings.getInt("maximum-results", 10));
        List<Player> nearby = player.getWorld().getPlayers().stream()
            .filter(other -> other != player && player.canSee(other))
            .filter(other -> !settings.getBoolean("exclude-spectators", true)
                || other.getGameMode() != GameMode.SPECTATOR)
            .filter(other -> other.getLocation().distanceSquared(player.getLocation()) <= radius * radius)
            .sorted(Comparator.comparingDouble(other ->
                other.getLocation().distanceSquared(player.getLocation())))
            .limit(maximum).toList();
        if (nearby.isEmpty()) {
            send(player, "messages.none", "<white>No visible players are within <#f72a4c>%radius%</#f72a4c> blocks.",
                "radius", number(radius));
            return true;
        }
        send(player, "messages.header", "<#f72a4c>Nearby players within <#f72a4c>%radius%</#f72a4c> blocks:</#f72a4c>",
            "radius", number(radius));
        nearby.forEach(other -> player.sendMessage(MM.deserialize(settings.getString("messages.entry",
                "<#f72a4c>%player%</#f72a4c> <white>- %distance%m</white>"),
            Placeholder.unparsed("player", other.getName()),
            Placeholder.unparsed("distance", number(other.getLocation().distance(player.getLocation()))))));
        return true;
    }

    private void send(Player player, String path, String fallback, String key, String value) {
        player.sendMessage(MM.deserialize(settings.getString(path, fallback),
            Placeholder.unparsed(key, value)));
    }

    private static String number(double value) {
        return Long.toString(Math.round(value));
    }
}
