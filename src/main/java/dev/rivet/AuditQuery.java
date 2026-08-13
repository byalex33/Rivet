package dev.rivet;

import java.util.Set;

record AuditQuery(long since, String playerName, String world, Integer x, Integer y,
                  Integer z, Integer radius, Set<AuditAction> actions,
                  boolean includeCommands, int limit, int offset) {
    AuditQuery {
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        limit = Math.max(1, limit);
        offset = Math.max(0, offset);
    }

    AuditQuery page(int page, int pageSize) {
        return new AuditQuery(since, playerName, world, x, y, z, radius, actions,
            includeCommands, pageSize, Math.max(0, page - 1) * pageSize);
    }
}
