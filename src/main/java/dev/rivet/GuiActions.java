package dev.rivet;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
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
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final RivetPlugin plugin;
    private final Set<ActiveBar> bossBars = new HashSet<>();
    private final Map<NamespacedKey, ActiveToast> toasts = new HashMap<>();

    GuiActions(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    void run(Player player, List<String> configuredActions) {
        run(player, configuredActions, TagResolver.empty());
    }

    void run(Player player, List<String> configuredActions, TagResolver placeholders) {
        TagResolver resolved = TagResolver.resolver(placeholders,
            Placeholder.unparsed("player", player.getName()));
        for (String configured : configuredActions) {
            Action action = parseAction(configured);
            if (action == null) {
                plugin.getLogger().warning("Invalid GUI action '" + configured + "'.");
                continue;
            }
            switch (action.tag()) {
                case "message" -> player.sendMessage(MM.deserialize(action.value(), placeholders));
                case "broadcast" -> plugin.getServer().broadcast(
                    MM.deserialize(action.value(), resolved));
                case "player", "command" -> runPlayerCommand(player,
                    command(action.value(), player, resolved));
                case "console" -> runConsoleCommand(command(action.value(), player, resolved));
                case "sound" -> sound(player, action.value());
                case "toast" -> toast(player, action.value(), resolved);
                case "actionbar", "action-bar" -> player.sendActionBar(
                    MM.deserialize(action.value(), resolved));
                case "particle", "particles" -> particle(player, action.value());
                case "firework" -> firework(player, action.value());
                case "title" -> title(player, action.value(), resolved);
                case "bossbar", "boss-bar" -> bossBar(player, action.value(), resolved);
                case "lightning" -> player.getWorld().strikeLightningEffect(player.getLocation());
                case "close" -> player.closeInventory();
                default -> plugin.getLogger().warning("Unknown GUI action tag '[" + action.tag() + "]'.");
            }
        }
    }

    private static String command(String configured, Player player, TagResolver placeholders) {
        String value = configured.replace("%player_name%", player.getName());
        String command = PLAIN.serialize(MM.deserialize(value, placeholders)).trim();
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private void runPlayerCommand(Player player, String command) {
        if (command.isBlank()) {
            warn("player command", command);
        } else {
            player.performCommand(command);
        }
    }

    private void runConsoleCommand(String command) {
        if (command.isBlank()) {
            warn("console command", command);
        } else {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
        }
    }

    void shutdown() {
        new ArrayList<>(bossBars).forEach(this::remove);
        new HashMap<>(toasts).forEach((key, toast) -> cleanupToast(key, toast));
    }

    private void sound(Player player, String configured) {
        String[] arguments = arguments(configured);
        Sound sound = arguments.length == 0 ? null : ConfiguredEffect.resolveSound(arguments[0]);
        if (sound == null) {
            warn("sound", configured);
            return;
        }
        float volume = positiveFloat(arguments, 1, 1);
        float pitch = rangedFloat(arguments, 2, 1, 0, 2);
        player.playSound(player.getLocation(), sound, volume, pitch);
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

    private void firework(Player player, String configured) {
        String[] fields = configured.split("\\s*\\|\\s*", -1);
        List<Color> colors = fields.length == 0 || fields[0].isBlank()
            ? List.of(Color.LIME, Color.YELLOW)
            : java.util.Arrays.stream(fields[0].split(",")).map(String::strip)
                .map(GuiActions::color).filter(java.util.Objects::nonNull).toList();
        if (colors.isEmpty()) {
            warn("firework", configured);
            return;
        }
        FireworkEffect.Type type;
        try {
            type = fields.length > 1 && !fields[1].isBlank()
                ? FireworkEffect.Type.valueOf(fields[1].strip().toUpperCase(Locale.ROOT))
                : FireworkEffect.Type.BALL_LARGE;
        } catch (IllegalArgumentException exception) {
            warn("firework", configured);
            return;
        }
        int power = Math.clamp(positiveInt(fields, 2, 1), 0, 2);
        int count = Math.clamp(positiveInt(fields, 3, 1), 1, 100);
        int gap = Math.clamp(positiveInt(fields, 4, 10), 0, 1_200);
        for (int index = 0; index < count; index++) {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> spawnFirework(player, colors, type, power), (long) index * gap);
        }
    }

    private static void spawnFirework(Player player, List<Color> colors,
                                      FireworkEffect.Type type, int power) {
        if (!player.isOnline()) {
            return;
        }
        Firework firework = player.getWorld().spawn(player.getLocation().add(0, 1, 0), Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder().with(type).withColor(colors)
            .flicker(true).trail(true).build());
        meta.setPower(power);
        firework.setFireworkMeta(meta);
    }

    private static Color color(String configured) {
        String hex = configured.startsWith("#") ? configured.substring(1) : configured;
        try {
            return hex.matches("[0-9a-fA-F]{6}")
                ? Color.fromRGB(Integer.parseInt(hex, 16)) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void title(Player player, String configured, TagResolver placeholders) {
        String[] parts = configured.split("\\|", -1);
        Component title = MM.deserialize(parts.length == 0 ? "" : parts[0].trim(), placeholders);
        Component subtitle = MM.deserialize(parts.length < 2 ? "" : parts[1].trim(), placeholders);
        int fadeIn = positiveInt(parts, 2, 10);
        int stay = positiveInt(parts, 3, 50);
        int fadeOut = positiveInt(parts, 4, 10);
        player.showTitle(Title.title(title, subtitle, Title.Times.times(
            ticks(fadeIn), ticks(stay), ticks(fadeOut))));
    }

    private void bossBar(Player player, String configured, TagResolver placeholders) {
        StaffTools.BossBarArguments parsed = StaffTools.parseBossBarArguments(arguments(configured),
            5, "purple", "solid");
        if (!parsed.valid()) {
            warn("bossbar", configured);
            return;
        }
        BossBar bar = BossBar.bossBar(MM.deserialize(parsed.message(), placeholders), 1,
            parsed.color(), parsed.overlay());
        ActiveBar active = new ActiveBar(player, bar);
        bossBars.add(active);
        player.showBossBar(bar);
        active.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> remove(active),
            Math.max(1, (long) Math.ceil(parsed.duration() * 20)));
    }

    private void toast(Player player, String configured, TagResolver placeholders) {
        StaffTools.ToastArguments parsed = StaffTools.parseToastArguments(arguments(configured),
            "task", "paper");
        if (!parsed.valid()) {
            warn("toast", configured);
            return;
        }
        Component title = MM.deserialize(parsed.title(), placeholders);
        Component description = parsed.message().isEmpty()
            ? Component.empty() : MM.deserialize(parsed.message(), placeholders);
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

    private static float positiveFloat(String[] values, int index, float fallback) {
        return rangedFloat(values, index, fallback, 0, Float.MAX_VALUE);
    }

    private static float rangedFloat(String[] values, int index, float fallback,
                                     float minimum, float maximum) {
        if (index >= values.length) {
            return fallback;
        }
        try {
            float parsed = Float.parseFloat(values[index].trim());
            return Float.isFinite(parsed) && parsed >= minimum && parsed <= maximum
                ? parsed : fallback;
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
