package dev.rivet;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public final class TreeFellerVineTest {
    @Test
    public void collectsAdjacentJungleVinesAndTheirHangingColumns() {
        TestBlocks blocks = new TestBlocks();
        Block log = blocks.put(0, 10, 0, Material.JUNGLE_LOG);
        Block leaf = blocks.put(1, 12, 0, Material.JUNGLE_LEAVES);
        Block trunkVine = blocks.put(-1, 10, 0, Material.VINE);
        Block hangingVine = blocks.put(2, 12, 0, Material.VINE);
        Block hangingVineBottom = blocks.put(2, 11, 0, Material.VINE);
        Block unrelatedVine = blocks.put(4, 12, 0, Material.VINE);

        Set<Block> growth = TreeFeller.attachedJungleGrowth(
            Set.of(log), Set.of(leaf), 512);

        assertEquals(Set.of(trunkVine, hangingVine, hangingVineBottom), growth);
        assertEquals(false, growth.contains(unrelatedVine));
    }

    private static final class TestBlocks {
        private final Map<Position, Material> materials = new HashMap<>();
        private final Map<Position, Block> blocks = new HashMap<>();

        Block put(int x, int y, int z, Material material) {
            Position position = new Position(x, y, z);
            materials.put(position, material);
            return block(position);
        }

        private Block block(Position position) {
            return blocks.computeIfAbsent(position, ignored -> (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(), new Class<?>[]{Block.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getType" -> materials.getOrDefault(position, Material.AIR);
                        case "getRelative" -> relative(position, args);
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> position.hashCode();
                        case "toString" -> position.toString();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }));
        }

        private Block relative(Position position, Object[] args) {
            if (args.length == 1 && args[0] instanceof BlockFace face) {
                return block(new Position(position.x() + face.getModX(),
                    position.y() + face.getModY(), position.z() + face.getModZ()));
            }
            throw new UnsupportedOperationException("getRelative");
        }
    }

    private record Position(int x, int y, int z) {
    }
}
