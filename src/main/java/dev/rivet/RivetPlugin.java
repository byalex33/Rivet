package dev.rivet;

import io.papermc.paper.event.block.BlockBreakBlockEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BiomeSearchResult;
import org.bstats.bukkit.Metrics;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RivetPlugin extends JavaPlugin implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final String LEGACY_WORLD_MARKER = ".rivet-test-world";
    private static final long MINECRAFT_DAY_TICKS = 24_000;
    private static final double DEFAULT_MOB_HEAD_CHANCE = .03;
    private static final double DEFAULT_MOB_HEAD_LOOTING_BONUS = .01;
    private static final Map<EntityType, String> MOB_HEAD_TEXTURES = loadMobHeadTextures();
    private static final ChunkGenerator VOID_GENERATOR = new ChunkGenerator() {
        @Override
        public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                  int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        }
    };
    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> flightEnabled = new HashSet<>();
    private final Set<UUID> biomeSearches = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitRunnable> timeTransitions = new HashMap<>();
    private final Map<WaterCropKey, PendingWaterCrop> pendingWaterCrops = new HashMap<>();
    private BukkitTask waterCropTask;
    private ChatModule chat;
    private AutoBreeder autoBreeder;
    private EggCapture eggCapture;
    private VillagerRerollModule villagerReroll;
    private CreeperRestoration creeperRestoration;
    private GraveModule graves;
    private HologramModule holograms;
    private PermissionModule permissions;
    private TreeFeller treeFeller;
    private DelayedTeleport delayedTeleports;
    private SpawnModule spawn;
    private TpaModule tpa;
    private KitsModule kits;
    private AfkModule afk;
    private JoinLeaveModule joinLeave;
    private AnnouncementsModule announcements;
    private NicknameModule nicknames;
    private StatisticsModule statistics;
    private TrashModule trash;
    private UtilitiesModule utilities;
    private PosesModule poses;
    private BackpacksModule backpacks;
    private DailyModule daily;
    private RtpModule rtp;
    private NearModule near;
    private ItemTools itemTools;
    private ScanModule scans;
    private StaffTools staffTools;
    private FilterModule filter;
    private HelpModule help;
    private HopperModule hoppers;
    private LaggModule lagg;
    private AuditModule audit;
    private DeathMessagesModule deathMessages;
    private GuiActions guiActions;
    private MessageActions messageActions;
    private RivetConfig files;
    private YamlConfiguration homes;
    private YamlConfiguration warps;
    private YamlConfiguration worldData;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            files = new RivetConfig(this);
        } catch (IOException | InvalidConfigurationException exception) {
            getLogger().severe("Could not prepare Rivet's configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        homes = data("homes");
        warps = data("warps");
        worldData = data("worlds");
        guiActions = new GuiActions(this);
        messageActions = new MessageActions(this);
        new Metrics(this, 33219);
        if (moduleEnabled("homes") || moduleEnabled("warps") || moduleEnabled("spawn")
            || moduleEnabled("tpa") || moduleEnabled("graves") || moduleEnabled("rtp")) {
            delayedTeleports = new DelayedTeleport(this);
            getServer().getPluginManager().registerEvents(delayedTeleports, this);
        }
        if (moduleEnabled("breeders")) {
            autoBreeder = new AutoBreeder(this);
            getServer().getPluginManager().registerEvents(autoBreeder, this);
        }
        if (moduleEnabled("egg-capture")) {
            eggCapture = new EggCapture(this);
            getServer().getPluginManager().registerEvents(eggCapture, this);
        }
        if (moduleEnabled("villager-reroll")) {
            villagerReroll = new VillagerRerollModule(this);
            getServer().getPluginManager().registerEvents(villagerReroll, this);
        }
        if (moduleEnabled("creeper-restoration")) {
            creeperRestoration = new CreeperRestoration(this);
            getServer().getPluginManager().registerEvents(creeperRestoration, this);
        }
        if (moduleEnabled("permissions")) {
            permissions = new PermissionModule(this);
            getServer().getPluginManager().registerEvents(permissions, this);
        }
        if (moduleEnabled("chat")) {
            chat = new ChatModule(this);
            getServer().getPluginManager().registerEvents(chat, this);
        }
        if (moduleEnabled("graves")) {
            graves = new GraveModule(this, delayedTeleports);
            getServer().getPluginManager().registerEvents(graves, this);
        }
        if (moduleEnabled("holograms")) {
            holograms = new HologramModule(this);
            getServer().getPluginManager().registerEvents(holograms, this);
        }
        if (moduleEnabled("tree-feller")) {
            treeFeller = new TreeFeller(this);
            getServer().getPluginManager().registerEvents(treeFeller, this);
        }
        if (moduleEnabled("spawn")) {
            spawn = new SpawnModule(this, delayedTeleports);
            getServer().getPluginManager().registerEvents(spawn, this);
        }
        if (moduleEnabled("tpa")) {
            tpa = new TpaModule(this, delayedTeleports);
            getServer().getPluginManager().registerEvents(tpa, this);
        }
        if (moduleEnabled("kits")) {
            kits = new KitsModule(this);
        }
        if (moduleEnabled("nicknames")) {
            nicknames = new NicknameModule(this);
            getServer().getPluginManager().registerEvents(nicknames, this);
        }
        if (moduleEnabled("afk")) {
            afk = new AfkModule(this);
            getServer().getPluginManager().registerEvents(afk, this);
        }
        if (moduleEnabled("join-leave")) {
            joinLeave = new JoinLeaveModule(this);
            getServer().getPluginManager().registerEvents(joinLeave, this);
        }
        if (moduleEnabled("announcements")) {
            announcements = new AnnouncementsModule(this);
        }
        if (moduleEnabled("lagg")) {
            lagg = new LaggModule(this);
        }
        if (moduleEnabled("logs")) {
            try {
                audit = new AuditModule(this);
                getServer().getPluginManager().registerEvents(audit, this);
            } catch (java.sql.SQLException exception) {
                getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not open Rivet's SQLite audit log", exception);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        if (moduleEnabled("death-messages")) {
            deathMessages = new DeathMessagesModule(this);
            getServer().getPluginManager().registerEvents(deathMessages, this);
        }
        if (moduleEnabled("statistics")) {
            statistics = new StatisticsModule(this);
        }
        if (moduleEnabled("trash")) {
            trash = new TrashModule(this);
            getServer().getPluginManager().registerEvents(trash, this);
        }
        if (moduleEnabled("utilities")) {
            utilities = new UtilitiesModule(this);
            getServer().getPluginManager().registerEvents(utilities, this);
        }
        if (moduleEnabled("poses")) {
            poses = new PosesModule(this);
            getServer().getPluginManager().registerEvents(poses, this);
        }
        if (moduleEnabled("backpacks")) {
            backpacks = new BackpacksModule(this);
            getServer().getPluginManager().registerEvents(backpacks, this);
        }
        if (moduleEnabled("daily")) {
            daily = new DailyModule(this);
        }
        if (moduleEnabled("rtp")) {
            rtp = new RtpModule(this, delayedTeleports);
        }
        if (moduleEnabled("near")) {
            near = new NearModule(this);
        }
        if (moduleEnabled("inventory")) {
            itemTools = new ItemTools(this);
            scans = new ScanModule(this);
        }
        if (moduleEnabled("filter")) {
            filter = new FilterModule(this);
            getServer().getPluginManager().registerEvents(filter, this);
        }
        if (moduleEnabled("help")) {
            help = new HelpModule(this);
        }
        if (settings("gameplay").getBoolean("hoppers.enabled", true)) {
            hoppers = new HopperModule(this);
            getServer().getPluginManager().registerEvents(hoppers, this);
        }
        if (moduleEnabled("staff")) {
            staffTools = new StaffTools(this);
            getServer().getPluginManager().registerEvents(staffTools, this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (permissions != null) {
            getServer().getOnlinePlayers().forEach(permissions::apply);
        }
        if (nicknames != null) {
            getServer().getOnlinePlayers().forEach(this::refreshDisplayName);
        }
        if (moduleEnabled("worlds")) {
            migrateLegacyWorldMarkers();
        }
        if (moduleEnabled("staff")) {
            getServer().getScheduler().runTaskTimer(this, this::spawnFlightClouds, 1, 4);
        }
    }

    @Override
    public void onDisable() {
        getServer().getOnlinePlayers().stream()
            .filter(player -> flightEnabled.contains(player.getUniqueId()))
            .forEach(this::disableFlight);
        if (autoBreeder != null) {
            autoBreeder.shutdown();
        }
        if (eggCapture != null) {
            eggCapture.shutdown();
        }
        if (villagerReroll != null) {
            villagerReroll.shutdown();
        }
        if (creeperRestoration != null) {
            creeperRestoration.shutdown();
        }
        if (treeFeller != null) {
            treeFeller.shutdown();
        }
        if (graves != null) {
            graves.shutdown();
        }
        if (tpa != null) {
            tpa.shutdown();
        }
        if (announcements != null) {
            announcements.shutdown();
        }
        if (lagg != null) {
            lagg.shutdown();
        }
        if (audit != null) {
            audit.shutdown();
        }
        if (afk != null) {
            afk.shutdown();
        }
        if (poses != null) {
            poses.shutdown();
        }
        if (backpacks != null) {
            backpacks.shutdown();
        }
        if (rtp != null) {
            rtp.shutdown();
        }
        if (utilities != null) {
            utilities.shutdown();
        }
        if (staffTools != null) {
            staffTools.shutdown();
        }
        if (filter != null) {
            filter.shutdown();
        }
        if (scans != null) {
            scans.shutdown();
        }
        if (guiActions != null) {
            guiActions.shutdown();
        }
        biomeSearches.clear();
        if (nicknames != null) {
            nicknames.shutdown();
        }
        if (delayedTeleports != null) {
            delayedTeleports.shutdown();
        }
        timeTransitions.values().forEach(BukkitRunnable::cancel);
        timeTransitions.clear();
        shutdownWaterCropReplanting();
    }

    GuiActions guiActions() {
        return guiActions;
    }

    ChatMetadata chatMetadata(Player player) {
        return permissions == null ? ChatMetadata.empty() : permissions.chatMetadata(player.getUniqueId());
    }

    Component chatDisplayName(Player player, Component externalName) {
        if (permissions == null) {
            return externalName;
        }
        Component name = nicknames == null ? Component.text(player.getName()) : nicknames.displayName(player);
        if (afk != null && afk.isAfk(player.getUniqueId()) && afk.showIndicator()) {
            name = name.append(afk.indicator());
        }
        return name;
    }

    MessageActions messageActions() {
        return messageActions;
    }

    private boolean cropTrampleProtection() {
        return settings("gameplay").getBoolean("crop-trample-protection", true);
    }

    private boolean waterHarvestReplanting() {
        return settings("gameplay").getBoolean("water-harvest-replanting", true);
    }

    private boolean ironGolemPoppyDrops() {
        return settings("gameplay").getBoolean("iron-golem-poppy-drops", true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (moduleEnabled("worlds") && blocksSpawn(event.getLocation().getWorld().getWorldType(),
            event.getSpawnReason(), settings("worlds")
                .getBoolean("flat-worlds.allow-natural-mob-spawning"))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        boolean poppyDropsEnabled = ironGolemPoppyDrops();
        event.getDrops().removeIf(drop -> isDisabledIronGolemPoppyDrop(
            event.getEntityType(), poppyDropsEnabled, drop.getType()));
        Player killer = event.getEntity().getKiller();
        if (!moduleEnabled("mob-heads") || killer == null
            || !usesCustomMobHeadDrop(event.getEntityType(), settings("mob-heads")
                .getBoolean("custom-wither-skeleton-drops", false))
            || !dropsMobHead(event.getEntityType(), ThreadLocalRandom.current().nextDouble(),
                mobHeadChance(killer.getInventory().getItemInMainHand()
                    .getEnchantmentLevel(Enchantment.LOOTING)),
                settings("mob-heads").getStringList("disallowed-heads"))) {
            return;
        }
        ItemStack head = createMobHead(event.getEntityType());
        String mob = titleCase(event.getEntityType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        head.editMeta(meta -> meta.displayName(MM.deserialize(settings("mob-heads").getString(
                "drop-display.name", "<#f72a4c><bold>%mob% Head</bold></#f72a4c>"),
            Placeholder.unparsed("mob", mob))));
        event.getDrops().add(head);
        celebrateHeadDrop(event.getEntity().getLocation().add(0, .8, 0));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (cropTrampleProtection() && isCropTrample(event.getAction(),
            event.getClickedBlock() == null ? null : event.getClickedBlock().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        if (cropTrampleProtection() && event.getBlock().getType() == Material.FARMLAND) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreakBlock(BlockBreakBlockEvent event) {
        if (!waterHarvestReplanting() || event.getSource().getType() != Material.WATER) {
            return;
        }
        Block block = event.getBlock();
        Material plantingItem = plantingItem(block.getType());
        if (plantingItem == null || !consumePlantingItem(event.getDrops(), plantingItem)) {
            return;
        }
        WaterCropKey key = new WaterCropKey(block.getWorld().getUID(), block.getBlockKey());
        PendingWaterCrop replaced = pendingWaterCrops.put(key,
            new PendingWaterCrop(block.getType(), plantingItem));
        if (replaced != null) {
            refundPlantingItem(block, replaced.plantingItem());
        }
        if (waterCropTask == null) {
            waterCropTask = getServer().getScheduler().runTaskTimer(
                this, this::tickPendingWaterCrops, 5, 5);
        }
    }

    private void tickPendingWaterCrops() {
        Iterator<Map.Entry<WaterCropKey, PendingWaterCrop>> iterator =
            pendingWaterCrops.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<WaterCropKey, PendingWaterCrop> entry = iterator.next();
            World world = getServer().getWorld(entry.getKey().worldId());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAtKey(entry.getKey().blockKey());
            PendingWaterCrop crop = entry.getValue();
            if (block.getType() == crop.crop()) {
                refundPlantingItem(block, crop.plantingItem());
                iterator.remove();
                continue;
            }
            BlockData cropData = crop.crop().createBlockData();
            if (block.isEmpty() && block.canPlace(cropData)) {
                if (cropData instanceof Ageable ageable) {
                    ageable.setAge(0);
                }
                block.setBlockData(cropData);
                iterator.remove();
                continue;
            }
            if (crop.addElapsedTicks(5) >= 1_200) {
                refundPlantingItem(block, crop.plantingItem());
                iterator.remove();
            }
        }
        if (pendingWaterCrops.isEmpty() && waterCropTask != null) {
            waterCropTask.cancel();
            waterCropTask = null;
        }
    }

    private void shutdownWaterCropReplanting() {
        if (waterCropTask != null) {
            waterCropTask.cancel();
            waterCropTask = null;
        }
        pendingWaterCrops.forEach((key, crop) -> {
            World world = getServer().getWorld(key.worldId());
            if (world != null) {
                refundPlantingItem(world.getBlockAtKey(key.blockKey()), crop.plantingItem());
            }
        });
        pendingWaterCrops.clear();
    }

    private static void refundPlantingItem(Block block, Material plantingItem) {
        block.getWorld().dropItemNaturally(
            block.getLocation().add(.5, .5, .5), new ItemStack(plantingItem));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (moduleEnabled("staff")) {
            refreshVanishVisibility(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!moduleEnabled("staff")) {
            return;
        }
        vanished.remove(event.getPlayer().getUniqueId());
        if (flightEnabled.contains(event.getPlayer().getUniqueId())) {
            disableFlight(event.getPlayer());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String name = command.getName();
        if (name.equals("rivet")) {
            return adminCommand(sender, args);
        }
        String module = moduleForCommand(name);
        if (commandDisabled(name, this::moduleEnabled)) {
            send(sender, "<white>The <#f72a4c>" + module + "</#f72a4c> module is disabled.");
            return true;
        }
        if (name.equals("lagg")) {
            return lagg.command(sender, args);
        }
        if (name.equals("log")) {
            return audit.command(sender, args);
        }
        if (name.equals("perm")) {
            return permissions.command(sender, args);
        }
        if (name.equals("hologram")) {
            return holograms.command(sender, args);
        }
        if (name.equals("help")) {
            return help.command(sender, args);
        }
        if (name.equals("nick")) {
            return nicknames.command(sender, args);
        }
        if (name.equals("stats")) {
            return statistics.command(sender, args);
        }
        if (name.equals("playtime")) {
            return statistics.playtime(sender, args);
        }
        if (name.equals("seen")) {
            return statistics.seen(sender, args);
        }
        if (name.equals("givebreeder")) {
            return autoBreeder.command(sender, args);
        }
        if (name.equals("clearhologram")) {
            return autoBreeder.clearHolograms(sender);
        }
        if (name.equals("restorationcore")) {
            return creeperRestoration.command(sender, args);
        }
        if (name.equals("scan")) {
            return scans.command(sender, args);
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<white>This command is only available to players.");
            return true;
        }

        GameMode gameMode = gameModeFor(name);
        if (gameMode != null) {
            player.setGameMode(gameMode);
            send(player, "<white>Gamemode set to <#f72a4c>" + gameMode.name().toLowerCase(Locale.ROOT) + "</#f72a4c>.");
            effect(player, "<white>" + titleCase(gameMode.name().toLowerCase(Locale.ROOT)) + "</white>", "<white>Gamemode changed</white>",
                Sound.ENTITY_EXPERIENCE_ORB_PICKUP, Particle.HAPPY_VILLAGER);
            return true;
        }

        return switch (name) {
            case "flat" -> legacyFlat(player);
            case "flatworld" -> flatWorld(player, args);
            case "voidworld" -> voidWorld(player, args);
            case "worldspawn" -> worldSpawn(player);
            case "setworldspawn" -> setWorldSpawn(player);
            case "sethome" -> setHome(player, args);
            case "home" -> home(player, args);
            case "delhome" -> deleteHome(player, args);
            case "setwarp" -> setWarp(player, args);
            case "warp" -> warp(player, args);
            case "delwarp" -> deleteWarp(player, args);
            case "clear" -> itemTools.clear(player, args);
            case "i" -> giveItem(player, args);
            case "condense" -> itemTools.condense(player, args);
            case "donate" -> itemTools.donate(player, args);
            case "giveall" -> itemTools.giveAll(player, args);
            case "killall" -> killAll(player);
            case "day", "night", "noon", "midnight" -> setTime(player, name);
            case "sun", "rain", "thunder" -> setWeather(player, name);
            case "msg" -> chat.message(player, args);
            case "r" -> chat.reply(player, args);
            case "socialspy" -> chat.socialSpy(player, args);
            case "ignore" -> chat.ignore(player, args);
            case "chatcolor" -> chat.chatColor(player, args);
            case "tag" -> chat.tag(player, args);
            case "me" -> chat.me(player, args);
            case "tp" -> teleportPlayer(player, args);
            case "tppos" -> teleportPosition(player, args);
            case "vanish" -> toggleVanish(player);
            case "fly" -> toggleFlight(player, args);
            case "heal" -> staffTools.heal(player, args);
            case "feed" -> staffTools.feed(player, args);
            case "god" -> staffTools.god(player, args);
            case "flyspeed" -> staffTools.flySpeed(player, args);
            case "commandspy" -> staffTools.commandSpy(player, args);
            case "bossbarmsg" -> staffTools.bossBar(player, args);
            case "note" -> staffTools.note(player, args);
            case "sameip" -> staffTools.sameIp(player, args);
            case "toast" -> staffTools.toast(player, args);
            case "spawn", "setspawn" -> spawn.command(player, name, args);
            case "tpa", "tpahere", "tpaccept", "tpdeny" -> tpa.command(player, name, args);
            case "kit" -> kits.command(player, args);
            case "back" -> graves.back(player, args);
            case "afk" -> afk.command(player, args);
            case "afkcheck" -> afk.check(player, args);
            case "invsee" -> inventoryView(player, args);
            case "enderchest" -> enderChest(player, args);
            case "repair" -> itemTools.repair(player, args);
            case "hat" -> itemTools.hat(player, args);
            case "rename" -> itemTools.rename(player, args);
            case "lore" -> itemTools.lore(player, args);
            case "trash" -> trash.command(player, args);
            case "craft", "anvil", "smithing", "stonecutter", "grindstone", "jump", "list", "ping", "ride" ->
                utilities.command(player, name, args);
            case "sit", "lay", "crawl" -> poses.command(player, name, args);
            case "head" -> playerHead(player, args);
            case "backpack" -> backpacks.command(player, args);
            case "daily" -> daily.command(player, args);
            case "rtp" -> rtp.command(player, args);
            case "near" -> near.command(player, args);
            case "findbiome" -> findBiome(player, args);
            case "top" -> top(player, args);
            case "tree" -> tree(player, args);
            case "filter" -> filter.command(player, args);
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!moduleEnabled(moduleForCommand(command.getName()))) {
            return List.of();
        }
        List<String> choices;
        try {
            choices = switch (command.getName()) {
                case "perm" -> permissions.completions(args);
                case "hologram" -> holograms.completions(args);
                case "flatworld" -> args.length == 1 ? List.of("create", "list", "reset", "tp")
                    : args.length == 2 && args[0].equalsIgnoreCase("tp") ? testWorlds(null)
                    : args.length == 2 && args[0].equalsIgnoreCase("reset") ? testWorlds("flat") : List.of();
                case "voidworld" -> args.length == 1 ? List.of("create") : List.of();
                case "home", "delhome" -> args.length == 1 && sender instanceof Player player
                    ? homeNames(player) : List.of();
                case "warp", "delwarp" -> args.length == 1 ? warpNames() : List.of();
                case "i" -> args.length == 1 ? List.of(Material.values()).stream()
                    .filter(material -> material.isItem() && !material.isAir())
                    .map(material -> material.name().toLowerCase(Locale.ROOT)).sorted().toList()
                    : args.length == 2 ? List.of("1", "16", "32", "64") : List.of();
                case "givebreeder" -> autoBreeder.completions(sender, args);
                case "restorationcore" -> creeperRestoration.completions(sender, args);
                case "scan" -> scans.completions(sender, args);
                case "msg", "tp" -> args.length == 1 ? getServer().getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList() : List.of();
                case "tppos" -> playerCoordinateCompletions(sender, args);
                case "ignore" -> sender instanceof Player player
                    ? chat.ignoreCompletions(player, args) : List.of();
                case "chatcolor" -> sender instanceof Player player
                    ? chat.chatColorCompletions(player, args) : List.of();
                case "tag" -> sender instanceof Player player
                    ? chat.tagCompletions(player, args) : List.of();
                case "tpa", "tpahere", "tpaccept", "tpdeny" -> sender instanceof Player player
                    ? tpa.completions(player, command.getName(), args) : List.of();
                case "kit" -> sender instanceof Player player ? kits.completions(player, args) : List.of();
                case "nick" -> nicknames.completions(sender, args);
                case "stats" -> statistics.completions(sender, args);
                case "playtime" -> statistics.playtimeCompletions(sender, args);
                case "seen" -> statistics.seenCompletions(sender, args);
                case "invsee", "enderchest", "head" -> args.length == 1
                    ? getServer().getOnlinePlayers().stream().map(Player::getName)
                        .sorted(String.CASE_INSENSITIVE_ORDER).toList() : List.of();
                case "rtp" -> sender instanceof Player player ? rtp.completions(player, args) : List.of();
                case "heal" -> sender instanceof Player player
                    ? staffTools.completions(player, args, "rivet.heal.others") : List.of();
                case "feed" -> sender instanceof Player player
                    ? staffTools.completions(player, args, "rivet.feed.others") : List.of();
                case "god" -> sender instanceof Player player ? staffTools.godCompletions(player, args) : List.of();
                case "flyspeed" -> sender instanceof Player player
                    ? staffTools.flySpeedCompletions(player, args) : List.of();
                case "bossbarmsg" -> sender instanceof Player player
                    ? staffTools.bossBarCompletions(player, args) : List.of();
                case "note" -> sender instanceof Player player
                    ? staffTools.noteCompletions(player, args) : List.of();
                case "sameip" -> sender instanceof Player player
                    ? staffTools.sameIpCompletions(player, args) : List.of();
                case "toast" -> sender instanceof Player player
                    ? staffTools.toastCompletions(player, args) : List.of();
                case "ping" -> sender instanceof Player player
                    ? utilities.completions(player, "ping", args) : List.of();
                case "top" -> args.length == 1 && sender.hasPermission("rivet.top.others")
                    ? getServer().getOnlinePlayers().stream()
                        .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                        .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList() : List.of();
                case "tree" -> args.length == 1 ? Arrays.stream(TreeType.values())
                    .map(type -> type.name().toLowerCase(Locale.ROOT)).sorted().toList() : List.of();
                case "afk", "afkcheck" -> sender instanceof Player player
                    ? afk.completions(player, command.getName(), args) : List.of();
                case "clear", "condense", "donate", "giveall" -> sender instanceof Player player
                    ? itemTools.completions(player, command.getName(), args) : List.of();
                case "findbiome" -> args.length == 1 ? Registry.BIOME.keyStream()
                    .map(NamespacedKey::getKey).sorted().toList() : List.of();
                case "filter" -> filter.completions(args);
                case "help" -> help.completions(sender, args);
                case "repair" -> args.length == 1 && sender.hasPermission("rivet.repair.all")
                    ? List.of("all") : List.of();
                case "rename" -> args.length == 1 ? List.of("clear") : List.of();
                case "lore" -> args.length == 1 ? List.of("add", "set", "remove", "clear") : List.of();
                case "lagg" -> args.length == 1 ? List.of("clear", "reload") : List.of();
                case "log" -> audit.completions(sender, args);
                case "rivet" -> args.length == 1 ? List.of("reload") : List.of();
                default -> List.of();
            };
        } catch (IOException exception) {
            choices = List.of();
        }
        return completions(choices, args.length == 0 ? "" : args[args.length - 1]);
    }

    private boolean flatWorld(Player player, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            return listWorlds(player);
        }
        if (args.length != 2 || !validWorldName(args[1])) {
            send(player, "<white>Usage: /flatworld <create|tp|reset|list> &lt;name&gt;");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> createWorld(player, args[1], "flat");
            case "tp" -> teleportWorld(player, args[1]);
            case "reset" -> resetWorld(player, args[1]);
            default -> {
                send(player, "<white>Usage: /flatworld <create|tp|reset|list> &lt;name&gt;");
                yield true;
            }
        };
    }

    private boolean voidWorld(Player player, String[] args) {
        if (args.length != 2 || !args[0].equalsIgnoreCase("create") || !validWorldName(args[1])) {
            send(player, "<white>Usage: /voidworld create &lt;name&gt;");
            return true;
        }
        return createWorld(player, args[1], "void");
    }

    private boolean createWorld(Player player, String name, String type) {
        Path path = worldPath(name);
        if (Files.exists(path)) {
            send(player, "<white>A world named <#f72a4c>" + name + "</#f72a4c> already exists.");
            return true;
        }

        World world = loadWorld(name, type);
        if (world == null) {
            send(player, "<white>Could not create <#f72a4c>" + name + "</#f72a4c>.");
            return true;
        }

        try {
            trackWorld(name, type);
            send(player, "<white>Created " + type + " world <#f72a4c>" + name + "</#f72a4c>.");
            effect(player, "<white>World created</white>", "<white>" + name + "</white>",
                Sound.BLOCK_BEACON_ACTIVATE, Particle.END_ROD);
        } catch (IOException exception) {
            getLogger().severe("Created world " + name + " but could not mark it as a test world: " + exception.getMessage());
            send(player, "<white>World created, but Rivet could not track it. Check the console.");
        }
        return true;
    }

    private boolean teleportWorld(Player player, String name) {
        String type = worldType(name);
        if (type == null) {
            send(player, "<white>No test world named <#f72a4c>" + name + "</#f72a4c> exists.");
            return true;
        }

        World world = getServer().getWorld(name);
        if (world == null) {
            world = loadWorld(name, type);
        }
        if (world == null) {
            send(player, "<white>Could not load <#f72a4c>" + name + "</#f72a4c>.");
            return true;
        }

        player.teleport(world.getSpawnLocation());
        send(player, "<white>Teleported to <#f72a4c>" + name + "</#f72a4c>.");
        effect(player, "<#f72a4c>" + name + "</#f72a4c>", "<white>Teleported</white>",
            Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL);
        return true;
    }

    private boolean resetWorld(Player player, String name) {
        if (!"flat".equals(worldType(name))) {
            send(player, "<white>No flat test world named <#f72a4c>" + name + "</#f72a4c> exists.");
            return true;
        }

        World world = getServer().getWorld(name);
        if (world != null) {
            World fallback = getServer().getWorlds().stream().filter(candidate -> !candidate.equals(world)).findFirst().orElse(null);
            if (!world.getPlayers().isEmpty() && fallback == null) {
                send(player, "<white>Cannot reset the only loaded world while players are inside it.");
                return true;
            }
            if (fallback != null) {
                world.getPlayers().forEach(occupant -> occupant.teleport(fallback.getSpawnLocation()));
            }
            if (!getServer().unloadWorld(world, false)) {
                send(player, "<white>Could not unload <#f72a4c>" + name + "</#f72a4c>.");
                return true;
            }
        }

        try {
            deleteWorld(worldPath(name));
        } catch (IOException exception) {
            getLogger().severe("Could not delete world " + name + ": " + exception.getMessage());
            send(player, "<white>Could not delete <#f72a4c>" + name + "</#f72a4c>. Check the console.");
            return true;
        }

        World regenerated = loadWorld(name, "flat");
        if (regenerated == null) {
            send(player, "<white>Deleted the world, but could not regenerate it.");
            return true;
        }
        try {
            trackWorld(name, "flat");
            send(player, "<white>Reset flat world <#f72a4c>" + name + "</#f72a4c>.");
            effect(player, "<#f72a4c>World reset</#f72a4c>", "<white>" + name + "</white>",
                Sound.BLOCK_BEACON_ACTIVATE, Particle.POOF);
        } catch (IOException exception) {
            getLogger().severe("Regenerated world " + name + " but could not save its data: " + exception.getMessage());
            send(player, "<white>World regenerated, but Rivet could not track it. Check the console.");
        }
        return true;
    }

    private boolean listWorlds(Player player) {
        List<String> worlds;
        try {
            worlds = testWorlds(null);
        } catch (IOException exception) {
            send(player, "<white>Could not list test worlds.");
            return true;
        }

        if (worlds.isEmpty()) {
            send(player, "<white>No test worlds have been generated.");
            return true;
        }

        Component message = MM.deserialize("<#f72a4c>Test worlds:</#f72a4c>");
        for (String name : worlds) {
            String type = worldType(name);
            message = message.append(Component.newline()).append(MM.deserialize(
                "<click:run_command:'/flatworld tp " + name + "'><#f72a4c>" + name
                    + "</#f72a4c> <white>(" + type + ")</white></click>"));
        }
        player.sendMessage(message);
        return true;
    }

    private List<String> testWorlds(String type) throws IOException {
        try (var paths = Files.list(getServer().getWorldContainer().toPath())) {
            return paths.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> type == null ? worldType(name) != null : type.equals(worldType(name)))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        }
    }

    private boolean legacyFlat(Player player) {
        World world = getServer().getWorld("flat");
        if (world == null) {
            world = new WorldCreator("flat").type(WorldType.FLAT).generateStructures(false).createWorld();
        }
        if (world == null) {
            send(player, "<white>Could not create the flat world.");
            return true;
        }
        player.teleport(world.getSpawnLocation());
        send(player, "<white>Teleported to <#f72a4c>flat</#f72a4c>.");
        effect(player, "<#f72a4c>flat</#f72a4c>", "<white>Teleported</white>",
            Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL);
        return true;
    }

    private boolean worldSpawn(Player player) {
        player.teleport(player.getWorld().getSpawnLocation());
        send(player, "<white>Teleported to this world's spawn.");
        effect(player, "<#f72a4c>World spawn</#f72a4c>", "<white>Teleported</white>",
            Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL);
        return true;
    }

    private boolean setWorldSpawn(Player player) {
        player.getWorld().setSpawnLocation(player.getLocation());
        send(player, "<white>Set this world's spawn to your location.");
        effect(player, "<white>Spawn set</white>", "<white>Current location</white>",
            Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, Particle.END_ROD);
        return true;
    }

    private boolean setHome(Player player, String[] args) {
        String name = homeName(args);
        if (name == null) {
            moduleMessage(player, "homes", "messages.usage-set",
                "<white>Usage: /sethome [name]</white>");
            return true;
        }
        if (saveLocation(homes, "homes", homePath(player, name), player.getLocation(), player)) {
            moduleMessage(player, "homes", "messages.set",
                "<white>Home <#f72a4c>%name%</#f72a4c> set.</white>",
                Placeholder.unparsed("name", name));
            configuredEffect(player, "homes", "effects.set",
                Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, Particle.END_ROD,
                Placeholder.unparsed("name", titleCase(name)));
        }
        return true;
    }

    private boolean home(Player player, String[] args) {
        String name = homeName(args);
        if (name == null) {
            moduleMessage(player, "homes", "messages.usage-teleport",
                "<white>Usage: /home [name]</white>");
            return true;
        }
        String path = homePath(player, name);
        if (name.equals("home") && !homes.isConfigurationSection(path)
            && homes.contains("homes." + player.getUniqueId() + ".world")) {
            path = "homes." + player.getUniqueId();
        }
        return teleportSaved(player, homes, path, name, "homes");
    }

    private boolean deleteHome(Player player, String[] args) {
        String name = homeName(args);
        if (name == null) {
            moduleMessage(player, "homes", "messages.usage-delete",
                "<white>Usage: /delhome [name]</white>");
            return true;
        }
        String path = homePath(player, name);
        String legacy = "homes." + player.getUniqueId();
        if (!homes.isConfigurationSection(path)
            && (!name.equals("home") || !homes.contains(legacy + ".world"))) {
            moduleMessage(player, "homes", "messages.missing",
                "<white>No %name% is set.</white>", Placeholder.unparsed("name", name));
            return true;
        }
        homes.set(path, null);
        if (name.equals("home")) {
            homes.set(legacy + ".world", null);
            homes.set(legacy + ".x", null);
            homes.set(legacy + ".y", null);
            homes.set(legacy + ".z", null);
            homes.set(legacy + ".yaw", null);
            homes.set(legacy + ".pitch", null);
        }
        if (saveData("homes", player)) {
            moduleMessage(player, "homes", "messages.deleted",
                "<white>Home <#f72a4c>%name%</#f72a4c> deleted.</white>",
                Placeholder.unparsed("name", name));
            configuredEffect(player, "homes", "effects.delete",
                Sound.BLOCK_NOTE_BLOCK_PLING, Particle.POOF,
                Placeholder.unparsed("name", titleCase(name)));
        }
        return true;
    }

    private boolean setWarp(Player player, String[] args) {
        if (args.length != 1 || !validWorldName(args[0])) {
            moduleMessage(player, "warps", "messages.usage-set",
                "<white>Usage: /setwarp &lt;name&gt;</white>");
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        if (saveLocation(warps, "warps", "warps." + name, player.getLocation(), player)) {
            moduleMessage(player, "warps", "messages.set",
                "<white>Warp <#f72a4c>%name%</#f72a4c> set.</white>",
                Placeholder.unparsed("name", name));
            configuredEffect(player, "warps", "effects.set",
                Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, Particle.END_ROD,
                Placeholder.unparsed("name", titleCase(name)));
        }
        return true;
    }

    private boolean warp(Player player, String[] args) {
        if (args.length != 1 || !validWorldName(args[0])) {
            moduleMessage(player, "warps", "messages.usage-teleport",
                "<white>Usage: /warp &lt;name&gt;</white>");
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        return teleportSaved(player, warps, "warps." + name, name, "warps");
    }

    private boolean deleteWarp(Player player, String[] args) {
        if (args.length != 1 || !validWorldName(args[0])) {
            moduleMessage(player, "warps", "messages.usage-delete",
                "<white>Usage: /delwarp &lt;name&gt;</white>");
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        String path = "warps." + name;
        if (!warps.isConfigurationSection(path)) {
            moduleMessage(player, "warps", "messages.missing",
                "<white>No %name% is set.</white>", Placeholder.unparsed("name", name));
            return true;
        }
        warps.set(path, null);
        if (saveData("warps", player)) {
            moduleMessage(player, "warps", "messages.deleted",
                "<white>Warp <#f72a4c>%name%</#f72a4c> deleted.</white>",
                Placeholder.unparsed("name", name));
            configuredEffect(player, "warps", "effects.delete",
                Sound.BLOCK_NOTE_BLOCK_PLING, Particle.POOF,
                Placeholder.unparsed("name", titleCase(name)));
        }
        return true;
    }

    private boolean teleportSaved(Player player, YamlConfiguration data, String path,
                                  String name, String module) {
        if (!data.isConfigurationSection(path)) {
            moduleMessage(player, module, "messages.missing",
                "<white>No %name% is set.</white>", Placeholder.unparsed("name", name));
            return true;
        }
        Location location = savedLocation(data, path);
        if (location == null) {
            moduleMessage(player, module, "messages.world-unavailable",
                "<white>The world for <#f72a4c>%name%</#f72a4c> is unavailable.</white>",
                Placeholder.unparsed("name", name));
            return true;
        }
        delayedTeleports.start(player, location, () -> {
            moduleMessage(player, module, "messages.teleported",
                "<white>Teleported to <#f72a4c>%name%</#f72a4c>.</white>",
                Placeholder.unparsed("name", name));
            configuredEffect(player, module, "effects.teleport",
                Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL,
                Placeholder.unparsed("name", titleCase(name)));
        });
        return true;
    }

    private boolean saveLocation(YamlConfiguration data, String file, String path,
                                 Location location, Player player) {
        data.set(path + ".world", location.getWorld().getName());
        data.set(path + ".x", location.getX());
        data.set(path + ".y", location.getY());
        data.set(path + ".z", location.getZ());
        data.set(path + ".yaw", location.getYaw());
        data.set(path + ".pitch", location.getPitch());
        return saveData(file, player);
    }

    private boolean saveData(String file, Player player) {
        try {
            files.saveData(file);
            return true;
        } catch (IOException exception) {
            getLogger().severe("Could not save data/" + file + ".yml: " + exception.getMessage());
            moduleMessage(player, file, "messages.save-failed",
                "<white>Could not save the change. Check the console.</white>");
            return false;
        }
    }

    private Location savedLocation(YamlConfiguration data, String path) {
        String worldName = data.getString(path + ".world");
        World world = worldName == null ? null : getServer().getWorld(worldName);
        if (world == null && worldName != null) {
            String type = worldType(worldName);
            if (type != null) {
                world = loadWorld(worldName, type);
            }
        }
        double x = data.getDouble(path + ".x");
        double y = data.getDouble(path + ".y");
        double z = data.getDouble(path + ".z");
        float yaw = (float) data.getDouble(path + ".yaw");
        float pitch = (float) data.getDouble(path + ".pitch");
        return world == null || !validCoordinates(x, y, z, yaw, pitch)
            ? null : new Location(world, x, y, z, yaw, pitch);
    }

    private List<String> warpNames() {
        var saved = warps.getConfigurationSection("warps");
        return saved == null ? List.of() : saved.getKeys(false).stream()
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> homeNames(Player player) {
        Set<String> names = new HashSet<>();
        var saved = homes.getConfigurationSection("homes." + player.getUniqueId() + ".locations");
        if (saved != null) {
            names.addAll(saved.getKeys(false));
        }
        if (homes.contains("homes." + player.getUniqueId() + ".world")) {
            names.add("home");
        }
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String homePath(Player player, String name) {
        return "homes." + player.getUniqueId() + ".locations." + name;
    }

    private boolean findBiome(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<white>Usage: /findbiome &lt;biome&gt;");
            return true;
        }
        String value = args[0].toLowerCase(Locale.ROOT).replace(' ', '_');
        NamespacedKey key = NamespacedKey.fromString(value.contains(":") ? value : "minecraft:" + value);
        Biome biome = key == null ? null : Registry.BIOME.get(key);
        if (biome == null) {
            send(player, "<white>That biome does not exist.");
            return true;
        }
        if (!biomeSearches.add(player.getUniqueId())) {
            send(player, "<white>A biome search is already running.");
            return true;
        }
        int radius = Math.max(64, Math.min(30_000,
            settings("worlds").getInt("find-biome.radius", 6_400)));
        int horizontalStep = Math.max(1,
            settings("worlds").getInt("find-biome.horizontal-step", 32));
        int verticalStep = Math.max(1,
            settings("worlds").getInt("find-biome.vertical-step", 64));
        Location origin = player.getLocation().clone();
        send(player, "<white>Searching for the nearest <#f72a4c>" + key.getKey().replace('_', ' ') + "</#f72a4c>...</white>");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            BiomeSearchResult result = null;
            Throwable failure = null;
            try {
                result = origin.getWorld().locateNearestBiome(origin, radius, horizontalStep,
                    verticalStep, biome);
            } catch (Throwable throwable) {
                failure = throwable;
            }
            BiomeSearchResult found = result;
            Throwable error = failure;
            if (!isEnabled()) {
                return;
            }
            try {
                getServer().getScheduler().runTask(this, () -> {
                    biomeSearches.remove(player.getUniqueId());
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        getLogger().warning("Biome search failed: " + error.getMessage());
                        send(player, "<white>Biome search failed. Check the console.");
                        return;
                    }
                    if (found == null) {
                        send(player, "<white>No matching biome was found within <#f72a4c>" + radius + "</#f72a4c> blocks.");
                        return;
                    }
                    Location location = found.getLocation();
                    int distance = (int) Math.round(Math.hypot(location.getX() - origin.getX(),
                        location.getZ() - origin.getZ()));
                    int x = location.getBlockX();
                    int y = location.getBlockY();
                    int z = location.getBlockZ();
                    player.sendMessage(MM.deserialize("<white>Nearest <#f72a4c>%biome%</#f72a4c>: "
                            + "<click:suggest_command:'/tp " + x + " " + y + " " + z
                            + "'><#f72a4c>%x%, %y%, %z%</#f72a4c></click> <white>(%distance% blocks)</white>",
                        Placeholder.unparsed("biome", key.getKey().replace('_', ' ')),
                        Placeholder.unparsed("x", Integer.toString(x)),
                        Placeholder.unparsed("y", Integer.toString(y)),
                        Placeholder.unparsed("z", Integer.toString(z)),
                        Placeholder.unparsed("distance", Integer.toString(distance))));
                });
            } catch (IllegalStateException ignored) {
                // Plugin shutdown raced the async search completion.
            }
        });
        return true;
    }

    private boolean top(Player actor, String[] args) {
        Player target = actor;
        if (args.length == 1 && actor.hasPermission("rivet.top.others")) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null || !actor.canSee(target)) {
                send(actor, "<white>That player is not online.");
                return true;
            }
        } else if (args.length != 0) {
            send(actor, "<white>Usage: /top" + (actor.hasPermission("rivet.top.others") ? " [player]" : ""));
            return true;
        }
        Location current = target.getLocation();
        Location destination = SafeLocations.highest(target.getWorld(), current.getBlockX(),
            current.getBlockZ(), current);
        if (destination == null || !target.teleport(destination)) {
            send(actor, "<white>No safe top location was found at that position.");
            return true;
        }
        send(actor, "<white>Teleported <#f72a4c>" + target.getName()
            + "</#f72a4c> to the highest safe location.");
        if (!actor.equals(target)) {
            send(target, "<white>You were teleported to the highest safe location by <#f72a4c>"
                + actor.getName() + "</#f72a4c>.");
        }
        teleportFeedback(target, "Top");
        return true;
    }

    private boolean tree(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<white>Usage: /tree <treeType>");
            return true;
        }
        TreeType type = treeType(args[0]);
        if (type == null) {
            send(player, "<white>That vanilla tree type is not supported.");
            return true;
        }
        int range = Math.max(1, Math.min(200,
            settings("worlds").getInt("tree.maximum-range", 50)));
        org.bukkit.block.Block target = player.getTargetBlockExact(range, org.bukkit.FluidCollisionMode.NEVER);
        if (target == null) {
            send(player, "<white>No block is in range.");
            return true;
        }
        Location origin = target.getLocation().add(0, 1, 0);
        if (!SafeLocations.suitableTreeBase(target) || !SafeLocations.treeClearance(origin)) {
            send(player, "<white>That target does not have safe ground and enough clear space.");
            return true;
        }
        if (!player.getWorld().generateTree(origin, type)) {
            send(player, "<white>Tree generation failed; the location may be unsuitable or protected.");
            return true;
        }
        send(player, "<white>Generated <#f72a4c>" + type.name().toLowerCase(Locale.ROOT)
            .replace('_', ' ') + "</#f72a4c>.");
        return true;
    }

    static TreeType treeType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TreeType.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean giveItem(Player player, String[] args) {
        if (args.length < 1 || args.length > 2) {
            send(player, "<white>Usage: /i &lt;item&gt; [amount]");
            return true;
        }

        Material material = Material.matchMaterial(args[0]);
        int amount = args.length == 1 ? 64 : itemAmount(args[1]);
        if (material == null || material.isAir() || !material.isItem() || amount < 1) {
            send(player, "<white>Use a valid item and a positive whole-number amount.");
            return true;
        }

        player.getInventory().addItem(new ItemStack(material, amount)).values()
            .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(player, "<white>Gave <#f72a4c>" + amount + " " + material.name().toLowerCase(Locale.ROOT) + "</#f72a4c>.");
        return true;
    }

    private boolean inventoryView(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<white>Usage: /invsee &lt;player&gt;");
            return true;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            send(player, "<white>That player is not online.");
            return true;
        }
        player.openInventory(target.getInventory());
        send(player, "<white>Opened <#f72a4c>" + target.getName() + "</#f72a4c>'s inventory.");
        return true;
    }

    private boolean enderChest(Player player, String[] args) {
        Player target = player;
        if (args.length == 1 && player.hasPermission("rivet.inventory.enderchest.others")) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null || !player.canSee(target)) {
                send(player, "<white>That player is not online.");
                return true;
            }
        } else if (args.length != 0) {
            send(player, "<white>Usage: /enderchest [player]");
            return true;
        }
        player.openInventory(target.getEnderChest());
        return true;
    }

    private boolean playerHead(Player player, String[] args) {
        if (args.length != 1 || !args[0].matches("[A-Za-z0-9_]{1,16}")) {
            send(player, "<white>Usage: /head &lt;player&gt;");
            return true;
        }
        Player online = getServer().getPlayerExact(args[0]);
        OfflinePlayer target = online == null ? getServer().getOfflinePlayerIfCached(args[0]) : online;
        if (target == null || target.getName() == null) {
            send(player, "<white>That player profile is not known to this server.");
            return true;
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(target.getPlayerProfile());
        var placeholders = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] {
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("requester", player.getName())
        };
        meta.displayName(MM.deserialize(settings("mob-heads").getString("player-heads.display-name",
            "<#f72a4c>%player%'s Head</#f72a4c>"), placeholders));
        List<String> lore = settings("mob-heads").getStringList("player-heads.lore");
        if (lore.isEmpty()) {
            lore = List.of("<white>Requested by %requester%</white>");
        }
        meta.lore(lore.stream()
            .map(line -> MM.deserialize(line, placeholders)).toList());
        head.setItemMeta(meta);
        player.getInventory().addItem(head).values()
            .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(player, "<white>Gave you <#f72a4c>" + target.getName() + "</#f72a4c>'s head.");
        return true;
    }

    private boolean killAll(Player player) {
        var mobs = player.getWorld().getEntitiesByClass(Mob.class);
        mobs.forEach(Mob::remove);
        send(player, "<white>Removed <#f72a4c>" + mobs.size() + "</#f72a4c> mobs.");
        return true;
    }

    private boolean teleportPlayer(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<white>Usage: /tp &lt;player&gt;");
            return true;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            send(player, "<white>That player is not online.");
            return true;
        }
        if (!player.teleport(target)) {
            send(player, "<white>Teleport failed.");
            return true;
        }
        send(player, "<white>Teleported to <#f72a4c>" + target.getName() + "</#f72a4c>.");
        if (!vanished.contains(player.getUniqueId())) {
            if (getConfig().getBoolean("effects.sounds")) {
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.1f);
            }
            if (getConfig().getBoolean("effects.particles")) {
                player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4);
            }
        }
        return true;
    }

    private boolean teleportPosition(Player player, String[] args) {
        if (args.length != 3) {
            moduleMessage(player, "staff", "messages.tppos-usage",
                "<white>Usage: /tppos &lt;x&gt; &lt;y&gt; &lt;z&gt;</white>");
            return true;
        }
        Location current = player.getLocation();
        Double x = parseCoordinate(args[0], current.getX());
        Double y = parseCoordinate(args[1], current.getY());
        Double z = parseCoordinate(args[2], current.getZ());
        if (x == null || y == null || z == null) {
            moduleMessage(player, "staff", "messages.tppos-invalid",
                "<white>Coordinates must be finite numbers or relative values such as ~10.</white>");
            return true;
        }
        World world = player.getWorld();
        if (!validTeleportPosition(x, y, z, world.getMinHeight(), world.getMaxHeight())) {
            moduleMessage(player, "staff", "messages.tppos-out-of-world",
                "<white>Those coordinates are outside the world's usable limits.</white>");
            return true;
        }

        Location destination = new Location(world, x, y, z,
            current.getYaw(), current.getPitch());
        if (!player.teleport(destination)) {
            moduleMessage(player, "staff", "messages.tppos-failed",
                "<white>Teleport failed.</white>");
            return true;
        }
        moduleMessage(player, "staff", "messages.tppos-teleported",
            "<white>Teleported to <#f72a4c>%x%, %y%, %z%</#f72a4c>.</white>",
            Placeholder.unparsed("x", displayCoordinate(x)),
            Placeholder.unparsed("y", displayCoordinate(y)),
            Placeholder.unparsed("z", displayCoordinate(z)));
        if (!vanished.contains(player.getUniqueId())) {
            if (getConfig().getBoolean("effects.sounds")) {
                world.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, .8f, 1.1f);
            }
            if (getConfig().getBoolean("effects.particles")) {
                world.spawnParticle(Particle.PORTAL, destination.clone().add(0, 1, 0),
                    30, .4, .8, .4);
            }
        }
        return true;
    }

    private boolean toggleVanish(Player player) {
        boolean enable = !vanished.contains(player.getUniqueId());
        setVanished(player, enable);
        send(player, enable ? "<white>Vanish enabled." : "<white>Vanish disabled.");
        return true;
    }

    private boolean toggleFlight(Player player, String[] args) {
        if (args.length != 0) {
            send(player, "<white>Usage: /fly");
            return true;
        }
        boolean enable = flightEnabled.add(player.getUniqueId());
        if (enable) {
            player.setAllowFlight(true);
        } else {
            disableFlight(player);
        }
        send(player, enable ? "<#f72a4c>Flight enabled.</#f72a4c> <white>Double-tap jump to fly."
            : "<white>Flight disabled.");
        return true;
    }

    private void disableFlight(Player player) {
        flightEnabled.remove(player.getUniqueId());
        if (!hasNaturalFlight(player.getGameMode())) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private void spawnFlightClouds() {
        if (!getConfig().getBoolean("effects.particles")) {
            return;
        }
        getServer().getOnlinePlayers().stream()
            .filter(player -> flightEnabled.contains(player.getUniqueId()) && player.isFlying())
            .filter(player -> !vanished.contains(player.getUniqueId()))
            .forEach(player -> player.getWorld().spawnParticle(Particle.CLOUD,
                player.getLocation().add(0, -.35, 0), 3, .28, .05, .28, .01));
    }

    private void celebrateHeadDrop(Location location) {
        YamlConfiguration configured = settings("mob-heads");
        String path = "drop-effects";
        if (!configured.getBoolean(path + ".enabled", true)) {
            return;
        }
        if (getConfig().getBoolean("effects.sounds")
            && configured.getBoolean(path + ".sound.enabled", true)) {
            location.getWorld().playSound(location,
                ConfiguredEffect.sound(this, configured, path + ".sound.name",
                    Sound.ENTITY_TURTLE_EGG_HATCH),
                finiteFloat(configured.getDouble(path + ".sound.volume", 1.2), 1.2f, 0, 10),
                finiteFloat(configured.getDouble(path + ".sound.pitch", .85), .85f, 0, 2));
        }
        if (!getConfig().getBoolean("effects.particles")) {
            return;
        }
        String spiralPath = path + ".particles.spiral";
        String burstPath = path + ".particles.burst";
        boolean spiralEnabled = configured.getBoolean(spiralPath + ".enabled", true);
        boolean burstEnabled = configured.getBoolean(burstPath + ".enabled", true);
        if (!spiralEnabled && !burstEnabled) {
            return;
        }
        Particle spiral = spiralEnabled ? ConfiguredEffect.particle(this, configured,
            spiralPath + ".name", Particle.END_ROD) : null;
        Particle burst = burstEnabled ? ConfiguredEffect.particle(this, configured,
            burstPath + ".name", Particle.TOTEM_OF_UNDYING) : null;
        int steps = boundedInt(configured.getInt(spiralPath + ".steps", 8), 1, 100);
        int points = boundedInt(configured.getInt(spiralPath + ".points-per-step", 16), 1, 200);
        long period = boundedInt(configured.getInt(spiralPath + ".period-ticks", 2), 1, 1_200);
        double initialRadius = finiteDouble(configured.getDouble(
            spiralPath + ".initial-radius", .15), .15, 0, 20);
        double radiusPerStep = finiteDouble(configured.getDouble(
            spiralPath + ".radius-per-step", .08), .08, -20, 20);
        double heightPerStep = finiteDouble(configured.getDouble(
            spiralPath + ".height-per-step", .06), .06, -20, 20);
        double rotationPerStep = finiteDouble(configured.getDouble(
            spiralPath + ".rotation-per-step", .3), .3, -100, 100);
        double spiralSpeed = finiteDouble(configured.getDouble(
            spiralPath + ".speed", .01), .01, 0, 10);
        int burstCount = boundedInt(configured.getInt(burstPath + ".count", 8), 0, 1_000);
        double burstX = finiteDouble(configured.getDouble(burstPath + ".offset-x", .3), .3, 0, 20);
        double burstY = finiteDouble(configured.getDouble(burstPath + ".offset-y", .35), .35, 0, 20);
        double burstZ = finiteDouble(configured.getDouble(burstPath + ".offset-z", .3), .3, 0, 20);
        double burstSpeed = finiteDouble(configured.getDouble(burstPath + ".speed", .08), .08, 0, 10);
        new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (step == (spiralEnabled ? steps : 1)) {
                    cancel();
                    return;
                }
                int currentStep = ++step;
                if (spiral != null) {
                    double radius = initialRadius + currentStep * radiusPerStep;
                    for (int point = 0; point < points; point++) {
                        double angle = point * Math.PI * 2 / points + currentStep * rotationPerStep;
                        location.getWorld().spawnParticle(spiral,
                            location.clone().add(Math.cos(angle) * radius,
                                currentStep * heightPerStep, Math.sin(angle) * radius),
                            1, 0, 0, 0, spiralSpeed);
                    }
                }
                if (burst != null && burstCount > 0) {
                    location.getWorld().spawnParticle(burst, location, burstCount,
                        burstX, burstY, burstZ, burstSpeed);
                }
            }
        }.runTaskTimer(this, 0, period);
    }

    private static int boundedInt(int configured, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, configured));
    }

    private static double finiteDouble(double configured, double fallback,
                                       double minimum, double maximum) {
        return Double.isFinite(configured)
            ? Math.max(minimum, Math.min(maximum, configured)) : fallback;
    }

    private static float finiteFloat(double configured, float fallback,
                                     double minimum, double maximum) {
        return (float) finiteDouble(configured, fallback, minimum, maximum);
    }

    private double mobHeadChance(int lootingLevel) {
        YamlConfiguration configured = settings("mob-heads");
        return mobHeadChance(configured.getDouble("drop-chance", DEFAULT_MOB_HEAD_CHANCE),
            lootingLevel, configured.getDouble("looting-bonus-per-level",
                DEFAULT_MOB_HEAD_LOOTING_BONUS));
    }

    private ItemStack createMobHead(EntityType type) {
        Material material = mobHeadFor(type);
        if (material != null) {
            return new ItemStack(material);
        }
        String texture = MOB_HEAD_TEXTURES.get(type);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        PlayerProfile profile = getServer().createPlayerProfile(
            UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)));
        PlayerTextures textures = profile.getTextures();
        try {
            textures.setSkin(URI.create(
                "https://textures.minecraft.net/texture/" + texture).toURL());
        } catch (MalformedURLException exception) {
            throw new IllegalStateException("Invalid bundled mob head texture", exception);
        }
        profile.setTextures(textures);
        meta.setOwnerProfile(profile);
        head.setItemMeta(meta);
        return head;
    }

    private void setVanished(Player player, boolean enable) {
        if (enable) {
            vanished.add(player.getUniqueId());
        } else {
            vanished.remove(player.getUniqueId());
        }
        getServer().getOnlinePlayers().stream()
            .filter(viewer -> !viewer.equals(player) && !viewer.hasPermission("rivet.vanish.see"))
            .forEach(viewer -> {
                if (enable) {
                    viewer.hidePlayer(this, player);
                } else {
                    viewer.showPlayer(this, player);
                }
            });
    }

    void permissionsChanged(Player player) {
        if (moduleEnabled("staff") && vanished.contains(player.getUniqueId())
            && !player.hasPermission("rivet.vanish")) {
            setVanished(player, false);
            send(player, "<white>Vanish disabled because your permission changed.");
        }
        if (moduleEnabled("staff") && flightEnabled.contains(player.getUniqueId())
            && !player.hasPermission("rivet.fly")) {
            disableFlight(player);
            send(player, "<white>Flight disabled because your permission changed.");
        }
        if (moduleEnabled("staff")) {
            refreshVanishVisibility(player);
        }
        if (holograms != null) {
            holograms.refresh(player);
        }
        refreshDisplayName(player);
    }

    void teleportFeedback(Player player, String destination) {
        effect(player, "<#f72a4c>" + destination + "</#f72a4c>", "<white>Teleported</white>",
            Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL);
    }

    void refreshDisplayName(Player player) {
        Component name = nicknames == null ? Component.text(player.getName()) : nicknames.displayName(player);
        if (permissions != null) {
            name = permissions.decorateName(player, name);
        }
        if (afk != null && afk.isAfk(player.getUniqueId()) && afk.showIndicator()) {
            name = name.append(afk.indicator());
        }
        player.displayName(name);
    }

    public boolean isAfk(Player player) {
        return afk != null && afk.isAfk(player.getUniqueId());
    }

    private void refreshVanishVisibility(Player viewer) {
        vanished.stream().map(getServer()::getPlayer).filter(java.util.Objects::nonNull)
            .forEach(player -> {
                if (viewer.hasPermission("rivet.vanish.see")) {
                    viewer.showPlayer(this, player);
                } else {
                    viewer.hidePlayer(this, player);
                }
            });
    }

    private boolean setTime(Player player, String command) {
        long time = switch (command) {
            case "day" -> 1000;
            case "noon" -> 6000;
            case "night" -> 13000;
            default -> 18000;
        };
        World world = player.getWorld();
        cancelTimeTransition(world);
        if ((command.equals("day") || command.equals("night"))
            && settings("environment").getBoolean("speedUpEffect.enabled", true)) {
            transitionTime(world, time);
        } else {
            world.setTime(time);
        }
        runEnvironmentActions(player, "times", command);
        return true;
    }

    private void transitionTime(World world, long target) {
        long start = Math.floorMod(world.getTime(), MINECRAFT_DAY_TICKS);
        long distance = forwardTimeDistance(start, target);
        if (distance == 0) {
            world.setTime(target);
            return;
        }
        int duration = 100;
        int period = 1;
        int steps = Math.max(1, (duration + period - 1) / period);
        UUID worldId = world.getUID();
        BukkitRunnable transition = new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (getServer().getWorld(worldId) != world) {
                    finish();
                    return;
                }
                world.setTime(transitionedTime(start, distance, ++step, steps));
                if (step == steps) {
                    finish();
                }
            }

            private void finish() {
                timeTransitions.remove(worldId, this);
                cancel();
            }
        };
        timeTransitions.put(worldId, transition);
        transition.runTaskTimer(this, period, period);
    }

    private void cancelTimeTransition(World world) {
        BukkitRunnable transition = timeTransitions.remove(world.getUID());
        if (transition != null) {
            transition.cancel();
        }
    }

    static long forwardTimeDistance(long current, long target) {
        long normalizedCurrent = Math.floorMod(current, MINECRAFT_DAY_TICKS);
        long normalizedTarget = Math.floorMod(target, MINECRAFT_DAY_TICKS);
        return Math.floorMod(normalizedTarget - normalizedCurrent, MINECRAFT_DAY_TICKS);
    }

    static long transitionedTime(long start, long distance, int step, int steps) {
        if (step >= steps) {
            return Math.floorMod(start + distance, MINECRAFT_DAY_TICKS);
        }
        long progressed = Math.round(distance * (step / (double) steps));
        return Math.floorMod(start + progressed, MINECRAFT_DAY_TICKS);
    }

    private boolean setWeather(Player player, String command) {
        World world = player.getWorld();
        world.setStorm(!command.equals("sun"));
        world.setThundering(command.equals("thunder"));
        runEnvironmentActions(player, "weather", command);
        return true;
    }

    private void runEnvironmentActions(Player player, String section, String command) {
        String path = section + "." + command + ".commands_when_ran";
        messageActions.run(player, settings("environment"), path, List.of(
                "[actionbar] <white>" + (section.equals("times") ? "Time" : "Weather")
                    + " set to <green>" + titleCase(command) + "</green></white>",
                "[sound] ui_button_click"));
    }

    private void moduleMessage(Player player, String module, String path, String fallback,
                               TagResolver... placeholders) {
        messageActions.run(player, settings(module), path, fallback, placeholders);
    }

    private void configuredEffect(Player player, String module, String path,
                                  Sound fallbackSound, Particle fallbackParticle,
                                  TagResolver... placeholders) {
        YamlConfiguration configured = settings(module);
        if (!configured.getBoolean(path + ".enabled", true)) {
            return;
        }
        if (getConfig().getBoolean("effects.titles")
            && configured.getBoolean(path + ".titles-enabled", true)) {
            String title = configured.getString(path + ".title", "");
            String subtitle = configured.getString(path + ".subtitle", "");
            player.showTitle(Title.title(MM.deserialize(title, placeholders),
                MM.deserialize(subtitle, placeholders), Title.Times.times(
                    Duration.ofMillis(200), Duration.ofMillis(900), Duration.ofMillis(300))));
        }
        if (getConfig().getBoolean("effects.sounds")
            && configured.getBoolean(path + ".sound-enabled", true)) {
            player.playSound(player.getLocation(), ConfiguredEffect.sound(this, configured,
                    path + ".sound", fallbackSound),
                (float) configured.getDouble(path + ".volume", .8),
                (float) configured.getDouble(path + ".pitch", 1.1));
        }
        if (!getConfig().getBoolean("effects.particles")
            || !configured.getBoolean(path + ".particles-enabled", fallbackParticle != null)
            || fallbackParticle == null) {
            return;
        }
        Particle particle = ConfiguredEffect.particle(this, configured,
            path + ".particle", fallbackParticle);
        int steps = Math.max(1, configured.getInt(path + ".particle-steps", 6));
        int points = Math.max(1, configured.getInt(path + ".particle-points", 12));
        long period = Math.max(1, configured.getLong(path + ".particle-period-ticks", 2));
        new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (!player.isOnline() || step == steps) {
                    cancel();
                    return;
                }
                Location center = player.getLocation().add(0, .2, 0);
                double radius = .35 + step++ * .12;
                for (int point = 0; point < points; point++) {
                    double angle = point * Math.PI * 2 / points;
                    player.spawnParticle(particle, center.clone().add(
                        Math.cos(angle) * radius, 0, Math.sin(angle) * radius), 1);
                }
            }
        }.runTaskTimer(this, 0, period);
    }

    private void effect(Player player, String title, String subtitle, Sound sound, Particle particle) {
        if (getConfig().getBoolean("effects.titles")) {
            player.showTitle(Title.title(MM.deserialize(title), MM.deserialize(subtitle),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(900), Duration.ofMillis(300))));
        }
        if (getConfig().getBoolean("effects.sounds")) {
            player.playSound(player.getLocation(), sound, 0.8f, 1.1f);
        }
        if (particle == null || !getConfig().getBoolean("effects.particles")) {
            return;
        }

        new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (!player.isOnline() || step == 6) {
                    cancel();
                    return;
                }
                Location center = player.getLocation().add(0, 0.2, 0);
                double radius = 0.35 + step++ * 0.12;
                for (int point = 0; point < 12; point++) {
                    double angle = point * Math.PI / 6;
                    player.spawnParticle(particle, center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), 1);
                }
            }
        }.runTaskTimer(this, 0, 2);
    }

    private static String titleCase(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record WaterCropKey(UUID worldId, long blockKey) {
    }

    private static final class PendingWaterCrop {
        private final Material crop;
        private final Material plantingItem;
        private int elapsedTicks;

        private PendingWaterCrop(Material crop, Material plantingItem) {
            this.crop = crop;
            this.plantingItem = plantingItem;
        }

        private Material crop() {
            return crop;
        }

        private Material plantingItem() {
            return plantingItem;
        }

        private int addElapsedTicks(int ticks) {
            elapsedTicks += ticks;
            return elapsedTicks;
        }
    }

    private World loadWorld(String name, String type) {
        WorldCreator creator = new WorldCreator(name).generateStructures(false);
        return type.equals("void")
            ? creator.generator(VOID_GENERATOR).createWorld()
            : creator.type(WorldType.FLAT).createWorld();
    }

    private String worldType(String name) {
        if (!validWorldName(name)) {
            return null;
        }
        String tracked = worldData.getString("worlds." + name + ".type");
        if (tracked != null && (tracked.equals("flat") || tracked.equals("void"))) {
            return tracked;
        }
        String type;
        try {
            type = Files.readString(worldPath(name).resolve(LEGACY_WORLD_MARKER)).trim();
        } catch (IOException exception) {
            return null;
        }
        if (!type.equals("flat") && !type.equals("void")) {
            return null;
        }
        try {
            trackWorld(name, type);
            getLogger().info("Migrated test world " + name + " to data/worlds.yml.");
        } catch (IOException exception) {
            getLogger().warning("Could not migrate test world " + name + ": " + exception.getMessage());
        }
        return type;
    }

    private void trackWorld(String name, String type) throws IOException {
        String path = "worlds." + name + ".type";
        Object previous = worldData.get(path);
        worldData.set(path, type);
        try {
            files.saveData("worlds");
        } catch (IOException exception) {
            worldData.set(path, previous);
            throw exception;
        }
    }

    private void migrateLegacyWorldMarkers() {
        try {
            testWorlds(null);
        } catch (IOException exception) {
            getLogger().warning("Could not scan for legacy test worlds: " + exception.getMessage());
        }
    }

    private Path worldPath(String name) {
        return getServer().getWorldContainer().toPath().resolve(name);
    }

    private static void deleteWorld(Path path) throws IOException {
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(MM.deserialize(message));
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rivet.admin")) {
            send(sender, "<white>You do not have permission to use Rivet administration.");
            return true;
        }
        if (args.length == 0) {
            send(sender, "<#f72a4c><bold>Rivet</bold></#f72a4c> <white>- /rivet reload reloads global and module settings.</white>");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            send(sender, "<white>Usage: /rivet [reload]");
            return true;
        }
        try {
            RivetConfig.ReloadResult result = files.reload();
            if (chat != null) {
                chat.reload();
            }
            if (announcements != null) {
                announcements.reload();
            }
            if (lagg != null) {
                lagg.reload();
            }
            if (audit != null) {
                audit.reload();
            }
            if (graves != null) {
                graves.reload();
            }
            if (permissions != null) {
                permissions.reloadConfiguration();
            }
            send(sender, "<white>Rivet configuration reloaded.</white> <white>Loaded config.yml, modules.yml, and <#f72a4c>"
                + (result.fileCount() - 2) + "</#f72a4c> settings files.</white>");
            if (!result.changedModules().isEmpty()) {
                send(sender, "<white>Module enable/disable changes require a server restart: <#f72a4c>"
                    + String.join(", ", result.changedModules()) + "</#f72a4c>.</white>");
            }
        } catch (IOException | InvalidConfigurationException exception) {
            getLogger().severe("Rivet reload failed: " + exception.getMessage());
            send(sender, "<white>Reload failed; existing settings remain active. Check the console for the file and error.");
        }
        return true;
    }

    boolean moduleEnabled(String module) {
        return module == null || files != null && files.enabled(module);
    }

    YamlConfiguration settings(String module) {
        return files.settings(module);
    }

    YamlConfiguration data(String module) {
        return files.data(module);
    }

    java.io.File settingsFile(String module) {
        return files.settingsFile(module);
    }

    java.io.File dataFile(String module) {
        return files.dataFile(module);
    }

    void auditSyntheticBlockBreak(Player player, Block block, String beforeData) {
        if (audit != null) {
            audit.recordSyntheticBlockBreak(player, block, beforeData);
        }
    }

    void ignoreAuditBlockBreakCheck(Player player, Block block) {
        if (audit != null) {
            audit.ignoreBlockBreakCheck(player, block);
        }
    }

    void finishAuditBlockBreakCheck(Player player, Block block) {
        if (audit != null) {
            audit.finishBlockBreakCheck(player, block);
        }
    }

    void saveData(String module) throws IOException {
        files.saveData(module);
    }

    static String moduleForCommand(String command) {
        return switch (command) {
            case "msg", "r", "socialspy", "ignore", "chatcolor", "tag", "me" -> "chat";
            case "sethome", "home", "delhome" -> "homes";
            case "setwarp", "warp", "delwarp" -> "warps";
            case "hologram" -> "holograms";
            case "spawn", "setspawn" -> "spawn";
            case "tpa", "tpahere", "tpaccept", "tpdeny" -> "tpa";
            case "kit" -> "kits";
            case "back" -> "graves";
            case "afk", "afkcheck" -> "afk";
            case "nick" -> "nicknames";
            case "stats", "playtime", "seen" -> "statistics";
            case "trash" -> "trash";
            case "craft", "anvil", "smithing", "stonecutter", "grindstone", "jump", "list", "ping", "ride" -> "utilities";
            case "sit", "lay", "crawl" -> "poses";
            case "head" -> "mob-heads";
            case "backpack" -> "backpacks";
            case "daily" -> "daily";
            case "rtp" -> "rtp";
            case "near" -> "near";
            case "givebreeder", "clearhologram" -> "breeders";
            case "restorationcore" -> "creeper-restoration";
            case "perm" -> "permissions";
            case "flat", "flatworld", "voidworld", "worldspawn", "setworldspawn", "killall", "findbiome", "top", "tree" -> "worlds";
            case "gmc", "gms", "tp", "tppos", "vanish", "fly", "heal", "feed", "god", "flyspeed", "commandspy", "bossbarmsg",
                 "note", "sameip", "toast" -> "staff";
            case "day", "night", "noon", "midnight", "sun", "rain", "thunder" -> "environment";
            case "clear", "i", "invsee", "enderchest", "repair", "rename", "lore",
                 "condense", "donate", "giveall", "hat", "scan" -> "inventory";
            case "filter" -> "filter";
            case "help" -> "help";
            case "lagg" -> "lagg";
            case "log" -> "logs";
            default -> null;
        };
    }

    static boolean commandDisabled(String command, java.util.function.Predicate<String> enabled) {
        String module = moduleForCommand(command);
        return module != null && !enabled.test(module);
    }

    static boolean validWorldName(String name) {
        return name.matches("[A-Za-z0-9_-]{1,32}");
    }

    static GameMode gameModeFor(String command) {
        return switch (command) {
            case "gmc" -> GameMode.CREATIVE;
            case "gms" -> GameMode.SURVIVAL;
            default -> null;
        };
    }

    static boolean blocksSpawn(WorldType worldType, CreatureSpawnEvent.SpawnReason reason, boolean allowed) {
        return !allowed && worldType == WorldType.FLAT && reason == CreatureSpawnEvent.SpawnReason.NATURAL;
    }

    static boolean isCropTrample(Action action, Material material) {
        return action == Action.PHYSICAL && material == Material.FARMLAND;
    }

    static Material plantingItem(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            case TORCHFLOWER_CROP -> Material.TORCHFLOWER_SEEDS;
            default -> null;
        };
    }

    static boolean consumePlantingItem(List<ItemStack> drops, Material plantingItem) {
        Iterator<ItemStack> iterator = drops.iterator();
        while (iterator.hasNext()) {
            ItemStack drop = iterator.next();
            if (drop.getType() != plantingItem || drop.getAmount() < 1) {
                continue;
            }
            if (drop.getAmount() == 1) {
                iterator.remove();
            } else {
                drop.setAmount(drop.getAmount() - 1);
            }
            return true;
        }
        return false;
    }

    static boolean isDisabledIronGolemPoppyDrop(EntityType type, boolean enabled,
                                                Material material) {
        return type == EntityType.IRON_GOLEM && !enabled && material == Material.POPPY;
    }

    static boolean hasNaturalFlight(GameMode gameMode) {
        return gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR;
    }

    static double mobHeadChance(double baseChance, int lootingLevel, double lootingBonus) {
        double safeBaseChance = Double.isFinite(baseChance)
            ? Math.max(0, Math.min(1, baseChance)) : DEFAULT_MOB_HEAD_CHANCE;
        double safeLootingBonus = Double.isFinite(lootingBonus)
            ? Math.max(0, Math.min(1, lootingBonus)) : DEFAULT_MOB_HEAD_LOOTING_BONUS;
        return Math.min(1, safeBaseChance + Math.max(0, lootingLevel) * safeLootingBonus);
    }

    static boolean usesCustomMobHeadDrop(EntityType type, boolean customWitherSkeletonDrops) {
        return type != EntityType.WITHER_SKELETON || customWitherSkeletonDrops;
    }

    static boolean dropsMobHead(EntityType type, double roll) {
        return dropsMobHead(type, roll, DEFAULT_MOB_HEAD_CHANCE);
    }

    static boolean dropsMobHead(EntityType type, double roll, double chance) {
        return dropsMobHead(type, roll, chance, List.of());
    }

    static boolean dropsMobHead(EntityType type, double roll, double chance,
                                List<String> disallowedHeads) {
        return supportsMobHead(type) && !disallowedMobHead(type, disallowedHeads)
            && roll >= 0 && roll < chance;
    }

    static boolean disallowedMobHead(EntityType type, List<String> disallowedHeads) {
        Material head = mobHeadFor(type);
        String entityName = type.name();
        String materialName = head == null && MOB_HEAD_TEXTURES.containsKey(type)
            ? Material.PLAYER_HEAD.name() : head == null ? "" : head.name();
        return disallowedHeads.stream().filter(java.util.Objects::nonNull)
            .map(value -> value.trim().toUpperCase(Locale.ROOT).replace('-', '_'))
            .anyMatch(value -> value.equals(entityName) || value.equals(materialName));
    }

    static boolean supportsMobHead(EntityType type) {
        return mobHeadFor(type) != null || MOB_HEAD_TEXTURES.containsKey(type);
    }

    static Material mobHeadFor(EntityType type) {
        return switch (type) {
            case CREEPER -> Material.CREEPER_HEAD;
            case ZOMBIE, GIANT -> Material.ZOMBIE_HEAD;
            case SKELETON -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case PIGLIN -> Material.PIGLIN_HEAD;
            case ENDER_DRAGON -> Material.DRAGON_HEAD;
            default -> null;
        };
    }

    private static Map<EntityType, String> loadMobHeadTextures() {
        Properties properties = new Properties();
        try (InputStream input = RivetPlugin.class.getResourceAsStream("/mob-heads.properties")) {
            if (input == null) {
                throw new IOException("missing mob-heads.properties");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        Map<EntityType, String> textures = new java.util.EnumMap<>(EntityType.class);
        properties.forEach((type, texture) -> {
            String value = texture.toString();
            if (!value.matches("[0-9a-f]{32,64}")) {
                throw new IllegalArgumentException("Invalid mob head texture for " + type);
            }
            textures.put(EntityType.valueOf(type.toString()), value);
        });
        return Collections.unmodifiableMap(textures);
    }

    static int itemAmount(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    static Double parseCoordinate(String value, double origin) {
        try {
            double coordinate;
            if (value.startsWith("~")) {
                coordinate = value.length() == 1
                    ? origin : origin + Double.parseDouble(value.substring(1));
            } else {
                coordinate = Double.parseDouble(value);
            }
            return Double.isFinite(coordinate) ? coordinate : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static boolean validTeleportPosition(double x, double y, double z,
                                         int minimumY, int maximumY) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && Math.abs(x) <= 29_999_984 && Math.abs(z) <= 29_999_984
            && y >= minimumY && y < maximumY;
    }

    private static List<String> playerCoordinateCompletions(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 1 || args.length > 3) {
            return List.of();
        }
        Location location = player.getLocation();
        double coordinate = switch (args.length) {
            case 1 -> location.getX();
            case 2 -> location.getY();
            default -> location.getZ();
        };
        return List.of(displayCoordinate(coordinate), "~");
    }

    private static String displayCoordinate(double coordinate) {
        return BigDecimal.valueOf(coordinate).stripTrailingZeros().toPlainString();
    }

    static boolean validCoordinates(double x, double y, double z, float yaw, float pitch) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && Float.isFinite(yaw) && Float.isFinite(pitch);
    }

    static String homeName(String[] args) {
        return args.length == 0 ? "home"
            : args.length == 1 && validWorldName(args[0]) ? args[0].toLowerCase(Locale.ROOT) : null;
    }

    static List<String> completions(List<String> choices, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
