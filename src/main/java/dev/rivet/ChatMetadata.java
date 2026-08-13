package dev.rivet;

import net.kyori.adventure.text.Component;

record ChatMetadata(Component prefix, Component suffix) {
    private static final ChatMetadata EMPTY = new ChatMetadata(Component.empty(), Component.empty());

    static ChatMetadata empty() {
        return EMPTY;
    }
}
