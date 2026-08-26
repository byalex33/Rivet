package dev.rivet;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
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
    private final WelcomeHeadRenderer welcomeHeads;

    JoinLeaveModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("join");
        welcomeHeads = new WelcomeHeadRenderer(plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean first = !player.hasPlayedBefore();
        TagResolver placeholders = playerPlaceholders(player);
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
            placeholders);
        plugin.messageActions().run(player, settings, "welcome-title", List.of(
            "[title] <#f72a4c>Welcome, %player%!</#f72a4c> | <white>Enjoy your stay.</white> | 5 | 30 | 10"),
            placeholders);
        plugin.messageActions().run(player, settings, "welcome-chat", List.of(),
            placeholders);
        plugin.messageActions().run(player, settings, "motd", List.of(),
            placeholders);
        scheduleWelcome(player, first ? "first-join" : "returning", placeholders);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        plugin.messageActions().run(plugin.getServer().getOnlinePlayers(),
            plugin.getServer().getOnlinePlayers(), settings, "leave", "broadcast",
            "<white>%player% left the server.</white>",
            playerPlaceholders(event.getPlayer()));
    }

    private void scheduleWelcome(Player player, String type, TagResolver placeholders) {
        String path = "welcome." + type;
        MessageActions.ConfiguredMessage configured = plugin.messageActions().configured(
            settings, path, List.of());
        if (!configured.enabled() || configured.actions().isEmpty()) {
            return;
        }
        long delay = Math.clamp(settings.getLong(path + ".delay-ticks",
            settings.getLong("welcome.delay-ticks", 20)), 0, 20 * 60L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (String configuredAction : configured.actions()) {
                GuiActions.Action action = GuiActions.parseAction(configuredAction);
                if (action != null && action.tag().equals("welcome-head")) {
                    showWelcomeHead(player, action.value(), placeholders);
                } else {
                    plugin.guiActions().run(player, List.of(configuredAction), placeholders);
                }
            }
        }, delay);
    }

    private void showWelcomeHead(Player player, String configured, TagResolver placeholders) {
        String profile = configured.trim().toLowerCase(java.util.Locale.ROOT);
        if (!profile.matches("[a-z0-9_-]+")) {
            plugin.getLogger().warning("Invalid [welcome-head] profile '" + configured + "'.");
            return;
        }
        ConfigurationSection section = settings.getConfigurationSection("welcome-heads." + profile);
        if (section == null || !section.getBoolean("enabled", true)) {
            if (section == null) {
                plugin.getLogger().warning("Unknown [welcome-head] profile '" + configured + "'.");
            }
            return;
        }
        welcomeHeads.send(player, section, placeholders);
    }

    private static TagResolver playerPlaceholders(Player player) {
        return TagResolver.resolver(
            Placeholder.unparsed("player", player.getName()),
            Placeholder.unparsed("player_uuid", player.getUniqueId().toString()),
            RivetHeads.resolver(player));
    }
}
