package dev.rivet;

import java.util.UUID;

record SnapshotRecord(long id, UUID playerUuid, String playerName, String reason,
                      long timestamp, String world, double x, double y, double z,
                      String deathCause, String blobKey, SnapshotState state) {
    SnapshotRecord metadataOnly() {
        return new SnapshotRecord(id, playerUuid, playerName, reason, timestamp, world,
            x, y, z, deathCause, blobKey, null);
    }
}
