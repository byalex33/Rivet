package dev.rivet;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FishingModuleTest {
    @Test
    public void recognizesVanillaFishItems() {
        assertTrue(FishingModule.isFish(Material.COD));
        assertTrue(FishingModule.isFish(Material.SALMON));
        assertTrue(FishingModule.isFish(Material.PUFFERFISH));
        assertTrue(FishingModule.isFish(Material.TROPICAL_FISH));
        assertFalse(FishingModule.isFish(Material.BOW));
    }

    @Test
    public void generatesLengthsInsideConfiguredFishRanges() {
        YamlConfiguration settings = settings();

        assertEquals(30.0, FishingModule.randomLength(Material.COD, settings, 0), 0.0001);
        assertEquals(65.0, FishingModule.randomLength(Material.COD, settings, .5), 0.0001);
        assertEquals(100.0, FishingModule.randomLength(Material.COD, settings, 1), 0.0001);
        assertEquals("65.0", FishingModule.formatLength(65, 1));
    }

    @Test
    public void packagesFishingMessagesAndAllFishRanges() {
        YamlConfiguration settings = settings();

        assertTrue(settings.getBoolean("messages.fish.enabled"));
        assertTrue(settings.getBoolean("messages.catch.enabled"));
        for (Material fish : new Material[] {Material.COD, Material.SALMON,
            Material.PUFFERFISH, Material.TROPICAL_FISH}) {
            String path = "length.ranges." + fish.name().toLowerCase(java.util.Locale.ROOT);
            assertTrue(settings.contains(path + ".minimum"));
            assertTrue(settings.contains(path + ".maximum"));
        }
    }

    private static YamlConfiguration settings() {
        var resource = FishingModuleTest.class.getResourceAsStream("/settings/fishing.yml");
        assertNotNull(resource);
        return YamlConfiguration.loadConfiguration(
            new InputStreamReader(resource, StandardCharsets.UTF_8));
    }
}
