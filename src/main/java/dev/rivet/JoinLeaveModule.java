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
                "<gold>Welcome <white><player></white> to the server for the first time!</gold>"), player));
        } else if (settings.getBoolean("join.enabled", true)) {
            event.joinMessage(message(settings.getString("join.message",
                "<green><player> joined the server.</green>"), player));
        } else {
            event.joinMessage(null);
        }
        if (settings.getBoolean("welcome-title.enabled", true)) {
            player.showTitle(Title.title(
                message(settings.getString("welcome-title.title", "<gold>Welcome, <player>!</gold>"), player),
                message(settings.getString("welcome-title.subtitle", "<gray>Enjoy your stay.</gray>"), player),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1500), Duration.ofMillis(500))));
        }
        if (settings.getBoolean("welcome-chat.enabled", false)) {
            player.sendMessage(message(settings.getString("welcome-chat.message",
                "<yellow>Welcome to the server, <white><player></white>!</yellow>"), player));
        }
        if (settings.getBoolean("motd.enabled", false)) {
            settings.getStringList("motd.lines").forEach(line -> player.sendMessage(message(line, player)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(settings.getBoolean("leave.enabled", true)
            ? message(settings.getString("leave.message", "<red><player> left the server.</red>"), event.getPlayer())
            : null);
    }

    private static Component message(String template, Player player) {
        return MM.deserialize(template, Placeholder.unparsed("player", player.getName()));
    }
}
