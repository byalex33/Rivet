package dev.rivet;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

final class ChatModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final MiniMessage SAFE_FORMATTING = RivetMiniMessage.builder().tags(TagResolver.resolver(
        StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
        StandardTags.rainbow(), StandardTags.reset())).build();
    private static final MiniMessage CHAT_STYLES = RivetMiniMessage.builder().tags(TagResolver.resolver(
        StandardTags.color(), StandardTags.gradient(), StandardTags.rainbow())).build();
    private static final MiniMessage PLAYER_FORMATTING = SAFE_FORMATTING;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern ITEM_TOKEN = Pattern.compile("\\[(?:i|item)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final long SIMILARITY_MEMORY_MILLIS = 30_000;

    private final RivetPlugin plugin;
    private final Map<UUID, UUID> replies = new HashMap<>();
    private final Map<UUID, String> styles = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedTags = new ConcurrentHashMap<>();
    private final Map<UUID, RecentMessage> recentMessages = new ConcurrentHashMap<>();
    private final YamlConfiguration socialData;
    private final YamlConfiguration ignoreData;
    private final Map<String, StyleDefinition> colors = new ConcurrentHashMap<>();
    private final Map<String, StyleDefinition> gradients = new ConcurrentHashMap<>();
    private final Map<String, TagDefinition> tags = new ConcurrentHashMap<>();
    private volatile String chatFormat;
    private volatile String sentFormat;
    private volatile String receivedFormat;
    private volatile String mentionFormat;
    private volatile Sound mentionSound;
    private volatile long cooldownMillis;
    private volatile int similarityThreshold;
    private volatile boolean tagsEnabled;
    private volatile boolean mentionsEnabled;
    private volatile boolean antiSpamEnabled;
    private volatile boolean similarityEnabled;
    private volatile boolean allowCustomHex;
    private volatile boolean allowCustomGradients;
    private volatile boolean allowRainbow;

    ChatModule(RivetPlugin plugin) {
        this.plugin = plugin;
        socialData = plugin.data("chat");
        ignoreData = plugin.data("ignore");
        reload();
        loadSelections();
        migrateLegacyColors();
    }

    void reload() {
        YamlConfiguration config = plugin.settings("chat");
        chatFormat = config.getString("format",
            "%prefix%%tag% %player%%suffix%<dark_gray>: </dark_gray>%message%");
        sentFormat = config.getString("private-messages.sent",
            "<#f72a4c>[you → %player%]</#f72a4c> <white>%message%</white>");
        receivedFormat = config.getString("private-messages.received",
            "<#f72a4c>[%player% → you]</#f72a4c> <white>%message%</white>");
        mentionFormat = config.getString("mentions.format", "<yellow>@%player%</yellow>");
        mentionSound = ConfiguredEffect.resolveSound(config.getString("mentions.sound",
            "BLOCK_NOTE_BLOCK_PLING"));
        cooldownMillis = parseDurationMillis(config.getString("anti-spam.cooldown", "1s"), 1_000);
        similarityThreshold = Math.max(0, Math.min(100,
            config.getInt("anti-spam.similarity.threshold", 85)));
        tagsEnabled = config.getBoolean("tags.enabled", true);
        mentionsEnabled = config.getBoolean("mentions.enabled", true);
        antiSpamEnabled = config.getBoolean("anti-spam.enabled", true);
        similarityEnabled = config.getBoolean("anti-spam.similarity.enabled", true);
        allowCustomHex = config.getBoolean("chat-styles.allow-custom-hex", true);
        allowCustomGradients = config.getBoolean("chat-styles.allow-custom-gradients", true);
        allowRainbow = config.getBoolean("chat-styles.allow-rainbow", true);
        loadStyles(config, "chat-styles.colors", colors, false);
        loadStyles(config, "chat-styles.gradients", gradients, true);
        loadTags(config);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        String plain = PLAIN.serialize(event.message());
        if (blockedByAntiSpam(sender, plain, System.currentTimeMillis())) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin,
                () -> send(sender, "<white>Please wait before sending another similar message.</white>"));
            return;
        }

        ItemStack held = sender.getInventory().getItemInMainHand().clone();
        Component linkedMessage = itemTokens(event.message(), itemLink(held));
        event.message(linkedMessage);
        event.renderer((source, displayName, message, viewer) -> renderChat(
            source, displayName, message, viewer));
        scheduleMentionNotifications(sender, plain);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        replies.remove(player);
        replies.values().removeIf(player::equals);
        recentMessages.remove(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ChatGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || !player.getUniqueId().equals(holder.owner) || event.getRawSlot() < 0
            || event.getRawSlot() >= holder.inventory.getSize()) {
            return;
        }
        String choice = holder.choices.get(event.getRawSlot());
        if (choice == null) {
            return;
        }
        player.closeInventory();
        if (holder.type == GuiType.STYLE) {
            applyStyle(player, player, choice.equals("reset") ? null : choice);
        } else {
            applyTag(player, player, choice.equals("reset") ? null : choice);
        }
    }

    boolean message(Player sender, String[] args) {
        if (args.length < 2) {
            send(sender, "<white>Usage: /msg &lt;player&gt; &lt;message&gt;");
            return true;
        }
        Player recipient = plugin.getServer().getPlayerExact(args[0]);
        if (recipient == null || !sender.canSee(recipient)) {
            send(sender, "<white>That player is not online.");
            return true;
        }
        if (sender.equals(recipient)) {
            send(sender, "<white>You cannot message yourself.");
            return true;
        }
        if (ignores(ignoreData, recipient.getUniqueId(), sender.getUniqueId())
            && !sender.hasPermission("rivet.ignore.bypass")) {
            configuredMessage(sender, "ignore.messages.blocked",
                "<white>That private message could not be delivered.");
            return true;
        }

        Component message = Component.text(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        sender.sendMessage(format(sentFormat, recipient.displayName(), message));
        recipient.sendMessage(format(receivedFormat, sender.displayName(), message));
        String spyFormat = plugin.settings("chat").getString("social-spy.format",
            "<#f72a4c>[spy] %sender% → %recipient%:</#f72a4c> <white>%message%</white>");
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

    boolean me(Player sender, String[] args) {
        if (args.length == 0) {
            send(sender, "<white>Usage: /me &lt;message&gt;");
            return true;
        }
        String raw = String.join(" ", args);
        Component action = formatMeMessage(raw, sender.hasPermission("rivet.me.format"));
        String configured = plugin.settings("chat").getString("me.format",
            "<#f72a4c>* %player%</#f72a4c> <white>%message%</white>");
        plugin.getServer().getOnlinePlayers().stream()
            .filter(viewer -> viewer.canSee(sender) || viewer.equals(sender))
            .filter(viewer -> !ignores(ignoreData, viewer.getUniqueId(), sender.getUniqueId())
                || sender.hasPermission("rivet.ignore.bypass"))
            .forEach(viewer -> viewer.sendMessage(MM.deserialize(configured,
                Placeholder.component("player", sender.displayName()),
                Placeholder.component("message", action))));
        return true;
    }

    boolean reply(Player sender, String[] args) {
        if (args.length == 0) {
            send(sender, "<white>Usage: /r &lt;message&gt;");
            return true;
        }
        Player recipient = plugin.getServer().getPlayer(replies.get(sender.getUniqueId()));
        if (recipient == null || !sender.canSee(recipient)) {
            replies.remove(sender.getUniqueId());
            send(sender, "<white>You have nobody online to reply to.");
            return true;
        }
        String[] messageArgs = new String[args.length + 1];
        messageArgs[0] = recipient.getName();
        System.arraycopy(args, 0, messageArgs, 1, args.length);
        return message(sender, messageArgs);
    }

    boolean socialSpy(Player player, String[] args) {
        if (args.length != 0) {
            send(player, "<white>Usage: /socialspy");
            return true;
        }
        String path = "social-spy." + player.getUniqueId();
        boolean enabled = !socialData.getBoolean(path);
        socialData.set(path, enabled);
        if (!save("chat", player)) {
            socialData.set(path, !enabled);
            return true;
        }
        configuredMessage(player, "social-spy.messages." + (enabled ? "enabled" : "disabled"),
            enabled ? "<white>Social spy enabled.</white>" : "<white>Social spy disabled.</white>");
        return true;
    }

    boolean ignore(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<white>Usage: /ignore &lt;player|list|clear&gt;");
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
            configuredMessage(player, "ignore.messages.list",
                "<#f72a4c>Ignored players:</#f72a4c> <#f72a4c>%players%</#f72a4c>",
                Placeholder.unparsed("players", names.isEmpty() ? "none" : String.join(", ", names)));
            return true;
        }
        if (action.equalsIgnoreCase("clear")) {
            Object old = ignoreData.get(path);
            ignoreData.set(path, null);
            if (!save("ignore", player)) {
                ignoreData.set(path, old);
                return true;
            }
            configuredMessage(player, "ignore.messages.cleared",
                "<white>Your ignore list was cleared.</white>");
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(action);
        if (target == null || !player.canSee(target)) {
            send(player, "<white>That player is not online.");
            return true;
        }
        if (target.equals(player)) {
            configuredMessage(player, "ignore.messages.self", "<white>You cannot ignore yourself.</white>");
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
        configuredMessage(player, "ignore.messages." + key,
            added ? "<white>You now ignore <#f72a4c>%player%</#f72a4c>."
                : "<white>You no longer ignore <#f72a4c>%player%</#f72a4c>.",
            Placeholder.unparsed("player", target.getName()));
        return true;
    }

    boolean chatColor(Player actor, String[] args) {
        if (args.length == 0) {
            openStyleGui(actor);
            return true;
        }
        Player target = actor;
        String[] styleArgs = args;
        ParsedStyle parsed = parseStyle(args);
        if (parsed == null && args.length >= 2) {
            Player candidate = plugin.getServer().getPlayerExact(args[0]);
            if (candidate != null && actor.canSee(candidate)) {
                if (!canManageStyles(actor)) {
                    send(actor, "<white>You do not have permission to change another player's style.</white>");
                    return true;
                }
                target = candidate;
                styleArgs = Arrays.copyOfRange(args, 1, args.length);
                parsed = parseStyle(styleArgs);
            }
        }
        if (parsed == null) {
            send(actor, "<white>Usage: /chatcolor [player] &lt;color|#hex|gradient start end|rainbow|reset&gt;</white>");
            return true;
        }
        if (parsed.storage != null && !permittedStyle(actor, parsed)) {
            send(actor, "<white>You do not have permission to use that chat style.</white>");
            return true;
        }
        return applyStyle(actor, target, parsed.storage);
    }

    boolean tag(Player actor, String[] args) {
        if (!tagsEnabled) {
            send(actor, "<white>Chat tags are disabled.</white>");
            return true;
        }
        if (args.length == 0) {
            openTagGui(actor);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            List<String> available = tags.values().stream().filter(tag -> permittedTag(actor, tag.name))
                .map(tag -> tag.name).toList();
            send(actor, "<#f72a4c>Available tags:</#f72a4c> <white>"
                + (available.isEmpty() ? "none" : String.join(", ", available)) + "</white>");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
            return applyTag(actor, actor, null);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return setTag(actor, actor, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            Player target = otherTarget(actor, args[1], "rivet.chat.tag.others");
            return target == null || applyTag(actor, target, null);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            Player target = otherTarget(actor, args[1], "rivet.chat.tag.others");
            return target == null || setTag(actor, target, args[2]);
        }
        send(actor, "<white>Usage: /tag &lt;set [player] tag|reset [player]|list&gt;</white>");
        return true;
    }

    List<String> chatColorCompletions(Player player, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            choices.add("reset");
            colors.keySet().stream().filter(name -> permittedStyle(player,
                new ParsedStyle("color:" + name, "rivet.chat.color." + name))).forEach(choices::add);
            if (allowRainbow
                && player.hasPermission("rivet.chat.style.custom")) {
                choices.add("rainbow");
            }
            choices.add("gradient");
            if (canManageStyles(player)) {
                plugin.getServer().getOnlinePlayers().stream().filter(player::canSee)
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(choices::add);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("gradient")) {
            gradients.keySet().stream().filter(name -> permittedStyle(player,
                new ParsedStyle("gradient:" + name, "rivet.chat.gradient." + name)))
                .forEach(choices::add);
            if (player.hasPermission("rivet.chat.style.custom")) {
                choices.addAll(colors.keySet());
                choices.add("#ff0000");
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("gradient")
            && player.hasPermission("rivet.chat.style.custom")) {
            choices.addAll(colors.keySet());
            choices.add("#ffaa00");
        } else if (args.length >= 2 && canManageStyles(player)) {
            String[] remaining = Arrays.copyOfRange(args, 1, args.length);
            if (args.length == 2) {
                choices.addAll(chatColorCompletions(player, new String[]{remaining[0]}));
            } else if (args.length == 3 && remaining[0].equalsIgnoreCase("gradient")) {
                choices.addAll(chatColorCompletions(player, remaining));
            } else if (args.length == 4 && remaining[0].equalsIgnoreCase("gradient")) {
                choices.addAll(chatColorCompletions(player, remaining));
            }
        }
        return choices;
    }

    List<String> tagCompletions(Player player, String[] args) {
        if (args.length == 1) {
            return List.of("set", "reset", "list");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            List<String> choices = permittedTagNames(player);
            if (player.hasPermission("rivet.chat.tag.others")) {
                choices = new ArrayList<>(choices);
                plugin.getServer().getOnlinePlayers().stream().filter(player::canSee)
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(choices::add);
            }
            return choices;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")
            && player.hasPermission("rivet.chat.tag.others")) {
            return plugin.getServer().getOnlinePlayers().stream().filter(player::canSee)
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")
            && player.hasPermission("rivet.chat.tag.others")) {
            return tags.keySet().stream().sorted().toList();
        }
        return List.of();
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

    private Component renderChat(Player source, Component displayName, Component message, Audience viewer) {
        Component body = formatMessage(styleTag(styles.get(source.getUniqueId())), message);
        if (viewer instanceof Player player) {
            body = formatMentions(source, player, body);
        }
        ChatMetadata metadata = plugin.chatMetadata(source);
        Component tag = selectedTag(source);
        return format(chatFormat, metadata.prefix(), metadata.suffix(), tag,
            plugin.chatDisplayName(source, displayName), body);
    }

    private Component selectedTag(Player player) {
        if (!tagsEnabled) {
            return Component.empty();
        }
        String name = selectedTags.get(player.getUniqueId());
        TagDefinition tag = name == null ? null : tags.get(name);
        return tag == null || !permittedTag(player, name) ? Component.empty()
            : SAFE_FORMATTING.deserialize(tag.display);
    }

    private Component formatMentions(Player source, Player viewer, Component message) {
        if (!mentionsEnabled
            || !source.hasPermission("rivet.chat.mention")
            || !viewer.hasPermission("rivet.chat.mention.notify")) {
            return message;
        }
        Component formatted = replaceMention(message, viewer.getName(), mentionComponent(viewer.getName()));
        if (source.hasPermission("rivet.chat.mention.everyone")) {
            formatted = replaceMention(formatted, "everyone", mentionComponent("everyone"));
        }
        return formatted;
    }

    private void scheduleMentionNotifications(Player source, String message) {
        if (!mentionsEnabled
            || !source.hasPermission("rivet.chat.mention")) {
            return;
        }
        boolean everyone = source.hasPermission("rivet.chat.mention.everyone")
            && containsMention(message, "everyone");
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getServer().getOnlinePlayers().stream()
            .filter(viewer -> !viewer.equals(source) && viewer.hasPermission("rivet.chat.mention.notify"))
            .filter(viewer -> everyone || containsMention(message, viewer.getName()))
            .forEach(viewer -> {
                if (mentionSound != null) {
                    viewer.playSound(viewer.getLocation(), mentionSound, 0.8f, 1.2f);
                }
            }));
    }

    private Component mentionComponent(String name) {
        return SAFE_FORMATTING.deserialize(mentionFormat, Placeholder.unparsed("player", name));
    }

    private boolean blockedByAntiSpam(Player player, String message, long now) {
        if (!antiSpamEnabled
            || player.hasPermission("rivet.chat.antispam.bypass")) {
            return false;
        }
        String normalized = normalizeMessage(message);
        RecentMessage recent = recentMessages.get(player.getUniqueId());
        boolean cooldown = recent != null && now - recent.time < cooldownMillis;
        boolean similar = recent != null
            && similarityEnabled
            && now - recent.time < SIMILARITY_MEMORY_MILLIS
            && similarity(recent.message, normalized) >= similarityThreshold;
        if (cooldown || similar) {
            return true;
        }
        recentMessages.put(player.getUniqueId(), new RecentMessage(normalized, now));
        return false;
    }

    private void openStyleGui(Player player) {
        ChatGuiHolder holder = new ChatGuiHolder(player.getUniqueId(), GuiType.STYLE);
        List<GuiOption> options = new ArrayList<>();
        options.add(new GuiOption("reset", Component.text("Reset style", NamedTextColor.WHITE), Material.MILK_BUCKET));
        colors.values().stream().filter(style -> permittedStyle(player,
                new ParsedStyle("color:" + style.name, style.permission)))
            .forEach(style -> options.add(new GuiOption("color:" + style.name,
                SAFE_FORMATTING.deserialize(style.tag + titleCase(style.name)), Material.RED_DYE)));
        gradients.values().stream().filter(style -> permittedStyle(player,
                new ParsedStyle("gradient:" + style.name, style.permission)))
            .forEach(style -> options.add(new GuiOption("gradient:" + style.name,
                SAFE_FORMATTING.deserialize(style.tag + titleCase(style.name)), Material.GLOW_INK_SAC)));
        if (allowRainbow
            && player.hasPermission("rivet.chat.style.custom")) {
            options.add(new GuiOption("rainbow", SAFE_FORMATTING.deserialize("<rainbow>Rainbow"), Material.NETHER_STAR));
        }
        openGui(player, holder, options, Component.text("Chat style"));
    }

    private void openTagGui(Player player) {
        ChatGuiHolder holder = new ChatGuiHolder(player.getUniqueId(), GuiType.TAG);
        List<GuiOption> options = new ArrayList<>();
        options.add(new GuiOption("reset", Component.text("No tag", NamedTextColor.WHITE), Material.BARRIER));
        tags.values().stream().filter(tag -> permittedTag(player, tag.name))
            .forEach(tag -> options.add(new GuiOption(tag.name,
                SAFE_FORMATTING.deserialize(tag.display), Material.NAME_TAG)));
        openGui(player, holder, options, Component.text("Chat tag"));
    }

    private void openGui(Player player, ChatGuiHolder holder, List<GuiOption> options, Component title) {
        int size = Math.max(9, Math.min(54, ((options.size() + 8) / 9) * 9));
        holder.inventory = plugin.getServer().createInventory(holder, size, title);
        for (int slot = 0; slot < Math.min(size, options.size()); slot++) {
            GuiOption option = options.get(slot);
            ItemStack item = new ItemStack(option.material);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(option.display);
            item.setItemMeta(meta);
            holder.inventory.setItem(slot, item);
            holder.choices.put(slot, option.value);
        }
        player.openInventory(holder.inventory);
    }

    private boolean applyStyle(Player actor, Player target, String storage) {
        String path = "chat-style." + target.getUniqueId();
        String previous = styles.get(target.getUniqueId());
        socialData.set(path, storage);
        if (storage == null) {
            styles.remove(target.getUniqueId());
        } else {
            styles.put(target.getUniqueId(), storage);
        }
        if (!save("chat", actor)) {
            socialData.set(path, previous);
            if (previous == null) {
                styles.remove(target.getUniqueId());
            } else {
                styles.put(target.getUniqueId(), previous);
            }
            return true;
        }
        actor.sendMessage(MM.deserialize(storage == null
                ? "<white>Chat style reset for <#f72a4c>%player%</#f72a4c>."
                : "<white>Chat style set for <#f72a4c>%player%</#f72a4c>.",
            Placeholder.unparsed("player", target.getName())));
        return true;
    }

    private boolean setTag(Player actor, Player target, String value) {
        String name = value.toLowerCase(Locale.ROOT);
        if (!tags.containsKey(name)) {
            send(actor, "<white>That tag does not exist.</white>");
            return true;
        }
        if (!permittedTag(target, name) && !actor.equals(target)) {
            send(actor, "<white>That player does not have permission to use this tag.</white>");
            return true;
        }
        if (actor.equals(target) && !permittedTag(actor, name)) {
            send(actor, "<white>You do not have permission to use that tag.</white>");
            return true;
        }
        return applyTag(actor, target, name);
    }

    private boolean applyTag(Player actor, Player target, String name) {
        String path = "selected-tag." + target.getUniqueId();
        String previous = selectedTags.get(target.getUniqueId());
        socialData.set(path, name);
        if (name == null) {
            selectedTags.remove(target.getUniqueId());
        } else {
            selectedTags.put(target.getUniqueId(), name);
        }
        if (!save("chat", actor)) {
            socialData.set(path, previous);
            if (previous == null) {
                selectedTags.remove(target.getUniqueId());
            } else {
                selectedTags.put(target.getUniqueId(), previous);
            }
            return true;
        }
        actor.sendMessage(MM.deserialize(name == null
                ? "<white>Chat tag reset for <#f72a4c>%player%</#f72a4c>."
                : "<white>Chat tag set for <#f72a4c>%player%</#f72a4c>.",
            Placeholder.unparsed("player", target.getName())));
        return true;
    }

    private Player otherTarget(Player actor, String name, String permission) {
        if (!actor.hasPermission(permission)) {
            send(actor, "<white>You do not have permission to change another player.</white>");
            return null;
        }
        Player target = plugin.getServer().getPlayerExact(name);
        if (target == null || !actor.canSee(target)) {
            send(actor, "<white>That player is not online.</white>");
            return null;
        }
        return target;
    }

    private ParsedStyle parseStyle(String[] args) {
        if (args.length == 1) {
            String value = args[0].toLowerCase(Locale.ROOT);
            if (value.equals("reset")) {
                return new ParsedStyle(null, null);
            }
            if (value.equals("rainbow")) {
                return allowRainbow
                    ? new ParsedStyle("rainbow", "rivet.chat.style.custom") : null;
            }
            StyleDefinition color = colors.get(value);
            if (color != null) {
                return new ParsedStyle("color:" + value, color.permission);
            }
            StyleDefinition gradient = gradients.get(value);
            if (gradient != null) {
                return new ParsedStyle("gradient:" + value, gradient.permission);
            }
            String hex = normalizeColor(value);
            if (hex != null && hex.startsWith("#")
                && allowCustomHex) {
                return new ParsedStyle("custom:" + hex, "rivet.chat.style.custom");
            }
            if (validChatColor(value, true)) {
                return new ParsedStyle("legacy:" + value.toLowerCase(Locale.ROOT),
                    "rivet.chat.style.custom");
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("gradient")) {
            String name = args[1].toLowerCase(Locale.ROOT);
            StyleDefinition gradient = gradients.get(name);
            return gradient == null ? null
                : new ParsedStyle("gradient:" + name, gradient.permission);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("gradient")
            && allowCustomGradients) {
            String first = normalizeColor(args[1]);
            String second = normalizeColor(args[2]);
            return first == null || second == null ? null
                : new ParsedStyle("custom-gradient:" + first + ":" + second,
                    "rivet.chat.style.custom");
        }
        return null;
    }

    private boolean permittedStyle(Player player, ParsedStyle style) {
        return style.permission == null || player.hasPermission(style.permission)
            || style.permission.startsWith("rivet.chat.color.") && player.hasPermission("rivet.chat.color.*")
            || style.permission.startsWith("rivet.chat.gradient.") && player.hasPermission("rivet.chat.gradient.*")
            || style.permission.equals("rivet.chat.style.custom") && player.hasPermission("rivet.chatcolor.advanced");
    }

    private boolean canManageStyles(Player player) {
        return player.hasPermission("rivet.chat.style.others")
            || player.hasPermission("rivet.chatcolor.others");
    }

    private boolean permittedTag(Player player, String name) {
        return player.hasPermission("rivet.chat.tag." + name) || player.hasPermission("rivet.chat.tag.*");
    }

    private List<String> permittedTagNames(Player player) {
        return tags.keySet().stream().filter(name -> permittedTag(player, name)).sorted().toList();
    }

    private String styleTag(String storage) {
        if (storage == null) {
            return null;
        }
        if (storage.equals("rainbow")) {
            return allowRainbow ? "<rainbow>" : null;
        }
        if (storage.startsWith("color:")) {
            StyleDefinition style = colors.get(storage.substring("color:".length()));
            return style == null ? null : style.tag;
        }
        if (storage.startsWith("gradient:")) {
            StyleDefinition style = gradients.get(storage.substring("gradient:".length()));
            return style == null ? null : style.tag;
        }
        if (storage.startsWith("custom:")
            && allowCustomHex) {
            String color = storage.substring("custom:".length());
            return color(color) && color.startsWith("#") ? "<" + color + ">" : null;
        }
        if (storage.startsWith("custom-gradient:")
            && allowCustomGradients) {
            String[] values = storage.substring("custom-gradient:".length()).split(":", -1);
            return values.length == 2 && color(values[0]) && color(values[1])
                ? "<gradient:" + values[0] + ":" + values[1] + ">" : null;
        }
        if (storage.startsWith("legacy:")) {
            String value = storage.substring("legacy:".length());
            return validChatColor(value, true) ? value : null;
        }
        return null;
    }

    private void loadStyles(YamlConfiguration config, String path,
                            Map<String, StyleDefinition> destination, boolean gradient) {
        destination.clear();
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return;
        }
        for (String configuredName : section.getKeys(false)) {
            String name = configuredName.toLowerCase(Locale.ROOT);
            String tag = section.getString(configuredName);
            boolean valid = STYLE_NAME.matcher(name).matches() && validChatColor(tag, gradient);
            if (gradient && (tag == null || !tag.toLowerCase(Locale.ROOT).startsWith("<gradient:"))) {
                valid = false;
            }
            if (!gradient && tag != null && tag.toLowerCase(Locale.ROOT).startsWith("<gradient:")) {
                valid = false;
            }
            if (!valid) {
                plugin.getLogger().warning("Skipped unsafe chat style '" + path + "." + configuredName + "'.");
                continue;
            }
            String permission = "rivet.chat." + (gradient ? "gradient." : "color.") + name;
            destination.put(name, new StyleDefinition(name, tag.toLowerCase(Locale.ROOT), permission));
        }
    }

    private void loadTags(YamlConfiguration config) {
        tags.clear();
        ConfigurationSection section = config.getConfigurationSection("tags.list");
        if (section == null) {
            return;
        }
        for (String configuredName : section.getKeys(false)) {
            String name = configuredName.toLowerCase(Locale.ROOT);
            String display = section.getString(configuredName + ".display");
            if (!STYLE_NAME.matcher(name).matches() || display == null || display.length() > 256) {
                plugin.getLogger().warning("Skipped invalid chat tag '" + configuredName + "'.");
                continue;
            }
            tags.put(name, new TagDefinition(name, display));
        }
    }

    private void loadSelections() {
        loadUuidStrings("chat-style", styles);
        loadUuidStrings("selected-tag", selectedTags);
    }

    private void loadUuidStrings(String path, Map<UUID, String> destination) {
        ConfigurationSection section = socialData.getConfigurationSection(path);
        if (section == null) {
            return;
        }
        section.getKeys(false).forEach(value -> {
            try {
                String selection = section.getString(value);
                if (selection != null && !selection.isBlank()) {
                    destination.put(UUID.fromString(value), selection.toLowerCase(Locale.ROOT));
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipped invalid " + path + " owner UUID: " + value);
            }
        });
    }

    private void migrateLegacyColors() {
        ConfigurationSection saved = socialData.getConfigurationSection("chat-color");
        if (saved == null) {
            return;
        }
        int migrated = 0;
        for (String value : saved.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(value);
                String old = saved.getString(value);
                if (styles.containsKey(owner) || !validChatColor(old, true)) {
                    continue;
                }
                String storage = migrateLegacyStyle(old);
                styles.put(owner, storage);
                socialData.set("chat-style." + owner, storage);
                migrated++;
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipped invalid chat-color owner UUID: " + value);
            }
        }
        if (migrated == 0) {
            return;
        }
        try {
            plugin.saveData("chat");
            plugin.getLogger().info("Migrated " + migrated
                + " legacy chat-color selection(s); the recoverable legacy keys were retained.");
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not persist migrated chat colors: " + exception.getMessage());
        }
    }

    private String migrateLegacyStyle(String old) {
        String normalized = old.toLowerCase(Locale.ROOT);
        for (StyleDefinition style : colors.values()) {
            if (style.tag.equals(normalized)) {
                return "color:" + style.name;
            }
        }
        for (StyleDefinition style : gradients.values()) {
            if (style.tag.equals(normalized)) {
                return "gradient:" + style.name;
            }
        }
        return normalized.equals("<rainbow>") ? "rainbow" : "legacy:" + normalized;
    }

    private boolean save(String file, Player player) {
        try {
            plugin.saveData(file);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/" + file + ".yml: " + exception.getMessage());
            send(player, "<white>That change could not be saved safely. Try again.");
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

    static boolean validChatColor(String input, boolean advanced) {
        if (input == null || input.length() < 3 || input.charAt(0) != '<'
            || input.charAt(input.length() - 1) != '>') {
            return false;
        }
        String tag = input.substring(1, input.length() - 1).toLowerCase(Locale.ROOT);
        if (color(tag)) {
            return true;
        }
        if (!advanced) {
            return false;
        }
        if (tag.equals("rainbow")) {
            return true;
        }
        String[] gradient = tag.split(":", -1);
        return gradient.length >= 3 && gradient.length <= 10 && gradient[0].equals("gradient")
            && Arrays.stream(gradient, 1, gradient.length).allMatch(ChatModule::color);
    }

    static Component formatMessage(String color, Component message) {
        return color == null || color.isBlank() ? message
            : CHAT_STYLES.deserialize(color + "%message%", Placeholder.component("message", message));
    }

    static Component formatMeMessage(String message, boolean formatting) {
        return formatting ? PLAYER_FORMATTING.deserialize(message) : Component.text(message);
    }

    static Component safeFormatting(String message) {
        return message == null || message.isBlank() ? Component.empty()
            : SAFE_FORMATTING.deserialize(message);
    }

    static Component format(String format, Component player, Component message) {
        return MM.deserialize(format, Placeholder.component("player", player),
            Placeholder.component("message", message));
    }

    static Component format(String format, Component prefix, Component suffix, Component tag,
                            Component player, Component message) {
        return MM.deserialize(format, Placeholder.component("prefix", prefix),
            Placeholder.component("suffix", suffix), Placeholder.component("tag", tag),
            Placeholder.component("player", player), Placeholder.component("message", message));
    }

    static Component itemTokens(Component message, Component item) {
        return message.replaceText(builder -> builder.match(ITEM_TOKEN).replacement(item));
    }

    static Component replaceMention(Component message, String name, Component replacement) {
        Pattern mention = Pattern.compile("(?i)(?<![a-z0-9_])@" + Pattern.quote(name)
            + "(?![a-z0-9_])");
        return message.replaceText(builder -> builder.match(mention).replacement(replacement));
    }

    static boolean containsMention(String message, String name) {
        return Pattern.compile("(?i)(?<![a-z0-9_])@" + Pattern.quote(name)
            + "(?![a-z0-9_])").matcher(message).find();
    }

    static long parseDurationMillis(String configured, long fallback) {
        if (configured == null) {
            return fallback;
        }
        String value = configured.trim().toLowerCase(Locale.ROOT);
        try {
            long multiplier = value.endsWith("ms") ? 1 : value.endsWith("s") ? 1_000
                : value.endsWith("m") ? 60_000 : -1;
            int suffix = value.endsWith("ms") ? 2 : multiplier == -1 ? 0 : 1;
            long amount = Long.parseLong(value.substring(0, value.length() - suffix));
            return multiplier == -1 || amount < 0 ? fallback : Math.multiplyExact(amount, multiplier);
        } catch (ArithmeticException | NumberFormatException exception) {
            return fallback;
        }
    }

    static int similarity(String first, String second) {
        if (first.equals(second)) {
            return 100;
        }
        int maximum = Math.max(first.length(), second.length());
        if (maximum == 0) {
            return 100;
        }
        int[] previous = new int[second.length() + 1];
        for (int column = 0; column <= second.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= first.length(); row++) {
            int[] current = new int[second.length() + 1];
            current[0] = row;
            for (int column = 1; column <= second.length(); column++) {
                int substitution = previous[column - 1]
                    + (first.charAt(row - 1) == second.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(Math.min(previous[column] + 1,
                    current[column - 1] + 1), substitution);
            }
            previous = current;
        }
        return (int) Math.round((1d - (double) previous[second.length()] / maximum) * 100);
    }

    static String normalizeMessage(String message) {
        return message.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static Component itemLink(ItemStack item) {
        if (item.getType().isAir()) {
            return Component.text("[Empty Hand]", RivetPalette.SECONDARY);
        }
        return Component.text("[").append(item.effectiveName()).append(Component.text("]"))
            .hoverEvent(item.asHoverEvent());
    }

    private static String normalizeColor(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return color(normalized) ? normalized : null;
    }

    private static boolean color(String value) {
        return NamedTextColor.NAMES.value(value) != null
            || value.matches("#[0-9a-f]{6}") && TextColor.fromHexString(value) != null;
    }

    private static String titleCase(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).replace('_', ' ');
    }

    private void configuredMessage(Player player, String path, String fallback,
                                   net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... placeholders) {
        plugin.messageActions().run(player, plugin.settings("chat"), path, fallback, placeholders);
    }

    private static void send(Player player, String message) {
        player.sendMessage(MM.deserialize(message));
    }

    private record StyleDefinition(String name, String tag, String permission) {
    }

    private record TagDefinition(String name, String display) {
    }

    private record ParsedStyle(String storage, String permission) {
    }

    private record RecentMessage(String message, long time) {
    }

    private record GuiOption(String value, Component display, Material material) {
    }

    private enum GuiType {
        STYLE,
        TAG
    }

    private static final class ChatGuiHolder implements InventoryHolder {
        private final UUID owner;
        private final GuiType type;
        private final Map<Integer, String> choices = new HashMap<>();
        private Inventory inventory;

        private ChatGuiHolder(UUID owner, GuiType type) {
            this.owner = owner;
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
