package dev.rivet;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.List;

/** Executes configurable player-facing output using the same action model as OneMillionCrops. */
final class MessageActions {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;

    MessageActions(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    void run(CommandSender recipient, YamlConfiguration settings, String path, String fallback,
             TagResolver... placeholders) {
        run(recipient, settings, path, "message", fallback, placeholders);
    }

    void run(CommandSender recipient, YamlConfiguration settings, String path, String fallbackTag,
             String fallback, TagResolver... placeholders) {
        ConfiguredMessage configured = configured(settings, path, fallbackTag, fallback);
        run(recipient, configured, path, placeholders);
    }

    void run(CommandSender recipient, YamlConfiguration settings, String path,
             List<String> fallback, TagResolver... placeholders) {
        run(recipient, configured(settings, path, fallback), path, placeholders);
    }

    private void run(CommandSender recipient, ConfiguredMessage configured, String path,
                     TagResolver... placeholders) {
        if (!configured.enabled()) {
            return;
        }
        TagResolver resolver = TagResolver.resolver(placeholders);
        if (recipient instanceof Player player) {
            plugin.guiActions().run(player, configured.actions(), resolver);
            return;
        }
        for (String line : configured.actions()) {
            GuiActions.Action action = GuiActions.parseAction(line);
            if (action == null) {
                warn(path, line);
            } else if (action.tag().equals("message")) {
                recipient.sendMessage(MM.deserialize(action.value(), resolver));
            } else if (action.tag().equals("broadcast")) {
                plugin.getServer().broadcast(MM.deserialize(action.value(), resolver));
            } else {
                plugin.getLogger().warning("Action [" + action.tag() + "] for '" + path
                    + "' needs a player and was skipped for the console.");
            }
        }
    }

    void run(Iterable<? extends Audience> recipients, Iterable<? extends Player> players,
             YamlConfiguration settings, String path, String fallbackTag, String fallback,
             TagResolver... placeholders) {
        ConfiguredMessage configured = configured(settings, path, fallbackTag, fallback);
        run(recipients, players, configured, path, placeholders);
    }

    void run(Iterable<? extends Audience> recipients, Iterable<? extends Player> players,
             YamlConfiguration settings, String path, List<String> fallback,
             TagResolver... placeholders) {
        run(recipients, players, configured(settings, path, fallback), path, placeholders);
    }

    private void run(Iterable<? extends Audience> recipients, Iterable<? extends Player> players,
                     ConfiguredMessage configured, String path, TagResolver... placeholders) {
        if (!configured.enabled()) {
            return;
        }
        TagResolver resolver = TagResolver.resolver(placeholders);
        for (String line : configured.actions()) {
            GuiActions.Action action = GuiActions.parseAction(line);
            if (action == null) {
                warn(path, line);
                continue;
            }
            if (action.tag().equals("message")) {
                var message = MM.deserialize(action.value(), resolver);
                recipients.forEach(recipient -> recipient.sendMessage(message));
            } else if (action.tag().equals("broadcast")) {
                plugin.getServer().broadcast(MM.deserialize(action.value(), resolver));
            } else {
                players.forEach(player -> plugin.guiActions().run(player, List.of(line), resolver));
            }
        }
    }

    ConfiguredMessage configured(YamlConfiguration settings, String path,
                                 String fallbackTag, String fallback) {
        if (settings.isString(path)) {
            return new ConfiguredMessage(true,
                List.of("[" + fallbackTag + "] " + settings.getString(path, fallback)));
        }
        return configured(settings, path, List.of("[" + fallbackTag + "] " + fallback));
    }

    ConfiguredMessage configured(YamlConfiguration settings, String path, List<String> fallback) {
        ConfigurationSection section = settings.getConfigurationSection(path);
        if (section != null && (section.contains("enabled") || section.contains("actions"))) {
            return new ConfiguredMessage(section.getBoolean("enabled", true),
                List.copyOf(section.getStringList("actions")));
        }
        if (settings.isList(path)) {
            return new ConfiguredMessage(true, List.copyOf(settings.getStringList(path)));
        }
        return new ConfiguredMessage(true, fallback);
    }

    private void warn(String path, String action) {
        plugin.getLogger().warning("Invalid message action for '" + path + "': " + action);
    }

    record ConfiguredMessage(boolean enabled, List<String> actions) {
    }
}
