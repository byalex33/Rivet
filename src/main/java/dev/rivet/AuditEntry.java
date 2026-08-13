package dev.rivet;

import java.util.UUID;

record AuditEntry(long id, long timestamp, UUID playerUuid, String playerName,
                  AuditAction action, String world, int x, int y, int z,
                  String target, Integer amount, String beforeData,
                  String afterData, String metadata) {
    AuditEntry(long timestamp, UUID playerUuid, String playerName, AuditAction action,
               String world, int x, int y, int z, String target, Integer amount,
               String beforeData, String afterData, String metadata) {
        this(0, timestamp, playerUuid, playerName, action, world, x, y, z,
            target, amount, beforeData, afterData, metadata);
    }
}
