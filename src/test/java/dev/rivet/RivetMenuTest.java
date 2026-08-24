package dev.rivet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class RivetMenuTest {
    @Test
    public void parsesDeluxeMenusZeroBasedSlotsAndRanges() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("slot", 0);
        configuration.set("slots", List.of("10-12", "12, 53", -1, 54));

        assertEquals(List.of(0, 10, 11, 12, 53),
            RivetMenu.configuredSlots(configuration, 54, false));
    }

    @Test
    public void keepsLegacyTrashSlotsOneBased() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("slots", "46-49");
        configuration.set("slot", 50);

        assertEquals(List.of(49, 45, 46, 47, 48),
            RivetMenu.configuredSlots(configuration, 54, true));
    }

    @Test
    public void packagesEveryRivetOwnedInventoryWithTheSharedSchema() {
        assertMenu("trash", "gui");
        assertMenu("backpacks", "gui");
        assertMenu("breeders", "gui");
        assertMenu("filter", "gui");
        assertMenu("chat", "gui.chat-styles");
        assertMenu("chat", "gui.chat-tags");
        assertMenu("polls", "gui.list");
        assertMenu("polls", "gui.vote");
        for (String menu : List.of("categories", "list", "player-preview", "ender-preview",
                 "confirm")) {
            assertMenu("snapshots", "gui." + menu);
        }
    }

    private static void assertMenu(String module, String path) {
        var resource = RivetMenuTest.class.getResourceAsStream("/settings/" + module + ".yml");
        assertNotNull(module, resource);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(
            new InputStreamReader(resource, StandardCharsets.UTF_8));
        ConfigurationSection menu = configuration.getConfigurationSection(path);
        assertNotNull(module + ":" + path, menu);
        assertTrue(module + ":" + path, menu.isString("menu_title"));
        assertTrue(module + ":" + path, menu.isInt("size"));
        assertTrue(module + ":" + path, menu.isList("open_commands"));
        assertNotNull(module + ":" + path, menu.getConfigurationSection("items"));
    }
}
