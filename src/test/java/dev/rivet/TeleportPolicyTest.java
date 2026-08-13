package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public final class TeleportPolicyTest {
    @Test
    public void sharedCooldownRoundsUpAndExpiresCleanly() {
        assertEquals(10, DelayedTeleport.cooldownRemaining(1_000, 10, 1_000));
        assertEquals(1, DelayedTeleport.cooldownRemaining(1_000, 10, 10_001));
        assertEquals(0, DelayedTeleport.cooldownRemaining(1_000, 10, 11_000));
    }

    @Test
    public void noCooldownPermissionIsOperatorDefault() {
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        assertEquals("op", plugin.getString("permissions.rivet.tp.nocooldown.default"));
        assertEquals(0, DelayedTeleport.effectiveWarmup(3, true));
        assertEquals(3, DelayedTeleport.effectiveWarmup(3, false));
        assertEquals(0, DelayedTeleport.effectiveWarmup(-4, false));
    }
}
