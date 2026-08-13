package dev.rivet;

import java.util.UUID;

record CapturedSnapshot(UUID playerUuid, String playerName, String reason,
                        long timestamp, String world, double x, double y, double z,
                        String deathCause, SnapshotState state) {
}
