package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class TeleportHistoryTest {
    @Test
    public void backKeepsOneSimpleCommandAndTheExistingPermission() {
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        YamlConfiguration graves = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/settings/graves.yml"), StandardCharsets.UTF_8));

        assertEquals("rivet.back", plugin.getString("commands.back.permission"));
        assertNull(plugin.getString("commands.back.usage"));
        assertTrue(graves.isList("back.messages.usage.actions"));
        assertTrue(graves.isList("back.messages.none.actions"));
        assertTrue(graves.isList("back.messages.teleported.actions"));
    }

    @Test
    public void ignoresTinyMovesButTracksMeaningfulAndCrossWorldMoves() {
        UUID world = UUID.randomUUID();
        TeleportHistory.Snapshot origin = point(world, 10, 64, 10);

        assertFalse(TeleportHistory.meaningful(origin, point(world, 11, 64, 10)));
        assertTrue(TeleportHistory.meaningful(origin, point(world, 12, 64, 10)));
        assertTrue(TeleportHistory.meaningful(origin, point(UUID.randomUUID(), 10, 64, 10)));
    }

    @Test
    public void keepsOnlyRecentDistinctInternalLocations() {
        UUID world = UUID.randomUUID();
        List<TeleportHistory.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            TeleportHistory.push(entries, entry(world, index * 10));
        }

        assertEquals(TeleportHistory.MAX_ENTRIES, entries.size());
        assertEquals(90d, entries.getFirst().location().x(), 0);
        assertEquals(20d, entries.getLast().location().x(), 0);

        TeleportHistory.push(entries, entry(world, 21));
        assertEquals(TeleportHistory.MAX_ENTRIES, entries.size());
        assertEquals(21d, entries.getFirst().location().x(), 0);
        assertEquals(1, entries.stream().filter(candidate ->
            candidate.location().x() >= 20 && candidate.location().x() < 22).count());
    }

    @Test
    public void consumingBackEntriesPreventsRepeatedTwoLocationBounces() {
        UUID world = UUID.randomUUID();
        TeleportHistory.Entry older = entry(world, 0);
        TeleportHistory.Entry newest = entry(world, 100);
        List<TeleportHistory.Entry> entries = new ArrayList<>(List.of(newest, older));

        assertTrue(TeleportHistory.consume(entries, newest.id()));
        assertEquals(List.of(older), entries);
        assertFalse(TeleportHistory.consume(entries, newest.id()));
        assertTrue(TeleportHistory.consume(entries, older.id()));
        assertTrue(entries.isEmpty());
    }

    @Test
    public void persistedEntriesRoundTrip() {
        TeleportHistory.Entry original = entry(UUID.randomUUID(), 125.5);
        Map<String, Object> values = TeleportHistory.values(original);
        TeleportHistory.Entry restored = TeleportHistory.entry(values);

        assertNotNull(restored);
        assertEquals(original.id(), restored.id());
        assertEquals(original.location(), restored.location());
        assertEquals(original.source(), restored.source());
    }

    @Test
    @SuppressWarnings("removal")
    public void tracksIntentionalTeleportCausesAndRejectsInternalNoise() {
        assertTrue(DelayedTeleport.trackedCause(PlayerTeleportEvent.TeleportCause.COMMAND));
        assertTrue(DelayedTeleport.trackedCause(PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertTrue(DelayedTeleport.trackedCause(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));
        assertFalse(DelayedTeleport.trackedCause(PlayerTeleportEvent.TeleportCause.DISMOUNT));
        assertFalse(DelayedTeleport.trackedCause(PlayerTeleportEvent.TeleportCause.EXIT_BED));
        assertFalse(DelayedTeleport.trackedCause(PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT));
    }

    private static TeleportHistory.Entry entry(UUID world, double x) {
        return new TeleportHistory.Entry(UUID.randomUUID(), point(world, x, 64, 0),
            1_000, "plugin");
    }

    private static TeleportHistory.Snapshot point(UUID world, double x, double y, double z) {
        return new TeleportHistory.Snapshot(world, "world", x, y, z, 0, 0);
    }
}
