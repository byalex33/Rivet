package dev.rivet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class SoundResolutionTest {
    @Test
    public void normalizesLegacySoundNamesWithoutLosingUnderscores() {
        assertEquals("ENTITY_ENDERMAN_TELEPORT",
            ConfiguredEffect.legacySoundName("entity_enderman_teleport"));
        assertEquals("ENTITY_ENDERMAN_TELEPORT",
            ConfiguredEffect.legacySoundName("minecraft:ENTITY_ENDERMAN_TELEPORT"));
        assertEquals("BLOCK_NOTE_BLOCK_PLING",
            ConfiguredEffect.legacySoundName("block_note_block_pling"));
        assertEquals("ENTITY.ENDERMAN.TELEPORT",
            ConfiguredEffect.legacySoundName("entity.enderman.teleport"));
        assertNull(ConfiguredEffect.legacySoundName("custom:not_a_vanilla_sound"));
        assertNull(ConfiguredEffect.legacySoundName(" "));
    }
}
