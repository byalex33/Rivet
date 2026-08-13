package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class AuditModuleTest {
    @Test
    public void storesAndFiltersNewestEntriesForPlayerLocationAndAction() throws Exception {
        AuditStorage storage = storage();
        long now = System.currentTimeMillis();
        UUID alex = UUID.randomUUID();
        storage.append(entry(now - 3_000, alex, "Alex", AuditAction.BLOCK_PLACE,
            "world", 10, 64, 10, "STONE"));
        storage.append(entry(now - 2_000, alex, "Alex", AuditAction.BLOCK_BREAK,
            "world", 11, 64, 10, "DIRT"));
        storage.append(entry(now - 1_000, UUID.randomUUID(), "Steve", AuditAction.COMMAND,
            "world", 11, 64, 10, "/home"));
        storage.append(entry(now - 500, alex, "Alex", AuditAction.BLOCK_BREAK,
            "world", 11, 65, 11, "GRAVEL"));
        storage.flush();

        AuditQuery player = new AuditQuery(now - 10_000, "alex", null,
            null, null, null, null, Set.of(), false, 20, 0);
        List<AuditEntry> playerEntries = storage.query(player).get(5, TimeUnit.SECONDS);
        assertEquals(List.of(AuditAction.BLOCK_BREAK, AuditAction.BLOCK_BREAK,
                AuditAction.BLOCK_PLACE),
            playerEntries.stream().map(AuditEntry::action).toList());

        AuditQuery radius = new AuditQuery(now - 10_000, null, "world", 10, 64, 10,
            1, Set.of(AuditAction.BLOCK_BREAK), false, 20, 0);
        assertEquals(List.of("DIRT"), storage.query(radius).get(5, TimeUnit.SECONDS)
            .stream().map(AuditEntry::target).toList());
        assertEquals(3, storage.count(new AuditQuery(now - 10_000, null, null,
            null, null, null, null, Set.of(), false, 20, 0)).get(5, TimeUnit.SECONDS).intValue());
        assertEquals(4, storage.count(new AuditQuery(now - 10_000, null, null,
            null, null, null, null, Set.of(), true, 20, 0)).get(5, TimeUnit.SECONDS).intValue());
        storage.close();
    }

    @Test
    public void inspectorQueryReturnsOnlyExactContainerHistoryNewestFirst() throws Exception {
        AuditStorage storage = storage();
        long now = System.currentTimeMillis();
        storage.append(entry(now - 2_000, null, null, AuditAction.CONTAINER_ADD,
            "world", 125, 64, -240, "DIAMOND"));
        storage.append(entry(now - 1_000, null, null, AuditAction.CONTAINER_REMOVE,
            "world", 125, 64, -240, "IRON_INGOT"));
        storage.append(entry(now, null, null, AuditAction.BLOCK_BREAK,
            "world", 125, 64, -240, "CHEST"));
        storage.append(entry(now, null, null, AuditAction.CONTAINER_ADD,
            "world", 126, 64, -240, "GOLD_INGOT"));

        AuditQuery inspector = new AuditQuery(now - 10_000, null, "world",
            125, 64, -240, 0, Set.of(AuditAction.CONTAINER_ADD,
            AuditAction.CONTAINER_REMOVE), false, 7, 0);
        List<AuditEntry> results = storage.query(inspector).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("IRON_INGOT", "DIAMOND"),
            results.stream().map(AuditEntry::target).toList());
        storage.close();
    }

    @Test
    public void retentionPurgesOnlyEntriesOlderThanTheCutoff() throws Exception {
        AuditStorage storage = storage();
        long now = System.currentTimeMillis();
        storage.append(entry(now - Duration.ofDays(31).toMillis(), null, null,
            AuditAction.BLOCK_BREAK, "world", 0, 64, 0, "STONE"));
        storage.append(entry(now - Duration.ofDays(29).toMillis(), null, null,
            AuditAction.BLOCK_PLACE, "world", 0, 64, 0, "STONE"));

        int purged = storage.purgeBefore(now - Duration.ofDays(30).toMillis())
            .get(5, TimeUnit.SECONDS);
        assertEquals(1, purged);
        assertEquals(1, storage.count(new AuditQuery(0, null, null, null, null, null,
            null, Set.of(), true, 20, 0)).get(5, TimeUnit.SECONDS).intValue());
        storage.close();
    }

    @Test
    public void exclusionsNormalizeWorldsAndMaterials() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("excluded-worlds", List.of("Creative"));
        configuration.set("excluded-materials", List.of("minecraft:stone", "DIAMOND_ORE"));
        AuditSettings settings = new AuditSettings(configuration);

        assertTrue(settings.excluded("creative", "DIRT"));
        assertTrue(settings.excluded("world", "STONE"));
        assertTrue(settings.excluded("world", "minecraft:diamond_ore"));
        assertFalse(settings.excluded("world", "DIRT"));
        assertFalse(settings.excluded("world", "ZOMBIE"));
    }

    @Test
    public void packagesCompactDefaultsAndParsesLookupTimes() {
        var resource = getClass().getResourceAsStream("/settings/logs.yml");
        assertNotNull(resource);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(
            new InputStreamReader(resource, StandardCharsets.UTF_8));
        AuditSettings settings = new AuditSettings(configuration);

        for (AuditAction action : AuditAction.values()) {
            assertEquals(action.name(), action != AuditAction.COMMAND, settings.enabled(action));
        }
        assertEquals(30, settings.retentionDays());
        assertEquals(Duration.ofMinutes(30), settings.defaultTime());
        assertEquals(Duration.ofDays(14), AuditSettings.parseTime("2w"));
        assertEquals(null, AuditSettings.parseTime("forever"));
        assertEquals(null, AuditSettings.parseTime("999999999999999999s"));
        assertTrue(AuditModule.privateOrChatCommand("/msg Steve secret"));
        assertTrue(AuditModule.privateOrChatCommand("/minecraft:tell Steve secret"));
        assertFalse(AuditModule.privateOrChatCommand("/home"));
    }

    @Test
    public void formatsModernTaggedHistoryAndSafeInteractiveCoordinates() {
        long now = System.currentTimeMillis();
        AuditEntry entry = entry(now, UUID.randomUUID(), "Alex", AuditAction.BLOCK_BREAK,
            "world", 124, 65, -238, "STONE");
        Component history = AuditMessages.history(Component.text("RIVET ›"), "Block History",
            "STONE", "world", 124, 65, -238, List.of(entry), 1, 2, true);
        String plain = PlainTextComponentSerializer.plainText().serialize(history);

        assertTrue(plain.startsWith("RIVET › Block History\nSTONE at 124, 65, -238"));
        assertTrue(plain.contains("Alex broke STONE"));
        assertTrue(plain.contains("Page 1/2 [Next]"));
        Component coordinates = AuditMessages.coordinates("world", 124, 65, -238, true);
        assertNotNull(coordinates.hoverEvent());
        assertNotNull(coordinates.clickEvent());
        assertEquals(ClickEvent.Action.RUN_COMMAND, coordinates.clickEvent().action());
        assertTrue(coordinates.clickEvent().value().startsWith("/log tp "));

        Component lookup = AuditMessages.lookup(Component.text("RIVET ›"), "Logs for Alex",
            "Last 30 minutes • radius 50", List.of(entry), 1, 2, 7, true);
        String lookupPlain = PlainTextComponentSerializer.plainText().serialize(lookup);
        assertTrue(lookupPlain.contains("Logs for Alex"));
        assertTrue(lookupPlain.contains("Page 1/2 [Next]"));
    }

    private static AuditStorage storage() throws Exception {
        return new AuditStorage(Files.createTempFile("rivet-audit-", ".db"),
            throwable -> {
                throw new AssertionError(throwable);
            });
    }

    private static AuditEntry entry(long timestamp, UUID uuid, String name,
                                    AuditAction action, String world, int x, int y, int z,
                                    String target) {
        return new AuditEntry(timestamp, uuid, name, action, world, x, y, z, target,
            1, "before", "after", "metadata");
    }
}
