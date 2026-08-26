package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class JoinConfigurationTest {
    @Test
    public void packagesJoinSettingsUnderTheNewFilename() {
        assertTrue(RivetConfig.SETTINGS.contains("join"));
        assertFalse(RivetConfig.SETTINGS.contains("join-leave"));
        var resource = getClass().getResourceAsStream("/settings/join.yml");
        assertNotNull(resource);
        YamlConfiguration settings = YamlConfiguration.loadConfiguration(
            new InputStreamReader(resource, StandardCharsets.UTF_8));

        assertTrue(settings.getStringList("welcome.first-join.actions")
            .contains("[welcome-head] first-join"));
        assertEquals(8, settings.getStringList("welcome-heads.first-join.lines").size());
        assertTrue(settings.getStringList("join.actions").getFirst().contains("%head%"));
    }

    @Test
    public void upgradesThePreviousDefaultChatFormatWithAHead() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("format",
            "%prefix%%tag% %player%%suffix%<dark_gray>: </dark_gray>%message%");

        assertTrue(RivetConfig.migrateChatFormat(settings));
        assertTrue(settings.getString("format").startsWith("%head% "));
    }
}
