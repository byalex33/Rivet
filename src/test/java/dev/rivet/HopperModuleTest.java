package dev.rivet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class HopperModuleTest {
    @Test
    public void transferCooldownIsAlwaysAtLeastOneTick() {
        assertEquals(2, HopperModule.transferCooldown(2));
        assertEquals(1, HopperModule.transferCooldown(1));
        assertEquals(1, HopperModule.transferCooldown(0));
        assertEquals(1, HopperModule.transferCooldown(-10));
    }
}
