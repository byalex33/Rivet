package dev.rivet;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.WorldType;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.block.Action;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class RivetPluginTest {
    @Test
    public void mapsGamemodeCommands() {
        assertEquals(GameMode.CREATIVE, RivetPlugin.gameModeFor("gmc"));
        assertEquals(GameMode.SURVIVAL, RivetPlugin.gameModeFor("gms"));
        assertNull(RivetPlugin.gameModeFor("flat"));
    }

    @Test
    public void onlyCreativeAndSpectatorKeepNaturalFlight() {
        assertEquals(true, RivetPlugin.hasNaturalFlight(GameMode.CREATIVE));
        assertEquals(true, RivetPlugin.hasNaturalFlight(GameMode.SPECTATOR));
        assertEquals(false, RivetPlugin.hasNaturalFlight(GameMode.SURVIVAL));
        assertEquals(false, RivetPlugin.hasNaturalFlight(GameMode.ADVENTURE));
    }

    @Test
    public void onlyBreedsReadyAnimalsOutsideLoveMode() {
        assertEquals(true, AutoBreeder.readyToBreed(true, 0));
        assertEquals(false, AutoBreeder.readyToBreed(false, 0));
        assertEquals(false, AutoBreeder.readyToBreed(true, 600));
    }

    @Test
    public void autoBreederSupportsAnimalSpecificFoodAndHolograms() {
        assertEquals(Material.WHEAT, AutoBreeder.breedingFood(EntityType.COW));
        assertEquals(Material.CARROT, AutoBreeder.breedingFood(EntityType.PIG));
        assertEquals(EntityType.NAUTILUS, AutoBreeder.supportedAnimal("NAUTILUS"));
        assertNull(AutoBreeder.supportedAnimal("MULE"));
        Arrays.stream(EntityType.values())
            .filter(type -> AutoBreeder.breedingFood(type) != null)
            .forEach(type -> {
                assertEquals(true, Animals.class.isAssignableFrom(type.getEntityClass()));
                assertEquals(type.name() + "_SPAWN_EGG",
                    Material.valueOf(type.name() + "_SPAWN_EGG").name());
            });
        assertEquals("Pig Auto Breeder\nCarrot: 128\nAnimals bred: 42",
            PlainTextComponentSerializer.plainText().serialize(
                AutoBreeder.hologram(EntityType.PIG, 128, 42)));
    }

    @Test
    public void growsCapturedMobsWithAQuadraticRampAndSafeCap() {
        assertEquals(1, EggCapture.captureScale(1, 0), .0001);
        assertEquals(6, EggCapture.captureScale(1, 1), .0001);
        assertEquals(16, EggCapture.captureScale(4, 1), .0001);
    }

    @Test
    public void dropsNativeMobHeadsAtThreePercent() {
        assertEquals(Material.CREEPER_HEAD, RivetPlugin.mobHeadFor(EntityType.CREEPER));
        assertEquals(Material.WITHER_SKELETON_SKULL,
            RivetPlugin.mobHeadFor(EntityType.WITHER_SKELETON));
        assertEquals(true, RivetPlugin.dropsMobHead(EntityType.ZOMBIE, .0299));
        assertEquals(false, RivetPlugin.dropsMobHead(EntityType.ZOMBIE, .03));
        assertEquals(true, RivetPlugin.dropsMobHead(EntityType.COW, 0));
    }

    @Test
    public void supportsEveryVanillaAnimalAndMobHead() {
        Arrays.stream(EntityType.values())
            .filter(type -> type.getEntityClass() != null
                && (Mob.class.isAssignableFrom(type.getEntityClass())
                || type == EntityType.ENDER_DRAGON))
            .forEach(type -> assertEquals(type.name(), true, RivetPlugin.supportsMobHead(type)));
    }

    @Test
    public void validatesSafeWorldNames() {
        assertEquals(true, RivetPlugin.validWorldName("test_world-1"));
        assertEquals(false, RivetPlugin.validWorldName("../world"));
        assertEquals(false, RivetPlugin.validWorldName("world name"));
        assertEquals(false, RivetPlugin.validWorldName("a".repeat(33)));
    }

    @Test
    public void onlyBlocksNaturalSpawnsInFlatWorldsWhenDisabled() {
        assertEquals(true, RivetPlugin.blocksSpawn(WorldType.FLAT, CreatureSpawnEvent.SpawnReason.NATURAL, false));
        assertEquals(false, RivetPlugin.blocksSpawn(WorldType.FLAT, CreatureSpawnEvent.SpawnReason.SPAWNER, false));
        assertEquals(false, RivetPlugin.blocksSpawn(WorldType.NORMAL, CreatureSpawnEvent.SpawnReason.NATURAL, false));
        assertEquals(false, RivetPlugin.blocksSpawn(WorldType.FLAT, CreatureSpawnEvent.SpawnReason.NATURAL, true));
    }

    @Test
    public void parsesItemAmounts() {
        assertEquals(12, RivetPlugin.itemAmount("12"));
        assertEquals(-1, RivetPlugin.itemAmount("lots"));
    }

    @Test
    public void recognizesAxesWithoutMatchingPickaxes() {
        assertEquals(true, TreeFeller.isAxe(org.bukkit.Material.DIAMOND_AXE));
        assertEquals(false, TreeFeller.isAxe(org.bukkit.Material.DIAMOND_PICKAXE));
        assertEquals(false, TreeFeller.isAxe(org.bukkit.Material.STICK));
    }

    @Test
    public void groupsOreVariantsAndRejectsOrdinaryBlocks() {
        assertEquals(true, TreeFeller.isPickaxe(org.bukkit.Material.NETHERITE_PICKAXE));
        assertEquals("DIAMOND_ORE", TreeFeller.oreKey(org.bukkit.Material.DIAMOND_ORE));
        assertEquals("DIAMOND_ORE", TreeFeller.oreKey(org.bukkit.Material.DEEPSLATE_DIAMOND_ORE));
        assertEquals("NETHER_QUARTZ_ORE", TreeFeller.oreKey(org.bukkit.Material.NETHER_QUARTZ_ORE));
        assertEquals("ANCIENT_DEBRIS", TreeFeller.oreKey(org.bukkit.Material.ANCIENT_DEBRIS));
        assertNull(TreeFeller.oreKey(org.bukkit.Material.STONE));
    }

    @Test
    public void colorsOreActionBarNames() {
        assertEquals(0xAAAAAA, TreeFeller.oreColor(org.bukkit.Material.COAL_ORE).value());
        assertEquals(0xFFFFFF, TreeFeller.oreColor(org.bukkit.Material.IRON_ORE).value());
        assertEquals(0x55FFFF, TreeFeller.oreColor(org.bukkit.Material.DEEPSLATE_DIAMOND_ORE).value());
        assertEquals(0xFF5555, TreeFeller.oreColor(org.bukkit.Material.REDSTONE_ORE).value());
    }

    @Test
    public void colorsWoodActionBarNames() {
        assertEquals(0xC89B5B, TreeFeller.woodColor(org.bukkit.Material.OAK_LOG).value());
        assertEquals(0x815631, TreeFeller.woodColor(org.bukkit.Material.SPRUCE_LOG).value());
        assertEquals(0xE7A8B7, TreeFeller.woodColor(org.bukkit.Material.CHERRY_LOG).value());
    }

    @Test
    public void onlyCancelsPhysicalFarmlandInteractions() {
        assertEquals(true, RivetPlugin.isCropTrample(Action.PHYSICAL, org.bukkit.Material.FARMLAND));
        assertEquals(false, RivetPlugin.isCropTrample(Action.RIGHT_CLICK_BLOCK, org.bukkit.Material.FARMLAND));
        assertEquals(false, RivetPlugin.isCropTrample(Action.PHYSICAL, org.bukkit.Material.DIRT));
    }

    @Test
    public void graveHologramShowsOwnerCauseAndCoordinates() {
        Component hologram = GraveModule.hologram("Alex", "Alex fell from a high place", 12, 64, -8);
        assertEquals("Alex's Grave\nAlex fell from a high place\nX 12  Y 64  Z -8\nRight-click to reclaim",
            PlainTextComponentSerializer.plainText().serialize(hologram));
    }

    @Test
    public void rendersMiniMessageHologramLinesSafely() {
        Component rendered = HologramModule.renderText(List.of("<red>Hello", "<bold>World"));
        assertEquals("Hello\nWorld", PlainTextComponentSerializer.plainText().serialize(rendered));
    }

    @Test
    public void parsesHologramBackgroundColorsAndRemoval() {
        assertEquals(0, HologramModule.parseBackground("none"));
        assertEquals(0, HologramModule.parseBackground("delete"));
        assertEquals(0x80FF0000, HologramModule.parseBackground("#FF0000"));
        assertEquals(0x40010203, HologramModule.parseBackground("#40010203"));
    }

    @Test
    public void parsesNamedAndHexGlowColors() {
        assertEquals(0xFF5555, GlowModule.parseColor("red").asRGB());
        assertEquals(0x123ABC, GlowModule.parseColor("#123ABC").asRGB());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidGlowColors() {
        GlowModule.parseColor("definitely-not-a-color");
    }

    @Test
    public void calculatesHologramAnimationPresets() {
        double peak = Math.PI / 4.8;
        assertArrayEquals(new float[]{.22f, (float) (peak * 1.5), 2},
            HologramModule.animationValues(HologramModule.Animation.FLOAT_SPIN, peak, 2), .0001f);
        assertArrayEquals(new float[]{0, 0, 2.16f},
            HologramModule.animationValues(HologramModule.Animation.PULSE, peak, 2), .0001f);
    }

    @Test
    public void ordersTreeAnimationFromTopToBottom() {
        assertEquals(List.of("top", "middle", "bottom"),
            TreeFeller.topDown(Map.of(70, "top", 68, "bottom", 69, "middle")));
    }

    @Test
    public void rejectsLogStructuresAndTreesWithoutEnoughLeaves() {
        assertEquals(true, TreeFeller.validStructure(5, 20, 3, 5, 0));
        assertEquals(false, TreeFeller.validStructure(5, 20, 7, 5, 2));
        assertEquals(false, TreeFeller.validStructure(5, 9, 3, 5, 0));
        assertEquals(false, TreeFeller.validStructure(10, 20, 4, 2, 8));
    }

    @Test
    public void rejectsInvalidSavedCoordinates() {
        assertEquals(true, RivetPlugin.validCoordinates(1, 2, 3, 90, 0));
        assertEquals(false, RivetPlugin.validCoordinates(Double.NaN, 2, 3, 90, 0));
        assertEquals(false, RivetPlugin.validCoordinates(1, 2, 3, Float.POSITIVE_INFINITY, 0));
    }

    @Test
    public void normalizesOptionalHomeNames() {
        assertEquals("home", RivetPlugin.homeName(new String[0]));
        assertEquals("mine", RivetPlugin.homeName(new String[] {"Mine"}));
        assertNull(RivetPlugin.homeName(new String[] {"bad name"}));
        assertNull(RivetPlugin.homeName(new String[] {"one", "two"}));
    }

    @Test
    public void filtersTabCompletionsIgnoringCase() {
        assertEquals(List.of("create"), RivetPlugin.completions(List.of("create", "list"), "CR"));
    }

    @Test
    public void formatsChatWithoutParsingPlayerMessagesAsMarkup() {
        Component formatted = ChatModule.format("<player>: <message>", Component.text("Alex"), Component.text("<red>hello"));
        assertEquals("Alex: <red>hello", PlainTextComponentSerializer.plainText().serialize(formatted));
    }

    @Test
    public void replacesBothHeldItemChatTokensWithTheHoverableItem() {
        Component item = Component.text("[Diamond Sword]")
            .hoverEvent(Component.text("Sharpness V"));
        Component message = ChatModule.itemTokens(Component.text("Look: [i] / [ITEM] / [info]"), item);
        assertEquals("Look: [Diamond Sword] / [Diamond Sword] / [info]",
            PlainTextComponentSerializer.plainText().serialize(message));
        assertEquals(true, hasHover(message));
    }

    @Test
    public void resolvesPermissionHierarchyAndWildcards() {
        YamlConfiguration groups = new YamlConfiguration();
        groups.set("groups.default.permissions", List.of("rivet.message"));
        groups.set("groups.staff.parent", "default");
        groups.set("groups.staff.permissions", List.of("rivet.vanish", "rivet.world.*"));
        Set<String> permissions = PermissionModule.groupPermissions(groups, "staff");
        assertEquals(true, permissions.contains("rivet.message"));
        assertEquals(true, PermissionModule.grants(permissions, "rivet.world.reset"));
        assertEquals(false, PermissionModule.grants(permissions, "other.permission"));
    }

    private static boolean hasHover(Component component) {
        return component.hoverEvent() != null || component.children().stream().anyMatch(RivetPluginTest::hasHover);
    }
}
