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

package baritone.behavior.highway;

import baritone.api.Settings;
import baritone.api.TestSettings;
import baritone.api.schematic.CompositeSchematic;
import baritone.test.HeadlessGame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The builder's lookahead windows against the real cross-sections: window n is exactly the road
 * cells from its first slice on, up to the line square to the heading through its last slice's
 * leading corner. On a straight highway that is the whole slices it always was.
 */
public class LookaheadWindowTest {

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
    private static final int WINDOWS = 4;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Test
    public void windowsEndSquareAndCoverTheRoad() {
        for (int width : new int[]{4, 7}) {
            for (boolean pave : new boolean[]{true, false}) {
                for (int lookahead : new int[]{2, 4, 15}) {
                    for (int[] dir : DIRECTIONS) {
                        check(width, pave, lookahead, dir[0], dir[1]);
                    }
                }
            }
        }
    }

    private static void check(int width, boolean pave, int lookahead, int stepX, int stepZ) {
        BlockState air = Blocks.AIR.defaultBlockState(); // not a static field: Blocks needs the bootstrap first
        String what = String.format("width %d %s lookahead %d heading %d,%d", width, pave ? "pave" : "dig", lookahead, stepX, stepZ);
        Settings settings = TestSettings.fresh();
        settings.highwayWidth.value = width;
        CompositeSchematic slice = NetherHighwayBuilderBehavior.composeHighwaySchematic(new Vec3i(stepX, 0, stepZ), pave, settings);
        LookaheadWindow window = LookaheadWindow.of(slice, stepX, stepZ, lookahead);
        boolean straight = stepX == 0 || stepZ == 0;
        if (straight) {
            assertEquals(what, 0, window.extraSlices());
        }

        // every road cell from a few slices before the start to past the last window, by slice
        Map<BlockPos, Integer> sliceOf = new HashMap<>();
        int lastSlice = (WINDOWS + 1) * lookahead + window.extraSlices() + 2;
        for (int i = -window.extraSlices() - 2; i <= lastSlice; i++) {
            for (int x = 0; x < slice.widthX(); x++) {
                for (int y = 0; y < slice.heightY(); y++) {
                    for (int z = 0; z < slice.lengthZ(); z++) {
                        if (slice.inSchematic(x, y, z, air)) {
                            Integer previous = sliceOf.put(new BlockPos(i * stepX + x, y, i * stepZ + z), i);
                            assertTrue(what + ": two slices share a cell", previous == null);
                        }
                    }
                }
            }
        }

        Set<BlockPos> covered = new HashSet<>();
        for (int n = 0; n < WINDOWS; n++) {
            int first = n * lookahead;
            int last = first + lookahead - 1;
            int front = Integer.MIN_VALUE;
            for (Map.Entry<BlockPos, Integer> cell : sliceOf.entrySet()) {
                if (cell.getValue() == last) {
                    front = Math.max(front, along(cell.getKey(), stepX, stepZ));
                }
            }
            Set<BlockPos> expected = new HashSet<>();
            for (Map.Entry<BlockPos, Integer> cell : sliceOf.entrySet()) {
                if (cell.getValue() >= first && along(cell.getKey(), stepX, stepZ) <= front) {
                    expected.add(cell.getKey());
                }
            }

            int originX = first * stepX + window.originShift().getX();
            int originZ = first * stepZ + window.originShift().getZ();
            CompositeSchematic schem = window.schematic();
            Set<BlockPos> actual = new HashSet<>();
            for (int x = 0; x < schem.widthX(); x++) {
                for (int y = 0; y < schem.heightY(); y++) {
                    for (int z = 0; z < schem.lengthZ(); z++) {
                        if (schem.inSchematic(x, y, z, air)) {
                            BlockPos pos = new BlockPos(originX + x, y, originZ + z);
                            assertTrue(what + ": window " + n + " has " + pos + ", not a road cell", sliceOf.containsKey(pos));
                            // the repeat count keeps the last window short of the end by this much
                            assertTrue(what + ": window " + n + " reaches past its extra slices",
                                    sliceOf.get(pos) <= last + window.extraSlices());
                            actual.add(pos);
                        }
                    }
                }
            }
            assertEquals(what + ": window " + n, expected, actual);
            if (straight) {
                for (BlockPos pos : actual) {
                    int i = sliceOf.get(pos);
                    assertTrue(what + ": straight window " + n + " isn't whole slices", i >= first && i <= last);
                }
            }
            covered.addAll(actual);
        }
        for (Map.Entry<BlockPos, Integer> cell : sliceOf.entrySet()) {
            if (cell.getValue() >= 0 && cell.getValue() < (WINDOWS - 1) * lookahead) {
                assertTrue(what + ": no window has " + cell.getKey(), covered.contains(cell.getKey()));
            }
        }
    }

    private static int along(BlockPos pos, int stepX, int stepZ) {
        return pos.getX() * stepX + pos.getZ() * stepZ;
    }
}
