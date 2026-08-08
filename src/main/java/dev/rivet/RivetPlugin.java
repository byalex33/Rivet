package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
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
import org.bukkit.util.BiomeSearchResult;
import org.bstats.bukkit.Metrics;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RivetPlugin extends JavaPlugin implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String LEGACY_WORLD_MARKER = ".rivet-test-world";
    private static final double DEFAULT_MOB_HEAD_CHANCE = .03;
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
    private ChatModule chat;
    private AutoBreeder autoBreeder;
    private EggCapture eggCapture;
    private GlowModule glows;
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
    private StaffTools staffTools;
    private FilterModule filter;
    private HelpModule help;
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
        new Metrics(this, 33219);
        if (moduleEnabled("spawn") || moduleEnabled("tpa") || moduleEnabled("graves")
            || moduleEnabled("rtp")) {
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
        if (moduleEnabled("permissions")) {
            permissions = new PermissionModule(this);
            getServer().getPluginManager().registerEvents(permissions, this);
        }
        if (moduleEnabled("chat")) {
            chat = new ChatModule(this);
            getServer().getPluginManager().registerEvents(chat, this);
        }
        if (moduleEnabled("glow")) {
            glows = new GlowModule(this);
            getServer().getPluginManager().registerEvents(glows, this);
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
        }
        if (moduleEnabled("filter")) {
            filter = new FilterModule(this);
            getServer().getPluginManager().registerEvents(filter, this);
        }
        if (moduleEnabled("help")) {
            help = new HelpModule(this);
        }
        if (moduleEnabled("staff")) {
            staffTools = new StaffTools(this);
            getServer().getPluginManager().registerEvents(staffTools, this);
        }
        if (moduleEnabled("worlds") || moduleEnabled("staff") || moduleEnabled("mob-heads")) {
            getServer().getPluginManager().registerEvents(this, this);
        }
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
        if (glows != null) {
            glows.shutdown();
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
        biomeSearches.clear();
        if (nicknames != null) {
            nicknames.shutdown();
        }
        if (delayedTeleports != null) {
            delayedTeleports.shutdown();
        }
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
        if (!moduleEnabled("mob-heads") || event.getEntity().getKiller() == null
            || !dropsMobHead(event.getEntityType(), ThreadLocalRandom.current().nextDouble(),
                mobHeadChance())) {
            return;
        }
        ItemStack head = createMobHead(event.getEntityType());
        head.editMeta(meta -> meta.displayName(MM.deserialize("<gold><bold>"
            + titleCase(event.getEntityType().name().toLowerCase(Locale.ROOT).replace('_', ' '))
            + " Head</bold></gold>")));
        event.getDrops().add(head);
        celebrateHeadDrop(event.getEntity().getLocation().add(0, .8, 0));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (moduleEnabled("worlds") && isCropTrample(event.getAction(),
            event.getClickedBlock() == null ? null : event.getClickedBlock().getType())) {
            event.setCancelled(true);
        }
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
            send(sender, "<yellow>The <white>" + module + "</white> module is disabled.");
            return true;
        }
        if (name.equals("perm") || name.equals("group")) {
            return permissions.command(sender, name, args);
        }
        if (name.equals("hologram")) {
            return holograms.command(sender, args);
        }
        if (name.equals("glow")) {
            return glows.command(sender, args);
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
        if (!(sender instanceof Player player)) {
            send(sender, "<red>This command is only available to players.");
            return true;
        }

        GameMode gameMode = gameModeFor(name);
        if (gameMode != null) {
            player.setGameMode(gameMode);
            send(player, "<green>Gamemode set to <white>" + gameMode.name().toLowerCase(Locale.ROOT) + "</white>.");
            effect(player, "<green>" + titleCase(gameMode.name().toLowerCase(Locale.ROOT)) + "</green>", "<gray>Gamemode changed</gray>",
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
            case "me" -> chat.me(player, args);
            case "tp" -> teleportPlayer(player, args);
            case "vanish" -> toggleVanish(player);
            case "fly" -> toggleFlight(player, args);
            case "heal" -> staffTools.heal(player, args);
            case "feed" -> staffTools.feed(player, args);
            case "god" -> staffTools.god(player, args);
            case "flyspeed" -> staffTools.flySpeed(player, args);
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
                case "perm", "group" -> permissions.completions(command.getName(), args);
                case "hologram" -> holograms.completions(args);
                case "glow" -> glows.completions(args);
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
                case "msg", "tp" -> args.length == 1 ? getServer().getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList() : List.of();
                case "ignore" -> sender instanceof Player player
                    ? chat.ignoreCompletions(player, args) : List.of();
                case "chatcolor" -> sender instanceof Player player
                    ? chat.chatColorCompletions(player, args) : List.of();
                case "tpa", "tpahere", "tpaccept", "tpdeny" -> sender instanceof Player player
                    ? tpa.completions(player, command.getName(), args) : List.of();
                case "kit" -> sender instanceof Player player ? kits.completions(player, args) : List.of();
                case "nick" -> nicknames.completions(sender, args);
                case "stats" -> statistics.completions(sender, args);
                case "playtime" -> statistics.playtimeCompletions(sender, args);
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
            send(player, "<red>Usage: /flatworld <create|tp|reset|list> <name>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> createWorld(player, args[1], "flat");
            case "tp" -> teleportWorld(player, args[1]);
            case "reset" -> resetWorld(player, args[1]);
            default -> {
                send(player, "<red>Usage: /flatworld <create|tp|reset|list> <name>");
                yield true;
            }
        };
    }

    private boolean voidWorld(Player player, String[] args) {
        if (args.length != 2 || !args[0].equalsIgnoreCase("create") || !validWorldName(args[1])) {
            send(player, "<red>Usage: /voidworld create <name>");
            return true;
        }
        return createWorld(player, args[1], "void");
    }

    private boolean createWorld(Player player, String name, String type) {
        Path path = worldPath(name);
        if (Files.exists(path)) {
            send(player, "<red>A world named <white>" + name + "</white> already exists.");
            return true;
        }

        World world = loadWorld(name, type);
        if (world == null) {
            send(player, "<red>Could not create <white>" + name + "</white>.");
            return true;
        }

        try {
            trackWorld(name, type);
            send(player, "<green>Created " + type + " world <white>" + name + "</white>.");
            effect(player, "<green>World created</green>", "<gray>" + name + "</gray>",
                Sound.BLOCK_BEACON_ACTIVATE, Particle.END_ROD);
        } catch (IOException exception) {
            getLogger().severe("Created world " + name + " but could not mark it as a test world: " + exception.getMessage());
            send(player, "<red>World created, but Rivet could not track it. Check the console.");
        }
        return true;
    }

    private boolean teleportWorld(Player player, String name) {
        String type = worldType(name);
        if (type == null) {
            send(player, "<red>No test world named <white>" + name + "</white> exists.");
            return true;
        }

        World world = getServer().getWorld(name);
        if (world == null) {
            world = loadWorld(name, type);
        }
        if (world == null) {
            send(player, "<red>Could not load <white>" + name + "</white>.");
            return true;
        }

        player.teleport(world.getSpawnLocation());
        send(player, "<green>Teleported to <white>" + name + "</white>.");
        effect(player, "<aqua>" + name + "</aqua>", "<gray>Teleported</gray>",
            Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL);
        return true;
    }

    private boolean resetWorld(Player player, String name) {
        if (!"flat".equals(worldType(name))) {
            send(player, "<red>No flat test world named <white>" + name + "</white> exists.");
            return true;
        }

        World world = getServer().getWorld(name);
        if (world != null) {
            World fallback = getServer().getWorlds().stream().filter(candidate -> !candidate.equals(world)).findFirst().orElse(null);
            if (!world.getPlayers().isEmpty() && fallback == null) {
                send(player, "<red>Cannot reset the only loaded world while players are inside it.");
                return true;
            }
            if (fallback != null) {
                world.getPlayers().forEach(occupant -> occupant.teleport(fallback.getSpawnLocation()));
            }
            if (!getServer().unloadWorld(world, false)) {
                send(player, "<red>Could not unload <white>" + name + "</white>.");
                return true;
            }
        }

        try {
            deleteWorld(worldPath(name));
        } catch (IOException exception) {
            getLogger().severe("Could not delete world " + name + ": " + exception.getMessage());
            send(player, "<red>Could not delete <white>" + name + "</white>. Check the console.");
            return true;
        }

        World regenerated = loadWorld(name, "flat");
        if (regenerated == null) {
            send(player, "<red>Deleted the world, but could not regenerate it.");
            return true;
        }
        try {
            trackWorld(name, "flat");
            send(player, "<green>Reset flat world <white>" + name + "</white>.");
            effect(player, "<gold>World reset</gold>", "<gray>" + name + "</gray>",
                Sound.BLOCK_BEACON_ACTIVATE, Particle.POOF);
        } catch (IOException exception) {
            getLogger().severe("Regenerated world " + name + " but could not save its data: " + exception.getMessage());
            send(player, "<red>World regenerated, but Rivet could not track it. Check the console.");
        }
        return true;
    }

    private boolean listWorlds(Player player) {
        List<String> worlds;
        try {
            worlds = testWorlds(null);
        } catch (IOException exception) {
            send(player, "<red>Could not list test worlds.");
            return true;
        }

        if (worlds.isEmpty()) {
            send(player, "<gray>No test worlds have been generated.");
            return true;
        }

        Component message = MM.deserialize("<gold>Test worlds:</gold>");
        for (String name : worlds) {
            String type = worldType(name);
            message = message.append(Component.newline()).append(MM.deserialize(
                "<click:run_command:'/flatworld tp " + name + "'><aqua>" + name
                    + "</aqua> <gray>(" + type + ")</gray></click>"));
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
            send(player, "<red>Could not create the flat world.");
            return true;
        }
        player.teleport(world.getSpawnLocation());
        send(player, "<green>Teleported to <white>flat</white>.");
        effect(player, "<aqua>flat</aqua>", "<gray>Teleported</gray>",
            Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL);
        return true;
    }

    private boolean worldSpawn(Player player) {
        player.teleport(player.getWorld().getSpawnLocation());
        send(player, "<green>Teleported to this world's spawn.");
        effect(player, "<aqua>World spawn</aqua>", "<gray>Teleported</gray>",
            Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL);
        return true;
    }

    private boolean setWorldSpawn(Player player) {
        player.getWorld().setSpawnLocation(player.getLocation());
        send(player, "<green>Set this world's spawn to your location.");
        effect(player, "<green>Spawn set</green>", "<gray>Current location</gray>",
            Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, Particle.END_ROD);
        return true;
    }

    private boolean setHome(Player player, String[] args) {
        String name = homeName(args);
        if (name == null) {
            send(player, "<red>Usage: /sethome [name]");
            return true;
        }
        if (saveLocation(homes, "homes", homePath(player, name), player.getLocation(), player)) {
            send(player, "<green>Home <white>" + name + "</white> set.");
        }
        return true;
    }

    private boolean home(Player player, String[] args) {
        String name = homeName(args);
        if (name == null) {
            send(player, "<red>Usage: /home [name]");
            return true;
        }
        String path = homePath(player, name);
        if (name.equals("home") && !homes.isConfigurationSection(path)
            && homes.contains("homes." + player.getUniqueId() + ".world")) {
            path = "homes." + player.getUniqueId();
        }
        return teleportSaved(player, homes, path, name);
    }

    private boolean deleteHome(Player player, String[] args) {
        String name = homeName(args);
        if (name == null) {
            send(player, "<red>Usage: /delhome [name]");
            return true;
        }
        String path = homePath(player, name);
        String legacy = "homes." + player.getUniqueId();
        if (!homes.isConfigurationSection(path)
            && (!name.equals("home") || !homes.contains(legacy + ".world"))) {
            send(player, "<red>No " + name + " is set.");
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
            send(player, "<green>Home <white>" + name + "</white> deleted.");
        }
        return true;
    }

    private boolean setWarp(Player player, String[] args) {
        if (args.length != 1 || !validWorldName(args[0])) {
            send(player, "<red>Usage: /setwarp <name>");
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        if (saveLocation(warps, "warps", "warps." + name, player.getLocation(), player)) {
            send(player, "<green>Warp <white>" + name + "</white> set.");
        }
        return true;
    }

    private boolean warp(Player player, String[] args) {
        if (args.length != 1 || !validWorldName(args[0])) {
            send(player, "<red>Usage: /warp <name>");
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        return teleportSaved(player, warps, "warps." + name, name);
    }

    private boolean deleteWarp(Player player, String[] args) {
        if (args.length != 1 || !validWorldName(args[0])) {
            send(player, "<red>Usage: /delwarp <name>");
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        String path = "warps." + name;
        if (!warps.isConfigurationSection(path)) {
            send(player, "<red>No " + name + " is set.");
            return true;
        }
        warps.set(path, null);
        if (saveData("warps", player)) {
            send(player, "<green>Warp <white>" + name + "</white> deleted.");
        }
        return true;
    }

    private boolean teleportSaved(Player player, YamlConfiguration data, String path, String name) {
        if (!data.isConfigurationSection(path)) {
            send(player, "<red>No " + name + " is set.");
            return true;
        }
        Location location = savedLocation(data, path);
        if (location == null) {
            send(player, "<red>The world for <white>" + name + "</white> is unavailable.");
            return true;
        }
        if (!player.teleport(location)) {
            send(player, "<red>Teleport failed.");
            return true;
        }
        send(player, "<green>Teleported to <white>" + name + "</white>.");
        effect(player, "<aqua>" + titleCase(name) + "</aqua>", "<gray>Teleported</gray>",
            Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL);
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
            send(player, "<red>Could not save the change. Check the console.");
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
            send(player, "<red>Usage: /findbiome <biome>");
            return true;
        }
        String value = args[0].toLowerCase(Locale.ROOT).replace(' ', '_');
        NamespacedKey key = NamespacedKey.fromString(value.contains(":") ? value : "minecraft:" + value);
        Biome biome = key == null ? null : Registry.BIOME.get(key);
        if (biome == null) {
            send(player, "<red>That biome does not exist.");
            return true;
        }
        if (!biomeSearches.add(player.getUniqueId())) {
            send(player, "<yellow>A biome search is already running.");
            return true;
        }
        int radius = Math.max(64, Math.min(30_000,
            settings("worlds").getInt("find-biome.radius", 6_400)));
        int horizontalStep = Math.max(1,
            settings("worlds").getInt("find-biome.horizontal-step", 32));
        int verticalStep = Math.max(1,
            settings("worlds").getInt("find-biome.vertical-step", 64));
        Location origin = player.getLocation().clone();
        send(player, "<gray>Searching for the nearest <white>" + key.getKey().replace('_', ' ') + "</white>...</gray>");
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
                        send(player, "<red>Biome search failed. Check the console.");
                        return;
                    }
                    if (found == null) {
                        send(player, "<yellow>No matching biome was found within <white>" + radius + "</white> blocks.");
                        return;
                    }
                    Location location = found.getLocation();
                    int distance = (int) Math.round(Math.hypot(location.getX() - origin.getX(),
                        location.getZ() - origin.getZ()));
                    int x = location.getBlockX();
                    int y = location.getBlockY();
                    int z = location.getBlockZ();
                    player.sendMessage(MM.deserialize("<green>Nearest <white><biome></white>: "
                            + "<click:suggest_command:'/tp " + x + " " + y + " " + z
                            + "'><aqua><x>, <y>, <z></aqua></click> <gray>(<distance> blocks)</gray>",
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
                send(actor, "<red>That player is not online.");
                return true;
            }
        } else if (args.length != 0) {
            send(actor, "<red>Usage: /top" + (actor.hasPermission("rivet.top.others") ? " [player]" : ""));
            return true;
        }
        Location current = target.getLocation();
        Location destination = SafeLocations.highest(target.getWorld(), current.getBlockX(),
            current.getBlockZ(), current);
        if (destination == null || !target.teleport(destination)) {
            send(actor, "<red>No safe top location was found at that position.");
            return true;
        }
        send(actor, "<green>Teleported <white>" + target.getName()
            + "</white> to the highest safe location.");
        if (!actor.equals(target)) {
            send(target, "<green>You were teleported to the highest safe location by <white>"
                + actor.getName() + "</white>.");
        }
        teleportFeedback(target, "Top");
        return true;
    }

    private boolean tree(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<red>Usage: /tree <treeType>");
            return true;
        }
        TreeType type = treeType(args[0]);
        if (type == null) {
            send(player, "<red>That vanilla tree type is not supported.");
            return true;
        }
        int range = Math.max(1, Math.min(200,
            settings("worlds").getInt("tree.maximum-range", 50)));
        org.bukkit.block.Block target = player.getTargetBlockExact(range, org.bukkit.FluidCollisionMode.NEVER);
        if (target == null) {
            send(player, "<yellow>No block is in range.");
            return true;
        }
        Location origin = target.getLocation().add(0, 1, 0);
        if (!SafeLocations.suitableTreeBase(target) || !SafeLocations.treeClearance(origin)) {
            send(player, "<red>That target does not have safe ground and enough clear space.");
            return true;
        }
        if (!player.getWorld().generateTree(origin, type)) {
            send(player, "<red>Tree generation failed; the location may be unsuitable or protected.");
            return true;
        }
        send(player, "<green>Generated <white>" + type.name().toLowerCase(Locale.ROOT)
            .replace('_', ' ') + "</white>.");
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
            send(player, "<red>Usage: /i <item> [amount]");
            return true;
        }

        Material material = Material.matchMaterial(args[0]);
        int amount = args.length == 1 ? 64 : itemAmount(args[1]);
        if (material == null || material.isAir() || !material.isItem() || amount < 1) {
            send(player, "<red>Use a valid item and a positive whole-number amount.");
            return true;
        }

        player.getInventory().addItem(new ItemStack(material, amount)).values()
            .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(player, "<green>Gave <white>" + amount + " " + material.name().toLowerCase(Locale.ROOT) + "</white>.");
        return true;
    }

    private boolean inventoryView(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<red>Usage: /invsee <player>");
            return true;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            send(player, "<red>That player is not online.");
            return true;
        }
        player.openInventory(target.getInventory());
        send(player, "<gray>Opened <white>" + target.getName() + "</white>'s inventory.");
        return true;
    }

    private boolean enderChest(Player player, String[] args) {
        Player target = player;
        if (args.length == 1 && player.hasPermission("rivet.inventory.enderchest.others")) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null || !player.canSee(target)) {
                send(player, "<red>That player is not online.");
                return true;
            }
        } else if (args.length != 0) {
            send(player, "<red>Usage: /enderchest [player]");
            return true;
        }
        player.openInventory(target.getEnderChest());
        return true;
    }

    private boolean playerHead(Player player, String[] args) {
        if (args.length != 1 || !args[0].matches("[A-Za-z0-9_]{1,16}")) {
            send(player, "<red>Usage: /head <player>");
            return true;
        }
        Player online = getServer().getPlayerExact(args[0]);
        OfflinePlayer target = online == null ? getServer().getOfflinePlayerIfCached(args[0]) : online;
        if (target == null || target.getName() == null) {
            send(player, "<red>That player profile is not known to this server.");
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
            "<gold><player>'s Head</gold>"), placeholders));
        List<String> lore = settings("mob-heads").getStringList("player-heads.lore");
        if (lore.isEmpty()) {
            lore = List.of("<gray>Requested by <requester></gray>");
        }
        meta.lore(lore.stream()
            .map(line -> MM.deserialize(line, placeholders)).toList());
        head.setItemMeta(meta);
        player.getInventory().addItem(head).values()
            .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(player, "<green>Gave you <white>" + target.getName() + "</white>'s head.");
        return true;
    }

    private boolean killAll(Player player) {
        var mobs = player.getWorld().getEntitiesByClass(Mob.class);
        mobs.forEach(Mob::remove);
        send(player, "<green>Removed <white>" + mobs.size() + "</white> mobs.");
        return true;
    }

    private boolean teleportPlayer(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<red>Usage: /tp <player>");
            return true;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            send(player, "<red>That player is not online.");
            return true;
        }
        if (!player.teleport(target)) {
            send(player, "<red>Teleport failed.");
            return true;
        }
        send(player, "<green>Teleported to <white>" + target.getName() + "</white>.");
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

    private boolean toggleVanish(Player player) {
        boolean enable = !vanished.contains(player.getUniqueId());
        setVanished(player, enable);
        send(player, enable ? "<green>Vanish enabled." : "<yellow>Vanish disabled.");
        return true;
    }

    private boolean toggleFlight(Player player, String[] args) {
        if (args.length != 0) {
            send(player, "<red>Usage: /fly");
            return true;
        }
        boolean enable = flightEnabled.add(player.getUniqueId());
        if (enable) {
            player.setAllowFlight(true);
        } else {
            disableFlight(player);
        }
        send(player, enable ? "<aqua>Flight enabled.</aqua> <gray>Double-tap jump to fly."
            : "<yellow>Flight disabled.");
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
        if (getConfig().getBoolean("effects.sounds")) {
            location.getWorld().playSound(
                location, Sound.ENTITY_TURTLE_EGG_HATCH, 1.2f, .85f);
        }
        if (!getConfig().getBoolean("effects.particles")) {
            return;
        }
        new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (step++ == 8) {
                    cancel();
                    return;
                }
                double radius = .15 + step * .08;
                for (int point = 0; point < 16; point++) {
                    double angle = point * Math.PI / 8 + step * .3;
                    location.getWorld().spawnParticle(Particle.END_ROD,
                        location.clone().add(Math.cos(angle) * radius, step * .06,
                            Math.sin(angle) * radius), 1, 0, 0, 0, .01);
                }
                location.getWorld().spawnParticle(
                    Particle.TOTEM_OF_UNDYING, location, 8, .3, .35, .3, .08);
            }
        }.runTaskTimer(this, 0, 2);
    }

    private double mobHeadChance() {
        double chance = settings("mob-heads").getDouble("drop-chance", DEFAULT_MOB_HEAD_CHANCE);
        return Double.isFinite(chance) ? Math.max(0, Math.min(1, chance)) : DEFAULT_MOB_HEAD_CHANCE;
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
            send(player, "<yellow>Vanish disabled because your permission changed.");
        }
        if (moduleEnabled("staff") && flightEnabled.contains(player.getUniqueId())
            && !player.hasPermission("rivet.fly")) {
            disableFlight(player);
            send(player, "<yellow>Flight disabled because your permission changed.");
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
        effect(player, "<aqua>" + destination + "</aqua>", "<gray>Teleported</gray>",
            Sound.ENTITY_ENDERMAN_TELEPORT, Particle.PORTAL);
    }

    void refreshDisplayName(Player player) {
        Component name = nicknames == null ? Component.text(player.getName()) : nicknames.displayName(player);
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
        player.getWorld().setTime(time);
        send(player, "<green>Time set to <white>" + command + "</white>.");
        effect(player, "<yellow>" + titleCase(command) + "</yellow>", "<gray>Time changed</gray>",
            Sound.UI_BUTTON_CLICK, null);
        return true;
    }

    private boolean setWeather(Player player, String command) {
        World world = player.getWorld();
        world.setStorm(!command.equals("sun"));
        world.setThundering(command.equals("thunder"));
        send(player, "<green>Weather set to <white>" + command + "</white>.");
        effect(player, "<aqua>" + titleCase(command) + "</aqua>", "<gray>Weather changed</gray>",
            Sound.UI_BUTTON_CLICK, null);
        return true;
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
            send(sender, "<red>You do not have permission to use Rivet administration.");
            return true;
        }
        if (args.length == 0) {
            send(sender, "<gold><bold>Rivet</bold></gold> <gray>- /rivet reload reloads global and module settings.</gray>");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            send(sender, "<red>Usage: /rivet [reload]");
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
            if (graves != null) {
                graves.reload();
            }
            if (permissions != null) {
                permissions.reloadConfiguration();
            }
            send(sender, "<green>Rivet configuration reloaded.</green> <gray>Loaded config.yml, modules.yml, and <white>"
                + (result.fileCount() - 2) + "</white> settings files.</gray>");
            if (!result.changedModules().isEmpty()) {
                send(sender, "<yellow>Module enable/disable changes require a server restart: <white>"
                    + String.join(", ", result.changedModules()) + "</white>.</yellow>");
            }
        } catch (IOException | InvalidConfigurationException exception) {
            getLogger().severe("Rivet reload failed: " + exception.getMessage());
            send(sender, "<red>Reload failed; existing settings remain active. Check the console for the file and error.");
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

    void saveData(String module) throws IOException {
        files.saveData(module);
    }

    static String moduleForCommand(String command) {
        return switch (command) {
            case "msg", "r", "socialspy", "ignore", "chatcolor", "me" -> "chat";
            case "sethome", "home", "delhome" -> "homes";
            case "setwarp", "warp", "delwarp" -> "warps";
            case "hologram" -> "holograms";
            case "glow" -> "glow";
            case "spawn", "setspawn" -> "spawn";
            case "tpa", "tpahere", "tpaccept", "tpdeny" -> "tpa";
            case "kit" -> "kits";
            case "back" -> "graves";
            case "afk", "afkcheck" -> "afk";
            case "nick" -> "nicknames";
            case "stats", "playtime" -> "statistics";
            case "trash" -> "trash";
            case "craft", "anvil", "smithing", "stonecutter", "grindstone", "jump", "list", "ping", "ride" -> "utilities";
            case "sit", "lay", "crawl" -> "poses";
            case "head" -> "mob-heads";
            case "backpack" -> "backpacks";
            case "daily" -> "daily";
            case "rtp" -> "rtp";
            case "near" -> "near";
            case "perm", "group" -> "permissions";
            case "flat", "flatworld", "voidworld", "worldspawn", "setworldspawn", "killall", "findbiome", "top", "tree" -> "worlds";
            case "gmc", "gms", "tp", "vanish", "fly", "heal", "feed", "god", "flyspeed", "bossbarmsg",
                 "note", "sameip", "toast" -> "staff";
            case "day", "night", "noon", "midnight", "sun", "rain", "thunder" -> "environment";
            case "clear", "i", "invsee", "enderchest", "repair", "rename", "lore",
                 "condense", "donate", "giveall", "hat" -> "inventory";
            case "filter" -> "filter";
            case "help" -> "help";
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

    static boolean hasNaturalFlight(GameMode gameMode) {
        return gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR;
    }

    static boolean dropsMobHead(EntityType type, double roll) {
        return dropsMobHead(type, roll, DEFAULT_MOB_HEAD_CHANCE);
    }

    static boolean dropsMobHead(EntityType type, double roll, double chance) {
        return supportsMobHead(type) && roll >= 0 && roll < chance;
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
