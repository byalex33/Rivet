package dev.rivet;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

final class JoinLeaveModule implements Listener {
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;

    JoinLeaveModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("join-leave");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean first = !player.hasPlayedBefore();
        event.joinMessage(null);
        String joinPath = first ? "first-join" : "join";
        if (first && !plugin.messageActions().configured(settings, joinPath,
            "broadcast", "").enabled()) {
            joinPath = "join";
        }
        String joinFallback = first
            ? "<#f72a4c>Welcome <#f72a4c>%player%</#f72a4c> to the server for the first time!</#f72a4c>"
            : "<white>%player% joined the server.</white>";
        plugin.messageActions().run(plugin.getServer().getOnlinePlayers(),
            plugin.getServer().getOnlinePlayers(), settings, joinPath, "broadcast", joinFallback,
            Placeholder.unparsed("player", player.getName()));
        plugin.messageActions().run(player, settings, "welcome-title", List.of(
            "[title] <#f72a4c>Welcome, %player%!</#f72a4c> | <white>Enjoy your stay.</white> | 5 | 30 | 10"),
            Placeholder.unparsed("player", player.getName()));
        plugin.messageActions().run(player, settings, "welcome-chat", List.of(),
            Placeholder.unparsed("player", player.getName()));
        plugin.messageActions().run(player, settings, "motd", List.of(),
            Placeholder.unparsed("player", player.getName()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        plugin.messageActions().run(plugin.getServer().getOnlinePlayers(),
            plugin.getServer().getOnlinePlayers(), settings, "leave", "broadcast",
            "<white>%player% left the server.</white>",
            Placeholder.unparsed("player", event.getPlayer().getName()));
    }
}
