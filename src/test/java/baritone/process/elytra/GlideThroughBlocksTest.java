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

package baritone.process.elytra;

import baritone.test.HeadlessGame;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Which blocks the flight's pathfinder packs as air: the ones the game lets the box straight through and that do the
 * player no harm. Taken as solid, a sign on a road stops the glide dead in front of it.
 */
public class GlideThroughBlocksTest {

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    private static boolean through(BlockState state) {
        return NetherPathfinderContext.glidesThrough(state);
    }

    @Test
    public void signsTorchesRailsAndPlantsAreAir() {
        assertTrue(through(Blocks.AIR.defaultBlockState()));
        assertTrue(through(Blocks.CAVE_AIR.defaultBlockState()));
        assertTrue(through(Blocks.OAK_SIGN.defaultBlockState()));
        assertTrue(through(Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST)));
        assertTrue(through(Blocks.CRIMSON_HANGING_SIGN.defaultBlockState()));
        assertTrue(through(Blocks.WHITE_BANNER.defaultBlockState()));
        assertTrue(through(Blocks.TORCH.defaultBlockState()));
        assertTrue(through(Blocks.SOUL_WALL_TORCH.defaultBlockState()));
        assertTrue(through(Blocks.RAIL.defaultBlockState()));
        assertTrue(through(Blocks.STONE_BUTTON.defaultBlockState()));
        assertTrue(through(Blocks.LEVER.defaultBlockState()));
        assertTrue(through(Blocks.CRIMSON_ROOTS.defaultBlockState()));
        assertTrue(through(Blocks.NETHER_SPROUTS.defaultBlockState()));
        assertTrue(through(Blocks.RED_MUSHROOM.defaultBlockState()));
    }

    @Test
    public void blocksWithABoxOrThatHurtHoldOrCarryOffStaySolid() {
        assertFalse(through(Blocks.NETHERRACK.defaultBlockState()));
        assertFalse(through(Blocks.OBSIDIAN.defaultBlockState()));
        assertFalse(through(Blocks.NETHER_BRICK_FENCE.defaultBlockState()));
        assertFalse(through(Blocks.OAK_SLAB.defaultBlockState()));
        assertFalse(through(Blocks.WHITE_CARPET.defaultBlockState()));
        assertFalse(through(Blocks.LAVA.defaultBlockState()));
        assertFalse(through(Blocks.WATER.defaultBlockState()));
        assertFalse(through(Blocks.FIRE.defaultBlockState()));
        assertFalse(through(Blocks.SOUL_FIRE.defaultBlockState()));
        assertFalse(through(Blocks.NETHER_PORTAL.defaultBlockState()));
        assertFalse(through(Blocks.COBWEB.defaultBlockState()));
        assertFalse(through(Blocks.WEEPING_VINES.defaultBlockState()));
        assertFalse(through(Blocks.TWISTING_VINES_PLANT.defaultBlockState()));
        assertFalse(through(Blocks.LADDER.defaultBlockState()));
        assertFalse(through(Blocks.SCAFFOLDING.defaultBlockState()));
        assertFalse(through(Blocks.SWEET_BERRY_BUSH.defaultBlockState()));
        assertFalse(through(Blocks.WITHER_ROSE.defaultBlockState()));
        assertFalse(through(Blocks.POWDER_SNOW.defaultBlockState()));
        // a sign under water has water in it
        assertFalse(through(Blocks.OAK_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true)));
        assertFalse(through(null));
    }

    @Test
    public void fencesWallsAndClosedGatesReachIntoTheBlockAbove() {
        assertTrue(NetherPathfinderContext.reachesAbove(Blocks.NETHER_BRICK_FENCE.defaultBlockState()));
        assertTrue(NetherPathfinderContext.reachesAbove(Blocks.OAK_FENCE.defaultBlockState()));
        assertTrue(NetherPathfinderContext.reachesAbove(Blocks.COBBLESTONE_WALL.defaultBlockState()));
        assertTrue(NetherPathfinderContext.reachesAbove(Blocks.CRIMSON_FENCE_GATE.defaultBlockState()));
        // an open gate has no box at all: a glide goes through it
        final BlockState open = Blocks.CRIMSON_FENCE_GATE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, true);
        assertFalse(NetherPathfinderContext.reachesAbove(open));
        assertTrue(NetherPathfinderContext.glidesThrough(open));
        // a block or less high
        assertFalse(NetherPathfinderContext.reachesAbove(Blocks.NETHERRACK.defaultBlockState()));
        assertFalse(NetherPathfinderContext.reachesAbove(Blocks.NETHER_BRICK_SLAB.defaultBlockState()));
        assertFalse(NetherPathfinderContext.reachesAbove(Blocks.SOUL_SAND.defaultBlockState()));
        assertFalse(NetherPathfinderContext.reachesAbove(Blocks.AIR.defaultBlockState()));
        assertFalse(NetherPathfinderContext.reachesAbove(Blocks.OAK_SIGN.defaultBlockState()));
        // and both together, as a block update works it out: the fence's own block, and the air over it
        assertTrue(NetherPathfinderContext.glidesThrough(Blocks.AIR.defaultBlockState()));
        assertFalse(NetherPathfinderContext.glidesThrough(Blocks.NETHER_BRICK_FENCE.defaultBlockState()));
    }

    @Test
    public void lavaAndFireBurnAndTheBlocksNextToThemAreKeptOff() {
        assertTrue(NetherPathfinderContext.burns(Blocks.LAVA.defaultBlockState()));
        assertTrue(NetherPathfinderContext.burns(Blocks.FIRE.defaultBlockState()));
        assertTrue(NetherPathfinderContext.burns(Blocks.SOUL_FIRE.defaultBlockState()));
        assertFalse(NetherPathfinderContext.burns(Blocks.MAGMA_BLOCK.defaultBlockState()));
        assertFalse(NetherPathfinderContext.burns(Blocks.NETHERRACK.defaultBlockState()));
        assertFalse(NetherPathfinderContext.burns(Blocks.AIR.defaultBlockState()));

        // a lava pool's block, a fence two across, open air elsewhere
        final java.util.Map<net.minecraft.core.BlockPos, BlockState> blocks = new java.util.HashMap<>();
        blocks.put(new net.minecraft.core.BlockPos(0, 10, 0), Blocks.LAVA.defaultBlockState());
        blocks.put(new net.minecraft.core.BlockPos(5, 10, 0), Blocks.NETHER_BRICK_FENCE.defaultBlockState());
        final net.minecraft.world.level.BlockGetter world = new net.minecraft.world.level.BlockGetter() {
            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(net.minecraft.core.BlockPos pos) {
                return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            }

            @Override
            public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) {
                return this.getBlockState(pos).getFluidState();
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
        final BlockState air = Blocks.AIR.defaultBlockState();
        // over the pool, under it, beside it
        assertTrue(NetherPathfinderContext.solidAt(world, new net.minecraft.core.BlockPos(0, 11, 0), air));
        assertTrue(NetherPathfinderContext.solidAt(world, new net.minecraft.core.BlockPos(0, 9, 0), air));
        assertTrue(NetherPathfinderContext.solidAt(world, new net.minecraft.core.BlockPos(1, 10, 0), air));
        // two off it, or only corner to corner: open
        assertFalse(NetherPathfinderContext.solidAt(world, new net.minecraft.core.BlockPos(0, 12, 0), air));
        assertFalse(NetherPathfinderContext.solidAt(world, new net.minecraft.core.BlockPos(1, 11, 0), air));
        // the top of the fence
        assertTrue(NetherPathfinderContext.solidAt(world, new net.minecraft.core.BlockPos(5, 11, 0), air));
        assertFalse(NetherPathfinderContext.solidAt(world, new net.minecraft.core.BlockPos(5, 12, 0), air));
    }
}
