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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    public void mapsBreederBlocksToChunksIncludingNegativeCoordinates() {
        assertEquals(true, AutoBreeder.sameChunk(0, 15, 0, 0));
        assertEquals(true, AutoBreeder.sameChunk(16, 31, 1, 1));
        assertEquals(true, AutoBreeder.sameChunk(-1, -16, -1, -1));
        assertEquals(false, AutoBreeder.sameChunk(-17, 0, -1, 0));
    }

    @Test
    public void autoBreederSupportsAnimalSpecificFoodAndHolograms() {
        assertEquals(Material.WHEAT, AutoBreeder.breedingFood(EntityType.COW));
        assertEquals(Material.CARROT, AutoBreeder.breedingFood(EntityType.PIG));
        assertEquals(EntityType.NAUTILUS, AutoBreeder.supportedAnimal("NAUTILUS"));
        assertEquals(EntityType.MOOSHROOM, AutoBreeder.supportedAnimal("mooshroom"));
        assertNull(AutoBreeder.supportedAnimal("MULE"));
        Arrays.stream(EntityType.values())
            .filter(type -> AutoBreeder.breedingFood(type) != null)
            .forEach(type -> {
                assertEquals(true, Animals.class.isAssignableFrom(type.getEntityClass()));
                assertEquals(type.name() + "_SPAWN_EGG",
                    Material.valueOf(type.name() + "_SPAWN_EGG").name());
            });
        assertEquals("Pig Auto Breeder\nCarrot: 128\nAnimals bred: 42\nNext breed in: 3s",
            PlainTextComponentSerializer.plainText().serialize(
                AutoBreeder.hologram(EntityType.PIG, 128, 42, 3)));
        assertEquals(4, AutoBreeder.collectableOutput(60, 10, 64));
        assertEquals(0, AutoBreeder.collectableOutput(64, 10, 64));
        assertEquals(10, AutoBreeder.collectableOutput(0, 10, 64));
        assertEquals(Integer.MAX_VALUE,
            AutoBreeder.saturatedAdd(Integer.MAX_VALUE - 5, 10));
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
        assertEquals(.03, RivetPlugin.mobHeadChance(.03, 0, .01), .0001);
        assertEquals(.06, RivetPlugin.mobHeadChance(.03, 3, .01), .0001);
        assertEquals(1, RivetPlugin.mobHeadChance(.99, 3, .01), .0001);
        assertEquals(.03, RivetPlugin.mobHeadChance(.03, -1, .01), .0001);
        assertEquals(false, RivetPlugin.usesCustomMobHeadDrop(EntityType.WITHER_SKELETON, false));
        assertEquals(true, RivetPlugin.usesCustomMobHeadDrop(EntityType.WITHER_SKELETON, true));
        assertEquals(true, RivetPlugin.usesCustomMobHeadDrop(EntityType.COW, false));
        assertEquals(true, RivetPlugin.dropsMobHead(EntityType.ZOMBIE, .0299));
        assertEquals(false, RivetPlugin.dropsMobHead(EntityType.ZOMBIE, .03));
        assertEquals(true, RivetPlugin.dropsMobHead(EntityType.COW, 0));
        assertEquals(true, RivetPlugin.dropsMobHead(EntityType.COW, .49, .5));
        assertEquals(false, RivetPlugin.dropsMobHead(EntityType.COW, .5, .5));
        assertEquals(false, RivetPlugin.dropsMobHead(EntityType.COW, 0, 1,
            List.of("cow")));
        assertEquals(false, RivetPlugin.dropsMobHead(EntityType.GIANT, 0, 1,
            List.of("zombie-head")));
        assertEquals(false, RivetPlugin.dropsMobHead(EntityType.COW, 0, 1,
            List.of("PLAYER_HEAD")));
        assertEquals(true, RivetPlugin.dropsMobHead(EntityType.COW, 0, 1,
            List.of("PIG")));
    }

    @Test
    public void protectsTradedVillagersAndRegeneratesOffersForTheirLevel() {
        assertEquals(false, VillagerRerollModule.hasBeenTraded(1, 0, 0, 0));
        assertEquals(true, VillagerRerollModule.hasBeenTraded(1, 1, 0, 0));
        assertEquals(true, VillagerRerollModule.hasBeenTraded(1, 0, 0, 1));
        assertEquals(true, VillagerRerollModule.hasBeenTraded(2, 0, 0, 0));
        assertEquals(0, VillagerRerollModule.extraTradesForLevel(1));
        assertEquals(4, VillagerRerollModule.extraTradesForLevel(3));
        assertEquals(8, VillagerRerollModule.extraTradesForLevel(5));
        assertEquals(Material.NETHER_STAR,
            VillagerRerollModule.configuredMaterial("not_a_material", Material.NETHER_STAR));
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
        assertEquals(RivetConfig.ENABLED_BY_DEFAULT,
            modules.getKeys(false).stream().filter(modules::getBoolean).collect(Collectors.toSet()));
        RivetConfig.SETTINGS.forEach(name ->
            assertNotNull(name, getClass().getResource("/settings/" + name + ".yml")));

        var globalResource = getClass().getResourceAsStream("/config.yml");
        assertNotNull(globalResource);
        YamlConfiguration global = YamlConfiguration.loadConfiguration(
            new InputStreamReader(globalResource, StandardCharsets.UTF_8));
        assertEquals(Set.of("configuration-version", "effects", "message-palette-version", "messages"),
            global.getKeys(false));
        YamlConfiguration graves = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/settings/graves.yml"), StandardCharsets.UTF_8));
        assertEquals(false, graves.getBoolean("tracking-compass.enabled", true));
        YamlConfiguration gameplay = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/settings/gameplay.yml"), StandardCharsets.UTF_8));
        assertEquals(true, gameplay.getBoolean("crop-trample-protection", false));
        assertEquals(true, gameplay.getBoolean("water-harvest-replanting", false));
        assertEquals(true, gameplay.getBoolean("iron-golem-poppy-drops", false));
        assertEquals(true, gameplay.getBoolean("hoppers.enabled", false));
        assertEquals(2, gameplay.getInt("hoppers.transfer-cooldown-ticks"));
        YamlConfiguration teleports = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/settings/teleports.yml"), StandardCharsets.UTF_8));
        assertEquals(3, teleports.getInt("warmup-seconds"));
        assertEquals(10, teleports.getInt("cooldown-seconds"));
        assertEquals(true, teleports.getBoolean("cancel-on-move", false));
    }

    @Test
    public void packagesConfigurableMessagesSoundsAndParticlesForInteractiveModules() {
        Map<String, List<String>> required = Map.of(
            "breeders", List.of("messages.given", "messages.experience-collected",
                "collection.experience", "collection.chicken-eggs", "display.item-name",
                "display.next-breed", "gui.experience-item.name", "effects.activation.sound.name",
                "effects.activation.particles.enabled"),
            "creeper-restoration", List.of("messages.given", "core.name",
                "restoration.restore-container-contents", "effects.start-sound.name",
                "effects.particles.enabled"),
            "egg-capture", List.of("messages.cannot-capture", "captured-egg.name",
                "effects.start-sound.name", "effects.particles.enabled"),
            "environment", List.of("speedUpEffect.enabled", "times.day.commands_when_ran",
                "times.night.commands_when_ran", "weather.rain.commands_when_ran",
                "weather.thunder.commands_when_ran", "weather.sun.commands_when_ran"),
            "homes", List.of("messages.set", "messages.teleported",
                "effects.teleport.sound", "effects.teleport.particle"),
            "mob-heads", List.of("disallowed-heads", "looting-bonus-per-level",
                "custom-wither-skeleton-drops", "drop-display.name",
                "drop-effects.sound.name", "drop-effects.particles.spiral.name",
                "drop-effects.particles.burst.name"),
            "statistics", List.of("seen.usage", "seen.online", "seen.offline",
                "seen.staff-location", "seen.staff-death", "seen.date-format"),
            "tree-feller", List.of("tree-feller.message", "tree-feller.sound.name",
                "veinminer.message", "veinminer.particles.name"),
            "villager-reroll", List.of("permission", "allow-after-trading",
                "require-workstation", "trade.ingredient.material", "trade.result.material",
                "trade.result.name", "messages.rerolled.actions", "messages.trades-locked.actions",
                "messages.no-workstation.actions", "effects.sound.name"),
            "warps", List.of("messages.set", "messages.teleported",
                "effects.teleport.sound", "effects.teleport.particle"));
        required.forEach((module, paths) -> {
            var resource = getClass().getResourceAsStream("/settings/" + module + ".yml");
            assertNotNull(module, resource);
            YamlConfiguration settings = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
            paths.forEach(path -> assertEquals(module + ": " + path,
                true, settings.contains(path)));
        });
    }

    @Test
    public void fastForwardsWorldTimeAcrossMidnightAndStopsExactlyAtTheTarget() {
        assertEquals(12_000, RivetPlugin.forwardTimeDistance(1_000, 13_000));
        assertEquals(2_000, RivetPlugin.forwardTimeDistance(23_000, 1_000));
        assertEquals(0, RivetPlugin.forwardTimeDistance(13_000, 13_000));
        assertEquals(23_500, RivetPlugin.transitionedTime(23_000, 2_000, 1, 4));
        assertEquals(0, RivetPlugin.transitionedTime(23_000, 2_000, 2, 4));
        assertEquals(1_000, RivetPlugin.transitionedTime(23_000, 2_000, 4, 4));
    }

    @Test
    public void mapsWaterHarvestedCropsToTheirPlantingItems() {
        assertEquals(Material.WHEAT_SEEDS, RivetPlugin.plantingItem(Material.WHEAT));
        assertEquals(Material.CARROT, RivetPlugin.plantingItem(Material.CARROTS));
        assertEquals(Material.POTATO, RivetPlugin.plantingItem(Material.POTATOES));
        assertEquals(Material.BEETROOT_SEEDS, RivetPlugin.plantingItem(Material.BEETROOTS));
        assertEquals(Material.NETHER_WART, RivetPlugin.plantingItem(Material.NETHER_WART));
        assertEquals(Material.TORCHFLOWER_SEEDS,
            RivetPlugin.plantingItem(Material.TORCHFLOWER_CROP));
        assertNull(RivetPlugin.plantingItem(Material.SUGAR_CANE));
    }

    @Test
    public void optionallyRemovesOnlyIronGolemPoppyDrops() {
        assertEquals(true, RivetPlugin.isDisabledIronGolemPoppyDrop(
            EntityType.IRON_GOLEM, false, Material.POPPY));
        assertEquals(false, RivetPlugin.isDisabledIronGolemPoppyDrop(
            EntityType.IRON_GOLEM, true, Material.POPPY));
        assertEquals(false, RivetPlugin.isDisabledIronGolemPoppyDrop(
            EntityType.IRON_GOLEM, false, Material.IRON_INGOT));
        assertEquals(false, RivetPlugin.isDisabledIronGolemPoppyDrop(
            EntityType.VILLAGER, false, Material.POPPY));
    }

    @Test
    public void environmentCommandActionsUseSupportedTags() {
        YamlConfiguration environment = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/settings/environment.yml"), StandardCharsets.UTF_8));
        assertEquals(true, environment.getBoolean("speedUpEffect.enabled", false));
        List.of("times.day", "times.night", "weather.rain", "weather.thunder", "weather.sun")
            .forEach(path -> {
                List<GuiActions.Action> actions = environment
                    .getStringList(path + ".commands_when_ran.actions").stream()
                    .map(GuiActions::parseAction).toList();
                assertEquals(path, List.of("actionbar", "sound"),
                    actions.stream().map(GuiActions.Action::tag).toList());
            });
    }

    @Test
    public void migratesLegacyMessageColorsToTheRivetPalette() {
        assertEquals("<white>Saved <#f72a4c>Alex</#f72a4c>.</white>",
            RivetConfig.themeMessage("<green>Saved <white>Alex</white>.</green>"));
        assertEquals("<#f72a4c><bold>Rivet</bold></#f72a4c>",
            RivetConfig.themeMessage("<gradient:red:gold><bold>Rivet</bold></gradient>"));
        assertEquals("<#f72a4c>Accent</#f72a4c>",
            RivetConfig.themeMessage("<#f72a4c>Accent</#f72a4c>"));
        assertEquals("<#f72a4c>%player%:</#f72a4c> <white>%message%</white>",
            RivetConfig.themeConfiguredMessage("chat", "format",
                "<gray><player>:</gray> <white><message></white>"));
        assertEquals("<#f72a4c>* %player%</#f72a4c> <white>%message%</white>",
            RivetConfig.themeConfiguredMessage("chat", "me.format",
                "<light_purple>* <player> <message></light_purple>"));
        assertEquals("<#f72a4c>[spy] %sender% → %recipient%:</#f72a4c> <white>%message%</white>",
            RivetConfig.themeConfiguredMessage("chat", "social-spy.format",
                "<dark_gray>[spy]</dark_gray> <gray><sender> → <recipient>:</gray> <white><message></white>"));
        String themed = "<white>Saved <#f72a4c>Alex</#f72a4c>.</white>";
        assertEquals(themed, RivetConfig.migrateMessage("kits", "messages.saved", themed));
    }

    @Test
    public void usesPercentPlaceholdersWithoutTreatingThemAsMiniMessageTags() {
        assertEquals("<player> has <count> items and 100% luck",
            RivetMiniMessage.toResolverTags("%player% has %count% items and 100% luck"));
        assertEquals("<plural>", RivetMiniMessage.toResolverTags("%plural%"));
        assertEquals("%unknown%", RivetMiniMessage.toResolverTags("%unknown%"));
        Component rendered = RivetMiniMessage.miniMessage().deserialize(
            "<white>Hello %player%</white>",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", "Alex"));
        assertEquals("Hello Alex", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertEquals("Welcome %player%", RivetConfig.migratePlaceholders(
            "join-leave", "join.message", "Welcome <player>"));
        assertEquals("Usage: /msg <player> <message>", RivetConfig.migratePlaceholders(
            "chat", "messages.usage", "Usage: /msg <player> <message>"));
    }

    @Test
    public void supportsLimeAsABrightGreenMiniMessageAlias() {
        Component rendered = RivetMiniMessage.miniMessage().deserialize(
            "<lime>+ <white>%player%</white></lime>",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", "Alex"));

        assertEquals("<green>+ <white>Alex",
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(rendered));
    }

    @Test
    public void packagedSettingsUsePercentSyntaxForRivetPlaceholders() {
        RivetConfig.SETTINGS.forEach(module -> {
            YamlConfiguration settings = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/settings/" + module + ".yml"), StandardCharsets.UTF_8));
            settings.getValues(true).forEach((path, value) -> {
                if (path.toLowerCase(java.util.Locale.ROOT).contains("usage")) {
                    return;
                }
                if (value instanceof String text) {
                    assertEquals(module + ": " + path, text,
                        RivetMiniMessage.toPercentPlaceholders(text));
                } else if (value instanceof List<?> values) {
                    values.stream().filter(String.class::isInstance).map(String.class::cast)
                        .forEach(text -> assertEquals(module + ": " + path, text,
                            RivetMiniMessage.toPercentPlaceholders(text)));
                }
            });
        });
    }

    @Test
    public void routesEveryDeclaredCommandToItsModule() {
        var pluginResource = getClass().getResourceAsStream("/plugin.yml");
        assertNotNull(pluginResource);
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
            new InputStreamReader(pluginResource, StandardCharsets.UTF_8));
        assertEquals(98, plugin.getConfigurationSection("commands").getKeys(false).size());
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
        assertEquals("breeders", RivetPlugin.moduleForCommand("givebreeder"));
        assertEquals("breeders", RivetPlugin.moduleForCommand("clearhologram"));
        assertEquals("creeper-restoration", RivetPlugin.moduleForCommand("restorationcore"));
        assertEquals("statistics", RivetPlugin.moduleForCommand("seen"));
        assertEquals("staff", RivetPlugin.moduleForCommand("tppos"));
        assertEquals("filter", RivetPlugin.moduleForCommand("filter"));
        assertEquals("chat", RivetPlugin.moduleForCommand("chatcolor"));
        assertEquals("chat", RivetPlugin.moduleForCommand("tag"));
        assertEquals("staff", RivetPlugin.moduleForCommand("bossbarmsg"));
        assertEquals("staff", RivetPlugin.moduleForCommand("commandspy"));
        assertEquals("inventory", RivetPlugin.moduleForCommand("condense"));
        assertEquals("inventory", RivetPlugin.moduleForCommand("scan"));
        assertEquals("worlds", RivetPlugin.moduleForCommand("findbiome"));
        assertEquals("help", RivetPlugin.moduleForCommand("help"));
        assertEquals("lagg", RivetPlugin.moduleForCommand("lagg"));
        assertEquals("logs", RivetPlugin.moduleForCommand("log"));
        assertNull(RivetPlugin.moduleForCommand("group"));
        assertNull(RivetPlugin.moduleForCommand("rivet"));
        assertNull(RivetPlugin.moduleForCommand("unknown"));
    }

    @Test
    public void staggersRestorationBlocksAcrossTheConfiguredWindow() {
        assertEquals(0, CreeperRestoration.startTick(0, 5, 70, 10));
        assertEquals(30, CreeperRestoration.startTick(2, 5, 70, 10));
        assertEquals(60, CreeperRestoration.startTick(4, 5, 70, 10));
        assertEquals(0, CreeperRestoration.startTick(0, 1, 70, 10));
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
    public void mapsPositiveAndNegativeRegionHeaderSlotsToChunkCoordinates() {
        assertEquals(new ScanModule.ChunkPosition(0, 0),
            ScanModule.chunkPosition(0, 0, 0));
        assertEquals(new ScanModule.ChunkPosition(31, 31),
            ScanModule.chunkPosition(0, 0, 1023));
        assertEquals(new ScanModule.ChunkPosition(-32, 64),
            ScanModule.chunkPosition(-1, 2, 0));
        assertEquals(new ScanModule.ChunkPosition(-1, 95),
            ScanModule.chunkPosition(-1, 2, 1023));
    }

    @Test
    public void clearsOnlyTaggedAutoBreederHolograms() {
        assertEquals(true, AutoBreeder.clearableHologram("world:1,2,3", null, null, null));
        assertEquals(true, AutoBreeder.clearableHologram(null, "world:1,2,3", null, null));
        assertEquals(false, AutoBreeder.clearableHologram(null, null, null, null));
        assertEquals(false,
            AutoBreeder.clearableHologram("world:1,2,3", null, "shop-title", null));
        assertEquals(false,
            AutoBreeder.clearableHologram(null, "world:1,2,3", null, "shop-title"));
        assertEquals(true, AutoBreeder.notPlayerHologram(null, null));
        assertEquals(false, AutoBreeder.notPlayerHologram("shop-title", null));
        assertEquals(false, AutoBreeder.notPlayerHologram(null, "shop-title"));
        assertEquals(true, AutoBreeder.legacyAutoBreederLine("Pig Auto Breeder"));
        assertEquals(true, AutoBreeder.legacyAutoBreederLine("Animals bred: 42"));
        assertEquals(false, AutoBreeder.legacyAutoBreederLine("Welcome to spawn"));
        assertEquals(true,
            AutoBreeder.legacyHologramNeighbor(10, 64, 10, 10.2, 65.5, 9.8));
        assertEquals(false,
            AutoBreeder.legacyHologramNeighbor(10, 64, 10, 11, 64, 10));
    }

    @Test
    public void trashGuiUsesOneBasedSlotsAndInclusiveRanges() {
        YamlConfiguration item = new YamlConfiguration();
        item.set("slots", List.of("46-49", 54, "outside", 60));
        item.set("slot", 50);
        assertEquals(List.of(49, 45, 46, 47, 48, 53),
            TrashModule.configuredSlots(item, 54));
    }

    @Test
    public void guiActionsParseEverySupportedTagCaseInsensitively() {
        List<String> tags = List.of("toast", "actionbar", "particle", "title", "bossbar",
            "lightning", "sound", "message", "close");
        tags.forEach(tag -> assertEquals(tag,
            GuiActions.parseAction("[" + tag.toUpperCase() + "] value").tag()));
        assertEquals("value", GuiActions.parseAction("[message] value").value());
        assertNull(GuiActions.parseAction("message value"));
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
    public void commandSpyRequiresAnEnabledVisiblePermittedObserver() {
        UUID sender = UUID.randomUUID();
        UUID spy = UUID.randomUUID();
        assertEquals(true, StaffTools.shouldReceiveCommandSpy(spy, sender, true, true, true));
        assertEquals(false, StaffTools.shouldReceiveCommandSpy(sender, sender, true, true, true));
        assertEquals(false, StaffTools.shouldReceiveCommandSpy(spy, sender, false, true, true));
        assertEquals(false, StaffTools.shouldReceiveCommandSpy(spy, sender, true, false, true));
        assertEquals(false, StaffTools.shouldReceiveCommandSpy(spy, sender, true, true, false));
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
        assertEquals(true, RivetPlugin.commandDisabled("filter", module -> false));
        assertEquals(true, RivetPlugin.commandDisabled("help", module -> false));
    }

    @Test
    public void helpHidesDisabledAndUnpermittedCommands() {
        List<HelpModule.CommandEntry> commands = List.of(
            new HelpModule.CommandEntry("home", "/home", "Go home", "homes", "rivet.home"),
            new HelpModule.CommandEntry("spawn", "/spawn", "Go to spawn", "spawn", "rivet.spawn"),
            new HelpModule.CommandEntry("rivet", "/rivet", "Admin", null, "rivet.admin"));

        assertEquals(List.of("home"), HelpModule.visible(commands,
            module -> !module.equals("spawn"), permission -> permission.equals("rivet.home"))
            .stream().map(HelpModule.CommandEntry::name).toList());
    }

    @Test
    public void helpCalculatesAndClampsTextPages() {
        assertEquals(1, HelpModule.pageCount(0, 6));
        assertEquals(3, HelpModule.pageCount(13, 6));
        assertEquals(1, HelpModule.clampPage(-2, 3));
        assertEquals(3, HelpModule.clampPage(99, 3));
        Component navigation = HelpModule.navigation(2, 4);
        assertEquals(true, hasRunCommand(navigation, "/rivethelp 1"));
        assertEquals(true, hasRunCommand(navigation, "/rivethelp 3"));
        assertEquals(true, hasRunCommand(navigation, "/rivethelp 4"));
    }

    @Test
    public void managesMaterialFiltersAndHonorsLimitsAndToggles() {
        Set<Material> items = EnumSet.noneOf(Material.class);
        assertEquals(true, FilterModule.add(items, Material.DIAMOND, 1, false));
        assertEquals(false, FilterModule.add(items, Material.EMERALD, 1, false));
        assertEquals(true, FilterModule.add(items, Material.EMERALD, 1, true));
        assertEquals(true, FilterModule.remove(items, Material.DIAMOND));
        FilterModule.FilterState state = new FilterModule.FilterState(items, true);
        assertEquals(false, FilterModule.toggle(state));
        assertEquals(true, FilterModule.toggle(state));
        assertEquals(true, FilterModule.blocksPickup(true, false, items, Material.EMERALD));
        assertEquals(false, FilterModule.blocksPickup(false, false, items, Material.EMERALD));
        assertEquals(false, FilterModule.blocksPickup(true, true, items, Material.EMERALD));
    }

    @Test
    public void reloadsPersistedFilterMaterialsAndEnabledState() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("player.enabled", false);
        data.set("player.materials", List.of("DIAMOND", "EMERALD"));
        FilterModule.FilterState state = FilterModule.readState(
            data.getConfigurationSection("player"));
        assertEquals(false, state.enabled());
        assertEquals(Set.of(Material.DIAMOND, Material.EMERALD), state.items());
    }

    @Test
    public void parsesAfkReasonsTargetsAndSilentFlags() {
        assertEquals(new AfkModule.AfkArguments(null, "Lunch break", false, true),
            AfkModule.parseArguments(new String[]{"Lunch", "break"}));
        assertEquals(new AfkModule.AfkArguments("Alex", "Moderating", true, true),
            AfkModule.parseArguments(new String[]{"-s", "-p:Alex", "Moderating"}));
        assertEquals(false, AfkModule.parseArguments(new String[]{"-p:"}).valid());
        assertEquals("1h 1m", AfkModule.duration(3_660_000));
    }

    @Test
    public void parsesBossBarFlagsAndRejectsBadValues() {
        StaffTools.BossBarArguments parsed = StaffTools.parseBossBarArguments(
            new String[]{"-d:10", "-c:red", "-s:segmented_6", "<red>Hello"},
            5, "purple", "solid");
        assertEquals(true, parsed.valid());
        assertEquals(10, parsed.duration(), .0001);
        assertEquals("RED", parsed.color().name());
        assertEquals("NOTCHED_6", parsed.overlay().name());
        assertEquals(false, StaffTools.parseBossBarArguments(
            new String[]{"-d:nope", "hello"}, 5, "purple", "solid").valid());
    }

    @Test
    public void completesEveryBossBarFlagInAnyOrderWithoutDuplicates() {
        assertEquals(List.of("-c:pink", "-c:blue", "-c:red", "-c:green", "-c:yellow",
                "-c:purple", "-c:white"),
            StaffTools.bossBarFlagCompletions(new String[]{"all", "-c:"}));
        assertEquals(List.of("-s:solid", "-s:segmented_6", "-s:segmented_10",
                "-s:segmented_12", "-s:segmented_20"),
            StaffTools.bossBarFlagCompletions(new String[]{"all", "-d:10", "-s:"}));

        List<String> remaining = StaffTools.bossBarFlagCompletions(
            new String[]{"all", "-s:segmented_10", "-c:red", ""});
        assertEquals(List.of("-d:5", "-d:10", "-d:30", "-d:60"), remaining);
    }

    @Test
    public void parsesNoteActionsAndMaintainsSimpleIds() throws Exception {
        assertEquals(new StaffTools.NoteArguments("Alex", "add", "Repeated griefing", -1, false, true),
            StaffTools.parseNoteArguments(new String[]{"Alex", "add", "Repeated", "griefing"}));
        assertEquals(new StaffTools.NoteArguments("Alex", "remove", null, 12, false, true),
            StaffTools.parseNoteArguments(new String[]{"Alex", "remove", "12"}));
        assertEquals(false, StaffTools.parseNoteArguments(new String[]{"Alex", "remove", "zero"}).valid());
        assertEquals(false, StaffTools.parseNoteArguments(new String[]{"Alex", "clear", "now"}).valid());
        assertEquals(true, StaffTools.validNoteText("Readable note", 20));
        assertEquals(false, StaffTools.validNoteText("bad\nnote", 20));
        assertEquals(false, StaffTools.validNoteText("too long", 3));

        YamlConfiguration notes = new YamlConfiguration();
        notes.set("players.test.next-id", 2);
        notes.set("players.test.notes.2.text", "existing");
        assertEquals(3, StaffTools.nextNoteId(notes, "players.test"));
        assertEquals(true, StaffTools.removeNote(notes, "players.test", 2));
        assertEquals(false, StaffTools.removeNote(notes, "players.test", 2));

        notes.set("players.test.notes.3.text", "persists");
        YamlConfiguration restarted = new YamlConfiguration();
        restarted.loadFromString(notes.saveToString());
        assertEquals("persists", restarted.getString("players.test.notes.3.text"));
    }

    @Test
    public void formatsPlaytimeFromNativeStatisticTicks() {
        assertEquals("12d 4h 32m", StatisticsModule.duration((12L * 24 * 60 + 4 * 60 + 32) * 60_000));
        assertEquals("4h 32m", StatisticsModule.duration((4L * 60 + 32) * 60_000));
        assertEquals("32m", StatisticsModule.duration(32 * 60_000L));
        assertEquals("32s", StatisticsModule.relativeDuration(32_000));
        assertEquals("1m", StatisticsModule.relativeDuration(60_000));
    }

    @Test
    public void parsesToastFlagsAndRejectsUnsafeValues() {
        StaffTools.ToastArguments toast = StaffTools.parseToastArguments(
            new String[]{"i:diamond", "t:Event", "type:challenge", "<gold>Started!"},
            "task", "paper");
        assertEquals(true, toast.valid());
        assertEquals("challenge", toast.type());
        assertEquals(Material.DIAMOND, toast.icon());
        assertEquals("Event", toast.title());
        assertEquals("<gold>Started!", toast.message());
        StaffTools.ToastArguments legacy = StaffTools.parseToastArguments(
            new String[]{"-t:goal", "-icon:emerald", "Legacy title"}, "task", "paper");
        assertEquals("Legacy title", legacy.title());
        assertEquals("", legacy.message());
        assertEquals(false, StaffTools.parseToastArguments(
            new String[]{"type:unknown", "hello"}, "task", "paper").valid());
        assertEquals(false, StaffTools.parseToastArguments(
            new String[]{"i:lava", "hello"}, "task", "paper").valid());
    }

    @Test
    public void completesCompactToastIconTitleAndTypeTokens() {
        assertEquals(List.of("i:diamond", "t:title", "type:task", "type:goal", "type:challenge"),
            StaffTools.toastFlagCompletions(new String[]{"all", ""}, List.of("diamond")));
        assertEquals(List.of("i:diamond", "i:dirt"),
            StaffTools.toastFlagCompletions(new String[]{"all", "i:d"},
                List.of("diamond", "dirt")));
        assertEquals(List.of("t:title", "type:task", "type:goal", "type:challenge"),
            StaffTools.toastFlagCompletions(new String[]{"all", "i:diamond", ""},
                List.of("diamond")));
        assertEquals(List.of("t:title"),
            StaffTools.toastFlagCompletions(new String[]{"all", "t:"}, List.of("diamond")));
    }

    @Test
    public void validatesTreeTypesAndConservativeTreeBases() {
        assertEquals(org.bukkit.TreeType.CHERRY, RivetPlugin.treeType("cherry"));
        assertEquals(org.bukkit.TreeType.TALL_REDWOOD, RivetPlugin.treeType("tall-redwood"));
        assertNull(RivetPlugin.treeType("custom_tree"));
        assertEquals(true, SafeLocations.suitableTreeBase(Material.GRASS_BLOCK));
        assertEquals(false, SafeLocations.suitableTreeBase(Material.CHEST));
    }

    @Test
    public void topSafetyRejectsLeavesHazardsAndMissingHeadroom() {
        assertEquals(65, SafeLocations.topScanStart(320, 64));
        assertEquals(318, SafeLocations.topScanStart(320, 319));
        assertEquals(true, SafeLocations.safeStanding(Material.STONE, true, Material.AIR, true,
            Material.AIR, true, true));
        assertEquals(false, SafeLocations.safeStanding(Material.AIR, false, Material.AIR, true,
            Material.AIR, true, true));
        assertEquals(false, SafeLocations.safeStanding(Material.OAK_LEAVES, true, Material.AIR, true,
            Material.AIR, true, true));
        assertEquals(false, SafeLocations.safeStanding(Material.LAVA, false, Material.AIR, true,
            Material.AIR, true, true));
        assertEquals(false, SafeLocations.safeStanding(Material.STONE, true, Material.FIRE, true,
            Material.AIR, true, false));
        assertEquals(false, SafeLocations.safeStanding(Material.STONE, true, Material.AIR, true,
            Material.STONE, false, false));
    }

    @Test
    public void validatesRideTargetsWithoutAllowingUnsafeMounts() {
        assertEquals(true, UtilitiesModule.validRideTarget(false, false, false, true, false));
        assertEquals(false, UtilitiesModule.validRideTarget(true, false, false, true, false));
        assertEquals(false, UtilitiesModule.validRideTarget(false, true, false, true, false));
        assertEquals(true, UtilitiesModule.validRideTarget(false, true, true, true, false));
        assertEquals(false, UtilitiesModule.validRideTarget(false, false, false, true, true));
    }

    @Test
    public void filtersSameIpMatchesWithoutReturningHiddenOrTargetPlayers() {
        UUID target = UUID.randomUUID();
        UUID visible = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        List<StaffTools.IpEntry> entries = List.of(
            new StaffTools.IpEntry(target, "Target", "session-a"),
            new StaffTools.IpEntry(visible, "Visible", "session-a"),
            new StaffTools.IpEntry(hidden, "Hidden", "session-a"),
            new StaffTools.IpEntry(UUID.randomUUID(), "Different", "session-b"));
        assertEquals(List.of("Visible"), StaffTools.sameIpMatches(entries, target, "session-a",
            uuid -> !uuid.equals(hidden)));
    }

    @Test
    public void keepsSameIpStaffOnlyAndRawAddressesOutOfDefaultOutput() {
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        YamlConfiguration staff = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/settings/staff.yml"), StandardCharsets.UTF_8));
        assertEquals("op", plugin.getString("permissions.rivet.sameip.default"));
        assertEquals(false, staff.getString("same-ip.format", "").contains("<address>"));
        assertEquals(false, staff.getString("same-ip.format", "").contains("<ip>"));
    }

    @Test
    public void meFormattingNeverCreatesInteractiveEvents() {
        Component plain = ChatModule.formatMeMessage("<red>waves", false);
        Component formatted = ChatModule.formatMeMessage(
            "<red>waves <click:run_command:'/op me'>now", true);
        assertEquals("<red>waves", PlainTextComponentSerializer.plainText().serialize(plain));
        assertEquals("waves <click:run_command:'/op me'>now",
            PlainTextComponentSerializer.plainText().serialize(formatted));
        assertEquals(false, hasRunCommand(formatted, "/op me"));
    }

    @Test
    public void parsesPingAndPlaytimeTargetsOnlyWithPermission() {
        assertEquals(new UtilitiesModule.TargetArgument(null, true),
            UtilitiesModule.parseOptionalTarget(new String[0], false));
        assertEquals(new UtilitiesModule.TargetArgument("Alex", true),
            UtilitiesModule.parseOptionalTarget(new String[]{"Alex"}, true));
        assertEquals(false, UtilitiesModule.parseOptionalTarget(new String[]{"Alex"}, false).valid());
        assertEquals(false,
            UtilitiesModule.parseOptionalTarget(new String[]{"Alex", "extra"}, true).valid());
    }

    @Test
    public void validatesRestrictedChatColorTags() {
        assertEquals(true, ChatModule.validChatColor("<red>", false));
        assertEquals(true, ChatModule.validChatColor("<#12abEF>", false));
        assertEquals(false, ChatModule.validChatColor("<gradient:red:gold>", false));
        assertEquals(true, ChatModule.validChatColor("<gradient:red:gold>", true));
        assertEquals(true, ChatModule.validChatColor("<rainbow>", true));
        assertEquals(false, ChatModule.validChatColor("<click:run_command:'/op me'>", true));
    }

    @Test
    public void supportsFriendlyChatFormattingPlaceholders() {
        Component formatted = ChatModule.format(
            "%prefix%%tag% %player%%suffix%: %message%",
            ChatModule.safeFormatting("<red>[Admin] </red>"),
            ChatModule.safeFormatting(" <gray>[AFK]</gray>"),
            ChatModule.safeFormatting("<gold>[OG]</gold>"),
            Component.text("Alex"), Component.text("hello"));
        assertEquals("[Admin] [OG] Alex [AFK]: hello",
            PlainTextComponentSerializer.plainText().serialize(formatted));
    }

    @Test
    public void safeChatCosmeticsCannotCreateInteractiveEvents() {
        Component cosmetic = ChatModule.safeFormatting(
            "<gold>[OG]</gold><click:run_command:'/op me'>unsafe</click>");
        assertEquals(false, hasRunCommand(cosmetic, "/op me"));
        assertEquals(true, PlainTextComponentSerializer.plainText().serialize(cosmetic).contains("unsafe"));
    }

    @Test
    public void highlightsMentionsAtPlayerNameBoundaries() {
        Component message = Component.text("Hi @Alex, not @Alexander or email@Alex");
        Component highlighted = ChatModule.replaceMention(message, "alex",
            ChatModule.safeFormatting("<yellow>@Alex</yellow>"));
        assertEquals("Hi @Alex, not @Alexander or email@Alex",
            PlainTextComponentSerializer.plainText().serialize(highlighted));
        assertEquals(true, ChatModule.containsMention("Hi @aLeX!", "Alex"));
        assertEquals(false, ChatModule.containsMention("Hi @Alexander", "Alex"));
        assertEquals(false, ChatModule.containsMention("email@Alex", "Alex"));
        assertEquals(true, hasColor(highlighted));
    }

    @Test
    public void calculatesLightweightChatSimilarityAndDurations() {
        assertEquals(100, ChatModule.similarity("repeat", "repeat"));
        assertEquals(true, ChatModule.similarity("hello world", "hello world!") >= 85);
        assertEquals(false, ChatModule.similarity("hello", "goodbye") >= 85);
        assertEquals("hello world", ChatModule.normalizeMessage("  HELLO   world "));
        assertEquals(1_000L, ChatModule.parseDurationMillis("1s", 50));
        assertEquals(120_000L, ChatModule.parseDurationMillis("2m", 50));
        assertEquals(50L, ChatModule.parseDurationMillis("invalid", 50));
    }

    @Test
    public void migratesLegacyChatSettingsWithoutOverwritingNewSelections() {
        YamlConfiguration chat = new YamlConfiguration();
        chat.set("chat-colors.allow-hex", false);
        chat.set("chat-colors.allow-gradients", false);
        chat.set("chat-styles.allow-custom-gradients", true);
        assertEquals(true, RivetConfig.migrateLegacyChatSettings(chat));
        assertEquals(false, chat.getBoolean("chat-styles.allow-custom-hex"));
        assertEquals(true, chat.getBoolean("chat-styles.allow-custom-gradients"));
        chat.set("format", "<#f72a4c>%player%:</#f72a4c> <white>%message%</white>");
        assertEquals(true, RivetConfig.migrateChatFormat(chat));
        assertEquals(true, chat.getString("format").contains("%prefix%"));
        assertEquals(true, chat.getString("format").contains("%tag%"));
    }

    @Test
    public void packagesCompactChatControlStyleDefaultsAndPermissions() {
        YamlConfiguration chat = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/settings/chat.yml"), StandardCharsets.UTF_8));
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        assertEquals("<red>", chat.getString("chat-styles.colors.red"));
        assertEquals("<gradient:#ff512f:#f09819>",
            chat.getString("chat-styles.gradients.sunset"));
        assertEquals("<gold>[OG]</gold>", chat.getString("tags.list.og.display"));
        assertEquals(85, chat.getInt("anti-spam.similarity.threshold"));
        assertEquals(false, chat.contains("channels"));
        assertEquals(true, plugin.getBoolean("permissions.rivet.chat.mention.default"));
        assertEquals("op", plugin.getString("permissions.rivet.chat.style.custom.default"));
        assertEquals(false, plugin.getBoolean("permissions.rivet.chat.tag.og.default"));
    }

    @Test
    public void appliesChatColorsWithoutDroppingItemHoverEvents() {
        Component message = Component.text("Diamond")
            .hoverEvent(Component.text("A very shiny item"));
        Component colored = ChatModule.formatMessage("<gradient:red:gold>", message);
        assertEquals("Diamond", PlainTextComponentSerializer.plainText().serialize(colored));
        assertEquals(true, hasHover(colored));
        assertEquals(true, hasColor(colored));
    }

    @Test
    public void parsesModernSelectiveClearItems() {
        assertEquals(new ItemTools.ClearItem(Material.DIAMOND, Integer.MAX_VALUE, false),
            ItemTools.parseClearItem("diamond"));
        assertEquals(new ItemTools.ClearItem(Material.STONE, 12, false),
            ItemTools.parseClearItem("minecraft:stone:12"));
        assertEquals(new ItemTools.ClearItem(Material.DIAMOND_SWORD, 1, true),
            ItemTools.parseClearItem("diamond_sword:1;plain"));
        assertNull(ItemTools.parseClearItem("diamond:0"));
        assertNull(ItemTools.parseClearItem("diamond;legacy-data"));
    }

    @Test
    public void calculatesCondenseRecipesAndDonateAmounts() {
        assertEquals(7, ItemTools.condensedAmount(71, 9));
        assertEquals(0, ItemTools.condensedAmount(8, 9));
        assertEquals(true, ItemTools.condensable(false));
        assertEquals(false, ItemTools.condensable(true));
        assertEquals(true, ItemTools.validDonateAmount(3, 3));
        assertEquals(false, ItemTools.validDonateAmount(4, 3));
        assertEquals(false, ItemTools.validDonateAmount(0, 3));
    }

    @Test
    public void validatesNativeFlySpeedAndExplicitGodState() {
        assertEquals(Float.valueOf(.5f), StaffTools.parseFlySpeed("0.5"));
        assertNull(StaffTools.parseFlySpeed("1.1"));
        assertNull(StaffTools.parseFlySpeed("NaN"));
        assertEquals(new StaffTools.GodArguments(null, true, true, true),
            StaffTools.parseGodArguments(new String[]{"true", "-s"}));
        assertEquals(new StaffTools.GodArguments("Alex", false, false, true),
            StaffTools.parseGodArguments(new String[]{"Alex", "false"}));
        assertEquals(false, StaffTools.parseGodArguments(new String[]{"Alex", "maybe"}).valid());
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
        assertEquals("GLOWSTONE", TreeFeller.oreKey(org.bukkit.Material.GLOWSTONE));
        assertNull(TreeFeller.oreKey(org.bukkit.Material.STONE));
    }

    @Test
    public void colorsOreActionBarNames() {
        assertEquals(0xAAAAAA, TreeFeller.oreColor(org.bukkit.Material.COAL_ORE).value());
        assertEquals(0xFFFFFF, TreeFeller.oreColor(org.bukkit.Material.IRON_ORE).value());
        assertEquals(0x55FFFF, TreeFeller.oreColor(org.bukkit.Material.DEEPSLATE_DIAMOND_ORE).value());
        assertEquals(0xFF5555, TreeFeller.oreColor(org.bukkit.Material.REDSTONE_ORE).value());
        assertEquals(0xFFF47D, TreeFeller.oreColor(org.bukkit.Material.GLOWSTONE).value());
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
    public void layUsesAStableHorizontalPoseAndTogglesSeparatelyFromCrawl() {
        assertEquals(org.bukkit.entity.Pose.SWIMMING, PosesModule.modeFor("lay").pose());
        assertEquals(org.bukkit.entity.Pose.SWIMMING, PosesModule.modeFor("crawl").pose());
        assertEquals(false, PosesModule.modeFor("lay") == PosesModule.modeFor("crawl"));
        assertEquals(org.bukkit.entity.Pose.SITTING, PosesModule.modeFor("sit").pose());
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
    public void parsesAbsoluteAndRelativeTeleportCoordinatesWithinWorldLimits() {
        assertEquals(12.5, RivetPlugin.parseCoordinate("12.5", 100), .0001);
        assertEquals(100, RivetPlugin.parseCoordinate("~", 100), .0001);
        assertEquals(90.5, RivetPlugin.parseCoordinate("~-9.5", 100), .0001);
        assertNull(RivetPlugin.parseCoordinate("nowhere", 100));
        assertNull(RivetPlugin.parseCoordinate("NaN", 100));
        assertEquals(true, RivetPlugin.validTeleportPosition(10, 64, -10, -64, 320));
        assertEquals(false, RivetPlugin.validTeleportPosition(10, 320, -10, -64, 320));
        assertEquals(false, RivetPlugin.validTeleportPosition(30_000_000, 64, 0, -64, 320));
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
        Component formatted = ChatModule.format("%player%: %message%", Component.text("Alex"), Component.text("<red>hello"));
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

    private static boolean hasHover(Component component) {
        return component.hoverEvent() != null || component.children().stream().anyMatch(RivetPluginTest::hasHover);
    }

    private static boolean hasColor(Component component) {
        return component.color() != null || component.children().stream().anyMatch(RivetPluginTest::hasColor);
    }

    private static boolean hasRunCommand(Component component, String command) {
        return component.clickEvent() != null
            && component.clickEvent().action() == net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND
            && component.clickEvent().value().equals(command)
            || component.children().stream().anyMatch(child -> hasRunCommand(child, command));
    }
}
