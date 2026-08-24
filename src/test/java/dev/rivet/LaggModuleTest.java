package dev.rivet;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class LaggModuleTest {
    @Test
    public void packagesSafeCleanupDefaultsAndConfigurableMessages() {
        var resource = getClass().getResourceAsStream("/settings/lagg.yml");
        assertNotNull(resource);
        YamlConfiguration settings = YamlConfiguration.loadConfiguration(
            new InputStreamReader(resource, StandardCharsets.UTF_8));

        assertEquals(300, settings.getLong("cleanup-interval-seconds"));
        assertEquals(List.of(60, 30, 10), settings.getIntegerList("warning-seconds"));
        assertEquals(true, settings.getBoolean("ignore.custom-names"));
        assertEquals(true, settings.getBoolean("ignore.persistent-data"));
        assertEquals(true, settings.getBoolean("ignore.crops"));
        assertTrue(settings.getStringList("crop-materials").contains("WHEAT"));
        assertTrue(settings.getStringList("crop-materials").contains("NETHER_WART"));
        List.of("messages.warning.actions", "messages.cleanup.actions", "messages.timer.actions",
            "messages.usage.actions")
            .forEach(path -> assertEquals(path, true, settings.isList(path)));
        assertEquals(false, settings.contains("messages.reloaded"));
        assertEquals(false, settings.contains("messages.reload-failed"));
        assertEquals("[message] <white>Usage: /lagg <clear|timer></white>",
            settings.getStringList("messages.usage.actions").getFirst());
        assertEquals(false, settings.getStringList("messages.cleanup.actions").getFirst()
            .contains("entities"));
        assertEquals(false, settings.getString("messages.hover-entry").contains("entities"));
        List.of("messages.hover-label", "messages.hover-header", "messages.hover-entry",
            "messages.hover-empty")
            .forEach(path -> assertEquals(path, true, settings.isString(path)));
        assertEquals("<details>", RivetMiniMessage.toResolverTags("%details%"));
    }

    @Test
    public void schedulesOnlyValidDistinctWarnings() {
        assertEquals(List.of(60, 30, 10),
            LaggModule.warningSeconds(List.of(10, 60, 0, 30, 60, 300, -5), 300));
        assertEquals(List.of(), LaggModule.warningSeconds(List.of(60), 60));
        assertEquals(300, LaggModule.cleanupIntervalSeconds(0));
        assertEquals(45, LaggModule.cleanupIntervalSeconds(45));
        assertEquals(301, LaggModule.secondsUntil(301_000, 0));
        assertEquals(301, LaggModule.secondsUntil(300_001, 0));
        assertEquals(0, LaggModule.secondsUntil(1_000, 1_000));
        assertEquals(301_000, LaggModule.nextCleanupAt(1_000, 300));
        assertEquals(Long.MAX_VALUE, LaggModule.nextCleanupAt(Long.MAX_VALUE - 500, 1));
        assertEquals("now", LaggModule.formatDuration(0));
        assertEquals("59s", LaggModule.formatDuration(59));
        assertEquals("5m", LaggModule.formatDuration(300));
        assertEquals("1h 1m 1s", LaggModule.formatDuration(3_661));
        assertEquals("2d 3h 4m 5s", LaggModule.formatDuration(183_845));
    }

    @Test
    public void protectsNamedAndPersistentItemsByConfiguration() {
        Set<Material> crops = LaggModule.cropMaterials(List.of());
        assertEquals(true, LaggModule.protectedItem(true, true, true,
            true, false, Material.COBBLESTONE, crops));
        assertEquals(true, LaggModule.protectedItem(true, true, true,
            false, true, Material.COBBLESTONE, crops));
        assertEquals(true, LaggModule.protectedItem(true, true, true,
            false, false, Material.WHEAT, crops));
        assertEquals(false, LaggModule.protectedItem(false, true, false,
            true, false, Material.COBBLESTONE, crops));
        assertEquals(false, LaggModule.protectedItem(true, false, false,
            false, true, Material.COBBLESTONE, crops));
        assertEquals(false, LaggModule.protectedItem(true, true, false,
            false, false, Material.WHEAT, crops));
        assertEquals(false, LaggModule.protectedItem(true, true, true,
            false, false, Material.COBBLESTONE, crops));
    }

    @Test
    public void protectsConfiguredCropDropsAndAllowsCustomCropLists() {
        Set<Material> defaults = LaggModule.cropMaterials(List.of());
        assertTrue(defaults.containsAll(Set.of(Material.WHEAT, Material.CARROT,
            Material.POTATO, Material.BEETROOT, Material.NETHER_WART, Material.SUGAR_CANE,
            Material.CACTUS, Material.BAMBOO, Material.KELP)));
        assertEquals(Set.of(Material.WHEAT, Material.CARROT),
            LaggModule.cropMaterials(List.of("wheat", "minecraft:carrot", "invalid")));
    }

    @Test
    public void alwaysProtectsEveryShulkerBoxVariant() {
        Set<Material> crops = Set.of();
        assertTrue(LaggModule.protectedItem(false, false, false,
            false, false, Material.SHULKER_BOX, crops));
        assertTrue(LaggModule.protectedItem(false, false, false,
            false, false, Material.RED_SHULKER_BOX, crops));
        assertEquals(false, LaggModule.protectedItem(false, false, false,
            false, false, Material.SHULKER_SHELL, crops));
    }

    @Test
    public void migratesOnlyLegacyEntityCountMessagesInMemory() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("messages.cleanup.actions", List.of(
            "[broadcast] <white>Cleared <#f72a4c>%count%</#f72a4c> ground item entities "
                + "(<#f72a4c>%amount%</#f72a4c> items). %details%</white>"));
        settings.set("messages.hover-entry",
            "<white>%item%:</white> <#f72a4c>%amount%</#f72a4c> items in "
                + "<#f72a4c>%count%</#f72a4c> entities");
        settings.set("messages.hover-empty", "<white>No eligible item entities were found.</white>");

        assertTrue(LaggModule.migrateLegacyMessages(settings));
        assertEquals(false, settings.getStringList("messages.cleanup.actions").getFirst()
            .contains("entities"));
        assertEquals(false, settings.getString("messages.hover-entry").contains("entities"));
        settings.set("messages.cleanup.actions", List.of("[broadcast] Custom %count%"));
        assertEquals(false, LaggModule.migrateLegacyMessages(settings));
        assertEquals(List.of("[broadcast] Custom %count%"),
            settings.getStringList("messages.cleanup.actions"));
    }

    @Test
    public void aggregatesEveryRemovedItemEntityByMaterial() {
        Map<Material, LaggModule.RemovedItem> removed = new LinkedHashMap<>();
        LaggModule.recordRemoval(removed, Material.COBBLESTONE, 32);
        LaggModule.recordRemoval(removed, Material.COBBLESTONE, 16);
        LaggModule.recordRemoval(removed, Material.DIAMOND, 2);

        assertEquals(new LaggModule.RemovedItem(2, 48), removed.get(Material.COBBLESTONE));
        assertEquals(new LaggModule.RemovedItem(1, 2), removed.get(Material.DIAMOND));
    }
}
