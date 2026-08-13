package dev.rivet;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class GuiActions {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final Set<ActiveBar> bossBars = new HashSet<>();
    private final Map<NamespacedKey, ActiveToast> toasts = new HashMap<>();

    GuiActions(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    void run(Player player, List<String> configuredActions) {
        for (String configured : configuredActions) {
            Action action = parseAction(configured);
            if (action == null) {
                plugin.getLogger().warning("Invalid GUI action '" + configured + "'.");
                continue;
            }
            switch (action.tag()) {
                case "message" -> player.sendMessage(MM.deserialize(action.value()));
                case "sound" -> sound(player, action.value());
                case "toast" -> toast(player, action.value());
                case "actionbar", "action-bar" -> player.sendActionBar(MM.deserialize(action.value()));
                case "particle" -> particle(player, action.value());
                case "title" -> title(player, action.value());
                case "bossbar", "boss-bar" -> bossBar(player, action.value());
                case "lightning" -> player.getWorld().strikeLightningEffect(player.getLocation());
                case "close" -> player.closeInventory();
                default -> plugin.getLogger().warning("Unknown GUI action tag '[" + action.tag() + "]'.");
            }
        }
    }

    void shutdown() {
        new ArrayList<>(bossBars).forEach(this::remove);
        new HashMap<>(toasts).forEach((key, toast) -> cleanupToast(key, toast));
    }

    private void sound(Player player, String configured) {
        Sound sound = ConfiguredEffect.resolveSound(configured);
        if (sound == null) {
            warn("sound", configured);
            return;
        }
        player.playSound(player.getLocation(), sound, 1, 1);
    }

    private void particle(Player player, String configured) {
        String[] arguments = arguments(configured);
        if (arguments.length == 0) {
            warn("particle", configured);
            return;
        }
        NamespacedKey key = namespaced(arguments[0]);
        Particle particle = key == null ? null : Registry.PARTICLE_TYPE.get(key);
        int count = 20;
        try {
            if (arguments.length > 1) {
                count = Integer.parseInt(arguments[1]);
            }
        } catch (NumberFormatException exception) {
            warn("particle", configured);
            return;
        }
        if (particle == null || particle.getDataType() != Void.class || count < 1 || count > 1_000) {
            warn("particle", configured);
            return;
        }
        player.spawnParticle(particle, player.getLocation().add(0, 1, 0), count, .35, .5, .35, .02);
    }

    private void title(Player player, String configured) {
        String[] parts = configured.split("\\|", -1);
        Component title = MM.deserialize(parts.length == 0 ? "" : parts[0].trim());
        Component subtitle = MM.deserialize(parts.length < 2 ? "" : parts[1].trim());
        int fadeIn = positiveInt(parts, 2, 10);
        int stay = positiveInt(parts, 3, 50);
        int fadeOut = positiveInt(parts, 4, 10);
        player.showTitle(Title.title(title, subtitle, Title.Times.times(
            ticks(fadeIn), ticks(stay), ticks(fadeOut))));
    }

    private void bossBar(Player player, String configured) {
        StaffTools.BossBarArguments parsed = StaffTools.parseBossBarArguments(arguments(configured),
            5, "purple", "solid");
        if (!parsed.valid()) {
            warn("bossbar", configured);
            return;
        }
        BossBar bar = BossBar.bossBar(MM.deserialize(parsed.message()), 1,
            parsed.color(), parsed.overlay());
        ActiveBar active = new ActiveBar(player, bar);
        bossBars.add(active);
        player.showBossBar(bar);
        active.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> remove(active),
            Math.max(1, (long) Math.ceil(parsed.duration() * 20)));
    }

    private void toast(Player player, String configured) {
        StaffTools.ToastArguments parsed = StaffTools.parseToastArguments(arguments(configured),
            "task", "paper");
        if (!parsed.valid()) {
            warn("toast", configured);
            return;
        }
        Component title = MM.deserialize(parsed.title());
        Component description = parsed.message().isEmpty()
            ? Component.empty() : MM.deserialize(parsed.message());
        NamespacedKey key = new NamespacedKey(plugin,
            "gui_toast_" + UUID.randomUUID().toString().replace("-", ""));
        String json = "{\"criteria\":{\"show\":{\"trigger\":\"minecraft:impossible\"}},"
            + "\"display\":{\"icon\":{\"id\":\"" + parsed.icon().getKey() + "\"},"
            + "\"title\":" + GsonComponentSerializer.gson().serialize(title)
            + ",\"description\":" + GsonComponentSerializer.gson().serialize(description) + ","
            + "\"frame\":\"" + parsed.type() + "\",\"announce_to_chat\":false,"
            + "\"show_toast\":true,\"hidden\":true}}";
        Advancement advancement;
        try {
            advancement = loadAdvancement(key, json);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not display GUI toast: " + exception.getMessage());
            return;
        }
        if (advancement == null) {
            warn("toast", configured);
            return;
        }
        ActiveToast active = new ActiveToast(player, advancement);
        toasts.put(key, active);
        advancement.getCriteria().forEach(criterion ->
            player.getAdvancementProgress(advancement).awardCriteria(criterion));
        active.task = plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> cleanupToast(key, active), 2);
    }

    @SuppressWarnings("deprecation")
    private static Advancement loadAdvancement(NamespacedKey key, String json) {
        return Bukkit.getUnsafe().loadAdvancement(key, json);
    }

    @SuppressWarnings("deprecation")
    private void cleanupToast(NamespacedKey key, ActiveToast active) {
        if (!toasts.remove(key, active)) {
            return;
        }
        if (active.task != null) {
            active.task.cancel();
            active.task = null;
        }
        if (active.player.isOnline()) {
            AdvancementProgress progress = active.player.getAdvancementProgress(active.advancement);
            new ArrayList<>(progress.getAwardedCriteria()).forEach(progress::revokeCriteria);
        }
        Bukkit.getUnsafe().removeAdvancement(key);
    }

    private void remove(ActiveBar active) {
        if (!bossBars.remove(active)) {
            return;
        }
        if (active.task != null) {
            active.task.cancel();
            active.task = null;
        }
        active.player.hideBossBar(active.bar);
    }

    private void warn(String type, String configured) {
        plugin.getLogger().warning("Invalid GUI " + type + " action '" + configured + "'.");
    }

    private static NamespacedKey namespaced(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return NamespacedKey.fromString(normalized.contains(":")
            ? normalized : "minecraft:" + normalized);
    }

    private static String[] arguments(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }

    private static int positiveInt(String[] values, int index, int fallback) {
        if (index >= values.length) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(values[index].trim());
            return parsed >= 0 ? parsed : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    static Action parseAction(String configured) {
        String action = configured == null ? "" : configured.trim();
        int end = action.indexOf(']');
        if (!action.startsWith("[") || end < 2) {
            return null;
        }
        return new Action(action.substring(1, end).trim().toLowerCase(Locale.ROOT),
            action.substring(end + 1).trim());
    }

    record Action(String tag, String value) {
    }

    private static final class ActiveBar {
        private final Player player;
        private final BossBar bar;
        private BukkitTask task;

        private ActiveBar(Player player, BossBar bar) {
            this.player = player;
            this.bar = bar;
        }
    }

    private static final class ActiveToast {
        private final Player player;
        private final Advancement advancement;
        private BukkitTask task;

        private ActiveToast(Player player, Advancement advancement) {
            this.player = player;
            this.advancement = advancement;
        }
    }
}
