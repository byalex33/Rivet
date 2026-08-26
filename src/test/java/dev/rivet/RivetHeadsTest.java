package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RivetHeadsTest {
    private static final UUID PLAYER_ID = UUID.fromString("d8a7b1ad-7a15-4f51-a42f-9b43058b261f");

    @Test
    public void resolvesPercentHeadPlaceholderToANativePlayerObject() {
        Component rendered = RivetMiniMessage.miniMessage().deserialize("%head%",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                "head", RivetHeads.component(PLAYER_ID)));

        assertHead(rendered);
    }

    @Test
    public void acceptsNativeMiniMessageHeadsInsideConfiguredLore() {
        Component configuredLore = RivetMiniMessage.miniMessage().deserialize(
            "<head:" + PLAYER_ID + "> <gray>Player</gray>");

        assertHead(configuredLore);
    }

    private static void assertHead(Component component) {
        Component candidate = component instanceof ObjectComponent
            ? component : component.children().getFirst();
        assertTrue(candidate instanceof ObjectComponent);
        ObjectComponent object = (ObjectComponent) candidate;
        assertTrue(object.contents() instanceof PlayerHeadObjectContents);
        PlayerHeadObjectContents contents = (PlayerHeadObjectContents) object.contents();
        assertEquals(PLAYER_ID, contents.id());
    }
}
