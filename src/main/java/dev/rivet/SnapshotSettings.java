package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

record SnapshotSettings(int maxPerPlayer, int retentionDays, boolean deduplicate,
                        boolean saveOnDeath, boolean createSafetySnapshot,
                        boolean requireConfirmation, boolean auditCreations,
                        boolean allCategory, boolean automaticBackups,
                        int automaticIntervalSeconds, Map<String, Boolean> enabledBackups,
                        Map<String, Integer> saveLimits, int searchMaximumMatches,
                        int searchTimeoutSeconds) {
    SnapshotSettings(YamlConfiguration configuration) {
        this(totalLimit(configuration),
            Math.clamp(configuration.getInt("retention-days", 14), 1, 3_650),
            configuration.getBoolean("deduplicate", true),
            configuration.getBoolean("enabled-backups.death",
                configuration.getBoolean("save-on.death", true)),
            configuration.getBoolean("restore.create-safety-snapshot", true),
            configuration.getBoolean("restore.require-confirmation", true),
            configuration.getBoolean("audit.record-creations", true),
            configuration.getBoolean("enable-all-category", true),
            configuration.getBoolean("automatic-backup.enabled", true),
            Math.clamp(configuration.getInt("automatic-backup.seconds", 180), 1, 86_400),
            enabledBackups(configuration), saveLimits(configuration),
            Math.clamp(configuration.getInt("search.maximum-matches", 1_000), 1, 10_000),
            Math.clamp(configuration.getInt("search.timeout-seconds", 60), 1, 300));
    }

    SnapshotSettings {
        enabledBackups = Map.copyOf(enabledBackups);
        saveLimits = Map.copyOf(saveLimits);
    }

    boolean enabled(String reason) {
        return enabledBackups.getOrDefault(key(reason), true);
    }

    int limit(String reason) {
        return saveLimits.getOrDefault(key(reason), -1);
    }

    private static int totalLimit(YamlConfiguration configuration) {
        if (configuration.contains("save-limits.total")) {
            return limit(configuration.getInt("save-limits.total", -1));
        }
        return Math.clamp(configuration.getInt("max-per-player", 10), 1, 10_000);
    }

    private static Map<String, Boolean> enabledBackups(YamlConfiguration configuration) {
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        for (String reason : SnapshotModule.BACKUP_REASONS) {
            String key = key(reason);
            enabled.put(key, configuration.getBoolean("enabled-backups." + key, true));
        }
        return enabled;
    }

    private static Map<String, Integer> saveLimits(YamlConfiguration configuration) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (String reason : SnapshotModule.BACKUP_REASONS) {
            String key = key(reason);
            limits.put(key, limit(configuration.getInt("save-limits." + key, -1)));
        }
        return limits;
    }

    private static int limit(int configured) {
        return configured < 0 ? -1 : Math.min(configured, 100_000);
    }

    static String key(String reason) {
        return reason.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
