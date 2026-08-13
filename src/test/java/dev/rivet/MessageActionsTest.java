package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MessageActionsTest {
    private final MessageActions actions = new MessageActions(null);

    @Test
    public void readsEnabledActionEvents() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("messages.saved.enabled", false);
        settings.set("messages.saved.actions", List.of(
            "[message] <green>Saved", "[sound] ui.button.click 0.8 1.2"));

        MessageActions.ConfiguredMessage configured = actions.configured(
            settings, "messages.saved", "message", "fallback");

        assertFalse(configured.enabled());
        assertEquals(List.of("[message] <green>Saved", "[sound] ui.button.click 0.8 1.2"),
            configured.actions());
    }

    @Test
    public void keepsLegacyStringsBackwardCompatible() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("messages.saved", "<green>Legacy");

        MessageActions.ConfiguredMessage configured = actions.configured(
            settings, "messages.saved", "actionbar", "fallback");

        assertTrue(configured.enabled());
        assertEquals(List.of("[actionbar] <green>Legacy"), configured.actions());
    }
}
