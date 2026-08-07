package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class StaffTools implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;
    private final Set<UUID> god = new HashSet<>();

    StaffTools(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("staff");
        data = plugin.data("staff");
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
        feedback(actor, target, "heal", "<green>Healed <white><target></white>.");
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
        feedback(actor, target, "feed", "<green>Fed <white><target></white>.");
        return true;
    }

    boolean god(Player actor, String[] args) {
        Player target = target(actor, args, "rivet.god.others", "/god [player]");
        if (target == null) {
            return true;
        }
        boolean enabled = !god.remove(target.getUniqueId());
        if (enabled) {
            god.add(target.getUniqueId());
        }
        target.setInvulnerable(enabled);
        if (persistent() && !save()) {
            if (enabled) {
                god.remove(target.getUniqueId());
            } else {
                god.add(target.getUniqueId());
            }
            data.set("god-players", god.stream().map(UUID::toString).sorted().toList());
            target.setInvulnerable(!enabled);
            actor.sendMessage(MM.deserialize("<red>God mode could not be saved safely.</red>"));
            return true;
        }
        String fallback = enabled ? "<green>God mode enabled for <white><target></white>."
            : "<yellow>God mode disabled for <white><target></white>.";
        actor.sendMessage(MM.deserialize(settings.getString("messages.god-" +
                (enabled ? "enabled" : "disabled"), fallback),
            Placeholder.unparsed("target", target.getName())));
        if (actor != target) {
            target.sendMessage(MM.deserialize(settings.getString("messages.god-target-" +
                (enabled ? "enabled" : "disabled"), enabled
                ? "<green>God mode was enabled.</green>" : "<yellow>God mode was disabled.</yellow>")));
        }
        return true;
    }

    List<String> completions(Player actor, String[] args, String othersPermission) {
        return args.length == 1 && actor.hasPermission(othersPermission)
            ? plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList()
            : List.of();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        boolean enabled = persistent() && god.contains(event.getPlayer().getUniqueId());
        event.getPlayer().setInvulnerable(enabled);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.getPlayer().setInvulnerable(false);
        if (!persistent()) {
            god.remove(event.getPlayer().getUniqueId());
        }
    }

    void shutdown() {
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
            actor.sendMessage(MM.deserialize("<red>Usage: " + usage));
            return null;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !actor.canSee(target)) {
            actor.sendMessage(MM.deserialize("<red>That player is not online.</red>"));
            return null;
        }
        return target;
    }

    private void feedback(Player actor, Player target, String key, String fallback) {
        actor.sendMessage(MM.deserialize(settings.getString("messages." + key, fallback),
            Placeholder.unparsed("target", target.getName())));
        if (actor != target) {
            target.sendMessage(MM.deserialize(settings.getString("messages." + key + "-target",
                "<green>You were restored by <white><player></white>."),
                Placeholder.unparsed("player", actor.getName())));
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
}
