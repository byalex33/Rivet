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
        List.of("messages.warning.actions", "messages.cleanup.actions",
            "messages.reloaded.actions", "messages.reload-failed.actions",
            "messages.usage.actions")
            .forEach(path -> assertEquals(path, true, settings.isList(path)));
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
    public void aggregatesEveryRemovedItemEntityByMaterial() {
        Map<Material, LaggModule.RemovedItem> removed = new LinkedHashMap<>();
        LaggModule.recordRemoval(removed, Material.COBBLESTONE, 32);
        LaggModule.recordRemoval(removed, Material.COBBLESTONE, 16);
        LaggModule.recordRemoval(removed, Material.DIAMOND, 2);

        assertEquals(new LaggModule.RemovedItem(2, 48), removed.get(Material.COBBLESTONE));
        assertEquals(new LaggModule.RemovedItem(1, 2), removed.get(Material.DIAMOND));
    }
}
