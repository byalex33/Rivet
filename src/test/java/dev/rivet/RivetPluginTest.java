package dev.rivet;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Location;
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

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
        assertEquals(true, RivetPlugin.dropsMobHead(EntityType.COW, .49, .5));
        assertEquals(false, RivetPlugin.dropsMobHead(EntityType.COW, .5, .5));
    }

    @Test
    public void packagesEveryModularDefault() {
        var modulesResource = getClass().getResourceAsStream("/modules.yml");
        assertNotNull(modulesResource);
        YamlConfiguration modules = YamlConfiguration.loadConfiguration(
            new InputStreamReader(modulesResource, StandardCharsets.UTF_8));
        assertEquals(Set.copyOf(RivetConfig.MODULES), modules.getKeys(false));
        assertEquals(true, modules.getValues(false).values().stream()
            .allMatch(Boolean.class::isInstance));
        RivetConfig.MODULES.forEach(module ->
            assertNotNull(module, getClass().getResource("/settings/" + module + ".yml")));

        var globalResource = getClass().getResourceAsStream("/config.yml");
        assertNotNull(globalResource);
        YamlConfiguration global = YamlConfiguration.loadConfiguration(
            new InputStreamReader(globalResource, StandardCharsets.UTF_8));
        assertEquals(Set.of("effects"), global.getKeys(false));
    }

    @Test
    public void routesEveryDeclaredCommandToItsModule() {
        var pluginResource = getClass().getResourceAsStream("/plugin.yml");
        assertNotNull(pluginResource);
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
            new InputStreamReader(pluginResource, StandardCharsets.UTF_8));
        assertEquals(68, plugin.getConfigurationSection("commands").getKeys(false).size());
        plugin.getConfigurationSection("commands").getKeys(false)
            .stream().filter(command -> !command.equals("rivet"))
            .forEach(command -> assertNotNull(command, RivetPlugin.moduleForCommand(command)));
        assertEquals("chat", RivetPlugin.moduleForCommand("msg"));
        assertEquals("homes", RivetPlugin.moduleForCommand("home"));
        assertEquals("warps", RivetPlugin.moduleForCommand("warp"));
        assertEquals("worlds", RivetPlugin.moduleForCommand("flatworld"));
        assertEquals("staff", RivetPlugin.moduleForCommand("fly"));
        assertEquals("environment", RivetPlugin.moduleForCommand("thunder"));
        assertEquals("inventory", RivetPlugin.moduleForCommand("i"));
        assertEquals("permissions", RivetPlugin.moduleForCommand("perm"));
        assertEquals("holograms", RivetPlugin.moduleForCommand("hologram"));
        assertEquals("glow", RivetPlugin.moduleForCommand("glow"));
        assertEquals("spawn", RivetPlugin.moduleForCommand("setspawn"));
        assertEquals("tpa", RivetPlugin.moduleForCommand("tpaccept"));
        assertEquals("graves", RivetPlugin.moduleForCommand("back"));
        assertEquals("inventory", RivetPlugin.moduleForCommand("invsee"));
        assertEquals("mob-heads", RivetPlugin.moduleForCommand("head"));
        assertEquals("poses", RivetPlugin.moduleForCommand("crawl"));
        assertEquals("backpacks", RivetPlugin.moduleForCommand("backpack"));
        assertEquals("daily", RivetPlugin.moduleForCommand("daily"));
        assertEquals("rtp", RivetPlugin.moduleForCommand("rtp"));
        assertEquals("near", RivetPlugin.moduleForCommand("near"));
        assertNull(RivetPlugin.moduleForCommand("rivet"));
        assertNull(RivetPlugin.moduleForCommand("unknown"));
    }

    @Test
    public void resolvesTheLargestPermittedBackpackWithoutShrinkingTheDefault() {
        assertEquals(3, BackpacksModule.resolvedRows(3, row -> false));
        assertEquals(6, BackpacksModule.resolvedRows(2, row -> row == 4 || row == 6));
        assertEquals(6, BackpacksModule.resolvedRows(99, row -> false));
        assertEquals(1, BackpacksModule.resolvedRows(0, row -> false));
    }

    @Test
    public void backpackSavesVisibleSlotsWithoutDeletingHiddenOverflow() {
        assertEquals(Arrays.asList("new", null, "hidden-a", "hidden-b"),
            BackpacksModule.mergeContents(List.of("old", "old", "hidden-a", "hidden-b"),
                Arrays.asList("new", null), 4));
    }

    @Test
    public void evaluatesDailyClaimsAndStreakResetsFromServerTime() {
        assertEquals(new DailyModule.ClaimState(false, 3, 1_000),
            DailyModule.evaluate(1_000, 3, 10_000, 20_000, 10_000));
        assertEquals(new DailyModule.ClaimState(true, 4, 0),
            DailyModule.evaluate(1_000, 3, 10_000, 20_000, 11_000));
        assertEquals(new DailyModule.ClaimState(true, 1, 0),
            DailyModule.evaluate(1_000, 3, 10_000, 20_000, 21_000));
        assertEquals(new DailyModule.ClaimState(false, 3, 20_000),
            DailyModule.evaluate(50_000, 3, 10_000, 20_000, 40_000));
    }

    @Test
    public void cyclesOnlyAcrossConfiguredDailyRewardDays() {
        YamlConfiguration config = new YamlConfiguration();
        config.createSection("rewards.1");
        config.createSection("rewards.2");
        assertEquals(1, DailyModule.rewardDay(config.getConfigurationSection("rewards"), 3, true));
        assertEquals(-1, DailyModule.rewardDay(config.getConfigurationSection("rewards"), 3, false));
    }

    @Test
    public void validatesRtpRadiiBiomesAndDangerousGround() {
        assertEquals(true, RtpModule.validRadius(100, 500));
        assertEquals(false, RtpModule.validRadius(500, 100));
        assertEquals(100, RtpModule.randomRadius(100, 500, 0), .0001);
        assertEquals(true, RtpModule.biomeAllowed("minecraft:plains", List.of("plains"), List.of()));
        assertEquals(false, RtpModule.biomeAllowed("minecraft:desert", List.of(), List.of("desert")));
        assertEquals(true, RtpModule.safeGround(true, Material.GRASS_BLOCK));
        assertEquals(false, RtpModule.safeGround(true, Material.MAGMA_BLOCK));
    }

    @Test
    public void persistsDirectionalIgnoreRelationships() {
        UUID owner = UUID.randomUUID();
        UUID ignored = UUID.randomUUID();
        YamlConfiguration data = new YamlConfiguration();
        data.set("ignored." + owner, List.of(ignored.toString()));
        assertEquals(true, ChatModule.ignores(data, owner, ignored));
        assertEquals(false, ChatModule.ignores(data, ignored, owner));
    }

    @Test
    public void socialSpyNeverDuplicatesParticipantMessages() {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID spy = UUID.randomUUID();
        assertEquals(true, ChatModule.shouldReceiveSpy(spy, sender, recipient, true, true));
        assertEquals(false, ChatModule.shouldReceiveSpy(sender, sender, recipient, true, true));
        assertEquals(false, ChatModule.shouldReceiveSpy(recipient, sender, recipient, true, true));
        assertEquals(false, ChatModule.shouldReceiveSpy(spy, sender, recipient, false, true));
    }

    @Test
    public void repairsOnlyDamagedDurableItemsAndValidatesItemText() {
        assertEquals(true, ItemTools.repairEligible(true, 1));
        assertEquals(false, ItemTools.repairEligible(true, 0));
        assertEquals(false, ItemTools.repairEligible(false, 1));
        assertEquals(true, ItemTools.validText("Useful name", 20));
        assertEquals(false, ItemTools.validText("", 20));
        assertEquals(false, ItemTools.validText("bad\nname", 20));
        assertEquals(false, ItemTools.validText("too long", 3));
    }

    @Test
    public void detectsModuleSwitchChangesWithoutHotApplyingThem() {
        YamlConfiguration configured = new YamlConfiguration();
        RivetConfig.MODULES.forEach(module -> configured.set(module, true));
        Map<String, Boolean> active = new java.util.HashMap<>();
        RivetConfig.MODULES.forEach(module -> active.put(module, true));
        configured.set("daily", false);
        assertEquals(List.of("daily"), RivetConfig.changedModules(active, configured));
    }

    @Test
    public void disabledModuleCommandsAreCaughtByTheCentralGate() {
        assertEquals(true, RivetPlugin.commandDisabled("backpack", module -> false));
        assertEquals(false, RivetPlugin.commandDisabled("backpack", module -> true));
        assertEquals(false, RivetPlugin.commandDisabled("rivet", module -> false));
    }

    @Test
    public void migratesLegacySectionsWithoutOverwritingNewData() {
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("homes.player.world", "old-world");
        legacy.set("homes.player.x", 12);
        YamlConfiguration current = new YamlConfiguration();
        current.set("homes.player.world", "new-world");

        RivetConfig.copyMissing(legacy.getConfigurationSection("homes"), current, "homes");

        assertEquals("new-world", current.getString("homes.player.world"));
        assertEquals(12, current.getInt("homes.player.x"));
    }

    @Test
    public void expiresTpaRequestsAndCalculatesCooldowns() {
        assertEquals(false, TpaModule.expired(1_000, 60_000, 60_999));
        assertEquals(true, TpaModule.expired(1_000, 60_000, 61_000));
        assertEquals(30, TpaModule.cooldownRemaining(1_000, 30, 1_000));
        assertEquals(1, TpaModule.cooldownRemaining(1_000, 30, 30_999));
        assertEquals(0, TpaModule.cooldownRemaining(1_000, 30, 31_000));
    }

    @Test
    public void calculatesKitCooldowns() {
        assertEquals(60, KitsModule.cooldownRemaining(10_000, 60, 10_000));
        assertEquals(0, KitsModule.cooldownRemaining(10_000, 60, 70_000));
    }

    @Test
    public void transitionsToAutomaticAfkAtTheThreshold() {
        assertEquals(false, AfkModule.shouldAutoAfk(1_000, 60_000, 60_999));
        assertEquals(true, AfkModule.shouldAutoAfk(1_000, 60_000, 61_000));
        assertEquals(false, AfkModule.shouldAutoAfk(1_000, 0, 100_000));
    }

    @Test
    public void appliesGraveOwnershipAndExpiryRules() {
        java.util.UUID owner = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        assertEquals(true, GraveModule.canOpen(owner, owner, 1_000, true, 0, 10_000));
        assertEquals(false, GraveModule.canOpen(owner, other, 1_000, true, 60, 60_999));
        assertEquals(true, GraveModule.canOpen(owner, other, 1_000, true, 60, 61_000));
        assertEquals(true, GraveModule.canOpen(owner, other, 1_000, false, 0, 1_000));
        assertEquals(false, GraveModule.expired(1_000, 60, 60_999));
        assertEquals(true, GraveModule.expired(1_000, 60, 61_000));
    }

    @Test
    public void onlyCancelsDelayedTeleportsForActualMovement() {
        Location origin = new Location(null, 1, 2, 3, 0, 0);
        assertEquals(false, DelayedTeleport.moved(origin, new Location(null, 1, 2, 3, 90, 0)));
        assertEquals(true, DelayedTeleport.moved(origin, new Location(null, 1.2, 2, 3)));
    }

    @Test
    public void validatesVisibleNicknameLengthAndControls() {
        assertEquals(true, NicknameModule.validNickname("Alex", 16));
        assertEquals(false, NicknameModule.validNickname("", 16));
        assertEquals(false, NicknameModule.validNickname("a".repeat(17), 16));
        assertEquals(false, NicknameModule.validNickname("bad\nname", 16));
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
