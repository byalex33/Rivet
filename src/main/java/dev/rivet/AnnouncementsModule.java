package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

final class AnnouncementsModule {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private List<String> announcements = List.of();
    private BukkitTask task;
    private int index;

    AnnouncementsModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("announcements");
        reload();
    }

    void reload() {
        shutdown();
        ConfigurationSection section = settings.getConfigurationSection("announcements");
        announcements = section == null ? List.of() : section.getKeys(false).stream()
            .filter(key -> section.getBoolean(key + ".enabled", true))
            .filter(key -> section.isString(key + ".message")).toList();
        long interval = Math.max(1, settings.getLong("interval-seconds", 300)) * 20L;
        task = announcements.isEmpty() ? null
            : plugin.getServer().getScheduler().runTaskTimer(plugin, this::announce, interval, interval);
        index = 0;
    }

    void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void announce() {
        if (announcements.isEmpty() || settings.getBoolean("skip-when-empty", true)
            && plugin.getServer().getOnlinePlayers().isEmpty()) {
            return;
        }
        String key = announcements.get(index++ % announcements.size());
        plugin.getServer().broadcast(MM.deserialize(
            settings.getString("announcements." + key + ".message", "")));
        if (!settings.getBoolean("sound.enabled", false)) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(settings.getString("sound.name", "BLOCK_NOTE_BLOCK_PLING"));
            float volume = (float) settings.getDouble("sound.volume", .8);
            float pitch = (float) settings.getDouble("sound.pitch", 1.2);
            plugin.getServer().getOnlinePlayers().forEach(player ->
                player.playSound(player.getLocation(), sound, volume, pitch));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid announcement sound in settings/announcements.yml.");
        }
    }
}
