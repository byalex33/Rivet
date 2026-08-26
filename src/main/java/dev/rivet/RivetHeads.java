package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/** Native player-head text components introduced by Minecraft 1.21.9. */
final class RivetHeads {
    private RivetHeads() {
    }

    static Component component(OfflinePlayer player) {
        return component(player.getUniqueId());
    }

    static Component component(UUID playerId) {
        return Component.object(ObjectContents.playerHead(playerId));
    }

    static TagResolver resolver(OfflinePlayer player) {
        Component head = component(player);
        return TagResolver.resolver(
            Placeholder.component("head", head),
            Placeholder.component("player_head", head));
    }
}
