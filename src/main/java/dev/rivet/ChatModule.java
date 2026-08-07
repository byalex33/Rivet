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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

final class ChatModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern ITEM_TOKEN = Pattern.compile("\\[(?:i|item)]",
        Pattern.CASE_INSENSITIVE);
    private final RivetPlugin plugin;
    private final Map<UUID, UUID> replies = new HashMap<>();
    private final String chatFormat;
    private final String sentFormat;
    private final String receivedFormat;

    ChatModule(RivetPlugin plugin) {
        this.plugin = plugin;
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

        Component message = Component.text(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
        sender.sendMessage(format(sentFormat, recipient.displayName(), message));
        recipient.sendMessage(format(receivedFormat, sender.displayName(), message));
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
