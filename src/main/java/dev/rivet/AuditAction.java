package dev.rivet;

enum AuditAction {
    BLOCK_PLACE,
    BLOCK_BREAK,
    CONTAINER_ADD,
    CONTAINER_REMOVE,
    ITEM_PICKUP,
    ITEM_DROP,
    ENTITY_KILL,
    PLAYER_DEATH,
    SIGN_EDIT,
    EXPLOSION,
    CREEPER_DAMAGE,
    FIRE_DAMAGE,
    BLOCK_INTERACT,
    SNAPSHOT_CREATE,
    SNAPSHOT_RESTORE,
    COMMAND;

    boolean command() {
        return this == COMMAND;
    }

    boolean container() {
        return this == CONTAINER_ADD || this == CONTAINER_REMOVE;
    }

    boolean blockHistory() {
        return this == BLOCK_PLACE || this == BLOCK_BREAK || this == SIGN_EDIT
            || this == EXPLOSION || this == CREEPER_DAMAGE || this == FIRE_DAMAGE
            || this == BLOCK_INTERACT;
    }
}
