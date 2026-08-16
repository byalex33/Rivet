package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class UtilitiesModuleTest {
    @Test
    public void packagesCreeperConfettiDefaults() {
        var resource = getClass().getResourceAsStream("/settings/utilities.yml");
        assertNotNull(resource);
        YamlConfiguration settings = YamlConfiguration.loadConfiguration(
            new InputStreamReader(resource, StandardCharsets.UTF_8));

        assertEquals(true, settings.getBoolean("creeper-confetti.enabled"));
        assertEquals(.1, settings.getDouble("creeper-confetti.chance"), .0001);
        assertEquals(96, settings.getInt("creeper-confetti.particles.count"));
        assertEquals("entity.firework_rocket.blast",
            settings.getString("creeper-confetti.sound.name"));
    }

    @Test
    public void clampsCreeperConfettiChance() {
        assertEquals(true, UtilitiesModule.confettiSelected(.1, .0999));
        assertEquals(false, UtilitiesModule.confettiSelected(.1, .1));
        assertEquals(false, UtilitiesModule.confettiSelected(0, 0));
        assertEquals(true, UtilitiesModule.confettiSelected(2, .9999));
        assertEquals(false, UtilitiesModule.confettiSelected(-1, 0));
        assertEquals(false, UtilitiesModule.confettiSelected(Double.NaN, 0));
        assertEquals(false, UtilitiesModule.confettiSelected(.5, -1));
    }
}
