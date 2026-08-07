package dev.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import org.jetbrains.annotations.NotNull;

import java.io.File;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class CorePlugin extends JavaPlugin implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String MARKER = ".core-test-world";
    private static final double MOB_HEAD_CHANCE = .03;
    private static final Map<EntityType, String> MOB_HEAD_TEXTURES = loadMobHeadTextures();
    private static final ChunkGenerator VOID_GENERATOR = new ChunkGenerator() {
        @Override
        public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                  int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        }
    };
    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> flightEnabled = new HashSet<>();
    private ChatModule chat;
    private AutoBreeder autoBreeder;
    private EggCapture eggCapture;
    private GlowModule glows;
    private GraveModule graves;
    private HologramModule holograms;
    private PermissionModule permissions;
    private TreeFeller treeFeller;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        autoBreeder = new AutoBreeder(this);
        eggCapture = new EggCapture(this);
        permissions = new PermissionModule(this);
        chat = new ChatModule(this);
        glows = new GlowModule(this);
        graves = new GraveModule(this);
        holograms = new HologramModule(this);
        treeFeller = new TreeFeller(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(autoBreeder, this);
        getServer().getPluginManager().registerEvents(eggCapture, this);
        getServer().getPluginManager().registerEvents(chat, this);
        getServer().getPluginManager().registerEvents(glows, this);
        getServer().getPluginManager().registerEvents(graves, this);
        getServer().getPluginManager().registerEvents(holograms, this);
        getServer().getPluginManager().registerEvents(permissions, this);
        getServer().getPluginManager().registerEvents(treeFeller, this);
        getServer().getOnlinePlayers().forEach(permissions::apply);
        getServer().getScheduler().runTaskTimer(this, this::spawnFlightClouds, 1, 4);
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
        if (treeFeller != null) {
            treeFeller.shutdown();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (blocksSpawn(event.getLocation().getWorld().getWorldType(), event.getSpawnReason(),
            getConfig().getBoolean("flat-worlds.allow-natural-mob-spawning"))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null
            || !dropsMobHead(event.getEntityType(), ThreadLocalRandom.current().nextDouble())) {
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
        if (isCropTrample(event.getAction(),
            event.getClickedBlock() == null ? null : event.getClickedBlock().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshVanishVisibility(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        vanished.remove(event.getPlayer().getUniqueId());
        if (flightEnabled.contains(event.getPlayer().getUniqueId())) {
            disableFlight(event.getPlayer());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String name = command.getName();
        if (name.equals("perm") || name.equals("group")) {
            return permissions.command(sender, name, args);
        }
        if (name.equals("hologram")) {
            return holograms.command(sender, args);
        }
        if (name.equals("glow")) {
            return glows.command(sender, args);
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
            case "clear" -> clearInventory(player);
            case "i" -> giveItem(player, args);
            case "killall" -> killAll(player);
            case "day", "night", "noon", "midnight" -> setTime(player, name);
            case "sun", "rain", "thunder" -> setWeather(player, name);
            case "msg" -> chat.message(player, args);
            case "r" -> chat.reply(player, args);
            case "tp" -> teleportPlayer(player, args);
            case "vanish" -> toggleVanish(player);
            case "fly" -> toggleFlight(player, args);
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
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
            Files.writeString(path.resolve(MARKER), type);
            send(player, "<green>Created " + type + " world <white>" + name + "</white>.");
            effect(player, "<green>World created</green>", "<gray>" + name + "</gray>",
                Sound.BLOCK_BEACON_ACTIVATE, Particle.END_ROD);
        } catch (IOException exception) {
            getLogger().severe("Created world " + name + " but could not mark it as a test world: " + exception.getMessage());
            send(player, "<red>World created, but Core could not track it. Check the console.");
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
            Files.writeString(worldPath(name).resolve(MARKER), "flat");
            send(player, "<green>Reset flat world <white>" + name + "</white>.");
            effect(player, "<gold>World reset</gold>", "<gray>" + name + "</gray>",
                Sound.BLOCK_BEACON_ACTIVATE, Particle.POOF);
        } catch (IOException exception) {
            getLogger().severe("Regenerated world " + name + " but could not restore its marker: " + exception.getMessage());
            send(player, "<red>World regenerated, but Core could not track it. Check the console.");
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
        if (saveLocation(homePath(player, name), player.getLocation(), player)) {
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
        if (name.equals("home") && !getConfig().isConfigurationSection(path)
            && getConfig().contains("homes." + player.getUniqueId() + ".world")) {
            path = "homes." + player.getUniqueId();
        }
        return teleportSaved(player, path, name);
    }

    private boolean deleteHome(Player player, String[] args) {
        String name = homeName(args);
        if (name == null) {
            send(player, "<red>Usage: /delhome [name]");
            return true;
        }
        String path = homePath(player, name);
        String legacy = "homes." + player.getUniqueId();
        if (!getConfig().isConfigurationSection(path)
            && (!name.equals("home") || !getConfig().contains(legacy + ".world"))) {
            send(player, "<red>No " + name + " is set.");
            return true;
        }
        getConfig().set(path, null);
        if (name.equals("home")) {
            getConfig().set(legacy + ".world", null);
            getConfig().set(legacy + ".x", null);
            getConfig().set(legacy + ".y", null);
            getConfig().set(legacy + ".z", null);
            getConfig().set(legacy + ".yaw", null);
            getConfig().set(legacy + ".pitch", null);
        }
        if (saveConfig(player)) {
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
        if (saveLocation("warps." + name, player.getLocation(), player)) {
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
        return teleportSaved(player, "warps." + name, name);
    }

    private boolean deleteWarp(Player player, String[] args) {
        if (args.length != 1 || !validWorldName(args[0])) {
            send(player, "<red>Usage: /delwarp <name>");
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        String path = "warps." + name;
        if (!getConfig().isConfigurationSection(path)) {
            send(player, "<red>No " + name + " is set.");
            return true;
        }
        getConfig().set(path, null);
        if (saveConfig(player)) {
            send(player, "<green>Warp <white>" + name + "</white> deleted.");
        }
        return true;
    }

    private boolean teleportSaved(Player player, String path, String name) {
        if (!getConfig().isConfigurationSection(path)) {
            send(player, "<red>No " + name + " is set.");
            return true;
        }
        Location location = savedLocation(path);
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

    private boolean saveLocation(String path, Location location, Player player) {
        getConfig().set(path + ".world", location.getWorld().getName());
        getConfig().set(path + ".x", location.getX());
        getConfig().set(path + ".y", location.getY());
        getConfig().set(path + ".z", location.getZ());
        getConfig().set(path + ".yaw", location.getYaw());
        getConfig().set(path + ".pitch", location.getPitch());
        return saveConfig(player);
    }

    private boolean saveConfig(Player player) {
        try {
            getConfig().save(new File(getDataFolder(), "config.yml"));
            return true;
        } catch (IOException exception) {
            getLogger().severe("Could not save config.yml: " + exception.getMessage());
            send(player, "<red>Could not save the change. Check the console.");
            return false;
        }
    }

    private Location savedLocation(String path) {
        String worldName = getConfig().getString(path + ".world");
        World world = worldName == null ? null : getServer().getWorld(worldName);
        if (world == null && worldName != null) {
            String type = worldType(worldName);
            if (type != null) {
                world = loadWorld(worldName, type);
            }
        }
        double x = getConfig().getDouble(path + ".x");
        double y = getConfig().getDouble(path + ".y");
        double z = getConfig().getDouble(path + ".z");
        float yaw = (float) getConfig().getDouble(path + ".yaw");
        float pitch = (float) getConfig().getDouble(path + ".pitch");
        return world == null || !validCoordinates(x, y, z, yaw, pitch)
            ? null : new Location(world, x, y, z, yaw, pitch);
    }

    private List<String> warpNames() {
        var warps = getConfig().getConfigurationSection("warps");
        return warps == null ? List.of() : warps.getKeys(false).stream()
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> homeNames(Player player) {
        Set<String> names = new HashSet<>();
        var homes = getConfig().getConfigurationSection("homes." + player.getUniqueId() + ".locations");
        if (homes != null) {
            names.addAll(homes.getKeys(false));
        }
        if (getConfig().contains("homes." + player.getUniqueId() + ".world")) {
            names.add("home");
        }
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String homePath(Player player, String name) {
        return "homes." + player.getUniqueId() + ".locations." + name;
    }

    private boolean clearInventory(Player player) {
        player.getInventory().clear();
        send(player, "<green>Inventory cleared.");
        return true;
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
            .filter(viewer -> !viewer.equals(player) && !viewer.hasPermission("core.vanish.see"))
            .forEach(viewer -> {
                if (enable) {
                    viewer.hidePlayer(this, player);
                } else {
                    viewer.showPlayer(this, player);
                }
            });
    }

    void permissionsChanged(Player player) {
        if (vanished.contains(player.getUniqueId()) && !player.hasPermission("core.vanish")) {
            setVanished(player, false);
            send(player, "<yellow>Vanish disabled because your permission changed.");
        }
        if (flightEnabled.contains(player.getUniqueId()) && !player.hasPermission("core.fly")) {
            disableFlight(player);
            send(player, "<yellow>Flight disabled because your permission changed.");
        }
        refreshVanishVisibility(player);
        holograms.refresh(player);
    }

    private void refreshVanishVisibility(Player viewer) {
        vanished.stream().map(getServer()::getPlayer).filter(java.util.Objects::nonNull)
            .forEach(player -> {
                if (viewer.hasPermission("core.vanish.see")) {
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
        try {
            String type = Files.readString(worldPath(name).resolve(MARKER)).trim();
            return type.equals("flat") || type.equals("void") ? type : null;
        } catch (IOException exception) {
            return null;
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
        return supportsMobHead(type) && roll >= 0 && roll < MOB_HEAD_CHANCE;
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
        try (InputStream input = CorePlugin.class.getResourceAsStream("/mob-heads.properties")) {
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
