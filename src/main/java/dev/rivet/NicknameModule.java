package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class NicknameModule implements Listener {
    private static final MiniMessage FORMATTED = MiniMessage.builder()
        .tags(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations())).build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;

    NicknameModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("nicknames");
        data = plugin.data("nicknames");
    }

    boolean command(org.bukkit.command.CommandSender sender, String[] args) {
        Player target;
        String nickname;
        if (args.length == 1 && sender instanceof Player player) {
            target = player;
            nickname = args[0];
        } else if (args.length == 2 && sender.hasPermission("rivet.nick.others")) {
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(FORMATTED.deserialize("<white>That player is not online."));
                return true;
            }
            nickname = args[1];
        } else {
            sender.sendMessage(FORMATTED.deserialize("<white>Usage: /nick <nickname|off> or /nick <player> <nickname|off>"));
            return true;
        }

        String path = "nicknames." + target.getUniqueId();
        Object previous = data.get(path);
        if (nickname.equalsIgnoreCase("off")) {
            data.set(path, null);
        } else {
            Component rendered;
            try {
                rendered = render(target, nickname);
            } catch (RuntimeException exception) {
                sender.sendMessage(FORMATTED.deserialize("<white>That nickname contains invalid formatting.</white>"));
                return true;
            }
            String plain = PLAIN.serialize(rendered);
            int maximum = Math.max(1, settings.getInt("maximum-length", 24));
            if (!validNickname(plain, maximum)) {
                sender.sendMessage(FORMATTED.deserialize(settings.getString("messages.invalid",
                    "<white>Nicknames must be 1-<max> visible characters with no control characters.</white>"),
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                        "max", Integer.toString(maximum))));
                return true;
            }
            data.set(path, nickname);
        }
        try {
            plugin.saveData("nicknames");
        } catch (IOException exception) {
            data.set(path, previous);
            plugin.getLogger().severe("Could not save data/nicknames.yml: " + exception.getMessage());
            sender.sendMessage(FORMATTED.deserialize("<white>Could not save that nickname."));
            return true;
        }
        plugin.refreshDisplayName(target);
        sender.sendMessage(FORMATTED.deserialize(nickname.equalsIgnoreCase("off")
            ? settings.getString("messages.removed", "<white>Nickname removed.</white>")
            : settings.getString("messages.set", "<white>Nickname updated.</white>")));
        return true;
    }

    List<String> completions(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("off"));
            if (sender.hasPermission("rivet.nick.others")) {
                plugin.getServer().getOnlinePlayers().stream().map(Player::getName).sorted().forEach(values::add);
            }
            return values;
        }
        return args.length == 2 && sender.hasPermission("rivet.nick.others") ? List.of("off") : List.of();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.refreshDisplayName(event.getPlayer());
    }

    Component displayName(Player player) {
        String nickname = data.getString("nicknames." + player.getUniqueId());
        if (nickname == null) {
            return Component.text(player.getName());
        }
        try {
            return render(player, nickname);
        } catch (RuntimeException exception) {
            return Component.text(player.getName());
        }
    }

    void shutdown() {
        plugin.getServer().getOnlinePlayers().forEach(player -> player.displayName(Component.text(player.getName())));
    }

    private Component render(Player player, String nickname) {
        return player.hasPermission("rivet.nick.format") ? FORMATTED.deserialize(nickname)
            : Component.text(nickname);
    }

    static boolean validNickname(String nickname, int maximumLength) {
        return !nickname.isBlank() && nickname.length() <= maximumLength
            && nickname.chars().noneMatch(Character::isISOControl);
    }
}
