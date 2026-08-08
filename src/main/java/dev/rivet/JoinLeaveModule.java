package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;

final class JoinLeaveModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final YamlConfiguration settings;

    JoinLeaveModule(RivetPlugin plugin) {
        settings = plugin.settings("join-leave");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean first = !player.hasPlayedBefore();
        if (first && settings.getBoolean("first-join.enabled", true)) {
            event.joinMessage(message(settings.getString("first-join.message",
                "<#f72a4c>Welcome <#f72a4c><player></#f72a4c> to the server for the first time!</#f72a4c>"), player));
        } else if (settings.getBoolean("join.enabled", true)) {
            event.joinMessage(message(settings.getString("join.message",
                "<white><player> joined the server.</white>"), player));
        } else {
            event.joinMessage(null);
        }
        if (settings.getBoolean("welcome-title.enabled", true)) {
            player.showTitle(Title.title(
                message(settings.getString("welcome-title.title", "<#f72a4c>Welcome, <player>!</#f72a4c>"), player),
                message(settings.getString("welcome-title.subtitle", "<white>Enjoy your stay.</white>"), player),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1500), Duration.ofMillis(500))));
        }
        if (settings.getBoolean("welcome-chat.enabled", false)) {
            player.sendMessage(message(settings.getString("welcome-chat.message",
                "<white>Welcome to the server, <#f72a4c><player></#f72a4c>!</white>"), player));
        }
        if (settings.getBoolean("motd.enabled", false)) {
            settings.getStringList("motd.lines").forEach(line -> player.sendMessage(message(line, player)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(settings.getBoolean("leave.enabled", true)
            ? message(settings.getString("leave.message", "<white><player> left the server.</white>"), event.getPlayer())
            : null);
    }

    private static Component message(String template, Player player) {
        return MM.deserialize(template, Placeholder.unparsed("player", player.getName()));
    }
}
