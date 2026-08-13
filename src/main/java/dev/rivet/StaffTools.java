package dev.rivet;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class StaffTools implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;
    private final YamlConfiguration notes;
    private final Set<UUID> god = new HashSet<>();
    private final Set<ActiveBar> bossBars = new HashSet<>();
    private final java.util.Map<NamespacedKey, ActiveToast> temporaryToasts = new HashMap<>();
    private static final DateTimeFormatter NOTE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z")
        .withZone(ZoneId.systemDefault());

    StaffTools(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("staff");
        data = plugin.data("staff");
        notes = plugin.data("notes");
        if (persistent()) {
            data.getStringList("god-players").forEach(value -> {
                try {
                    god.add(UUID.fromString(value));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Skipped invalid UUID in data/staff.yml: " + value);
                }
            });
            plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> god.contains(player.getUniqueId()))
                .forEach(player -> player.setInvulnerable(true));
        }
    }

    boolean heal(Player actor, String[] args) {
        Player target = target(actor, args, "rivet.heal.others", "/heal [player]");
        if (target == null) {
            return true;
        }
        var attribute = target.getAttribute(Attribute.MAX_HEALTH);
        target.setHealth(attribute == null ? 20 : attribute.getValue());
        target.setFireTicks(0);
        target.setFreezeTicks(0);
        feedback(actor, target, "heal", "<white>Healed <#f72a4c>%target%</#f72a4c>.");
        return true;
    }

    boolean feed(Player actor, String[] args) {
        Player target = target(actor, args, "rivet.feed.others", "/feed [player]");
        if (target == null) {
            return true;
        }
        target.setFoodLevel(20);
        target.setSaturation(20);
        target.setExhaustion(0);
        feedback(actor, target, "feed", "<white>Fed <#f72a4c>%target%</#f72a4c>.");
        return true;
    }

    boolean god(Player actor, String[] args) {
        GodArguments parsed = parseGodArguments(args);
        if (!parsed.valid()) {
            actor.sendMessage(MM.deserialize("<white>Usage: /god [player] [true|false] [-s]"));
            return true;
        }
        Player target = actor;
        if (parsed.player() != null) {
            if (!actor.hasPermission("rivet.god.others")) {
                actor.sendMessage(MM.deserialize("<white>You cannot change god mode for another player."));
                return true;
            }
            target = plugin.getServer().getPlayerExact(parsed.player());
            if (target == null || !actor.canSee(target)) {
                actor.sendMessage(MM.deserialize("<white>That player is not online.</white>"));
                return true;
            }
        }
        boolean enabled = parsed.state() == null ? !god.contains(target.getUniqueId()) : parsed.state();
        boolean wasEnabled = god.contains(target.getUniqueId());
        if (enabled) {
            god.add(target.getUniqueId());
        } else {
            god.remove(target.getUniqueId());
        }
        target.setInvulnerable(enabled);
        if (persistent() && !save()) {
            if (wasEnabled) {
                god.add(target.getUniqueId());
            } else {
                god.remove(target.getUniqueId());
            }
            data.set("god-players", god.stream().map(UUID::toString).sorted().toList());
            target.setInvulnerable(wasEnabled);
            actor.sendMessage(MM.deserialize("<white>God mode could not be saved safely.</white>"));
            return true;
        }
        if (parsed.silent()) {
            return true;
        }
        String fallback = enabled ? "<white>God mode enabled for <#f72a4c>%target%</#f72a4c>."
            : "<white>God mode disabled for <#f72a4c>%target%</#f72a4c>.";
        plugin.messageActions().run(actor, settings,
            "messages.god-" + (enabled ? "enabled" : "disabled"), fallback,
            Placeholder.unparsed("target", target.getName()));
        if (actor != target) {
            plugin.messageActions().run(target, settings,
                "messages.god-target-" + (enabled ? "enabled" : "disabled"), enabled
                    ? "<white>God mode was enabled.</white>" : "<white>God mode was disabled.</white>");
        }
        return true;
    }

    boolean flySpeed(Player actor, String[] args) {
        Player target = actor;
        String value;
        if (args.length == 1) {
            value = args[0];
        } else if (args.length == 2 && actor.hasPermission("rivet.flyspeed.others")) {
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !actor.canSee(target)) {
                actor.sendMessage(MM.deserialize("<white>That player is not online.</white>"));
                return true;
            }
            value = args[1];
        } else {
            actor.sendMessage(MM.deserialize("<white>Usage: /flyspeed [player] &lt;amount&gt;"));
            return true;
        }
        Float speed = parseFlySpeed(value);
        if (speed == null) {
            actor.sendMessage(MM.deserialize("<white>Flight speed must be between -1.0 and 1.0."));
            return true;
        }
        target.setFlySpeed(speed);
        actor.sendMessage(MM.deserialize("<white>Flight speed for <#f72a4c>%player%</#f72a4c> set to <#f72a4c>%speed%</#f72a4c>.",
            Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("speed", Float.toString(speed))));
        if (actor != target) {
            target.sendMessage(MM.deserialize("<white>Your flight speed is now <#f72a4c>%speed%</#f72a4c>.",
                Placeholder.unparsed("speed", Float.toString(speed))));
        }
        return true;
    }

    boolean commandSpy(Player actor, String[] args) {
        if (args.length != 0) {
            actor.sendMessage(MM.deserialize("<white>Usage: /commandspy"));
            return true;
        }
        String path = "command-spy." + actor.getUniqueId();
        boolean previous = data.getBoolean(path);
        boolean enabled = !previous;
        data.set(path, enabled);
        try {
            plugin.saveData("staff");
        } catch (IOException exception) {
            data.set(path, previous);
            plugin.getLogger().severe("Could not save data/staff.yml: " + exception.getMessage());
            actor.sendMessage(MM.deserialize("<white>Command spy could not be saved safely.</white>"));
            return true;
        }
        plugin.messageActions().run(actor, settings,
            "messages.command-spy-" + (enabled ? "enabled" : "disabled"), enabled
                ? "<white>Command spy enabled.</white>" : "<white>Command spy disabled.</white>");
        return true;
    }

    boolean bossBar(Player actor, String[] args) {
        if (args.length < 2) {
            actor.sendMessage(MM.deserialize("<white>Usage: /bossbarmsg <player|all> [-d:seconds] [-c:color] [-s:style] &lt;message&gt;"));
            return true;
        }
        BossBarArguments parsed = parseBossBarArguments(Arrays.copyOfRange(args, 1, args.length),
            settings.getDouble("boss-bar.default-duration-seconds", 10),
            settings.getString("boss-bar.default-color", "purple"),
            settings.getString("boss-bar.default-style", "solid"));
        if (!parsed.valid()) {
            actor.sendMessage(MM.deserialize("<white>" + parsed.error() + "</white>"));
            return true;
        }
        List<Player> targets;
        if (args[0].equalsIgnoreCase("all")) {
            targets = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        } else {
            Player target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !actor.canSee(target)) {
                actor.sendMessage(MM.deserialize("<white>That player is not online.</white>"));
                return true;
            }
            targets = List.of(target);
        }
        if (targets.isEmpty()) {
            actor.sendMessage(MM.deserialize("<white>No players are online.</white>"));
            return true;
        }
        Component title;
        try {
            title = MM.deserialize(parsed.message());
        } catch (RuntimeException exception) {
            actor.sendMessage(MM.deserialize("<white>That MiniMessage text is invalid.</white>"));
            return true;
        }
        BossBar bar = BossBar.bossBar(title, 1, parsed.color(), parsed.overlay());
        ActiveBar active = new ActiveBar(bar, Set.copyOf(targets));
        targets.forEach(player -> player.showBossBar(bar));
        active.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> remove(active),
            Math.max(1, (long) Math.ceil(parsed.duration() * 20)));
        bossBars.add(active);
        actor.sendMessage(MM.deserialize("<white>Boss bar shown to <#f72a4c>%count%</#f72a4c> player(s).",
            Placeholder.unparsed("count", Integer.toString(targets.size()))));
        return true;
    }

    boolean note(Player actor, String[] args) {
        NoteArguments parsed = parseNoteArguments(args);
        if (!parsed.valid()) {
            actor.sendMessage(MM.deserialize("<white>Usage: /note &lt;player&gt; <add <note>|remove &lt;id&gt;|clear [confirm]|list>"));
            return true;
        }
        if (parsed.action().equals("add") && !validNoteText(parsed.value(),
            Math.max(1, settings.getInt("notes.maximum-length", 500)))) {
            actor.sendMessage(MM.deserialize("<white>Notes must be non-empty, contain no control characters, and fit the configured length limit.</white>"));
            return true;
        }
        OfflinePlayer target = resolveKnownPlayer(parsed.player());
        if (target == null || target.getName() == null) {
            actor.sendMessage(MM.deserialize("<white>That player profile is not known to this server."));
            return true;
        }
        String base = "players." + target.getUniqueId();
        switch (parsed.action()) {
            case "list" -> listNotes(actor, target, base);
            case "add" -> addNote(actor, target, base, parsed.value());
            case "remove" -> removeNote(actor, target, base, parsed.id());
            case "clear" -> clearNotes(actor, target, base, parsed.confirmed());
            default -> throw new IllegalStateException("Unexpected note action");
        }
        return true;
    }

    boolean sameIp(Player actor, String[] args) {
        if (args.length > 1) {
            actor.sendMessage(MM.deserialize("<white>Usage: /sameip [player]"));
            return true;
        }
        Player target = actor;
        if (args.length == 1) {
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !actor.canSee(target)) {
                actor.sendMessage(MM.deserialize("<white>That player is not online."));
                return true;
            }
        }
        String address = address(target);
        if (address == null) {
            actor.sendMessage(MM.deserialize("<white>That player's session address is unavailable."));
            return true;
        }
        List<IpEntry> online = plugin.getServer().getOnlinePlayers().stream()
            .map(player -> new IpEntry(player.getUniqueId(), player.getName(), address(player)))
            .toList();
        List<String> matches = sameIpMatches(online, target.getUniqueId(), address,
            uuid -> {
                Player player = plugin.getServer().getPlayer(uuid);
                return player != null && actor.canSee(player);
            });
        actor.sendMessage(MM.deserialize(settings.getString("same-ip.format",
                "<#f72a4c>Online players sharing %player%'s session address:</#f72a4c> <#f72a4c>%matches%</#f72a4c>"),
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("matches", matches.isEmpty() ? "none" : String.join(", ", matches))));
        actor.sendMessage(MM.deserialize(settings.getString("same-ip.disclaimer",
            "<white>A shared address does not prove that accounts belong to the same person; proxies and shared networks can affect results.</white>")));
        return true;
    }

    boolean toast(Player actor, String[] args) {
        if (args.length < 2) {
            actor.sendMessage(MM.deserialize("<white>Usage: /toast <player|all> i:<icon> t:<title> &lt;message&gt; [type:<type>]"));
            return true;
        }
        ToastArguments parsed = parseToastArguments(Arrays.copyOfRange(args, 1, args.length),
            settings.getString("toast.default-type", "task"),
            settings.getString("toast.default-icon", "paper"));
        if (!parsed.valid()) {
            actor.sendMessage(MM.deserialize("<white>" + parsed.error() + "</white>"));
            return true;
        }
        if (!parsed.icon().isItem()) {
            actor.sendMessage(MM.deserialize("<white>Toast icon must be a valid item material.</white>"));
            return true;
        }
        List<Player> targets;
        if (args[0].equalsIgnoreCase("all")) {
            targets = new ArrayList<Player>(plugin.getServer().getOnlinePlayers().stream()
                .filter(actor::canSee).toList());
        } else {
            Player target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !actor.canSee(target)) {
                actor.sendMessage(MM.deserialize("<white>That player is not online."));
                return true;
            }
            targets = List.of(target);
        }
        if (targets.isEmpty()) {
            actor.sendMessage(MM.deserialize("<white>No visible players are online."));
            return true;
        }
        Component title;
        Component description;
        try {
            title = MM.deserialize(parsed.title());
            description = parsed.message().isEmpty() ? Component.empty()
                : MM.deserialize(parsed.message());
        } catch (RuntimeException exception) {
            actor.sendMessage(MM.deserialize("<white>That MiniMessage text is invalid.</white>"));
            return true;
        }
        NamespacedKey key = new NamespacedKey(plugin, "toast_" + UUID.randomUUID().toString().replace("-", ""));
        String json = toastJson(parsed, GsonComponentSerializer.gson().serialize(title),
            GsonComponentSerializer.gson().serialize(description));
        Advancement advancement;
        try {
            advancement = Bukkit.getUnsafe().loadAdvancement(key, json);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not create temporary toast: " + exception.getMessage());
            actor.sendMessage(MM.deserialize("<white>The toast could not be displayed safely.</white>"));
            return true;
        }
        if (advancement == null) {
            actor.sendMessage(MM.deserialize("<white>The toast could not be displayed safely.</white>"));
            return true;
        }
        temporaryToasts.put(key, new ActiveToast(advancement, List.copyOf(targets)));
        targets.forEach(player -> advancement.getCriteria().forEach(criterion ->
            player.getAdvancementProgress(advancement).awardCriteria(criterion)));
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> cleanupToast(key, advancement, targets), 2L);
        actor.sendMessage(MM.deserialize("<white>Toast shown to <#f72a4c>" + targets.size() + "</#f72a4c> player(s)."));
        return true;
    }

    private void listNotes(Player actor, OfflinePlayer target, String base) {
        var section = notes.getConfigurationSection(base + ".notes");
        List<Integer> ids = section == null ? List.of() : section.getKeys(false).stream()
            .map(StaffTools::positiveInteger).filter(java.util.Objects::nonNull).sorted().toList();
        actor.sendMessage(MM.deserialize("<#f72a4c><bold>Notes for %player%</bold></#f72a4c> <white>(%count%)</white>",
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("count", Integer.toString(ids.size()))));
        if (ids.isEmpty()) {
            actor.sendMessage(MM.deserialize("<white>No notes recorded.</white>"));
            return;
        }
        for (int id : ids) {
            String path = base + ".notes." + id;
            long timestamp = notes.getLong(path + ".timestamp");
            actor.sendMessage(MM.deserialize("<white>#%id%</white> <#f72a4c>%text%</#f72a4c> <#f72a4c>— %staff%, %time%</#f72a4c>",
                Placeholder.unparsed("id", Integer.toString(id)),
                Placeholder.unparsed("text", notes.getString(path + ".text", "")),
                Placeholder.unparsed("staff", notes.getString(path + ".staff", "unknown")),
                Placeholder.unparsed("time", timestamp > 0 ? NOTE_TIME.format(Instant.ofEpochMilli(timestamp)) : "unknown")));
        }
    }

    private void addNote(Player actor, OfflinePlayer target, String base, String text) {
        int id = nextNoteId(notes, base);
        String path = base + ".notes." + id;
        Object previousName = notes.get(base + ".name");
        Object previousNextId = notes.get(base + ".next-id");
        notes.set(base + ".name", target.getName());
        notes.set(base + ".next-id", id + 1);
        notes.set(path + ".text", text);
        notes.set(path + ".staff", actor.getName());
        notes.set(path + ".staff-uuid", actor.getUniqueId().toString());
        notes.set(path + ".timestamp", System.currentTimeMillis());
        if (!saveNotes(actor)) {
            notes.set(path, null);
            notes.set(base + ".name", previousName);
            notes.set(base + ".next-id", previousNextId);
            return;
        }
        actor.sendMessage(MM.deserialize("<white>Added note <#f72a4c>#%id%</#f72a4c> to <#f72a4c>%player%</#f72a4c>.",
            Placeholder.unparsed("id", Integer.toString(id)), Placeholder.unparsed("player", target.getName())));
    }

    private void removeNote(Player actor, OfflinePlayer target, String base, int id) {
        java.util.Map<String, Object> previous = snapshot(notes, base + ".notes." + id);
        if (!removeNote(notes, base, id)) {
            actor.sendMessage(MM.deserialize("<white>No note with that ID exists."));
            return;
        }
        if (!saveNotes(actor)) {
            restore(notes, base + ".notes." + id, previous);
            return;
        }
        actor.sendMessage(MM.deserialize("<white>Removed note <#f72a4c>#%id%</#f72a4c> from <#f72a4c>%player%</#f72a4c>.",
            Placeholder.unparsed("id", Integer.toString(id)), Placeholder.unparsed("player", target.getName())));
    }

    private void clearNotes(Player actor, OfflinePlayer target, String base, boolean confirmed) {
        if (!notes.isConfigurationSection(base + ".notes")) {
            actor.sendMessage(MM.deserialize("<white>That player has no notes.</white>"));
            return;
        }
        if (!confirmed) {
            actor.sendMessage(MM.deserialize("<white>Run <#f72a4c>/note %player% clear confirm</#f72a4c> to remove every note.",
                Placeholder.unparsed("player", target.getName())));
            return;
        }
        java.util.Map<String, Object> previous = snapshot(notes, base + ".notes");
        notes.set(base + ".notes", null);
        if (!saveNotes(actor)) {
            restore(notes, base + ".notes", previous);
            return;
        }
        actor.sendMessage(MM.deserialize("<white>Cleared all notes for <#f72a4c>%player%</#f72a4c>.",
            Placeholder.unparsed("player", target.getName())));
    }

    private OfflinePlayer resolveKnownPlayer(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return online;
        }
        var players = notes.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                if (name.equalsIgnoreCase(notes.getString("players." + key + ".name", ""))) {
                    try {
                        return plugin.getServer().getOfflinePlayer(UUID.fromString(key));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore malformed external edits and continue with Paper's cache.
                    }
                }
            }
        }
        OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
    }

    private boolean saveNotes(Player actor) {
        try {
            plugin.saveData("notes");
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/notes.yml: " + exception.getMessage());
            actor.sendMessage(MM.deserialize("<white>The notes change could not be saved safely.</white>"));
            return false;
        }
    }

    private void cleanupToast(NamespacedKey key, Advancement advancement, List<Player> players) {
        try {
            players.forEach(player -> revokeToast(player, advancement));
        } finally {
            Bukkit.getUnsafe().removeAdvancement(key);
            temporaryToasts.remove(key);
        }
    }

    List<String> completions(Player actor, String[] args, String othersPermission) {
        return args.length == 1 && actor.hasPermission(othersPermission)
            ? plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList()
            : List.of();
    }

    List<String> godCompletions(Player actor, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("true", "false", "-s"));
            if (actor.hasPermission("rivet.god.others")) {
                plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(values::add);
            }
            return values;
        }
        return args.length == 2 ? List.of("true", "false", "-s")
            : args.length == 3 ? List.of("-s") : List.of();
    }

    List<String> flySpeedCompletions(Player actor, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("0.1", "0.5", "1.0"));
            if (actor.hasPermission("rivet.flyspeed.others")) {
                plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(values::add);
            }
            return values;
        }
        return args.length == 2 ? List.of("0.1", "0.5", "1.0") : List.of();
    }

    List<String> bossBarCompletions(Player actor, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("all"));
            plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(values::add);
            return values;
        }
        return bossBarFlagCompletions(args);
    }

    static List<String> bossBarFlagCompletions(String[] args) {
        if (args.length < 2) {
            return List.of();
        }
        int currentIndex = args.length - 1;
        boolean durationUsed = hasFlag(args, currentIndex, "-d:");
        boolean colorUsed = hasFlag(args, currentIndex, "-c:");
        boolean styleUsed = hasFlag(args, currentIndex, "-s:");
        String current = args[currentIndex].toLowerCase(Locale.ROOT);

        if (current.startsWith("-d:")) {
            return durationUsed ? List.of() : durationFlags();
        }
        if (current.startsWith("-c:")) {
            return colorUsed ? List.of() : colorFlags();
        }
        if (current.startsWith("-s:")) {
            return styleUsed ? List.of() : styleFlags();
        }

        List<String> values = new ArrayList<>();
        if (!durationUsed) {
            values.addAll(durationFlags());
        }
        if (!colorUsed) {
            values.addAll(colorFlags());
        }
        if (!styleUsed) {
            values.addAll(styleFlags());
        }
        return values;
    }

    private static boolean hasFlag(String[] args, int end, String prefix) {
        return java.util.stream.IntStream.range(1, end)
            .mapToObj(index -> args[index])
            .anyMatch(argument -> argument.regionMatches(true, 0, prefix, 0, prefix.length()));
    }

    private static List<String> durationFlags() {
        return List.of("-d:5", "-d:10", "-d:30", "-d:60");
    }

    private static List<String> colorFlags() {
        return Arrays.stream(BossBar.Color.values())
            .map(color -> "-c:" + color.name().toLowerCase(Locale.ROOT)).toList();
    }

    private static List<String> styleFlags() {
        return List.of("-s:solid", "-s:segmented_6", "-s:segmented_10",
            "-s:segmented_12", "-s:segmented_20");
    }

    List<String> noteCompletions(Player actor, String[] args) {
        if (args.length == 1) {
            return knownPlayerNames(actor);
        }
        if (args.length == 2) {
            return List.of("add", "clear", "list", "remove");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("clear")) {
            return List.of("confirm");
        }
        return List.of();
    }

    List<String> sameIpCompletions(Player actor, String[] args) {
        return args.length == 1 ? visiblePlayerNames(actor) : List.of();
    }

    List<String> toastCompletions(Player actor, String[] args) {
        if (args.length == 1) {
            List<String> targets = new ArrayList<>(List.of("all"));
            targets.addAll(visiblePlayerNames(actor));
            return targets;
        }
        List<String> icons = Arrays.stream(Material.values()).filter(Material::isItem)
            .map(material -> material.name().toLowerCase(Locale.ROOT)).sorted().toList();
        return toastFlagCompletions(args, icons);
    }

    static List<String> toastFlagCompletions(String[] args, List<String> icons) {
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (current.startsWith("i:")) {
            return icons.stream().map(material -> "i:" + material).toList();
        }
        if (current.startsWith("type:")) {
            return List.of("type:task", "type:goal", "type:challenge");
        }
        if (current.startsWith("t:")) {
            return List.of("t:title");
        }
        if (args.length <= 3) {
            List<String> choices = new ArrayList<>();
            if (Arrays.stream(args).noneMatch(argument -> argument.regionMatches(true, 0, "i:", 0, 2))) {
                choices.add("i:diamond");
            }
            if (Arrays.stream(args).noneMatch(argument -> argument.regionMatches(true, 0, "t:", 0, 2))) {
                choices.add("t:title");
            }
            choices.addAll(List.of("type:task", "type:goal", "type:challenge"));
            return choices;
        }
        return List.of();
    }

    private List<String> knownPlayerNames(Player actor) {
        Set<String> names = new HashSet<>(visiblePlayerNames(actor));
        var players = notes.getConfigurationSection("players");
        if (players != null) {
            players.getKeys(false).stream().map(key -> notes.getString("players." + key + ".name"))
                .filter(java.util.Objects::nonNull).forEach(names::add);
        }
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> visiblePlayerNames(Player actor) {
        return plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
            .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        boolean enabled = persistent() && god.contains(event.getPlayer().getUniqueId());
        event.getPlayer().setInvulnerable(enabled);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        temporaryToasts.values().forEach(toast -> revokeToast(event.getPlayer(), toast.advancement()));
        event.getPlayer().setInvulnerable(false);
        if (!persistent()) {
            god.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        String format = settings.getString("command-spy.format",
            "<#f72a4c>[command spy] %player%:</#f72a4c> <white>%command%</white>");
        plugin.getServer().getOnlinePlayers().stream()
            .filter(spy -> shouldReceiveCommandSpy(spy.getUniqueId(), sender.getUniqueId(),
                data.getBoolean("command-spy." + spy.getUniqueId()),
                spy.hasPermission("rivet.commandspy"), spy.canSee(sender)))
            .forEach(spy -> spy.sendMessage(MM.deserialize(format,
                Placeholder.component("player", sender.displayName()),
                Placeholder.unparsed("command", event.getMessage()))));
    }

    void shutdown() {
        new ArrayList<>(bossBars).forEach(this::remove);
        new HashMap<>(temporaryToasts).forEach((key, toast) ->
            cleanupToast(key, toast.advancement(), toast.players()));
        temporaryToasts.clear();
        god.stream().map(plugin.getServer()::getPlayer).filter(java.util.Objects::nonNull)
            .forEach(player -> player.setInvulnerable(false));
        if (!persistent()) {
            god.clear();
        }
    }

    private Player target(Player actor, String[] args, String othersPermission, String usage) {
        if (args.length == 0) {
            return actor;
        }
        if (args.length != 1 || !actor.hasPermission(othersPermission)) {
            actor.sendMessage(MM.deserialize("<white>Usage: " + usage));
            return null;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !actor.canSee(target)) {
            actor.sendMessage(MM.deserialize("<white>That player is not online.</white>"));
            return null;
        }
        return target;
    }

    private void feedback(Player actor, Player target, String key, String fallback) {
        plugin.messageActions().run(actor, settings, "messages." + key, fallback,
            Placeholder.unparsed("target", target.getName()));
        if (actor != target) {
            plugin.messageActions().run(target, settings, "messages." + key + "-target",
                "<white>You were restored by <#f72a4c>%player%</#f72a4c>.",
                Placeholder.unparsed("player", actor.getName()));
        }
    }

    private boolean persistent() {
        return settings.getBoolean("god.persist-across-reconnects", false);
    }

    private boolean save() {
        data.set("god-players", god.stream().map(UUID::toString).sorted().toList());
        try {
            plugin.saveData("staff");
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/staff.yml: " + exception.getMessage());
            return false;
        }
    }

    private void remove(ActiveBar active) {
        if (!bossBars.remove(active) && active.task == null) {
            return;
        }
        if (active.task != null) {
            active.task.cancel();
            active.task = null;
        }
        active.players.forEach(player -> player.hideBossBar(active.bar));
    }

    static Float parseFlySpeed(String value) {
        try {
            float speed = Float.parseFloat(value);
            return Float.isFinite(speed) && speed >= -1 && speed <= 1 ? speed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static boolean shouldReceiveCommandSpy(UUID spy, UUID sender, boolean enabled,
                                           boolean permitted, boolean visible) {
        return enabled && permitted && visible && !spy.equals(sender);
    }

    static GodArguments parseGodArguments(String[] args) {
        List<String> values = new ArrayList<>();
        boolean silent = false;
        for (String argument : args) {
            if (argument.equalsIgnoreCase("-s")) {
                silent = true;
            } else {
                values.add(argument);
            }
        }
        if (values.size() > 2) {
            return new GodArguments(null, null, silent, false);
        }
        if (values.isEmpty()) {
            return new GodArguments(null, null, silent, true);
        }
        Boolean first = booleanValue(values.get(0));
        if (values.size() == 1) {
            return first == null ? new GodArguments(values.get(0), null, silent, true)
                : new GodArguments(null, first, silent, true);
        }
        Boolean state = booleanValue(values.get(1));
        return new GodArguments(values.get(0), state, silent, state != null);
    }

    static BossBarArguments parseBossBarArguments(String[] args, double defaultDuration,
                                                   String defaultColor, String defaultStyle) {
        double duration = defaultDuration;
        BossBar.Color color = bossBarColor(defaultColor);
        BossBar.Overlay overlay = bossBarOverlay(defaultStyle);
        List<String> message = new ArrayList<>();
        boolean valid = color != null && overlay != null && Double.isFinite(duration)
            && duration > 0 && duration <= 3600;
        String error = valid ? null : "Boss bar defaults are invalid; check settings/staff.yml.";
        for (String argument : args) {
            try {
                if (argument.regionMatches(true, 0, "-d:", 0, 3)) {
                    duration = Double.parseDouble(argument.substring(3));
                    if (!Double.isFinite(duration) || duration <= 0 || duration > 3600) {
                        valid = false;
                        error = "Duration must be greater than 0 and at most 3600 seconds.";
                    }
                } else if (argument.regionMatches(true, 0, "-c:", 0, 3)) {
                    color = bossBarColor(argument.substring(3));
                    if (color == null) {
                        valid = false;
                        error = "Unknown boss bar color.";
                    }
                } else if (argument.regionMatches(true, 0, "-s:", 0, 3)) {
                    overlay = bossBarOverlay(argument.substring(3));
                    if (overlay == null) {
                        valid = false;
                        error = "Unknown boss bar style.";
                    }
                } else {
                    message.add(argument);
                }
            } catch (NumberFormatException exception) {
                valid = false;
                error = "Duration must be a number of seconds.";
            }
        }
        if (message.isEmpty()) {
            valid = false;
            error = "Boss bar message cannot be empty.";
        }
        return new BossBarArguments(duration, color, overlay, String.join(" ", message), error, valid);
    }

    static NoteArguments parseNoteArguments(String[] args) {
        if (args.length < 2 || !args[0].matches("[A-Za-z0-9_]{1,16}")) {
            return new NoteArguments(null, null, null, -1, false, false);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list" -> new NoteArguments(args[0], action, null, -1, false,
                args.length == 2);
            case "add" -> new NoteArguments(args[0], action,
                args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null,
                -1, false, args.length >= 3);
            case "remove" -> new NoteArguments(args[0], action, null,
                args.length == 3 && positiveInteger(args[2]) != null
                    ? positiveInteger(args[2]) : -1,
                false, args.length == 3 && positiveInteger(args[2]) != null);
            case "clear" -> new NoteArguments(args[0], action, null, -1,
                args.length == 3, args.length == 2
                    || args.length == 3 && args[2].equalsIgnoreCase("confirm"));
            default -> new NoteArguments(args[0], action, null, -1, false, false);
        };
    }

    static int nextNoteId(YamlConfiguration notes, String base) {
        int configured = Math.max(1, notes.getInt(base + ".next-id", 1));
        while (notes.contains(base + ".notes." + configured)) {
            configured++;
        }
        return configured;
    }

    static boolean removeNote(YamlConfiguration notes, String base, int id) {
        String path = base + ".notes." + id;
        if (id < 1 || !notes.contains(path)) {
            return false;
        }
        notes.set(path, null);
        return true;
    }

    static boolean validNoteText(String text, int maximum) {
        return text != null && !text.isBlank() && text.length() <= maximum
            && text.chars().noneMatch(character -> Character.isISOControl(character));
    }

    static ToastArguments parseToastArguments(String[] args, String defaultType, String defaultIcon) {
        String type = toastType(defaultType);
        Material icon = Material.matchMaterial(defaultIcon == null ? "" : defaultIcon);
        String title = null;
        String error = type == null || !validToastIcon(icon)
            ? "Toast defaults are invalid; check settings/staff.yml." : null;
        List<String> message = new ArrayList<>();
        for (String argument : args) {
            if (argument.regionMatches(true, 0, "-t:", 0, 3)) {
                type = toastType(argument.substring(3));
                if (type == null) {
                    error = "Toast type must be task, goal, or challenge.";
                }
            } else if (argument.regionMatches(true, 0, "type:", 0, 5)) {
                type = toastType(argument.substring(5));
                if (type == null) {
                    error = "Toast type must be task, goal, or challenge.";
                }
            } else if (argument.regionMatches(true, 0, "-icon:", 0, 6)) {
                icon = Material.matchMaterial(argument.substring(6));
                if (!validToastIcon(icon)) {
                    error = "Toast icon must be a valid item material.";
                }
            } else if (argument.regionMatches(true, 0, "i:", 0, 2)) {
                icon = Material.matchMaterial(argument.substring(2));
                if (!validToastIcon(icon)) {
                    error = "Toast icon must be a valid item material.";
                }
            } else if (argument.regionMatches(true, 0, "t:", 0, 2)) {
                title = argument.substring(2);
                if (title.isBlank()) {
                    error = "Toast title cannot be empty.";
                }
            } else if (argument.startsWith("-")) {
                error = "Unknown toast flag: " + argument;
            } else {
                message.add(argument);
            }
        }
        if (message.isEmpty()) {
            error = "Toast message cannot be empty.";
        }
        String joined = String.join(" ", message);
        if (title == null) {
            title = joined;
            joined = "";
        }
        return new ToastArguments(type, icon, title, joined, error, error == null);
    }

    static List<String> sameIpMatches(List<IpEntry> entries, UUID target, String address,
                                      Predicate<UUID> visible) {
        if (address == null) {
            return List.of();
        }
        return entries.stream().filter(entry -> !entry.uuid().equals(target))
            .filter(entry -> address.equals(entry.address())).filter(entry -> visible.test(entry.uuid()))
            .map(IpEntry::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String address(Player player) {
        InetSocketAddress socket = player.getAddress();
        return socket == null || socket.getAddress() == null ? null : socket.getAddress().getHostAddress();
    }

    private static String toastType(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return Set.of("task", "goal", "challenge").contains(normalized) ? normalized : null;
    }

    private static boolean validToastIcon(Material material) {
        return material != null && !Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.WATER, Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY,
            Material.PISTON_HEAD, Material.MOVING_PISTON).contains(material);
    }

    private static String toastJson(ToastArguments parsed, String titleJson, String descriptionJson) {
        return "{\"criteria\":{\"show\":{\"trigger\":\"minecraft:impossible\"}},"
            + "\"display\":{\"icon\":{\"id\":\"" + parsed.icon().getKey() + "\"},"
            + "\"title\":" + titleJson + ",\"description\":" + descriptionJson + ","
            + "\"frame\":\"" + parsed.type() + "\",\"announce_to_chat\":false,"
            + "\"show_toast\":true,\"hidden\":true}}";
    }

    private static Integer positiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static java.util.Map<String, Object> snapshot(YamlConfiguration configuration,
                                                           String path) {
        var section = configuration.getConfigurationSection(path);
        if (section == null) {
            return java.util.Map.of();
        }
        java.util.Map<String, Object> values = new HashMap<>();
        section.getValues(true).forEach((key, value) -> {
            if (!(value instanceof org.bukkit.configuration.ConfigurationSection)) {
                values.put(key, value);
            }
        });
        return values;
    }

    private static void restore(YamlConfiguration configuration, String path,
                                java.util.Map<String, Object> values) {
        configuration.set(path, null);
        values.forEach((key, value) -> configuration.set(path + "." + key, value));
    }

    private static void revokeToast(Player player, Advancement advancement) {
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        new ArrayList<>(progress.getAwardedCriteria()).forEach(progress::revokeCriteria);
    }

    private static BossBar.Color bossBarColor(String value) {
        try {
            return BossBar.Color.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private static BossBar.Overlay bossBarOverlay(String value) {
        try {
            String normalized = value.toUpperCase(Locale.ROOT).replace("SEGMENTED_", "NOTCHED_");
            if (normalized.equals("SOLID")) {
                normalized = "PROGRESS";
            }
            return BossBar.Overlay.valueOf(normalized);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private static Boolean booleanValue(String value) {
        return value.equalsIgnoreCase("true") ? Boolean.TRUE
            : value.equalsIgnoreCase("false") ? Boolean.FALSE : null;
    }

    record GodArguments(String player, Boolean state, boolean silent, boolean valid) {
    }

    record BossBarArguments(double duration, BossBar.Color color, BossBar.Overlay overlay,
                            String message, String error, boolean valid) {
    }

    record NoteArguments(String player, String action, String value, int id,
                         boolean confirmed, boolean valid) {
    }

    record ToastArguments(String type, Material icon, String title, String message,
                          String error, boolean valid) {
    }

    record IpEntry(UUID uuid, String name, String address) {
    }

    private record ActiveToast(Advancement advancement, List<Player> players) {
    }

    private static final class ActiveBar {
        private final BossBar bar;
        private final Set<Player> players;
        private BukkitTask task;

        private ActiveBar(BossBar bar, Set<Player> players) {
            this.bar = bar;
            this.players = players;
        }
    }
}
