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

package baritone.utils;

import baritone.test.HeadlessGame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A paved highway along +X: lane z = 1..3 of obsidian at {@link #FLOOR}, obsidian rails at z = 0
 * and z = 4 one up, each on a netherrack support, netherrack underneath everything.
 */
public class EntityHoleTest {

    private static final int FLOOR = 120;
    private static final int FEET = FLOOR + 1;
    private static final double PIGMAN_WIDTH = 0.6;
    private static final double PIGMAN_HEIGHT = 1.95;
    private static final double BOAT_WIDTH = 1.375;
    private static final double BOAT_HEIGHT = 0.5625;

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final Set<BlockPos> refill = new HashSet<>();
    private final BlockGetter world = new BlockGetter() {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    };

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Before
    public void buildHighway() {
        for (int x = -5; x <= 5; x++) {
            for (int z = -2; z <= 6; z++) {
                for (int y = FLOOR - 6; y < FLOOR; y++) {
                    blocks.put(new BlockPos(x, y, z), Blocks.NETHERRACK.defaultBlockState());
                }
            }
            for (int z = 1; z <= 3; z++) {
                set(x, FLOOR, z, Blocks.OBSIDIAN.defaultBlockState(), true);
            }
            for (int z : new int[]{0, 4}) {
                set(x, FLOOR, z, Blocks.NETHERRACK.defaultBlockState(), true);
                set(x, FEET, z, Blocks.OBSIDIAN.defaultBlockState(), true);
            }
        }
    }

    private void set(int x, int y, int z, BlockState state, boolean builtBack) {
        blocks.put(new BlockPos(x, y, z), state);
        if (builtBack) {
            refill.add(new BlockPos(x, y, z));
        }
    }

    private void remove(int x, int y, int z) {
        blocks.remove(new BlockPos(x, y, z));
    }

    private static AABB standing(double x, double feetY, double z, double width, double height) {
        return new AABB(x - width / 2, feetY, z - width / 2, x + width / 2, feetY + height, z + width / 2);
    }

    private List<BlockPos> plan(AABB box, int clearY) {
        return EntityHole.plan(world, box, clearY, refill::contains);
    }

    private static Set<BlockPos> cells(int... xyz) {
        Set<BlockPos> set = new HashSet<>();
        for (int i = 0; i < xyz.length; i += 3) {
            set.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        }
        return set;
    }

    @Test
    public void mobInAFloorGapOnlyDigsUnderItself() {
        remove(0, FLOOR, 2);
        List<BlockPos> hole = plan(standing(0.5, FLOOR, 2.5, PIGMAN_WIDTH, PIGMAN_HEIGHT), FLOOR);
        // 1.95 tall has to sink two blocks for its head to clear the floor
        assertEquals(cells(0, FLOOR - 1, 2, 0, FLOOR - 2, 2), new HashSet<>(hole));
    }

    @Test
    public void mobAcrossTwoColumnsDigsBoth() {
        remove(0, FLOOR, 2);
        remove(1, FLOOR, 2);
        List<BlockPos> hole = plan(standing(1.0, FLOOR, 2.5, PIGMAN_WIDTH, PIGMAN_HEIGHT), FLOOR);
        assertEquals(cells(0, FLOOR - 1, 2, 0, FLOOR - 2, 2, 1, FLOOR - 1, 2, 1, FLOOR - 2, 2), new HashSet<>(hole));
    }

    @Test
    public void neighbouringRailSupportsAreLeftAlone() {
        // The rail at x = 0 is missing and the mob stands right in its column, on its support
        remove(0, FEET, 0);
        List<BlockPos> hole = plan(standing(0.5, FEET, 0.5, PIGMAN_WIDTH, PIGMAN_HEIGHT), FEET);
        assertNotNull(hole);
        assertFalse(hole.contains(new BlockPos(-1, FLOOR, 0)));
        assertFalse(hole.contains(new BlockPos(1, FLOOR, 0)));
        // its own support gets built back, so it has to drop below that too
        assertEquals(cells(0, FLOOR, 0, 0, FLOOR - 1, 0, 0, FLOOR - 2, 0), new HashSet<>(hole));
    }

    @Test
    public void mobOnTheFloorBesideARailGapDigsThroughTheFloor() {
        // standing on the lane next to the missing rail, half over each
        remove(0, FEET, 0);
        List<BlockPos> hole = plan(standing(0.5, FEET, 0.9, PIGMAN_WIDTH, PIGMAN_HEIGHT), FEET);
        // the floor and the rail support both get built back, so it has to end up below them
        assertEquals(cells(0, FLOOR, 0, 0, FLOOR - 1, 0, 0, FLOOR - 2, 0,
                0, FLOOR, 1, 0, FLOOR - 1, 1, 0, FLOOR - 2, 1), new HashSet<>(hole));
    }

    @Test
    public void unbreakableBlockMeansNoHole() {
        remove(0, FLOOR, 2);
        set(0, FLOOR - 2, 2, Blocks.BEDROCK.defaultBlockState(), false);
        assertNull(plan(standing(0.5, FLOOR, 2.5, PIGMAN_WIDTH, PIGMAN_HEIGHT), FLOOR));
    }

    @Test
    public void boatSinksOneLayer() {
        for (int x = 0; x <= 1; x++) {
            for (int z = 1; z <= 3; z++) {
                remove(x, FLOOR, z);
            }
        }
        AABB boat = standing(1.0, FLOOR, 2.5, BOAT_WIDTH, BOAT_HEIGHT);
        List<BlockPos> hole = plan(boat, FLOOR);
        assertNotNull(hole);
        assertEquals(6, hole.size()); // 2 by 3 columns
        assertTrue(hole.stream().allMatch(pos -> pos.getY() == FLOOR - 1));

        // a rider makes the whole thing about two blocks taller
        AABB rider = standing(1.0, FLOOR + 0.5, 2.5, PIGMAN_WIDTH, 1.8);
        List<BlockPos> ridden = plan(boat.minmax(rider), FLOOR);
        assertNotNull(ridden);
        assertEquals(18, ridden.size());
        assertEquals(FLOOR - 3, ridden.stream().mapToInt(BlockPos::getY).min().getAsInt());
    }

    @Test
    public void fallingMobOnlyNeedsWhatsLeft() {
        remove(0, FLOOR, 2);
        remove(0, FLOOR - 1, 2);
        List<BlockPos> hole = plan(standing(0.5, FLOOR - 0.6, 2.5, PIGMAN_WIDTH, PIGMAN_HEIGHT), FLOOR);
        assertEquals(cells(0, FLOOR - 1, 2, 0, FLOOR - 2, 2), new HashSet<>(hole));
    }

    @Test
    public void tooTallIsntWorthIt() {
        remove(0, FLOOR, 2);
        assertNull(plan(standing(0.5, FLOOR, 2.5, PIGMAN_WIDTH, 7.0), FLOOR));
    }
}
