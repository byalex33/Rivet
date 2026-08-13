package dev.rivet;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class DeathMessagesModuleTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    public void classifiesKillersBeforeEnvironmentalDamage() {
        assertEquals("player", DeathMessagesModule.category("arrow", true, false));
        assertEquals("mob", DeathMessagesModule.category("mob_projectile", false, true));
        assertEquals("fall", DeathMessagesModule.category("fall", false, false));
        assertEquals("fire", DeathMessagesModule.category("lava", false, false));
        assertEquals("drowning", DeathMessagesModule.category("drown", false, false));
        assertEquals("explosion", DeathMessagesModule.category("bad_respawn_point", false, false));
        assertEquals("projectile", DeathMessagesModule.category("trident", false, false));
        assertEquals("void", DeathMessagesModule.category("out_of_world", false, false));
        assertEquals("suffocation", DeathMessagesModule.category("in_wall", false, false));
        assertEquals("magic", DeathMessagesModule.category("indirect_magic", false, false));
        assertEquals("generic", DeathMessagesModule.category("starve", false, false));
        assertEquals("generic", DeathMessagesModule.category(null, false, false));
    }

    @Test
    public void selectsRareVariantsAndFallsBackSafely() {
        assertEquals("rare", DeathMessagesModule.select(List.of("normal"), List.of("fallback"),
            List.of("rare"), .02, .01, 0));
        assertEquals("normal two", DeathMessagesModule.select(
            List.of("normal one", "normal two"), List.of("fallback"), List.of("rare"),
            .02, .5, 1));
        assertEquals("fallback", DeathMessagesModule.select(List.of("", "  "),
            List.of("fallback"), List.of(), .02, .5, Integer.MIN_VALUE));
        assertNull(DeathMessagesModule.select(List.of(), List.of(), List.of(), .02, .5, 0));
        assertEquals("rare", DeathMessagesModule.select(List.of("normal"), List.of(),
            List.of("rare"), 2, .99, 0));
        assertEquals("normal", DeathMessagesModule.select(List.of("normal"), List.of(),
            List.of("rare"), -1, 0, 0));
    }

    @Test
    public void rendersPlayerControlledNamesWithoutMiniMessageInjection() {
        Component hostileName = Component.text("<click:run_command:'/op Alex'>Alex</click>")
            .clickEvent(ClickEvent.runCommand("/op Alex"))
            .append(Component.text(" child").hoverEvent(Component.text("unsafe")));
        Component rendered = DeathMessagesModule.render(
            "<red>%player%</red> was defeated by %killer% in %world% using %weapon%.",
            hostileName, Component.text("Steve"), Component.text("Zombie"),
            Component.text("Sword"), Component.text("world"));

        assertEquals("<click:run_command:'/op Alex'>Alex</click> child was defeated by Steve in world using Sword.",
            PLAIN.serialize(rendered));
        assertFalse(hasClick(rendered));
        assertFalse(hasHover(rendered, HoverEvent.Action.SHOW_TEXT));
    }

    @Test
    public void preservesOnlySafeItemInspectionHoverOnWeaponPlaceholder() {
        Component weapon = Component.text("Alex's Blade")
            .clickEvent(ClickEvent.runCommand("/give @s diamond_sword"))
            .hoverEvent(HoverEvent.showItem(Key.key("minecraft:diamond_sword"), 1));
        Component rendered = DeathMessagesModule.render("%player% used %weapon%.",
            Component.text("Alex"), Component.empty(), Component.empty(), weapon, Component.empty());

        assertEquals("Alex used Alex's Blade.", PLAIN.serialize(rendered));
        assertFalse(hasClick(rendered));
        assertTrue(hasHover(rendered, HoverEvent.Action.SHOW_ITEM));
    }

    @Test
    public void packagesSimpleDefaultsAndAllSupportedPools() {
        var stream = getClass().getResourceAsStream("/settings/death-messages.yml");
        var settings = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(settings.getBoolean("enabled"));
        assertFalse(settings.getBoolean("killer-health"));
        assertEquals(.02, settings.getDouble("rare-chance"), .0001);
        List.of("player", "mob", "fall", "fire", "drowning", "explosion", "projectile",
            "void", "suffocation", "magic", "generic", "rare")
            .forEach(pool -> assertFalse(pool, settings.getStringList(pool).isEmpty()));
    }

    private static boolean hasClick(Component component) {
        return component.clickEvent() != null || component.children().stream()
            .anyMatch(DeathMessagesModuleTest::hasClick);
    }

    private static boolean hasHover(Component component, HoverEvent.Action<?> action) {
        return component.hoverEvent() != null && component.hoverEvent().action() == action
            || component.children().stream().anyMatch(child -> hasHover(child, action));
    }
}
