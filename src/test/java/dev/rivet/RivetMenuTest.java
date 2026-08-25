package dev.rivet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class RivetMenuTest {
    @Test
    public void checksForGlintOverrideBeforeReadingOptionalComponent() {
        assertFalse(RivetMenu.glintOverride(glintMeta(false, true)));
        assertTrue(RivetMenu.glintOverride(glintMeta(true, true)));
        assertFalse(RivetMenu.glintOverride(glintMeta(true, false)));
    }

    private static ItemMeta glintMeta(boolean hasOverride, boolean override) {
        return (ItemMeta) Proxy.newProxyInstance(
            ItemMeta.class.getClassLoader(),
            new Class<?>[]{ItemMeta.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hasEnchantmentGlintOverride" -> hasOverride;
                case "getEnchantmentGlintOverride" -> {
                    if (!hasOverride) {
                        throw new IllegalStateException("Optional glint component is absent");
                    }
                    yield override;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

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
        for (String menu : List.of("categories", "list", "player-preview", "ender-preview",
                 "confirm")) {
            assertMenu("snapshots", "gui." + menu);
        }
    }

    @Test
    public void pollVotingDoesNotPackageASeparateVoteScreen() {
        var resource = RivetMenuTest.class.getResourceAsStream("/settings/polls.yml");
        assertNotNull(resource);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(
            new InputStreamReader(resource, StandardCharsets.UTF_8));

        assertEquals(null, configuration.getConfigurationSection("gui.vote"));
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
