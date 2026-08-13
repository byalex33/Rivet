package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PermissionModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final String USAGE = "<white>Usage: /perm &lt;user|group|check|tree|listgroups|reload&gt;</white>";
    private final RivetPlugin plugin;
    private final File groupsFile;
    private final File usersFile;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private YamlConfiguration groups;
    private YamlConfiguration users;

    PermissionModule(RivetPlugin plugin) {
        this.plugin = plugin;
        groupsFile = plugin.settingsFile("permissions");
        usersFile = plugin.dataFile("permissions");
        groups = plugin.settings("permissions");
        groups.options().pathSeparator('/');
        users = new YamlConfiguration();
        users.options().pathSeparator('/');
        try {
            boolean groupsChanged = migrateGroups(groups);
            if (usersFile.isFile()) {
                users.load(usersFile);
            }
            boolean usersChanged = migrateUsers(users);
            validate(groups, users);
            if (groupsChanged) {
                groups.save(groupsFile);
            }
            if (usersChanged) {
                users.save(usersFile);
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not load Rivet permissions: " + exception.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        attachments.remove(event.getPlayer().getUniqueId());
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, USAGE);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "user" -> userCommand(sender, args);
            case "group" -> groupCommand(sender, args);
            case "check" -> checkCommand(sender, args);
            case "tree" -> treeCommand(sender, args);
            case "listgroups" -> listGroups(sender, args);
            case "reload" -> reloadCommand(sender, args);
            default -> {
                send(sender, USAGE);
                yield true;
            }
        };
    }

    List<String> completions(String[] args) {
        if (args.length == 1) {
            return List.of("check", "group", "listgroups", "reload", "tree", "user");
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("user")) {
            if (args.length == 2) {
                return playerNames();
            }
            if (args.length == 3) {
                return List.of("group", "info", "permission");
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("group")) {
                return List.of("add", "remove", "set");
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("permission")) {
                return List.of("set", "unset");
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("group")) {
                return groupNames();
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("permission")) {
                return permissionNames();
            }
            if (args.length == 6 && args[2].equalsIgnoreCase("permission")
                && args[3].equalsIgnoreCase("set")) {
                return List.of("false", "true");
            }
        }
        if (root.equals("group")) {
            if (args.length == 2) {
                return groupNames();
            }
            if (args.length == 3) {
                return List.of("create", "delete", "info", "members", "meta", "parent",
                    "permission", "weight");
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("parent")) {
                return List.of("add", "remove");
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("weight")) {
                return List.of("set");
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("permission")) {
                return List.of("set", "unset");
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("meta")) {
                return List.of("prefix", "suffix");
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("parent")) {
                return groupNames();
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("weight")) {
                return List.of("0", "10", "50", "100");
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("permission")) {
                return permissionNames();
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("meta")) {
                return List.of("set");
            }
            if (args.length == 6 && args[2].equalsIgnoreCase("permission")
                && args[3].equalsIgnoreCase("set")) {
                return List.of("false", "true");
            }
        }
        if (args.length == 2 && (root.equals("check") || root.equals("tree"))) {
            return playerNames();
        }
        if (args.length == 3 && root.equals("check")) {
            return permissionNames();
        }
        return List.of();
    }

    private boolean userCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, USAGE);
            return true;
        }
        OfflinePlayer target = player(args[1], sender);
        if (target == null) {
            return true;
        }
        String operation = args[2].toLowerCase(Locale.ROOT);
        if (operation.equals("info") && args.length == 3) {
            userInfo(sender, target);
            return true;
        }
        if (operation.equals("group") && args.length == 5) {
            String group = group(args[4], sender);
            if (group == null) {
                return true;
            }
            List<String> assigned = new ArrayList<>(assignedGroups(target.getUniqueId()));
            switch (args[3].toLowerCase(Locale.ROOT)) {
                case "add" -> {
                    if (!assigned.contains(group)) {
                        assigned.add(group);
                    }
                }
                case "remove" -> assigned.remove(group);
                case "set" -> assigned = new ArrayList<>(List.of(group));
                default -> {
                    send(sender, USAGE);
                    return true;
                }
            }
            if (assigned.isEmpty() && PermissionResolver.hasGroup(groups, "default")) {
                assigned.add("default");
            }
            userPath(target);
            users.set(userBase(target.getUniqueId()) + "/groups", assigned.stream().distinct().toList());
            if (saveUsers(sender)) {
                refresh(target);
                success(sender, "<white>Updated <#f72a4c>" + targetName(target)
                    + "</#f72a4c>'s groups.</white>");
            }
            return true;
        }
        if (operation.equals("permission")) {
            if (args.length == 6 && args[3].equalsIgnoreCase("set")) {
                String node = permission(args[4], sender);
                Boolean state = state(args[5], sender);
                if (node != null && state != null) {
                    userPath(target);
                    permissionSection(users, userBase(target.getUniqueId()) + "/permissions")
                        .set(node, state);
                    if (saveUsers(sender)) {
                        refresh(target);
                        success(sender, changedMessage(targetName(target), node, state));
                    }
                }
                return true;
            }
            if (args.length == 5 && args[3].equalsIgnoreCase("unset")) {
                String node = permission(args[4], sender);
                if (node != null) {
                    ConfigurationSection section = users.getConfigurationSection(
                        userBase(target.getUniqueId()) + "/permissions");
                    if (section != null) {
                        section.set(node, null);
                    }
                    if (saveUsers(sender)) {
                        refresh(target);
                        success(sender, "<white>Unset <#f72a4c>" + node + "</#f72a4c> for <#f72a4c>"
                            + targetName(target) + "</#f72a4c>.</white>");
                    }
                }
                return true;
            }
        }
        send(sender, USAGE);
        return true;
    }

    private boolean groupCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, USAGE);
            return true;
        }
        String name = PermissionResolver.normalize(args[1]);
        String operation = args[2].toLowerCase(Locale.ROOT);
        if (operation.equals("create") && args.length == 3) {
            if (!PermissionResolver.validGroupName(name)) {
                send(sender, "<white>Use a valid group name.</white>");
            } else if (PermissionResolver.hasGroup(groups, name)) {
                send(sender, "<white>That group already exists.</white>");
            } else {
                createGroup(name);
                if (saveGroups(sender)) {
                    refreshAll();
                    success(sender, "<white>Created group <#f72a4c>" + name + "</#f72a4c>.</white>");
                }
            }
            return true;
        }
        String group = group(name, sender);
        if (group == null) {
            return true;
        }
        String base = PermissionResolver.groupPath(group);
        if (operation.equals("delete") && args.length == 3) {
            if (group.equals("default")) {
                send(sender, "<white>The default group cannot be deleted.</white>");
            } else {
                deleteGroup(group);
                if (saveGroups(sender) && saveUsers(sender)) {
                    refreshAll();
                    success(sender, "<white>Deleted group <#f72a4c>" + group + "</#f72a4c>.</white>");
                }
            }
            return true;
        }
        if (operation.equals("info") && args.length == 3) {
            groupInfo(sender, group);
            return true;
        }
        if (operation.equals("members") && args.length == 3) {
            groupMembers(sender, group);
            return true;
        }
        if (operation.equals("parent") && args.length == 5) {
            String parent = group(args[4], sender);
            if (parent == null) {
                return true;
            }
            List<String> parents = new ArrayList<>(PermissionResolver.parents(groups, group));
            if (args[3].equalsIgnoreCase("add")) {
                if (PermissionResolver.wouldCreateCycle(groups, group, parent)) {
                    send(sender, "<white>That parent would create an inheritance cycle.</white>");
                    return true;
                }
                if (!parents.contains(parent)) {
                    parents.add(parent);
                }
            } else if (args[3].equalsIgnoreCase("remove")) {
                parents.remove(parent);
            } else {
                send(sender, USAGE);
                return true;
            }
            groups.set(base + "/parents", parents);
            if (saveGroups(sender)) {
                refreshAll();
                success(sender, "<white>Updated parents for <#f72a4c>" + group + "</#f72a4c>.</white>");
            }
            return true;
        }
        if (operation.equals("weight") && args.length == 5
            && args[3].equalsIgnoreCase("set")) {
            try {
                groups.set(base + "/weight", Integer.parseInt(args[4]));
                if (saveGroups(sender)) {
                    refreshAll();
                    success(sender, "<white>Set <#f72a4c>" + group + "</#f72a4c> weight to <#f72a4c>"
                        + args[4] + "</#f72a4c>.</white>");
                }
            } catch (NumberFormatException exception) {
                send(sender, "<white>Weight must be a whole number.</white>");
            }
            return true;
        }
        if (operation.equals("permission")) {
            if (args.length == 6 && args[3].equalsIgnoreCase("set")) {
                String node = permission(args[4], sender);
                Boolean state = state(args[5], sender);
                if (node != null && state != null) {
                    permissionSection(groups, base + "/permissions").set(node, state);
                    if (saveGroups(sender)) {
                        refreshAll();
                        success(sender, "<white>Set <#f72a4c>" + node + "</#f72a4c> to <#f72a4c>"
                            + state + "</#f72a4c> for group <#f72a4c>" + group + "</#f72a4c>.</white>");
                    }
                }
                return true;
            }
            if (args.length == 5 && args[3].equalsIgnoreCase("unset")) {
                String node = permission(args[4], sender);
                if (node != null) {
                    ConfigurationSection section = groups.getConfigurationSection(base + "/permissions");
                    if (section != null) {
                        section.set(node, null);
                    }
                    if (saveGroups(sender)) {
                        refreshAll();
                        success(sender, "<white>Unset <#f72a4c>" + node + "</#f72a4c> for group <#f72a4c>"
                            + group + "</#f72a4c>.</white>");
                    }
                }
                return true;
            }
        }
        if (operation.equals("meta") && args.length >= 6
            && List.of("prefix", "suffix").contains(args[3].toLowerCase(Locale.ROOT))
            && args[4].equalsIgnoreCase("set")) {
            String value = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
            try {
                MM.deserialize(value);
                groups.set(base + "/meta/" + args[3].toLowerCase(Locale.ROOT), value);
                if (saveGroups(sender)) {
                    refreshAll();
                    success(sender, "<white>Updated <#f72a4c>" + args[3].toLowerCase(Locale.ROOT)
                        + "</#f72a4c> for group <#f72a4c>" + group + "</#f72a4c>.</white>");
                }
            } catch (RuntimeException exception) {
                send(sender, "<white>That MiniMessage value is invalid.</white>");
            }
            return true;
        }
        send(sender, USAGE);
        return true;
    }

    void apply(Player player) {
        PermissionAttachment old = attachments.remove(player.getUniqueId());
        if (old != null) {
            player.removeAttachment(old);
        }
        PermissionAttachment attachment = player.addAttachment(plugin);
        Set<String> candidates = new LinkedHashSet<>();
        plugin.getServer().getPluginManager().getPermissions().stream()
            .map(Permission::getName).forEach(candidates::add);
        Map<String, Boolean> direct = directPermissions(player.getUniqueId());
        candidates.addAll(direct.keySet());
        List<String> assigned = assignedGroups(player.getUniqueId());
        PermissionResolver.memberships(groups, assigned).forEach(group ->
            candidates.addAll(PermissionResolver.groupPermissions(groups, group.name()).keySet()));
        for (String permission : candidates) {
            PermissionResolver.Resolution resolution = PermissionResolver.resolve(groups, assigned,
                direct, permission, paperDefault(player, permission));
            if (resolution.configuredValue() != null) {
                attachment.setPermission(permission, resolution.configuredValue());
            }
        }
        attachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();
        plugin.permissionsChanged(player);
    }

    Component decorateName(Player player, Component name) {
        ChatMetadata metadata = chatMetadata(player.getUniqueId());
        return metadata.prefix().append(name).append(metadata.suffix());
    }

    ChatMetadata chatMetadata(UUID player) {
        List<PermissionResolver.GroupRef> memberships = PermissionResolver.memberships(
            groups, assignedGroups(player));
        return new ChatMetadata(
            ChatModule.safeFormatting(PermissionResolver.effectiveMeta(groups, memberships, "prefix")),
            ChatModule.safeFormatting(PermissionResolver.effectiveMeta(groups, memberships, "suffix")));
    }

    private boolean checkCommand(CommandSender sender, String[] args) {
        if (args.length != 3) {
            send(sender, USAGE);
            return true;
        }
        OfflinePlayer target = player(args[1], sender);
        String node = permission(args[2], sender);
        if (target == null || node == null) {
            return true;
        }
        boolean fallback = paperDefault(target, node);
        PermissionResolver.Resolution resolution = PermissionResolver.resolve(groups,
            assignedGroups(target.getUniqueId()), directPermissions(target.getUniqueId()),
            node, fallback);
        send(sender, "<#f72a4c><bold>Permission check</bold></#f72a4c> <white>" + targetName(target)
            + " → " + node + "</white>");
        send(sender, "<white>Final result:</white> " + (resolution.value()
            ? "<#55ff55>ALLOW</#55ff55>" : "<#ff5555>DENY</#ff5555>"));
        PermissionResolver.Match winner = resolution.winner();
        if (winner == null) {
            send(sender, "<white>Winner:</white> <#f72a4c>Paper default (" + fallback + ")</#f72a4c>");
        } else {
            send(sender, "<white>Winner:</white> <#f72a4c>" + winner.source() + " → "
                + winner.node() + " = " + winner.value() + "</#f72a4c>"
                + (winner.direct() ? " <white>(direct user override)</white>"
                : " <white>(weight " + winner.weight() + (winner.inherited() ? ", inherited" : "") + ")</white>"));
        }
        send(sender, "<white>Direct user override won:</white> <#f72a4c>"
            + (winner != null && winner.direct()) + "</#f72a4c>");
        String memberships = resolution.groups().stream().map(group -> group.name() + "["
                + group.weight() + (group.inherited() ? ", inherited" : "") + "]")
            .reduce((left, right) -> left + ", " + right).orElse("none");
        send(sender, "<white>Groups:</white> <#f72a4c>" + memberships + "</#f72a4c>");
        if (!resolution.groupMatches().isEmpty()) {
            send(sender, "<white>Relevant group rules:</white>");
            resolution.groupMatches().forEach(match -> send(sender, "  <#f72a4c>• " + match.source()
                + " [weight " + match.weight() + (match.inherited() ? ", inherited" : "") + "]</#f72a4c>"
                + " <white>" + match.node() + " = " + match.value() + "</white>"));
        }
        return true;
    }

    private boolean treeCommand(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, USAGE);
            return true;
        }
        OfflinePlayer target = player(args[1], sender);
        if (target != null) {
            send(sender, "<#f72a4c><bold>" + targetName(target) + "'s permission tree</bold></#f72a4c>");
            PermissionResolver.treeLines(groups, assignedGroups(target.getUniqueId()))
                .forEach(line -> send(sender, "<white>" + line + "</white>"));
        }
        return true;
    }

    private boolean listGroups(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String display = groupNames().stream().map(group -> group + "["
                + PermissionResolver.weight(groups, group) + "]")
                .reduce((left, right) -> left + ", " + right).orElse("none");
            send(sender, "<#f72a4c>Groups:</#f72a4c> <white>" + display + "</white>");
        } else {
            send(sender, USAGE);
        }
        return true;
    }

    private boolean reloadCommand(CommandSender sender, String[] args) {
        if (args.length != 1) {
            send(sender, USAGE);
            return true;
        }
        try {
            YamlConfiguration nextGroups = load(groupsFile);
            YamlConfiguration nextUsers = usersFile.isFile() ? load(usersFile) : empty();
            boolean groupsChanged = migrateGroups(nextGroups);
            boolean usersChanged = migrateUsers(nextUsers);
            validate(nextGroups, nextUsers);
            if (groupsChanged) {
                nextGroups.save(groupsFile);
            }
            if (usersChanged) {
                nextUsers.save(usersFile);
            }
            groups = nextGroups;
            users = nextUsers;
            refreshAll();
            success(sender, "<white>Permissions reloaded.</white>");
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            plugin.getLogger().severe("Permission reload failed: " + exception.getMessage());
            send(sender, "<white>Permission reload failed; existing values remain active. Check the console.</white>");
        }
        return true;
    }

    void reloadConfiguration() {
        groups = plugin.settings("permissions");
        groups.options().pathSeparator('/');
        try {
            boolean changed = migrateGroups(groups);
            validate(groups, users);
            if (changed) {
                groups.save(groupsFile);
            }
            refreshAll();
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not apply reloaded permissions: " + exception.getMessage());
        }
    }

    private boolean saveUsers(CommandSender sender) {
        try {
            users.save(usersFile);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/permissions.yml: " + exception.getMessage());
            send(sender, "<white>Permission changed for this session, but could not be saved. Check the console.");
            return false;
        }
    }

    private boolean saveGroups(CommandSender sender) {
        try {
            groups.save(groupsFile);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save settings/permissions.yml: " + exception.getMessage());
            send(sender, "<white>Permission changed for this session, but could not be saved. Check the console.</white>");
            return false;
        }
    }

    private OfflinePlayer player(String name, CommandSender sender) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            send(sender, "<white>Use a valid player name.");
            return null;
        }
        return plugin.getServer().getOfflinePlayer(name);
    }

    private String permission(String value, CommandSender sender) {
        String permission = value.toLowerCase(Locale.ROOT);
        if (!PermissionResolver.validPermission(permission)) {
            send(sender, "<white>Use a valid permission node.");
            return null;
        }
        return permission;
    }

    private String group(String value, CommandSender sender) {
        String group = value.toLowerCase(Locale.ROOT);
        if (!PermissionResolver.hasGroup(groups, group)) {
            send(sender, "<white>Unknown group.");
            return null;
        }
        return group;
    }

    private String userPath(OfflinePlayer player) {
        String path = userBase(player.getUniqueId());
        users.set(path + "/name", player.getName());
        return path;
    }

    private List<String> playerNames() {
        Set<String> names = new LinkedHashSet<>();
        plugin.getServer().getOnlinePlayers().stream().map(Player::getName).forEach(names::add);
        ConfigurationSection section = users.getConfigurationSection("users");
        if (section != null) {
            section.getKeys(false).stream().map(uuid -> users.getString("users/" + uuid + "/name"))
                .filter(java.util.Objects::nonNull).forEach(names::add);
        }
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> permissionNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add("*");
        plugin.getServer().getPluginManager().getPermissions().stream().map(Permission::getName)
            .forEach(names::add);
        groupNames().forEach(group -> names.addAll(PermissionResolver.groupPermissions(groups, group).keySet()));
        ConfigurationSection section = users.getConfigurationSection("users");
        if (section != null) {
            section.getKeys(false).forEach(uuid -> names.addAll(directPermissions(UUID.fromString(uuid)).keySet()));
        }
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> groupNames() {
        ConfigurationSection section = groups.getConfigurationSection("groups");
        return section == null ? List.of() : section.getKeys(false).stream().sorted().toList();
    }

    private void userInfo(CommandSender sender, OfflinePlayer target) {
        List<PermissionResolver.GroupRef> memberships = PermissionResolver.memberships(groups,
            assignedGroups(target.getUniqueId()));
        send(sender, "<#f72a4c><bold>" + targetName(target) + "</bold></#f72a4c>");
        send(sender, "<white>Groups:</white> <#f72a4c>" + memberships.stream()
            .map(group -> group.name() + "[" + group.weight()
                + (group.inherited() ? ", inherited" : "") + "]")
            .reduce((left, right) -> left + ", " + right).orElse("none") + "</#f72a4c>");
        send(sender, "<white>Direct permissions:</white> <#f72a4c>"
            + formatPermissions(directPermissions(target.getUniqueId())) + "</#f72a4c>");
        Component prefix = ChatModule.safeFormatting(
            PermissionResolver.effectiveMeta(groups, memberships, "prefix"));
        Component suffix = ChatModule.safeFormatting(
            PermissionResolver.effectiveMeta(groups, memberships, "suffix"));
        sender.sendMessage(MM.deserialize("<white>Effective metadata:</white> ")
            .append(prefix).append(Component.text(targetName(target))).append(suffix));
    }

    private void groupInfo(CommandSender sender, String group) {
        String base = PermissionResolver.groupPath(group);
        send(sender, "<#f72a4c><bold>" + group + "</bold></#f72a4c> <white>weight:</white> <#f72a4c>"
            + PermissionResolver.weight(groups, group) + "</#f72a4c>");
        send(sender, "<white>Parents:</white> <#f72a4c>"
            + String.join(", ", PermissionResolver.parents(groups, group)) + "</#f72a4c>");
        send(sender, "<white>Permissions:</white> <#f72a4c>"
            + formatPermissions(PermissionResolver.groupPermissions(groups, group)) + "</#f72a4c>");
        sender.sendMessage(MM.deserialize("<white>Prefix:</white> ")
            .append(ChatModule.safeFormatting(groups.getString(base + "/meta/prefix", ""))));
        sender.sendMessage(MM.deserialize("<white>Suffix:</white> ")
            .append(ChatModule.safeFormatting(groups.getString(base + "/meta/suffix", ""))));
    }

    private void groupMembers(CommandSender sender, String group) {
        ConfigurationSection section = users.getConfigurationSection("users");
        List<String> members = section == null ? List.of() : section.getKeys(false).stream()
            .filter(uuid -> assignedGroups(UUID.fromString(uuid)).contains(group))
            .map(uuid -> users.getString("users/" + uuid + "/name", uuid)).sorted().toList();
        send(sender, "<#f72a4c>" + group + " members:</#f72a4c> <white>"
            + (members.isEmpty() ? "none" : String.join(", ", members)) + "</white>");
    }

    private List<String> assignedGroups(UUID player) {
        List<String> assigned = users.getStringList(userBase(player) + "/groups").stream()
            .map(PermissionResolver::normalize).filter(PermissionResolver::validGroupName)
            .distinct().toList();
        return assigned.isEmpty() && PermissionResolver.hasGroup(groups, "default")
            ? List.of("default") : assigned;
    }

    private Map<String, Boolean> directPermissions(UUID player) {
        return PermissionResolver.permissions(users, userBase(player) + "/permissions");
    }

    private boolean paperDefault(OfflinePlayer player, String node) {
        Permission permission = plugin.getServer().getPluginManager().getPermission(node);
        return permission != null && permission.getDefault().getValue(player.isOp());
    }

    private void refresh(OfflinePlayer player) {
        if (player.isOnline() && player.getPlayer() != null) {
            apply(player.getPlayer());
        }
    }

    private void refreshAll() {
        plugin.getServer().getOnlinePlayers().forEach(this::apply);
    }

    private void createGroup(String group) {
        String base = PermissionResolver.groupPath(group);
        groups.createSection(base);
        groups.set(base + "/weight", 0);
        groups.set(base + "/parents", List.of());
        groups.createSection(base + "/permissions");
        groups.set(base + "/meta/prefix", "");
        groups.set(base + "/meta/suffix", "");
    }

    private void deleteGroup(String deleted) {
        groups.set(PermissionResolver.groupPath(deleted), null);
        groupNames().forEach(group -> groups.set(PermissionResolver.groupPath(group) + "/parents",
            PermissionResolver.parents(groups, group).stream().filter(parent -> !parent.equals(deleted)).toList()));
        ConfigurationSection section = users.getConfigurationSection("users");
        if (section != null) {
            section.getKeys(false).forEach(uuid -> {
                String path = "users/" + uuid + "/groups";
                List<String> remaining = users.getStringList(path).stream()
                    .filter(group -> !group.equalsIgnoreCase(deleted)).toList();
                users.set(path, remaining.isEmpty() ? List.of("default") : remaining);
            });
        }
    }

    private static ConfigurationSection permissionSection(YamlConfiguration configuration,
                                                           String path) {
        ConfigurationSection section = configuration.getConfigurationSection(path);
        return section == null ? configuration.createSection(path) : section;
    }

    private static String formatPermissions(Map<String, Boolean> permissions) {
        return permissions.isEmpty() ? "none" : permissions.entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce((left, right) -> left + ", " + right).orElse("none");
    }

    private static String changedMessage(String player, String node, boolean state) {
        return "<white>Set <#f72a4c>" + node + "</#f72a4c> to <#f72a4c>" + state
            + "</#f72a4c> for <#f72a4c>" + player + "</#f72a4c>.</white>";
    }

    private Boolean state(String value, CommandSender sender) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        send(sender, "<white>Permission state must be true or false.</white>");
        return null;
    }

    private static String userBase(UUID player) {
        return "users/" + player;
    }

    private static String targetName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    static boolean migrateGroups(YamlConfiguration configuration) {
        configuration.options().pathSeparator('/');
        ConfigurationSection section = configuration.getConfigurationSection("groups");
        if (section == null) {
            return false;
        }
        boolean changed = false;
        for (String group : section.getKeys(false)) {
            String base = PermissionResolver.groupPath(group);
            if (!configuration.isInt(base + "/weight")) {
                configuration.set(base + "/weight", switch (group.toLowerCase(Locale.ROOT)) {
                    case "staff" -> 50;
                    case "admin" -> 100;
                    default -> 0;
                });
                changed = true;
            }
            List<String> parents = new ArrayList<>(configuration.getStringList(base + "/parents"));
            String legacyParent = configuration.getString(base + "/parent");
            if (legacyParent != null && !legacyParent.isBlank() && !parents.contains(legacyParent)) {
                parents.add(legacyParent);
            }
            if (configuration.contains(base + "/parent")) {
                configuration.set(base + "/parent", null);
                changed = true;
            }
            if (!configuration.contains(base + "/parents") || !parents.equals(
                configuration.getStringList(base + "/parents"))) {
                configuration.set(base + "/parents", parents);
                changed = true;
            }
            changed |= normalizePermissionSection(configuration, base + "/permissions");
            if (!configuration.isString(base + "/meta/prefix")) {
                configuration.set(base + "/meta/prefix", "");
                changed = true;
            }
            if (!configuration.isString(base + "/meta/suffix")) {
                configuration.set(base + "/meta/suffix", "");
                changed = true;
            }
        }
        return changed;
    }

    static boolean migrateUsers(YamlConfiguration configuration) {
        configuration.options().pathSeparator('/');
        ConfigurationSection section = configuration.getConfigurationSection("users");
        if (section == null) {
            return false;
        }
        boolean changed = false;
        for (String uuid : section.getKeys(false)) {
            String base = "users/" + uuid;
            List<String> groups = new ArrayList<>(configuration.getStringList(base + "/groups"));
            String legacyGroup = configuration.getString(base + "/group");
            if (legacyGroup != null && !legacyGroup.isBlank() && !groups.contains(legacyGroup)) {
                groups.add(legacyGroup);
            }
            if (groups.isEmpty()) {
                groups.add("default");
            }
            if (configuration.contains(base + "/group")) {
                configuration.set(base + "/group", null);
                changed = true;
            }
            if (!groups.equals(configuration.getStringList(base + "/groups"))) {
                configuration.set(base + "/groups", groups);
                changed = true;
            }
            changed |= normalizePermissionSection(configuration, base + "/permissions");
        }
        return changed;
    }

    private static boolean normalizePermissionSection(YamlConfiguration configuration,
                                                      String path) {
        Object raw = configuration.get(path);
        Map<String, Boolean> normalized = new LinkedHashMap<>();
        boolean legacy = raw instanceof List<?>;
        if (raw instanceof List<?> list) {
            list.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(PermissionResolver::normalize).filter(PermissionResolver::validPermission)
                .forEach(node -> normalized.put(node, true));
        } else {
            ConfigurationSection section = configuration.getConfigurationSection(path);
            if (section != null) {
                section.getValues(true).forEach((node, value) -> {
                    if (value instanceof Boolean state) {
                        normalized.put(node.replace('/', '.'), state);
                    }
                });
                legacy = section.getValues(true).entrySet().stream()
                    .anyMatch(entry -> entry.getValue() instanceof ConfigurationSection
                        || entry.getKey().contains("/"));
            }
        }
        if (raw == null) {
            configuration.createSection(path);
            return true;
        }
        if (!legacy) {
            return false;
        }
        configuration.set(path, null);
        ConfigurationSection section = configuration.createSection(path);
        normalized.forEach(section::set);
        return true;
    }

    static void validate(YamlConfiguration groups, YamlConfiguration users)
        throws org.bukkit.configuration.InvalidConfigurationException {
        ConfigurationSection groupSection = groups.getConfigurationSection("groups");
        if (groupSection == null || !PermissionResolver.hasGroup(groups, "default")) {
            throw new org.bukkit.configuration.InvalidConfigurationException(
                "settings/permissions.yml must contain groups/default");
        }
        for (String group : groupSection.getKeys(false)) {
            if (!PermissionResolver.validGroupName(group)) {
                throw new org.bukkit.configuration.InvalidConfigurationException(
                    "Invalid permission group name: " + group);
            }
            String base = PermissionResolver.groupPath(group);
            if (!groups.isInt(base + "/weight")) {
                throw new org.bukkit.configuration.InvalidConfigurationException(
                    group + " weight must be a whole number");
            }
            for (String parent : PermissionResolver.parents(groups, group)) {
                if (!PermissionResolver.hasGroup(groups, parent)) {
                    throw new org.bukkit.configuration.InvalidConfigurationException(
                        group + " has unknown parent " + parent);
                }
                if (PermissionResolver.wouldCreateCycle(groups, group, parent)) {
                    throw new org.bukkit.configuration.InvalidConfigurationException(
                        "Permission inheritance cycle involving " + group + " and " + parent);
                }
            }
            validatePermissionSection(groups, base + "/permissions");
        }
        ConfigurationSection userSection = users.getConfigurationSection("users");
        if (userSection != null) {
            for (String uuid : userSection.getKeys(false)) {
                try {
                    UUID.fromString(uuid);
                } catch (IllegalArgumentException exception) {
                    throw new org.bukkit.configuration.InvalidConfigurationException(
                        "Invalid permission user UUID: " + uuid);
                }
                validatePermissionSection(users, "users/" + uuid + "/permissions");
            }
        }
    }

    private static void validatePermissionSection(YamlConfiguration configuration, String path)
        throws org.bukkit.configuration.InvalidConfigurationException {
        ConfigurationSection section = configuration.getConfigurationSection(path);
        if (section == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : section.getValues(true).entrySet()) {
            if (entry.getValue() instanceof ConfigurationSection) {
                continue;
            }
            String node = entry.getKey().replace('/', '.');
            if (!PermissionResolver.validPermission(node) || !(entry.getValue() instanceof Boolean)) {
                throw new org.bukkit.configuration.InvalidConfigurationException(
                    path + "/" + node + " must be true or false");
            }
        }
    }

    private static YamlConfiguration load(File file)
        throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        YamlConfiguration configuration = empty();
        configuration.load(file);
        return configuration;
    }

    private static YamlConfiguration empty() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().pathSeparator('/');
        return configuration;
    }

    private void success(CommandSender sender, String message) {
        send(sender, message);
        if (sender instanceof Player player && plugin.getConfig().getBoolean("effects.sounds")) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f);
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(MM.deserialize(message));
    }
}
