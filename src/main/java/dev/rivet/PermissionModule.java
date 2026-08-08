package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PermissionModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
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
        reload();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        attachments.remove(event.getPlayer().getUniqueId());
    }

    boolean command(CommandSender sender, String command, String[] args) {
        return command.equals("perm") ? permissionCommand(sender, args) : groupCommand(sender, args);
    }

    List<String> completions(String command, String[] args) {
        if (args.length == 1) {
            return command.equals("perm")
                ? List.of("add", "check", "list", "reload", "remove")
                : List.of("info", "list", "set");
        }
        if (command.equals("perm")) {
            if (args.length == 2 && List.of("add", "check", "list").contains(args[0].toLowerCase(Locale.ROOT))) {
                return playerNames();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("remove")
                || args.length == 3 && List.of("add", "check").contains(args[0].toLowerCase(Locale.ROOT))) {
                return permissionNames();
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("remove")) {
                return playerNames();
            }
        } else {
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                return playerNames();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("info")
                || args.length == 3 && args[0].equalsIgnoreCase("set")) {
                return groupNames();
            }
        }
        return List.of();
    }

    private boolean permissionCommand(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reload();
            plugin.getServer().getOnlinePlayers().forEach(this::apply);
            success(sender, "<white>Permissions reloaded.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            OfflinePlayer target = player(args[1], sender);
            if (target != null) {
                Set<String> permissions = effective(target.getUniqueId());
                send(sender, "<#f72a4c>" + args[1] + "'s permissions:</#f72a4c> <#f72a4c>"
                    + (permissions.isEmpty() ? "none" : String.join(", ", permissions)) + "</#f72a4c>");
            }
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("check")) {
            OfflinePlayer target = player(args[1], sender);
            String permission = permission(args[2], sender);
            if (target != null && permission != null) {
                boolean granted = target.isOnline()
                    ? target.getPlayer().hasPermission(permission)
                    : grants(effective(target.getUniqueId()), permission);
                send(sender, granted ? "<white>Granted." : "<white>Not granted.");
            }
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            OfflinePlayer target = player(args[1], sender);
            String permission = permission(args[2], sender);
            if (target != null && permission != null) {
                String path = userPath(target) + ".permissions";
                List<String> permissions = new ArrayList<>(users.getStringList(path));
                if (!permissions.contains(permission)) {
                    permissions.add(permission);
                    users.set(path, permissions);
                    if (!saveUsers(sender)) {
                        refresh(target);
                        return true;
                    }
                }
                refresh(target);
                success(sender, "<white>Added <#f72a4c>" + permission + "</#f72a4c> to <#f72a4c>" + args[1] + "</#f72a4c>.");
            }
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("remove")) {
            String permission = permission(args[1], sender);
            OfflinePlayer target = player(args[2], sender);
            if (target != null && permission != null) {
                String path = userPath(target) + ".permissions";
                List<String> permissions = new ArrayList<>(users.getStringList(path));
                permissions.remove(permission);
                users.set(path, permissions);
                if (!saveUsers(sender)) {
                    refresh(target);
                    return true;
                }
                refresh(target);
                success(sender, "<white>Removed <#f72a4c>" + permission + "</#f72a4c> from <#f72a4c>" + args[2] + "</#f72a4c>.");
            }
            return true;
        }
        send(sender, "<white>Usage: /perm <add user permission|remove permission user|list user|check user permission|reload>");
        return true;
    }

    private boolean groupCommand(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            send(sender, "<#f72a4c>Groups:</#f72a4c> <#f72a4c>" + String.join(", ", groupNames()) + "</#f72a4c>");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            String group = group(args[1], sender);
            if (group != null) {
                String parent = groups.getString("groups." + group + ".parent", "none");
                send(sender, "<#f72a4c>" + group + "</#f72a4c> <white>parent:</white> <#f72a4c>" + parent
                    + "</#f72a4c> <white>permissions:</white> <#f72a4c>"
                    + String.join(", ", groupPermissions(groups, group)) + "</#f72a4c>");
            }
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            OfflinePlayer target = player(args[1], sender);
            String group = group(args[2], sender);
            if (target != null && group != null) {
                users.set(userPath(target) + ".group", group);
                if (!saveUsers(sender)) {
                    refresh(target);
                    return true;
                }
                refresh(target);
                success(sender, "<white>Set <#f72a4c>" + args[1] + "</#f72a4c> to group <#f72a4c>" + group + "</#f72a4c>.");
            }
            return true;
        }
        send(sender, "<white>Usage: /group <set user group|list|info group>");
        return true;
    }

    void apply(Player player) {
        PermissionAttachment old = attachments.remove(player.getUniqueId());
        if (old != null) {
            player.removeAttachment(old);
        }
        PermissionAttachment attachment = player.addAttachment(plugin);
        Set<String> granted = effective(player.getUniqueId());
        granted.forEach(permission -> attachment.setPermission(permission, true));
        plugin.getServer().getPluginManager().getPermissions().stream()
            .map(Permission::getName).filter(permission -> grants(granted, permission))
            .forEach(permission -> attachment.setPermission(permission, true));
        attachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();
        plugin.permissionsChanged(player);
    }

    private Set<String> effective(UUID player) {
        String path = "users." + player;
        String group = users.getString(path + ".group", "default");
        if (!groups.isConfigurationSection("groups." + group)) {
            group = "default";
        }
        Set<String> permissions = groupPermissions(groups, group);
        permissions.addAll(users.getStringList(path + ".permissions"));
        return permissions;
    }

    static Set<String> groupPermissions(YamlConfiguration groups, String group) {
        Set<String> permissions = new HashSet<>();
        Set<String> visited = new HashSet<>();
        while (group != null && visited.add(group)) {
            permissions.addAll(groups.getStringList("groups." + group + ".permissions"));
            group = groups.getString("groups." + group + ".parent");
        }
        return permissions;
    }

    static boolean grants(Set<String> permissions, String requested) {
        return permissions.contains(requested) || permissions.contains("*")
            || permissions.stream().filter(permission -> permission.endsWith(".*"))
                .map(permission -> permission.substring(0, permission.length() - 1))
                .anyMatch(requested::startsWith);
    }

    private void refresh(OfflinePlayer player) {
        if (player.isOnline()) {
            apply(player.getPlayer());
        }
    }

    private void reload() {
        groups = YamlConfiguration.loadConfiguration(groupsFile);
        users = YamlConfiguration.loadConfiguration(usersFile);
    }

    void reloadConfiguration() {
        groups = plugin.settings("permissions");
        plugin.getServer().getOnlinePlayers().forEach(this::apply);
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

    private OfflinePlayer player(String name, CommandSender sender) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            send(sender, "<white>Use a valid player name.");
            return null;
        }
        return plugin.getServer().getOfflinePlayer(name);
    }

    private String permission(String value, CommandSender sender) {
        String permission = value.toLowerCase(Locale.ROOT);
        if (!permission.matches("(\\*|[a-z0-9_.-]+(?:\\.\\*)?)")) {
            send(sender, "<white>Use a valid permission node.");
            return null;
        }
        return permission;
    }

    private String group(String value, CommandSender sender) {
        String group = value.toLowerCase(Locale.ROOT);
        if (!groups.isConfigurationSection("groups." + group)) {
            send(sender, "<white>Unknown group.");
            return null;
        }
        return group;
    }

    private String userPath(OfflinePlayer player) {
        String path = "users." + player.getUniqueId();
        users.set(path + ".name", player.getName());
        return path;
    }

    private List<String> playerNames() {
        return plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> permissionNames() {
        return plugin.getServer().getPluginManager().getPermissions().stream().map(Permission::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> groupNames() {
        ConfigurationSection section = groups.getConfigurationSection("groups");
        return section == null ? List.of() : section.getKeys(false).stream().sorted().toList();
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
