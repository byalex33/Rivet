package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

final class RivetMessages {
    static final String DEFAULT_TAG = "<#f72a4c><bold>RIVET</bold></#f72a4c> <dark_gray>›</dark_gray>";
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();

    private RivetMessages() {
    }

    static Component tag(RivetPlugin plugin) {
        return MM.deserialize(plugin.getConfig().getString("messages.tag", DEFAULT_TAG));
    }

    static Component tagged(RivetPlugin plugin, Component message) {
        return tag(plugin).append(Component.space()).append(message);
    }

    static Component tagged(RivetPlugin plugin, String message) {
        return tagged(plugin, MM.deserialize(message));
    }
}
