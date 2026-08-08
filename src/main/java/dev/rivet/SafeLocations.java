package dev.rivet;

import org.bukkit.Location;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Set;

final class SafeLocations {
    private static final Set<Material> DANGEROUS = Set.of(
        Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
        Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WATER);
    private static final int[][] NEARBY = {
        {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final Set<Material> TREE_BASES = Set.of(
        Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL,
        Material.MYCELIUM, Material.ROOTED_DIRT, Material.MOSS_BLOCK, Material.MUD,
        Material.SAND, Material.RED_SAND, Material.END_STONE, Material.NETHERRACK,
        Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM);

    private SafeLocations() {
    }

    static Location nearTarget(Block target, Location facing) {
        World world = target.getWorld();
        int baseY = target.getY() + 1;
        for (int vertical : new int[]{0, 1, 2, 3, -1, -2}) {
            for (int[] offset : NEARBY) {
                int x = target.getX() + offset[0];
                int z = target.getZ() + offset[1];
                int y = baseY + vertical;
                if (safeStanding(world, x, y, z, false)) {
                    Location location = centered(world, x, y, z, facing);
                    if (world.getWorldBorder().isInside(location)) {
                        return location;
                    }
                }
            }
        }
        return null;
    }

    static Location highest(World world, int x, int z, Location facing) {
        int highestNonAirY = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        int startFeetY = topScanStart(world.getMaxHeight(), highestNonAirY);
        for (int feetY = startFeetY; feetY > world.getMinHeight(); feetY--) {
            if (world.getBlockAt(x, feetY - 1, z).getType().isAir()) {
                continue;
            }
            if (safeStanding(world, x, feetY, z, true)) {
                return centered(world, x, feetY, z, facing);
            }
        }
        return null;
    }

    static int topScanStart(int maxHeight, int highestNonAirY) {
        return Math.min(maxHeight - 2, highestNonAirY + 1);
    }

    static boolean safeStanding(World world, int x, int feetY, int z, boolean avoidLeaves) {
        if (feetY <= world.getMinHeight() || feetY + 1 >= world.getMaxHeight()) {
            return false;
        }
        Block ground = world.getBlockAt(x, feetY - 1, z);
        Block feet = world.getBlockAt(x, feetY, z);
        Block head = world.getBlockAt(x, feetY + 1, z);
        return safeStanding(ground.getType(), ground.getType().isSolid(), feet.getType(),
            feet.isPassable(), head.getType(), head.isPassable(), avoidLeaves);
    }

    static boolean safeStanding(Material ground, boolean groundSolid, Material feet,
                                boolean feetPassable, Material head, boolean headPassable,
                                boolean avoidLeaves) {
        return groundSolid && !DANGEROUS.contains(ground)
            && (!avoidLeaves || !ground.name().endsWith("_LEAVES"))
            && feetPassable && headPassable
            && !DANGEROUS.contains(feet) && !DANGEROUS.contains(head);
    }

    static boolean suitableTreeBase(Block base) {
        return suitableTreeBase(base.getType());
    }

    static boolean suitableTreeBase(Material material) {
        return TREE_BASES.contains(material);
    }

    static boolean treeClearance(Location origin) {
        World world = origin.getWorld();
        if (world == null || origin.getBlockY() < world.getMinHeight()
            || origin.getBlockY() + 32 >= world.getMaxHeight()) {
            return false;
        }
        for (int y = 0; y <= 32; y++) {
            int radius = y < 2 ? 0 : 4;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = world.getBlockAt(origin.getBlockX() + x,
                        origin.getBlockY() + y, origin.getBlockZ() + z);
                    if (!block.isPassable() && !replaceablePlant(block.getType())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean replaceablePlant(Material material) {
        String name = material.name();
        return name.endsWith("_SAPLING")
            || name.endsWith("_FLOWER") || name.equals("GRASS") || name.equals("TALL_GRASS")
            || name.equals("FERN") || name.equals("LARGE_FERN") || name.equals("VINE");
    }

    private static Location centered(World world, int x, int y, int z, Location facing) {
        return new Location(world, x + .5, y, z + .5, facing.getYaw(), facing.getPitch());
    }
}
