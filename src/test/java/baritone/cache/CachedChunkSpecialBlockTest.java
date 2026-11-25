/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.cache;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * A tracked block in a cached chunk reads as itself where it is, in a dimension whose floor isn't at y 0 too
 */
public class CachedChunkSpecialBlockTest {

    private static final int OVERWORLD_MIN_Y = -64;
    private static final int OVERWORLD_HEIGHT = 384;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * A chunk with a portal column at x 3, z 5 from world y 74 to 76, in a dimension starting at {@code minY}. The
     * packed bits are left air there, so all a lookup can find the portal by is the tracked block
     */
    private static CachedChunk withPortal(int minY, int height) {
        BitSet data = new BitSet(CachedChunk.size(height));
        BlockState[] overview = new BlockState[256];
        Arrays.fill(overview, Blocks.AIR.defaultBlockState());
        List<BlockPos> portal = new ArrayList<>();
        for (int y = 74; y <= 76; y++) {
            portal.add(new BlockPos(3, y, 5)); // world y, the way ChunkPacker and the region file both give them
        }
        Map<String, List<BlockPos>> special = new HashMap<>();
        special.put("minecraft:nether_portal", portal);
        return new CachedChunk(0, 0, height, minY, data, overview, special, 0);
    }

    private static BlockState at(CachedChunk chunk, int minY, int worldY) {
        // what BlockStateInterface and CachedRegion ask for: y counted from the bottom of the world
        return chunk.getBlock(3, worldY - minY, 5, null, Level.OVERWORLD);
    }

    @Test
    public void anOverworldPortalReadsAsAPortalWhereItIs() {
        CachedChunk chunk = withPortal(OVERWORLD_MIN_Y, OVERWORLD_HEIGHT);
        for (int y = 74; y <= 76; y++) {
            assertEquals("y " + y, Blocks.NETHER_PORTAL, at(chunk, OVERWORLD_MIN_Y, y).getBlock());
        }
        // and not 64 blocks below it, where keying by world y reads as one
        for (int y = 10; y <= 12; y++) {
            assertNotEquals("y " + y, Blocks.NETHER_PORTAL, at(chunk, OVERWORLD_MIN_Y, y).getBlock());
        }
    }

    @Test
    public void aNetherPortalReadsAsAPortalWhereItIs() {
        CachedChunk chunk = withPortal(0, 256);
        for (int y = 74; y <= 76; y++) {
            assertEquals("y " + y, Blocks.NETHER_PORTAL, at(chunk, 0, y).getBlock());
        }
        assertEquals(Blocks.AIR, at(chunk, 0, 73).getBlock());
        assertEquals(Blocks.AIR, at(chunk, 0, 77).getBlock());
    }
}
