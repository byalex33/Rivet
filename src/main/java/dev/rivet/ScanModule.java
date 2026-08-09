package dev.rivet;

import io.papermc.paper.block.TileStateInventoryHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.Campfire;
import org.bukkit.block.Vault;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.OminousItemSpawner;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ScanModule {
    private static final TextColor ACCENT = TextColor.color(0xf72a4c);
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int REGION_HEADER_BYTES = 4096;
    private static final int MAX_NESTING_DEPTH = 16;

    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private ScanJob active;

    ScanModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("inventory");
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            status(sender);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            cancel(sender);
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            usage(sender);
            return true;
        }
        Material material = Material.matchMaterial(args[0]);
        if (material == null || material.isAir() || !material.isItem()) {
            sender.sendMessage(Component.text("Use a valid item material.", NamedTextColor.WHITE));
            return true;
        }
        if (active != null) {
            sender.sendMessage(Component.text("A map scan is already running. Use ", NamedTextColor.WHITE)
                .append(Component.text("/scan status", ACCENT))
                .append(Component.text(" or ", NamedTextColor.WHITE))
                .append(Component.text("/scan cancel", ACCENT))
                .append(Component.text('.', NamedTextColor.WHITE)));
            return true;
        }
        List<World> worlds = resolveWorlds(sender, args.length == 2 ? args[1] : null);
        if (worlds == null) {
            return true;
        }

        List<WorldSource> sources = worlds.stream().map(world -> new WorldSource(world,
            world.getWorldFolder().toPath(), world.getSpawnLocation().getBlockX() >> 4,
            world.getSpawnLocation().getBlockZ() >> 4,
            Arrays.stream(world.getLoadedChunks())
                .map(chunk -> new ChunkPosition(chunk.getX(), chunk.getZ()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))))
            .toList();
        ScanJob job = new ScanJob(sender, material, Math.max(1,
            settings.getInt("scan.maximum-results", 100)), Instant.now());
        active = job;
        sender.sendMessage(Component.text("Discovering saved chunks for ", NamedTextColor.WHITE)
            .append(Component.text(materialName(material), ACCENT))
            .append(Component.text(" in " + worlds.size() + " world(s)...", NamedTextColor.WHITE)));

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ChunkTarget> targets = new ArrayList<>();
            int failures = 0;
            for (WorldSource source : sources) {
                if (job.cancelled) {
                    break;
                }
                try {
                    Set<ChunkPosition> positions = discoverChunks(source.folder());
                    positions.addAll(source.loadedChunks());
                    positions.stream()
                        .sorted(Comparator.comparingLong(position -> distanceSquared(position,
                            source.spawnChunkX(), source.spawnChunkZ())))
                        .map(position -> new ChunkTarget(source.world(), position.x(), position.z()))
                        .forEach(targets::add);
                } catch (IOException exception) {
                    failures++;
                    plugin.getLogger().warning("Could not discover chunks in "
                        + source.world().getName() + ": " + exception.getMessage());
                }
            }
            int discoveryFailures = failures;
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin,
                () -> beginScanning(job, targets, discoveryFailures));
        });
        return true;
    }

    List<String> completions(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>();
            choices.add("status");
            choices.add("cancel");
            Arrays.stream(Material.values()).filter(material -> material.isItem() && !material.isAir())
                .map(material -> material.name().toLowerCase(Locale.ROOT)).forEach(choices::add);
            return choices;
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("status")
            && !args[0].equalsIgnoreCase("cancel")) {
            List<String> choices = new ArrayList<>();
            choices.add("all");
            plugin.getServer().getWorlds().stream().map(World::getName).forEach(choices::add);
            return choices;
        }
        return List.of();
    }

    void shutdown() {
        if (active != null) {
            active.cancelled = true;
            active = null;
        }
    }

    private List<World> resolveWorlds(CommandSender sender, String requested) {
        if (requested == null) {
            if (sender instanceof Player player) {
                return List.of(player.getWorld());
            }
            sender.sendMessage(Component.text("Console usage: /scan <item> <world|all>",
                NamedTextColor.WHITE));
            return null;
        }
        if (requested.equalsIgnoreCase("all")) {
            return List.copyOf(plugin.getServer().getWorlds());
        }
        World world = plugin.getServer().getWorld(requested);
        if (world == null) {
            sender.sendMessage(Component.text("That world is not loaded.", NamedTextColor.WHITE));
            return null;
        }
        return List.of(world);
    }

    private void beginScanning(ScanJob job, List<ChunkTarget> targets, int discoveryFailures) {
        if (job != active) {
            return;
        }
        job.targets = targets;
        job.discoveryFailures = discoveryFailures;
        if (job.cancelled) {
            finish(job, true);
            return;
        }
        if (targets.isEmpty()) {
            finish(job, false);
            return;
        }
        job.sender.sendMessage(Component.text("Scanning " + targets.size()
            + " saved chunk(s) asynchronously...", NamedTextColor.WHITE));
        startMore(job);
    }

    private void startMore(ScanJob job) {
        if (job != active) {
            return;
        }
        if (job.cancelled) {
            if (job.inFlight == 0) {
                finish(job, true);
            }
            return;
        }
        int concurrency = Math.max(1, Math.min(16,
            settings.getInt("scan.concurrent-chunk-loads", 2)));
        while (job.inFlight < concurrency && job.nextTarget < job.targets.size()) {
            ChunkTarget target = job.targets.get(job.nextTarget++);
            boolean wasLoaded = target.world().isChunkLoaded(target.x(), target.z());
            job.inFlight++;
            target.world().getChunkAtAsync(target.x(), target.z(), false, false)
                .whenComplete((chunk, error) -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(plugin,
                        () -> completeChunk(job, target, chunk, error, wasLoaded));
                });
        }
        if (job.nextTarget >= job.targets.size() && job.inFlight == 0) {
            finish(job, false);
        }
    }

    private void completeChunk(ScanJob job, ChunkTarget target, Chunk chunk, Throwable error,
                               boolean wasLoaded) {
        if (job != active) {
            return;
        }
        try {
            if (error != null) {
                job.chunkFailures++;
                plugin.getLogger().warning("Could not scan chunk " + target.x() + ","
                    + target.z() + " in " + target.world().getName() + ": " + error.getMessage());
            } else if (chunk != null && !job.cancelled) {
                chunk.addPluginChunkTicket(plugin);
                try {
                    scanChunk(job, chunk);
                } finally {
                    chunk.removePluginChunkTicket(plugin);
                }
            }
        } catch (RuntimeException exception) {
            job.chunkFailures++;
            plugin.getLogger().warning("Could not inspect chunk " + target.x() + ","
                + target.z() + " in " + target.world().getName() + ": "
                + exception.getMessage());
        } finally {
            if (!wasLoaded && chunk != null) {
                try {
                    target.world().unloadChunkRequest(target.x(), target.z());
                } catch (RuntimeException exception) {
                    job.chunkFailures++;
                    plugin.getLogger().warning("Could not release chunk " + target.x() + ","
                        + target.z() + " in " + target.world().getName() + ": "
                        + exception.getMessage());
                }
            }
            job.inFlight--;
            job.processed++;
        }

        int interval = Math.max(1, settings.getInt("scan.progress-interval-chunks", 500));
        if (!job.cancelled && (job.processed % interval == 0
            || job.processed == job.targets.size())) {
            job.sender.sendMessage(Component.text("Scan progress: " + job.processed + "/"
                + job.targets.size() + " chunks, " + job.foundLocations + " location(s), "
                + job.foundItems + " item(s).", NamedTextColor.WHITE));
        }
        startMore(job);
    }

    private void scanChunk(ScanJob job, Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(false)) {
            int amount = blockAmount(state, job.material);
            if (amount > 0) {
                record(job, state.getLocation(), blockName(state), amount);
            }
        }
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            int amount = entityAmount(entity, job.material);
            if (amount > 0) {
                record(job, entity.getLocation(), entityName(entity), amount);
            }
        }
    }

    private static int blockAmount(BlockState state, Material material) {
        if (state instanceof TileStateInventoryHolder holder) {
            return inventoryAmount(holder.getSnapshotInventory(), material);
        }
        if (state instanceof org.bukkit.inventory.InventoryHolder holder) {
            return inventoryAmount(holder.getInventory(), material);
        }
        if (state instanceof Campfire campfire) {
            int amount = 0;
            for (int slot = 0; slot < campfire.getSize(); slot++) {
                amount += itemAmount(campfire.getItem(slot), material, 0);
            }
            return amount;
        }
        if (state instanceof BrushableBlock brushable) {
            return itemAmount(brushable.getItem(), material, 0);
        }
        return 0;
    }

    private static int entityAmount(Entity entity, Material material) {
        if (entity instanceof InventoryHolder holder && !(entity instanceof HumanEntity)) {
            return inventoryAmount(holder.getInventory(), material);
        }
        if (entity instanceof LivingEntity living) {
            return equipmentAmount(living.getEquipment(), material);
        }
        if (entity instanceof ItemFrame frame) {
            return itemAmount(frame.getItem(), material, 0);
        }
        if (entity instanceof ItemDisplay display) {
            return itemAmount(display.getItemStack(), material, 0);
        }
        if (entity instanceof OminousItemSpawner spawner) {
            return itemAmount(spawner.getItem(), material, 0);
        }
        return 0;
    }

    private static int equipmentAmount(EntityEquipment equipment, Material material) {
        if (equipment == null) {
            return 0;
        }
        int amount = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            try {
                amount += itemAmount(equipment.getItem(slot), material, 0);
            } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                // Some entities expose EntityEquipment but do not support every modern slot.
            }
        }
        return amount;
    }

    private static int inventoryAmount(Inventory inventory, Material material) {
        return inventoryAmount(inventory, material, 0);
    }

    private static int inventoryAmount(Inventory inventory, Material material, int depth) {
        int amount = 0;
        for (ItemStack item : inventory.getContents()) {
            amount += itemAmount(item, material, depth);
        }
        return amount;
    }

    static int itemAmount(ItemStack item, Material material, int depth) {
        if (item == null || item.getType().isAir() || depth > MAX_NESTING_DEPTH) {
            return 0;
        }
        int amount = item.getType() == material ? item.getAmount() : 0;
        if (!item.hasItemMeta()) {
            return amount;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return amount;
        }
        if (meta instanceof BundleMeta bundle) {
            for (ItemStack nested : bundle.getItems()) {
                amount += itemAmount(nested, material, depth + 1);
            }
        } else if (meta instanceof BlockStateMeta blockMeta && blockMeta.hasBlockState()) {
            BlockState nestedState = blockMeta.getBlockState();
            if (nestedState instanceof TileStateInventoryHolder holder) {
                amount += inventoryAmount(holder.getSnapshotInventory(), material, depth + 1);
            } else if (nestedState instanceof org.bukkit.inventory.InventoryHolder holder) {
                amount += inventoryAmount(holder.getInventory(), material, depth + 1);
            }
        }
        return amount;
    }

    private void record(ScanJob job, Location location, String source, int amount) {
        job.foundLocations++;
        job.foundItems += amount;
        if (job.reportedLocations >= job.maximumResults) {
            return;
        }
        job.reportedLocations++;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        Component result = Component.text("  " + location.getWorld().getName() + " ",
                NamedTextColor.WHITE)
            .append(Component.text(x + " " + y + " " + z, ACCENT))
            .append(Component.text(" — " + source + " (" + amount + ")", NamedTextColor.WHITE));
        if (job.sender instanceof Player) {
            String dimension = location.getWorld().getKey().asString();
            result = result.clickEvent(ClickEvent.suggestCommand("/minecraft:execute in "
                + dimension + " run tp @s " + x + " " + y + " " + z));
        }
        job.sender.sendMessage(result);
    }

    private void status(CommandSender sender) {
        ScanJob job = active;
        if (job == null) {
            sender.sendMessage(Component.text("No map scan is running.", NamedTextColor.WHITE));
            return;
        }
        if (job.targets == null) {
            sender.sendMessage(Component.text("The " + materialName(job.material)
                + " scan is discovering saved chunks.", NamedTextColor.WHITE));
            return;
        }
        sender.sendMessage(Component.text("Scanning " + materialName(job.material) + ": "
            + job.processed + "/" + job.targets.size() + " chunks, " + job.foundLocations
            + " location(s), " + job.foundItems + " item(s).", NamedTextColor.WHITE));
    }

    private void cancel(CommandSender sender) {
        ScanJob job = active;
        if (job == null) {
            sender.sendMessage(Component.text("No map scan is running.", NamedTextColor.WHITE));
            return;
        }
        job.cancelled = true;
        sender.sendMessage(Component.text("Cancelling the map scan...", NamedTextColor.WHITE));
        if (job.inFlight == 0 && job.targets != null) {
            finish(job, true);
        }
    }

    private void finish(ScanJob job, boolean cancelled) {
        if (job != active) {
            return;
        }
        active = null;
        Duration duration = Duration.between(job.started, Instant.now());
        int chunks = job.targets == null ? 0 : job.processed;
        String outcome = cancelled ? "Scan cancelled after " : "Scan complete: ";
        job.sender.sendMessage(Component.text(outcome + chunks + " chunk(s), "
            + job.foundLocations + " location(s), " + job.foundItems + " "
            + materialName(job.material) + " item(s), " + duration.toSeconds() + "s.",
            NamedTextColor.WHITE));
        int failures = job.discoveryFailures + job.chunkFailures;
        if (failures > 0) {
            job.sender.sendMessage(Component.text(failures
                + " world/chunk operation(s) failed; see the server log.", NamedTextColor.WHITE));
        }
        if (job.foundLocations > job.maximumResults) {
            job.sender.sendMessage(Component.text("Only the first " + job.maximumResults
                + " locations were shown; totals include every match.", NamedTextColor.WHITE));
        }
    }

    private static Set<ChunkPosition> discoverChunks(Path worldFolder) throws IOException {
        Set<ChunkPosition> chunks = new HashSet<>();
        if (!Files.isDirectory(worldFolder)) {
            return chunks;
        }
        try (var paths = Files.walk(worldFolder)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path parent = path.getParent();
                if (parent == null || !(parent.getFileName().toString().equals("region")
                    || parent.getFileName().toString().equals("entities"))) {
                    continue;
                }
                Matcher matcher = REGION_FILE.matcher(path.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                int regionX;
                int regionZ;
                try {
                    regionX = Integer.parseInt(matcher.group(1));
                    regionZ = Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                readRegionHeader(path, regionX, regionZ, chunks);
            }
        }
        return chunks;
    }

    private static void readRegionHeader(Path path, int regionX, int regionZ,
                                         Set<ChunkPosition> chunks) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(REGION_HEADER_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long position = 0;
            while (header.hasRemaining()) {
                int read = channel.read(header, position);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    break;
                }
                position += read;
            }
        }
        header.flip();
        for (int index = 0; index < 1024 && header.remaining() >= Integer.BYTES; index++) {
            if (header.getInt() != 0) {
                chunks.add(chunkPosition(regionX, regionZ, index));
            }
        }
    }

    static ChunkPosition chunkPosition(int regionX, int regionZ, int headerIndex) {
        return new ChunkPosition(regionX * 32 + (headerIndex & 31),
            regionZ * 32 + (headerIndex >> 5));
    }

    private static long distanceSquared(ChunkPosition position, int x, int z) {
        long dx = (long) position.x() - x;
        long dz = (long) position.z() - z;
        return dx * dx + dz * dz;
    }

    private static String blockName(BlockState state) {
        return materialName(state.getType());
    }

    private static String entityName(Entity entity) {
        return entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String materialName(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /scan <item> [world|all], /scan status, or /scan cancel",
            NamedTextColor.WHITE));
    }

    record ChunkPosition(int x, int z) {
    }

    private record ChunkTarget(World world, int x, int z) {
    }

    private record WorldSource(World world, Path folder, int spawnChunkX, int spawnChunkZ,
                               Set<ChunkPosition> loadedChunks) {
    }

    private static final class ScanJob {
        private final CommandSender sender;
        private final Material material;
        private final int maximumResults;
        private final Instant started;
        private volatile boolean cancelled;
        private List<ChunkTarget> targets;
        private int nextTarget;
        private int inFlight;
        private int processed;
        private int foundLocations;
        private long foundItems;
        private int reportedLocations;
        private int discoveryFailures;
        private int chunkFailures;

        private ScanJob(CommandSender sender, Material material, int maximumResults,
                        Instant started) {
            this.sender = sender;
            this.material = material;
            this.maximumResults = maximumResults;
            this.started = started;
        }
    }
}
