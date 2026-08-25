package dev.rivet;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class GuiActionsTest {
    @Test
    public void explicitTargetPlayerOverridesTheViewingPlayer() {
        var placeholders = GuiActions.playerPlaceholders("Viewer",
            Placeholder.unparsed("player", "Target"));
        var rendered = RivetMiniMessage.miniMessage().deserialize("%player%", placeholders);

        assertEquals("Target",
            PlainTextComponentSerializer.plainText().serialize(rendered));
    }
}
