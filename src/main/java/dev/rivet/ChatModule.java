package dev.rivet;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

final class ChatModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern ITEM_TOKEN = Pattern.compile("\\[(?:i|item)]",
        Pattern.CASE_INSENSITIVE);
    private final RivetPlugin plugin;
    private final Map<UUID, UUID> replies = new HashMap<>();
    private final YamlConfiguration socialData;
    private final YamlConfiguration ignoreData;
    private String chatFormat;
    private String sentFormat;
    private String receivedFormat;

    ChatModule(RivetPlugin plugin) {
        this.plugin = plugin;
        socialData = plugin.data("chat");
        ignoreData = plugin.data("ignore");
        reload();
    }

    void reload() {
        YamlConfiguration config = plugin.settings("chat");
        chatFormat = config.getString("format", "<gray><player>:</gray> <white><message></white>");
        sentFormat = config.getString("private-messages.sent", "<gray>[you → <player>]</gray> <white><message></white>");
        receivedFormat = config.getString("private-messages.received", "<gray>[<player> → you]</gray> <white><message></white>");
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand().clone();
        event.message(itemTokens(event.message(), itemLink(held)));
        event.renderer((source, displayName, message, viewer) -> format(chatFormat, displayName, message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        replies.remove(player);
        replies.values().removeIf(player::equals);
    }

    boolean message(Player sender, String[] args) {
        if (args.length < 2) {
            send(sender, "<red>Usage: /msg <player> <message>");
            return true;
        }
        Player recipient = plugin.getServer().getPlayerExact(args[0]);
        if (recipient == null || !sender.canSee(recipient)) {
            send(sender, "<red>That player is not online.");
            return true;
        }
        if (sender.equals(recipient)) {
            send(sender, "<red>You cannot message yourself.");
            return true;
        }
        if (ignores(ignoreData, recipient.getUniqueId(), sender.getUniqueId())
            && !sender.hasPermission("rivet.ignore.bypass")) {
            send(sender, plugin.settings("chat").getString("ignore.messages.blocked",
                "<red>That private message could not be delivered."));
            return true;
        }

        Component message = Component.text(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
        sender.sendMessage(format(sentFormat, recipient.displayName(), message));
        recipient.sendMessage(format(receivedFormat, sender.displayName(), message));
        String spyFormat = plugin.settings("chat").getString("social-spy.format",
            "<dark_gray>[spy]</dark_gray> <gray><sender> -> <recipient>:</gray> <white><message></white>");
        plugin.getServer().getOnlinePlayers().stream()
            .filter(spy -> shouldReceiveSpy(spy.getUniqueId(), sender.getUniqueId(),
                recipient.getUniqueId(), socialData.getBoolean("social-spy." + spy.getUniqueId()),
                spy.hasPermission("rivet.socialspy")))
            .forEach(spy -> spy.sendMessage(MM.deserialize(spyFormat,
                Placeholder.component("sender", sender.displayName()),
                Placeholder.component("recipient", recipient.displayName()),
                Placeholder.component("message", message))));
        replies.put(sender.getUniqueId(), recipient.getUniqueId());
        replies.put(recipient.getUniqueId(), sender.getUniqueId());
        if (plugin.getConfig().getBoolean("effects.sounds")) {
            recipient.playSound(recipient.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        }
        return true;
    }

    boolean reply(Player sender, String[] args) {
        if (args.length == 0) {
            send(sender, "<red>Usage: /r <message>");
            return true;
        }
        Player recipient = plugin.getServer().getPlayer(replies.get(sender.getUniqueId()));
        if (recipient == null || !sender.canSee(recipient)) {
            replies.remove(sender.getUniqueId());
            send(sender, "<red>You have nobody online to reply to.");
            return true;
        }
        String[] messageArgs = new String[args.length + 1];
        messageArgs[0] = recipient.getName();
        System.arraycopy(args, 0, messageArgs, 1, args.length);
        return message(sender, messageArgs);
    }

    boolean socialSpy(Player player, String[] args) {
        if (args.length != 0) {
            send(player, "<red>Usage: /socialspy");
            return true;
        }
        String path = "social-spy." + player.getUniqueId();
        boolean enabled = !socialData.getBoolean(path);
        socialData.set(path, enabled);
        if (!save("chat", player)) {
            socialData.set(path, !enabled);
            return true;
        }
        send(player, plugin.settings("chat").getString("social-spy.messages." +
            (enabled ? "enabled" : "disabled"), enabled
            ? "<green>Social spy enabled.</green>" : "<yellow>Social spy disabled.</yellow>"));
        return true;
    }

    boolean ignore(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<red>Usage: /ignore <player|list|clear>");
            return true;
        }
        String action = args[0];
        String path = "ignored." + player.getUniqueId();
        if (action.equalsIgnoreCase("list")) {
            List<String> names = ignoreData.getStringList(path).stream().map(value -> {
                try {
                    String name = plugin.getServer().getOfflinePlayer(UUID.fromString(value)).getName();
                    return name == null ? value : name;
                } catch (IllegalArgumentException exception) {
                    return value;
                }
            }).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            player.sendMessage(MM.deserialize(plugin.settings("chat").getString("ignore.messages.list",
                    "<gold>Ignored players:</gold> <white><players></white>"),
                Placeholder.unparsed("players", names.isEmpty() ? "none" : String.join(", ", names))));
            return true;
        }
        if (action.equalsIgnoreCase("clear")) {
            Object old = ignoreData.get(path);
            ignoreData.set(path, null);
            if (!save("ignore", player)) {
                ignoreData.set(path, old);
                return true;
            }
            send(player, plugin.settings("chat").getString("ignore.messages.cleared",
                "<green>Your ignore list was cleared.</green>"));
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(action);
        if (target == null || !player.canSee(target)) {
            send(player, "<red>That player is not online.");
            return true;
        }
        if (target.equals(player)) {
            send(player, plugin.settings("chat").getString("ignore.messages.self",
                "<red>You cannot ignore yourself.</red>"));
            return true;
        }
        List<String> previous = new ArrayList<>(ignoreData.getStringList(path));
        List<String> ignored = new ArrayList<>(previous);
        String uuid = target.getUniqueId().toString();
        boolean added;
        if (ignored.remove(uuid)) {
            added = false;
        } else {
            ignored.add(uuid);
            added = true;
        }
        ignoreData.set(path, ignored);
        if (!save("ignore", player)) {
            ignoreData.set(path, previous);
            return true;
        }
        String key = added ? "added" : "removed";
        player.sendMessage(MM.deserialize(plugin.settings("chat").getString("ignore.messages." + key,
                added ? "<yellow>You now ignore <white><player></white>."
                    : "<green>You no longer ignore <white><player></white>."),
            Placeholder.unparsed("player", target.getName())));
        return true;
    }

    List<String> ignoreCompletions(Player player, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> choices = new ArrayList<>(List.of("list", "clear"));
        plugin.getServer().getOnlinePlayers().stream().filter(other -> other != player && player.canSee(other))
            .map(Player::getName).sorted(Comparator.naturalOrder()).forEach(choices::add);
        return choices;
    }

    private boolean save(String file, Player player) {
        try {
            plugin.saveData(file);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/" + file + ".yml: " + exception.getMessage());
            send(player, "<red>That change could not be saved safely. Try again.");
            return false;
        }
    }

    static boolean ignores(YamlConfiguration data, UUID owner, UUID target) {
        return data.getStringList("ignored." + owner).contains(target.toString());
    }

    static boolean shouldReceiveSpy(UUID spy, UUID sender, UUID recipient,
                                    boolean enabled, boolean permitted) {
        return enabled && permitted && !spy.equals(sender) && !spy.equals(recipient);
    }

    static Component format(String format, Component player, Component message) {
        return MM.deserialize(format, Placeholder.component("player", player), Placeholder.component("message", message));
    }

    static Component itemTokens(Component message, Component item) {
        return message.replaceText(builder -> builder.match(ITEM_TOKEN).replacement(item));
    }

    private static Component itemLink(ItemStack item) {
        if (item.getType().isAir()) {
            return Component.text("[Empty Hand]", NamedTextColor.DARK_GRAY);
        }
        return Component.text("[")
            .append(item.effectiveName())
            .append(Component.text("]"))
            .hoverEvent(item.asHoverEvent());
    }

    private static void send(Player player, String message) {
        player.sendMessage(MM.deserialize(message));
    }
}
