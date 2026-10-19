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
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An end crystal on an obsidian floor at {@link #FLOOR}, going off at its base, and a player standing on the same
 * floor along +X from it.
 */
public class ExplosionExposureTest {

    private static final int FLOOR = 119;
    private static final Vec3 BLAST = new Vec3(0.5, FLOOR + 1, 0.5);

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
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
    public void buildFloor() {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                blocks.put(new BlockPos(x, FLOOR, z), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
    }

    /** A standing player's box with its feet {@code distance} out along +X from the crystal. */
    private static AABB standing(double distance) {
        double x = BLAST.x + distance;
        return new AABB(x - 0.3, FLOOR + 1, BLAST.z - 0.3, x + 0.3, FLOOR + 2.8, BLAST.z + 0.3);
    }

    @Test
    public void theFloorItSitsOnHidesNothing() {
        assertEquals(1.0f, ExplosionExposure.seenPercent(world, BLAST, standing(3.8)), 0);
    }

    @Test
    public void aBlockAtOurFeetHidesMostOfUs() {
        blocks.put(new BlockPos(3, FLOOR + 1, 0), Blocks.NETHERRACK.defaultBlockState());
        float exposure = ExplosionExposure.seenPercent(world, BLAST, standing(3.8));
        assertTrue("exposure " + exposure, exposure <= 0.25f);
    }

    @Test
    public void twoHighHidesAllOfUs() {
        blocks.put(new BlockPos(3, FLOOR + 1, 0), Blocks.NETHERRACK.defaultBlockState());
        blocks.put(new BlockPos(3, FLOOR + 2, 0), Blocks.NETHERRACK.defaultBlockState());
        assertEquals(0.0f, ExplosionExposure.seenPercent(world, BLAST, standing(3.8)), 0);
    }

    @Test
    public void coverNotPlacedYetCountsTheSame() {
        BlockPos cover = new BlockPos(3, FLOOR + 1, 0);
        float planned = ExplosionExposure.seenPercent(ExplosionExposure.withBlocks(world, Set.of(cover), Blocks.NETHERRACK.defaultBlockState()), BLAST, standing(3.8));
        blocks.put(cover, Blocks.NETHERRACK.defaultBlockState());
        assertEquals(ExplosionExposure.seenPercent(world, BLAST, standing(3.8)), planned, 0);
    }
}
