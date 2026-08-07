package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Axis;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TreeFeller implements Listener {
    // ponytail: hard caps protect one server tick; batch larger custom trees/veins if they become a real use case.
    private static final int MAX_LOGS = 96;
    private static final int MAX_LEAVES = 512;
    private static final int MAX_ORES = 64;
    private static final int MIN_LOGS = 4;
    private static final int MIN_LEAVES = 10;
    private static final int MAX_HORIZONTAL_LOG_RUN = 6;

    private final RivetPlugin plugin;
    private final Set<Block> activeBlocks = new HashSet<>();
    private boolean checkingProtection;

    TreeFeller(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (checkingProtection) {
            return;
        }

        Player player = event.getPlayer();
        Block base = event.getBlock();
        if (activeBlocks.contains(base)) {
            event.setCancelled(true);
            return;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }

        if (isAxe(tool.getType()) && Tag.LOGS.isTagged(base.getType())
            && !Tag.LOGS.isTagged(base.getRelative(0, -1, 0).getType())) {
            fellTree(event, player, base, tool);
        } else if (isPickaxe(tool.getType()) && oreKey(base.getType()) != null
            && !base.getDrops(tool, player).isEmpty()) {
            mineVein(event, player, base, tool);
        }
    }

    private void fellTree(BlockBreakEvent event, Player player, Block base, ItemStack axe) {
        Material wood = base.getType();
        Set<Block> logs = connectedLogs(base);
        Set<Block> leaves = connectedLeaves(logs);
        int vertical = 0;
        int horizontal = 0;
        for (Block log : logs) {
            if (log.getBlockData() instanceof Orientable data) {
                if (data.getAxis() == Axis.Y) {
                    vertical++;
                } else {
                    horizontal++;
                }
            }
        }
        if (!validStructure(logs.size(), leaves.size(), longestHorizontalRun(logs), vertical, horizontal)) {
            return;
        }
        Set<Block> tree = new HashSet<>(logs);
        tree.addAll(leaves);
        if (!canBreak(tree, base, player)) {
            return;
        }

        event.setCancelled(true);
        ItemStack dropTool = axe.clone();
        player.getInventory().setItemInMainHand(axe.damage(logs.size(), player));
        animate(tree, dropTool, player);
        player.sendActionBar(Component.text("Felled ", NamedTextColor.GRAY)
            .append(Component.text(logs.size(), NamedTextColor.WHITE))
            .append(Component.text(" × ", NamedTextColor.GRAY))
            .append(Component.translatable(wood.translationKey()).color(woodColor(wood))));
    }

    private void mineVein(BlockBreakEvent event, Player player, Block base, ItemStack pickaxe) {
        Material mined = base.getType();
        Set<Block> vein = connectedOres(base);
        if (vein.isEmpty()) {
            return;
        }
        if (vein.size() > 1) {
            if (!canBreak(vein, base, player)) {
                return;
            }
            event.setCancelled(true);
            ItemStack dropTool = pickaxe.clone();
            player.getInventory().setItemInMainHand(pickaxe.damage(vein.size(), player));
            vein.forEach(ore -> ore.breakNaturally(dropTool, true, true));
        }
        player.sendActionBar(Component.text("Mined ", NamedTextColor.GRAY)
            .append(Component.text(vein.size(), NamedTextColor.WHITE))
            .append(Component.text(" of ", NamedTextColor.GRAY))
            .append(Component.translatable(mined.translationKey()).color(oreColor(mined))));
    }

    private Set<Block> connectedLogs(Block base) {
        Set<Block> logs = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(base);

        while (!queue.isEmpty()) {
            Block block = queue.removeFirst();
            if (block.getY() < base.getY() || block.getType() != base.getType() || !logs.add(block)) {
                continue;
            }
            if (logs.size() > MAX_LOGS) {
                return Set.of();
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x != 0 || y != 0 || z != 0) {
                            queue.add(block.getRelative(x, y, z));
                        }
                    }
                }
            }
        }
        return logs;
    }

    private Set<Block> connectedLeaves(Set<Block> logs) {
        Set<Block> leaves = new HashSet<>();
        ArrayDeque<LeafCandidate> queue = new ArrayDeque<>();
        for (Block log : logs) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (Math.abs(x) + Math.abs(y) + Math.abs(z) == 1) {
                            queue.add(new LeafCandidate(log.getRelative(x, y, z), 0));
                        }
                    }
                }
            }
        }

        while (!queue.isEmpty() && leaves.size() < MAX_LEAVES) {
            LeafCandidate candidate = queue.removeFirst();
            Block block = candidate.block();
            if (!(block.getBlockData() instanceof Leaves data) || data.isPersistent()
                || data.getDistance() <= candidate.previousDistance() || !leaves.add(block)) {
                continue;
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x != 0 || y != 0 || z != 0) {
                            queue.add(new LeafCandidate(block.getRelative(x, y, z), data.getDistance()));
                        }
                    }
                }
            }
        }
        return leaves;
    }

    private static int longestHorizontalRun(Set<Block> logs) {
        int longest = 0;
        for (Block log : logs) {
            int xRun = 1;
            for (int x = 1; logs.contains(log.getRelative(x, 0, 0)); x++) {
                xRun++;
            }
            for (int x = -1; logs.contains(log.getRelative(x, 0, 0)); x--) {
                xRun++;
            }
            int zRun = 1;
            for (int z = 1; logs.contains(log.getRelative(0, 0, z)); z++) {
                zRun++;
            }
            for (int z = -1; logs.contains(log.getRelative(0, 0, z)); z--) {
                zRun++;
            }
            longest = Math.max(longest, Math.max(xRun, zRun));
        }
        return longest;
    }

    private Set<Block> connectedOres(Block base) {
        String key = oreKey(base.getType());
        Set<Block> ores = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(base);

        while (!queue.isEmpty()) {
            Block block = queue.removeFirst();
            if (!key.equals(oreKey(block.getType())) || !ores.add(block)) {
                continue;
            }
            if (ores.size() > MAX_ORES) {
                return Set.of();
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x != 0 || y != 0 || z != 0) {
                            queue.add(block.getRelative(x, y, z));
                        }
                    }
                }
            }
        }
        return ores;
    }

    private boolean canBreak(Set<Block> logs, Block base, Player player) {
        checkingProtection = true;
        try {
            for (Block log : logs) {
                if (log.equals(base)) {
                    continue;
                }
                BlockBreakEvent check = new BlockBreakEvent(log, player);
                plugin.getServer().getPluginManager().callEvent(check);
                if (check.isCancelled()) {
                    return false;
                }
            }
            return true;
        } finally {
            checkingProtection = false;
        }
    }

    private void animate(Set<Block> tree, ItemStack tool, Player player) {
        Map<Integer, List<Piece>> layers = new HashMap<>();
        for (Block block : tree) {
            Piece piece = new Piece(block, block.getBlockData(), List.copyOf(block.getDrops(tool, player)));
            layers.computeIfAbsent(block.getY(), ignored -> new ArrayList<>()).add(piece);
        }
        activeBlocks.addAll(tree);
        player.swingMainHand();
        Iterator<List<Piece>> animation = topDown(layers).iterator();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!animation.hasNext()) {
                    cancel();
                    return;
                }
                List<Piece> layer = animation.next();
                layer.forEach(piece -> breakPiece(piece, player));
                if (plugin.getConfig().getBoolean("effects.sounds") && !layer.isEmpty()) {
                    layer.getFirst().block().getWorld().playSound(
                        layer.getFirst().block().getLocation(), Sound.BLOCK_WOOD_BREAK, .8f, 1.1f);
                }
            }
        }.runTaskTimer(plugin, 0, 2);
    }

    private void breakPiece(Piece piece, Player player) {
        Block block = piece.block();
        activeBlocks.remove(block);
        if (block.getType() != piece.data().getMaterial()) {
            return;
        }
        block.setType(Material.AIR, false);
        piece.drops().forEach(drop -> player.getInventory().addItem(drop).values()
            .forEach(leftover -> block.getWorld().dropItemNaturally(block.getLocation(), leftover)));
        if (plugin.getConfig().getBoolean("effects.particles")) {
            block.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE,
                block.getLocation().add(.5, .5, .5), 8, .3, .3, .3, piece.data());
        }
    }

    void shutdown() {
        activeBlocks.clear();
    }

    static boolean isAxe(Material material) {
        return material.name().endsWith("_AXE");
    }

    static boolean isPickaxe(Material material) {
        return material.name().endsWith("_PICKAXE");
    }

    static String oreKey(Material material) {
        String name = material.name();
        if (name.startsWith("DEEPSLATE_")) {
            name = name.substring(10);
        }
        return name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS ? name : null;
    }

    static TextColor oreColor(Material material) {
        return TextColor.color(switch (oreKey(material)) {
            case "COAL_ORE" -> 0xAAAAAA;
            case "IRON_ORE", "NETHER_QUARTZ_ORE" -> 0xFFFFFF;
            case "COPPER_ORE" -> 0xE77C56;
            case "GOLD_ORE", "NETHER_GOLD_ORE" -> 0xFFD700;
            case "REDSTONE_ORE" -> 0xFF5555;
            case "LAPIS_ORE" -> 0x345EC3;
            case "EMERALD_ORE" -> 0x55FF55;
            case "DIAMOND_ORE" -> 0x55FFFF;
            case "ANCIENT_DEBRIS" -> 0x8B5A4A;
            default -> 0xFFFFFF;
        });
    }

    static TextColor woodColor(Material material) {
        String name = material.name();
        return TextColor.color(
            name.startsWith("SPRUCE_") ? 0x815631
                : name.startsWith("BIRCH_") ? 0xE5D7A3
                : name.startsWith("JUNGLE_") ? 0xB88752
                : name.startsWith("ACACIA_") ? 0xD87F4B
                : name.startsWith("DARK_OAK_") ? 0x5B3A29
                : name.startsWith("MANGROVE_") ? 0x8F3F3F
                : name.startsWith("CHERRY_") ? 0xE7A8B7
                : name.startsWith("PALE_OAK_") ? 0xD5D1C4
                : 0xC89B5B);
    }

    static <T> List<T> topDown(Map<Integer, T> layers) {
        return layers.entrySet().stream().sorted(Map.Entry.<Integer, T>comparingByKey().reversed())
            .map(Map.Entry::getValue).toList();
    }

    static boolean validStructure(int logs, int leaves, int horizontalRun, int vertical, int horizontal) {
        return logs >= MIN_LOGS && leaves >= MIN_LEAVES && horizontalRun <= MAX_HORIZONTAL_LOG_RUN
            && (horizontal == 0 || vertical / (double) horizontal >= .5);
    }

    private record Piece(Block block, BlockData data, List<ItemStack> drops) {
    }

    private record LeafCandidate(Block block, int previousDistance) {
    }
}
