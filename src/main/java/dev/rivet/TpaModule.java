package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TpaModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final DelayedTeleport teleports;
    private final Map<UUID, List<Request>> requests = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final BukkitTask cleanupTask;

    TpaModule(RivetPlugin plugin, DelayedTeleport teleports) {
        this.plugin = plugin;
        this.teleports = teleports;
        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
            () -> prune(System.currentTimeMillis(), true), 20, 20);
    }

    boolean command(Player player, String command, String[] args) {
        return switch (command) {
            case "tpa" -> request(player, args, false);
            case "tpahere" -> request(player, args, true);
            case "tpaccept" -> answer(player, args, true);
            default -> answer(player, args, false);
        };
    }

    List<String> completions(Player player, String command, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        if (command.equals("tpaccept") || command.equals("tpdeny")) {
            prune(System.currentTimeMillis(), false);
            return requests.getOrDefault(player.getUniqueId(), List.of()).stream()
                .map(request -> request.requester.getName()).distinct().sorted().toList();
        }
        return plugin.getServer().getOnlinePlayers().stream().filter(target -> !target.equals(player))
            .filter(player::canSee).map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        requests.remove(player);
        requests.values().forEach(list -> list.removeIf(request -> request.requester.getUniqueId().equals(player)));
        requests.values().removeIf(List::isEmpty);
        cooldowns.remove(player);
    }

    void shutdown() {
        cleanupTask.cancel();
        requests.clear();
        cooldowns.clear();
    }

    private boolean request(Player requester, String[] args, boolean here) {
        if (args.length != 1) {
            send(requester, "usage-request", "<white>Usage: /" + (here ? "tpahere" : "tpa") + " &lt;player&gt;");
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !requester.canSee(target)) {
            send(requester, "not-online", "<white>That player is not online.");
            return true;
        }
        if (target.equals(requester)) {
            send(requester, "self", "<white>You cannot send a teleport request to yourself.");
            return true;
        }
        long now = System.currentTimeMillis();
        long remaining = cooldownRemaining(cooldowns.getOrDefault(requester.getUniqueId(), 0L),
            setting("cooldown-seconds", 30), now);
        if (remaining > 0 && !requester.hasPermission("rivet.tp.nocooldown")) {
            send(requester, "cooldown", "<white>Wait %seconds% seconds before sending another request.",
                Placeholder.unparsed("seconds", Long.toString(remaining)));
            return true;
        }
        prune(now, true);
        List<Request> incoming = requests.computeIfAbsent(target.getUniqueId(), ignored -> new ArrayList<>());
        incoming.removeIf(request -> request.requester.equals(requester));
        incoming.add(new Request(requester, target, here, now));
        if (!requester.hasPermission("rivet.tp.nocooldown")) {
            cooldowns.put(requester.getUniqueId(), now);
        }
        send(requester, "sent", "<white>Teleport request sent to <#f72a4c>%player%</#f72a4c>.", player(target));
        send(target, here ? "received-here" : "received",
            here
                ? "<white>%player% wants you to teleport to them. Use <#f72a4c>/tpaccept %player%</#f72a4c>."
                : "<white>%player% wants to teleport to you. Use <#f72a4c>/tpaccept %player%</#f72a4c>.",
            player(requester));
        return true;
    }

    private boolean answer(Player target, String[] args, boolean accept) {
        if (args.length > 1) {
            send(target, "usage-answer", "<white>Usage: /" + (accept ? "tpaccept" : "tpdeny") + " [player]");
            return true;
        }
        prune(System.currentTimeMillis(), true);
        List<Request> incoming = requests.getOrDefault(target.getUniqueId(), List.of());
        Request request = null;
        if (args.length == 1) {
            request = incoming.stream().filter(candidate ->
                candidate.requester.getName().equalsIgnoreCase(args[0])).findFirst().orElse(null);
        } else if (incoming.size() == 1) {
            request = incoming.getFirst();
        } else if (incoming.size() > 1) {
            send(target, "multiple", "<white>You have multiple requests: <#f72a4c>%players%</#f72a4c>. Specify a player.",
                Placeholder.unparsed("players", incoming.stream().map(candidate -> candidate.requester.getName())
                    .distinct().sorted().collect(java.util.stream.Collectors.joining(", "))));
            return true;
        }
        if (request == null) {
            send(target, "none", "<white>No matching teleport request was found.");
            return true;
        }
        if (!accept) {
            incoming.remove(request);
            if (incoming.isEmpty()) {
                requests.remove(target.getUniqueId());
            }
            send(target, "denied", "<white>Denied <#f72a4c>%player%</#f72a4c>'s request.", player(request.requester));
            send(request.requester, "denied-requester", "<white>%player% denied your teleport request.", player(target));
            return true;
        }
        Player moving = request.here ? target : request.requester;
        Player destinationPlayer = request.here ? request.requester : target;
        Location destination = destinationPlayer.getLocation().clone();
        if (!teleports.start(moving, destination, () -> {
            send(moving, "teleported", "<white>Teleport complete.");
            plugin.teleportFeedback(moving, "Teleport");
        })) {
            return true;
        }
        incoming.remove(request);
        if (incoming.isEmpty()) {
            requests.remove(target.getUniqueId());
        }
        send(target, "accepted", "<white>Accepted <#f72a4c>%player%</#f72a4c>'s request.", player(request.requester));
        send(request.requester, "accepted-requester", "<white>%player% accepted your teleport request.", player(target));
        return true;
    }

    private void prune(long now, boolean notify) {
        long timeout = setting("request-expiry-seconds", 60) * 1000L;
        requests.values().forEach(list -> list.removeIf(request -> {
            if (!expired(request.createdAt, timeout, now)) {
                return false;
            }
            if (notify) {
                send(request.requester, "expired", "<white>Your teleport request to <#f72a4c>%player%</#f72a4c> expired.",
                    player(request.target));
            }
            return true;
        }));
        requests.values().removeIf(List::isEmpty);
    }

    private int setting(String path, int fallback) {
        return Math.max(0, plugin.settings("tpa").getInt(path, fallback));
    }

    private void send(Player recipient, String path, String fallback,
                      net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... placeholders) {
        plugin.messageActions().run(recipient, plugin.settings("tpa"), "messages." + path,
            fallback, placeholders);
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver player(Player player) {
        return Placeholder.unparsed("player", player.getName());
    }

    static boolean expired(long createdAt, long timeoutMillis, long now) {
        return timeoutMillis > 0 && now - createdAt >= timeoutMillis;
    }

    static long cooldownRemaining(long lastUse, long cooldownSeconds, long now) {
        return Math.max(0, (lastUse + cooldownSeconds * 1000L - now + 999) / 1000);
    }

    private record Request(Player requester, Player target, boolean here, long createdAt) {
    }
}
