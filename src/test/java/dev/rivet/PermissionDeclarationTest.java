package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public final class PermissionDeclarationTest {
    @Test
    public void declaresFeatureAndCommandPermissionGates() {
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));

        assertEquals("rivet.snapshots", plugin.getString("commands.snapshot.permission"));
        assertEquals(true, plugin.getBoolean("permissions.rivet.treefeller.default"));
        assertEquals(true, plugin.getBoolean("permissions.rivet.veinminer.default"));
        assertEquals("op", plugin.getString("permissions.rivet.snapshots.default"));
        assertEquals(true, plugin.getBoolean(
            "permissions.rivet.snapshots.children.rivet.snapshots.restore"));
    }
}
