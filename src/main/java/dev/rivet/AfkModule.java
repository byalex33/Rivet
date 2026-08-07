package dev.rivet;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class AfkModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final Set<UUID> afk = new HashSet<>();
    private final Map<UUID, Long> lastActivity = new HashMap<>();
    private final BukkitTask task;

    AfkModule(RivetPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getOnlinePlayers().forEach(player ->
            lastActivity.put(player.getUniqueId(), System.currentTimeMillis()));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkAutomatic, 20, 20);
    }

    boolean command(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<red>Usage: /afk"));
            return true;
        }
        set(player, !isAfk(player.getUniqueId()));
        return true;
    }

    boolean isAfk(UUID player) {
        return afk.contains(player);
    }

    Component indicator() {
        return MM.deserialize(plugin.settings("afk").getString("indicator.format", " <gray>[AFK]</gray>"));
    }

    boolean showIndicator() {
        return plugin.settings("afk").getBoolean("indicator.enabled", true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        afk.remove(id);
        lastActivity.remove(id);
        event.getPlayer().setSleepingIgnored(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (DelayedTeleport.moved(event.getFrom(), event.getTo())) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInput(PlayerInputEvent event) {
        if (event.getInput().isForward() || event.getInput().isBackward()
            || event.getInput().isLeft() || event.getInput().isRight()
            || event.getInput().isJump() || event.getInput().isSneak()
            || event.getInput().isSprint()) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().equalsIgnoreCase("/afk")) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> activity(event.getPlayer()));
    }

    void shutdown() {
        task.cancel();
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            player.setSleepingIgnored(false);
            if (afk.contains(player.getUniqueId())) {
                afk.remove(player.getUniqueId());
                plugin.refreshDisplayName(player);
            }
        });
        lastActivity.clear();
    }

    private void activity(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (isAfk(player.getUniqueId())) {
            set(player, false);
        }
    }

    private void checkAutomatic() {
        long minutes = Math.max(0, plugin.settings("afk").getLong("automatic-after-minutes", 10));
        if (minutes == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        plugin.getServer().getOnlinePlayers().stream()
            .filter(player -> !isAfk(player.getUniqueId()))
            .filter(player -> shouldAutoAfk(lastActivity.getOrDefault(player.getUniqueId(), now),
                minutes * 60_000L, now))
            .forEach(player -> set(player, true));
    }

    private void set(Player player, boolean value) {
        if (value) {
            afk.add(player.getUniqueId());
        } else {
            afk.remove(player.getUniqueId());
            lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        }
        player.setSleepingIgnored(value
            && plugin.settings("afk").getBoolean("exclude-from-sleep", true));
        plugin.refreshDisplayName(player);
        String path = value ? "messages.afk" : "messages.return";
        String fallback = value ? "<yellow><player> is now AFK.</yellow>"
            : "<green><player> is no longer AFK.</green>";
        Component message = MM.deserialize(plugin.settings("afk").getString(path, fallback),
            Placeholder.unparsed("player", player.getName()));
        plugin.getServer().broadcast(message);
    }

    static boolean shouldAutoAfk(long lastActivity, long thresholdMillis, long now) {
        return thresholdMillis > 0 && now - lastActivity >= thresholdMillis;
    }
}
