package dev.rivet;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
        List.of("messages.warning", "messages.cleanup", "messages.hover-label",
            "messages.hover-header", "messages.hover-entry", "messages.hover-empty",
            "messages.reloaded", "messages.reload-failed", "messages.usage")
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
        assertEquals(true, LaggModule.protectedItem(true, true, true, false));
        assertEquals(true, LaggModule.protectedItem(true, true, false, true));
        assertEquals(false, LaggModule.protectedItem(false, true, true, false));
        assertEquals(false, LaggModule.protectedItem(true, false, false, true));
        assertEquals(false, LaggModule.protectedItem(true, true, false, false));
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
