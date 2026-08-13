package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;

record SnapshotSettings(int maxPerPlayer, int retentionDays, boolean deduplicate,
                        boolean saveOnDeath, boolean createSafetySnapshot,
                        boolean requireConfirmation, boolean auditCreations) {
    SnapshotSettings(YamlConfiguration configuration) {
        this(Math.clamp(configuration.getInt("max-per-player", 10), 1, 100),
            Math.clamp(configuration.getInt("retention-days", 30), 1, 3_650),
            configuration.getBoolean("deduplicate", true),
            configuration.getBoolean("save-on.death", true),
            configuration.getBoolean("restore.create-safety-snapshot", true),
            configuration.getBoolean("restore.require-confirmation", true),
            configuration.getBoolean("audit.record-creations", true));
    }
}
