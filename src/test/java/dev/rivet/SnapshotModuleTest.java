package dev.rivet;

import org.bukkit.Material;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class SnapshotModuleTest {
    static {
        ConfigurationSerialization.registerClass(TestItem.class);
    }

    @Test
    public void serializesEverySlotEquipmentAndPlayerState() throws Exception {
        SnapshotState original = detailedState();

        byte[] encoded = SnapshotCodec.encode(original);
        SnapshotState decoded = SnapshotCodec.decode(encoded);

        assertTrue(encoded.length > 0);
        assertEquals(SnapshotCodec.hash(encoded), SnapshotCodec.hash(SnapshotCodec.encode(decoded)));
        assertItems(original.inventory(), decoded.inventory());
        assertItems(original.armour(), decoded.armour());
        assertItem(original.offhand(), decoded.offhand());
        assertEquals(27, decoded.xpLevel());
        assertEquals(0.625F, decoded.xpProgress(), 0.0001F);
        assertEquals(17.5D, decoded.health(), 0.0001D);
        assertEquals(16, decoded.hunger());
        assertEquals(4.25F, decoded.saturation(), 0.0001F);
    }

    @Test
    public void createsDeathAndPreRestoreSnapshotsWithMetadata() throws Exception {
        try (SnapshotStorage storage = storage()) {
            UUID player = UUID.randomUUID();
            SnapshotSettings settings = settings(10, 3_650, true);
            SnapshotRecord death = storage.saveNow(captured(player, "Alex", "DEATH",
                System.currentTimeMillis() - 1_000, "FALL", detailedState()), settings);
            SnapshotRecord safety = storage.saveNow(captured(player, "Alex", "PRE_RESTORE",
                System.currentTimeMillis(), null, detailedState()), settings);

            assertEquals(List.of("PRE_RESTORE", "DEATH"), storage.listNow(player, 10).stream()
                .map(SnapshotRecord::reason).toList());
            SnapshotRecord loadedDeath = storage.loadNow(death.id());
            assertEquals("FALL", loadedDeath.deathCause());
            assertEquals("world", loadedDeath.world());
            assertEquals(12.25D, loadedDeath.x(), 0.0001D);
            assertItems(detailedState().inventory(), loadedDeath.state().inventory());
            assertEquals("PRE_RESTORE", storage.loadNow(safety.id()).reason());
        }
    }

    @Test
    public void reusesIdenticalBlobsOnlyWhenDeduplicationIsEnabled() throws Exception {
        UUID player = UUID.randomUUID();
        SnapshotState state = detailedState();
        long now = System.currentTimeMillis();
        try (SnapshotStorage storage = storage()) {
            SnapshotSettings deduplicated = settings(10, 3_650, true);
            storage.saveNow(captured(player, "Alex", "DEATH", now - 1_000, "FALL", state),
                deduplicated);
            storage.saveNow(captured(player, "Alex", "PRE_RESTORE", now, null, state),
                deduplicated);

            assertEquals(2, storage.snapshotCountNow());
            assertEquals(1, storage.blobCountNow());
        }

        try (SnapshotStorage storage = storage()) {
            SnapshotSettings separate = settings(10, 3_650, false);
            storage.saveNow(captured(player, "Alex", "DEATH", now - 1_000, "FALL", state), separate);
            storage.saveNow(captured(player, "Alex", "PRE_RESTORE", now, null, state), separate);

            assertEquals(2, storage.snapshotCountNow());
            assertEquals(2, storage.blobCountNow());
        }
    }

    @Test
    public void cleanupEnforcesRetentionAndMaximumAndRemovesOrphanedBlobs() throws Exception {
        try (SnapshotStorage storage = storage()) {
            long now = System.currentTimeMillis();
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            SnapshotSettings generous = settings(10, 3_650, true);
            storage.saveNow(captured(first, "Alex", "DEATH", now - 3_000, "FALL",
                stateWithAmount(1)), generous);
            storage.saveNow(captured(first, "Alex", "DEATH", now - 2_000, "FIRE",
                stateWithAmount(2)), generous);
            storage.saveNow(captured(first, "Alex", "DEATH", now - 1_000, "LAVA",
                stateWithAmount(3)), generous);
            storage.saveNow(captured(second, "Steve", "DEATH",
                now - Duration.ofDays(31).toMillis(), "VOID", stateWithAmount(4)), generous);

            SnapshotStorage.CleanupResult result = storage.cleanupNow(
                now - Duration.ofDays(30).toMillis(), 2);

            assertEquals(1, result.expiredSnapshots());
            assertEquals(1, result.excessSnapshots());
            assertEquals(2, result.orphanedBlobs());
            assertEquals(2, storage.snapshotCountNow());
            assertEquals(2, storage.blobCountNow());
            List<Integer> amounts = new ArrayList<>();
            for (SnapshotRecord record : storage.listNow(first, 10)) {
                amounts.add(storage.loadNow(record.id()).state().inventory()[0].getAmount());
            }
            assertEquals(List.of(3, 2), amounts);
        }
    }

    @Test
    public void savingImmediatelyPrunesSnapshotsAboveThePlayerMaximum() throws Exception {
        try (SnapshotStorage storage = storage()) {
            long now = System.currentTimeMillis();
            UUID player = UUID.randomUUID();
            SnapshotSettings limited = settings(2, 3_650, true);
            storage.saveNow(captured(player, "Alex", "FIRST", now - 2_000, null,
                stateWithAmount(1)), limited);
            storage.saveNow(captured(player, "Alex", "SECOND", now - 1_000, null,
                stateWithAmount(2)), limited);
            storage.saveNow(captured(player, "Alex", "THIRD", now, null,
                stateWithAmount(3)), limited);

            assertEquals(2, storage.snapshotCountNow());
            assertEquals(List.of("THIRD", "SECOND"), storage.listNow(player, 10).stream()
                .map(SnapshotRecord::reason).toList());
            SnapshotStorage.CleanupResult result = storage.cleanupNow(0, 2);
            assertEquals(1, result.orphanedBlobs());
        }
    }

    @Test
    public void categoryLimitsPruneOnlyMatchingBackups() throws Exception {
        try (SnapshotStorage storage = storage()) {
            UUID player = UUID.randomUUID();
            long now = System.currentTimeMillis();
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.set("save-limits.total", -1);
            configuration.set("save-limits.death", 1);
            SnapshotSettings limited = new SnapshotSettings(configuration);
            storage.saveNow(captured(player, "Alex", "DEATH", now - 2_000, "FALL",
                stateWithAmount(1)), limited);
            storage.saveNow(captured(player, "Alex", "MANUAL", now - 1_000, null,
                stateWithAmount(2)), limited);
            storage.saveNow(captured(player, "Alex", "DEATH", now, "LAVA",
                stateWithAmount(3)), limited);

            assertEquals(List.of("DEATH", "MANUAL"), storage.listNow(player, -1).stream()
                .map(SnapshotRecord::reason).toList());
        }
    }

    @Test
    public void searchesMaterialsAcrossInventoryEquipmentAndOffhand() {
        assertTrue(SnapshotModule.matchesSearch(detailedState(), "diamond"));
        assertTrue(SnapshotModule.matchesSearch(detailedState(), "shield"));
        assertFalse(SnapshotModule.matchesSearch(detailedState(), "netherite"));
    }

    @Test
    public void migratesOnlyTheUntouchedLegacyUsageMessage() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("messages.usage.actions", List.of(
            "[message] <white>%tag% Usage: /snapshot &lt;player&gt;</white>"));
        assertTrue(SnapshotModule.migrateLegacyConfiguration(configuration));
        assertTrue(configuration.getStringList("messages.usage.actions").getFirst()
            .contains("saveall"));
        configuration.set("messages.usage.actions", List.of("[message] Custom usage"));
        assertFalse(SnapshotModule.migrateLegacyConfiguration(configuration));
        assertEquals(List.of("[message] Custom usage"),
            configuration.getStringList("messages.usage.actions"));
    }

    @Test
    public void allSnapshotGuiHoldersAreReadOnlyAndConfirmationOrdersSafetyFirst() {
        UUID uuid = UUID.randomUUID();
        SnapshotModule.SnapshotTarget target = new SnapshotModule.SnapshotTarget(uuid, "Alex");
        SnapshotRecord record = new SnapshotRecord(1, uuid, "Alex", "DEATH", 1,
            "world", 0, 64, 0, "FALL", "blob", detailedState());

        assertTrue(SnapshotModule.readOnlyHolder(
            new SnapshotModule.SnapshotListHolder(target, List.of(record), 0)));
        assertTrue(SnapshotModule.readOnlyHolder(
            new SnapshotModule.SnapshotPreviewHolder(target, record)));
        assertTrue(SnapshotModule.readOnlyHolder(
            new SnapshotModule.SnapshotConfirmHolder(target, record)));
        assertFalse(SnapshotModule.readOnlyHolder(new InventoryHolder() {
            @Override public Inventory getInventory() { return null; }
        }));
        assertEquals(SnapshotModule.RestoreStep.CONFIRM,
            SnapshotModule.restoreStep(true, false, true));
        assertEquals(SnapshotModule.RestoreStep.SAFETY_SNAPSHOT,
            SnapshotModule.restoreStep(true, true, true));
        assertEquals(SnapshotModule.RestoreStep.APPLY,
            SnapshotModule.restoreStep(false, false, false));
        assertEquals(1D, SnapshotModule.restorableHealth("DEATH", 0D), 0D);
        assertEquals(0D, SnapshotModule.restorableHealth("PRE_RESTORE", 0D), 0D);
    }

    @Test
    public void restoreReplacesExactSlotsEquipmentXpHealthAndFood() {
        SnapshotState state = detailedState();
        RecordingRestoreTarget target = new RecordingRestoreTarget();

        SnapshotModule.applyState(state, target);

        assertEquals(List.of("clear", "inventory", "armour", "offhand", "xp", "health", "food"),
            target.operations);
        assertItems(state.inventory(), target.inventory);
        assertNull(target.inventory[1]);
        assertItems(state.armour(), target.armour);
        assertItem(state.offhand(), target.offhand);
        assertEquals(27, target.level);
        assertEquals(0.625F, target.progress, 0.0001F);
        assertEquals(17.5D, target.health, 0.0001D);
        assertEquals(16, target.hunger);
        assertEquals(4.25F, target.saturation, 0.0001F);
    }

    @Test
    public void auditRecordsOnlySnapshotMetadataAndPackagesSafeDefaults() {
        UUID player = UUID.randomUUID();
        SnapshotRecord record = new SnapshotRecord(42, player, "Alex", "DEATH", 1,
            "world", 0, 64, 0, "FALL", "secret-blob-key", detailedState());
        String metadata = AuditModule.snapshotMetadata(record);

        assertTrue(metadata.contains("snapshot_id=42"));
        assertTrue(metadata.contains("target_uuid=" + player));
        assertTrue(metadata.contains("target_name=Alex"));
        assertTrue(metadata.contains("reason=DEATH"));
        assertFalse(metadata.contains("secret-blob-key"));
        assertFalse(metadata.toLowerCase().contains("inventory"));
        assertFalse(metadata.contains("DIAMOND"));

        var resource = getClass().getResourceAsStream("/settings/snapshots.yml");
        assertNotNull(resource);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(
            new InputStreamReader(resource, StandardCharsets.UTF_8));
        SnapshotSettings defaults = new SnapshotSettings(configuration);
        assertEquals(-1, defaults.maxPerPlayer());
        assertEquals(14, defaults.retentionDays());
        assertTrue(defaults.deduplicate());
        assertTrue(defaults.saveOnDeath());
        assertTrue(defaults.allCategory());
        assertTrue(defaults.automaticBackups());
        assertEquals(180, defaults.automaticIntervalSeconds());
        assertTrue(defaults.enabled("ENDER_CHEST"));
        assertTrue(defaults.enabled("CONTAINER_CLOSE"));
        assertEquals(-1, defaults.limit("DEATH"));
        assertEquals(1_000, defaults.searchMaximumMatches());
        assertTrue(defaults.createSafetySnapshot());
        assertTrue(defaults.requireConfirmation());
        assertTrue(defaults.auditCreations());
        for (String path : List.of("messages.denied.actions", "messages.denied-others.actions",
                 "messages.denied-restore.actions", "messages.denied-teleport.actions",
                 "messages.denied-export.actions", "messages.searching.actions",
                 "messages.cleaned-up.actions", "messages.exported.actions",
                 "messages.restored.actions", "messages.restored-target.actions",
                 "messages.safety-failed.actions", "messages.restore-failed.actions")) {
            assertTrue(path, configuration.isList(path));
        }
    }

    private static SnapshotStorage storage() throws Exception {
        Path directory = Files.createTempDirectory("rivet-snapshots-");
        return new SnapshotStorage(directory.resolve("snapshots.db"), failure -> {
            throw new AssertionError(failure);
        });
    }

    private static SnapshotSettings settings(int maximum, int retention, boolean deduplicate) {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("max-per-player", maximum);
        configuration.set("retention-days", retention);
        configuration.set("deduplicate", deduplicate);
        return new SnapshotSettings(configuration);
    }

    private static CapturedSnapshot captured(UUID uuid, String name, String reason,
                                             long timestamp, String deathCause,
                                             SnapshotState state) {
        return new CapturedSnapshot(uuid, name, reason, timestamp, "world",
            12.25, 64.5, -31.75, deathCause, state);
    }

    private static SnapshotState detailedState() {
        ItemStack[] inventory = new ItemStack[36];
        inventory[0] = item(Material.DIAMOND, 17);
        inventory[8] = item(Material.ENDER_PEARL, 4);
        inventory[9] = item(Material.COOKED_BEEF, 12);
        inventory[17] = item(Material.OAK_LOG, 32);
        inventory[35] = item(Material.COMPASS, 1);
        ItemStack[] armour = {
            item(Material.DIAMOND_BOOTS, 1),
            item(Material.DIAMOND_LEGGINGS, 1),
            item(Material.DIAMOND_CHESTPLATE, 1),
            item(Material.DIAMOND_HELMET, 1)
        };
        return new SnapshotState(inventory, armour, item(Material.SHIELD, 1),
            27, 0.625F, 17.5D, 16, 4.25F);
    }

    private static SnapshotState stateWithAmount(int amount) {
        ItemStack[] inventory = new ItemStack[36];
        inventory[0] = item(Material.STONE, amount);
        return new SnapshotState(inventory, new ItemStack[4], null,
            amount, 0, 20, 20, 5);
    }

    private static void assertItems(ItemStack[] expected, ItemStack[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertItem(expected[index], actual[index]);
        }
    }

    private static void assertItem(ItemStack expected, ItemStack actual) {
        if (expected == null) {
            assertNull(actual);
        } else {
            assertNotNull(actual);
            assertEquals(expected.getType(), actual.getType());
            assertEquals(expected.getAmount(), actual.getAmount());
        }
    }

    private static ItemStack item(Material material, int amount) {
        return new TestItem(material, amount);
    }

    @SerializableAs("RivetSnapshotTestItem")
    public static final class TestItem extends ItemStack {
        private final Material material;
        private final int amount;

        public TestItem(Material material, int amount) {
            super();
            this.material = material;
            this.amount = amount;
        }

        @Override public Material getType() { return material; }
        @Override public int getAmount() { return amount; }
        @Override public TestItem clone() { return new TestItem(material, amount); }

        @Override
        public Map<String, Object> serialize() {
            return Map.of("material", material.name(), "amount", amount);
        }

        public static TestItem deserialize(Map<String, Object> values) {
            return new TestItem(Material.valueOf((String) values.get("material")),
                ((Number) values.get("amount")).intValue());
        }
    }

    private static final class RecordingRestoreTarget implements SnapshotModule.RestoreTarget {
        private final List<String> operations = new ArrayList<>();
        private ItemStack[] inventory;
        private ItemStack[] armour;
        private ItemStack offhand;
        private int level;
        private float progress;
        private double health;
        private int hunger;
        private float saturation;

        @Override public void clearInventory() { operations.add("clear"); }
        @Override public void setInventory(ItemStack[] contents) {
            operations.add("inventory");
            inventory = contents;
        }
        @Override public void setArmour(ItemStack[] contents) {
            operations.add("armour");
            armour = contents;
        }
        @Override public void setOffhand(ItemStack item) {
            operations.add("offhand");
            offhand = item;
        }
        @Override public void setXp(int savedLevel, float savedProgress) {
            operations.add("xp");
            level = savedLevel;
            progress = savedProgress;
        }
        @Override public void setHealth(double savedHealth) {
            operations.add("health");
            health = savedHealth;
        }
        @Override public void setFood(int savedHunger, float savedSaturation) {
            operations.add("food");
            hunger = savedHunger;
            saturation = savedSaturation;
        }
        @Override public double maxHealth() { return 20; }
    }
}
