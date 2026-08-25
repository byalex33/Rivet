package dev.rivet;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class StatisticsModuleTest {
    @Test
    public void formatsCompactFriendlyElapsedTimes() {
        assertEquals("just now", StatisticsModule.friendlyElapsed(4_000));
        assertEquals("47m", StatisticsModule.friendlyElapsed(47 * 60_000L));
        assertEquals("2h 14m", StatisticsModule.friendlyElapsed((2 * 60 + 14) * 60_000L));
        assertEquals("1 day", StatisticsModule.friendlyElapsed(24 * 60 * 60_000L));
        assertEquals("3 days", StatisticsModule.friendlyElapsed(3 * 24 * 60 * 60_000L));
    }

    @Test
    public void relativeTimeComponentsCarryExactTimeHoverText() {
        var value = StatisticsModule.timeComponent("2h 14m ago", "13 Aug 2026 at 01:00 BST");
        assertEquals("2h 14m ago", PlainTextComponentSerializer.plainText().serialize(value));
        assertNotNull(value.hoverEvent());
        assertEquals("Exact time: 13 Aug 2026 at 01:00 BST",
            PlainTextComponentSerializer.plainText().serialize(
                (net.kyori.adventure.text.Component) value.hoverEvent().value()));
    }

    @Test
    public void rendersEverySeenPlaceholder() {
        var rendered = RivetMiniMessage.miniMessage().deserialize(
            "%first_join%|%last_login%|%last_logout%|%session%|%coordinates%|%last_death%|%death_location%",
            Placeholder.unparsed("first_join", "first"),
            Placeholder.unparsed("last_login", "login"),
            Placeholder.unparsed("last_logout", "logout"),
            Placeholder.unparsed("session", "session"),
            Placeholder.unparsed("coordinates", "1, 2, 3"),
            Placeholder.unparsed("last_death", "death"),
            Placeholder.unparsed("death_location", "world 1, 2, 3"));

        assertEquals("first|login|logout|session|1, 2, 3|death|world 1, 2, 3",
            PlainTextComponentSerializer.plainText().serialize(rendered));
    }

    @Test
    public void roundTripsOnlyNeededLocationData() {
        YamlConfiguration data = new YamlConfiguration();
        StatisticsModule.StoredLocation stored = new StatisticsModule.StoredLocation(
            "world_nether", -1.2, 64.9, 42.8);
        StatisticsModule.storeLocation(data, "players.test.last-location", stored);

        StatisticsModule.StoredLocation loaded = StatisticsModule.storedLocation(
            data, "players.test.last-location");
        assertEquals(stored, loaded);
        assertEquals("-2, 64, 42", loaded.coordinates());
        assertNull(StatisticsModule.storedLocation(data, "players.missing.last-location"));
    }

    @Test
    public void migratesOnlyUntouchedLegacySeenMessages() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("seen.online.actions", List.of(
            "[message] <white><#f72a4c>%player%</#f72a4c> is currently online (joined <#f72a4c>%duration%</#f72a4c> ago).</white>"));
        settings.set("seen.offline.actions", List.of(
            "[message] <white><#f72a4c>%player%</#f72a4c> was last seen <#f72a4c>%duration%</#f72a4c> ago (%timestamp%).</white>"));
        assertTrue(StatisticsModule.migrateSeenV2(settings));
        assertEquals(5, settings.getStringList("seen.online.actions").size());
        assertTrue(settings.getStringList("seen.online.actions").getFirst().contains("%tag%"));

        settings.set("seen.online.actions", List.of("[message] My custom output"));
        assertFalse(StatisticsModule.migrateSeenV2(settings));
        assertEquals(List.of("[message] My custom output"),
            settings.getStringList("seen.online.actions"));
    }

    @Test
    public void migratesUnreadableSeenV2DefaultsWithoutTouchingCustomLayouts() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("seen.online.actions", List.of(
            "[message] %tag% <#f72a4c><bold>%player%</bold></#f72a4c> <green>Online</green> <dark_gray>•</dark_gray> <white>Online for %session%</white>",
            "[message] <dark_gray>First join:</dark_gray> <white>%first_join%</white> <dark_gray>• Last login:</dark_gray> <white>%last_login%</white>",
            "[message] <dark_gray>Last logout:</dark_gray> <white>%last_logout%</white> <dark_gray>• Playtime:</dark_gray> <white>%playtime%</white>"));
        settings.set("seen.offline.actions", List.of("[message] My custom offline layout"));
        settings.set("seen.staff-location.actions", List.of(
            "[message] <dark_gray>Location:</dark_gray> <white>%world%</white> <dark_gray>•</dark_gray> <white>%coordinates%</white>"));

        assertTrue(StatisticsModule.migrateSeenV2(settings));
        assertEquals(StatisticsModule.defaultSeenActions(true),
            settings.getStringList("seen.online.actions"));
        assertEquals(List.of("[message] My custom offline layout"),
            settings.getStringList("seen.offline.actions"));
        assertEquals(StatisticsModule.defaultStaffLocationActions(),
            settings.getStringList("seen.staff-location.actions"));
    }

    @Test
    public void packagesSeenV2MessagesAndPermissions() {
        YamlConfiguration settings = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/settings/statistics.yml"), StandardCharsets.UTF_8));
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));

        assertEquals(5, settings.getStringList("seen.online.actions").size());
        assertEquals(5, settings.getStringList("seen.offline.actions").size());
        assertTrue(settings.getStringList("seen.online.actions").stream()
            .noneMatch(line -> line.contains("<dark_gray>")));
        assertTrue(settings.getStringList("seen.offline.actions").stream()
            .noneMatch(line -> line.contains("<dark_gray>")));
        assertTrue(settings.isList("seen.staff-location.actions"));
        assertTrue(settings.isList("seen.staff-death.actions"));
        assertEquals("op", plugin.getString("permissions.rivet.seen.location.default"));
    }
}
