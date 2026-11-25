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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.Rotation;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.test.HeadlessClient;
import baritone.utils.PrinterClicks;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Backfill puts a block back facing the way the broken one did: basalt's axis comes from the face
 * clicked, so an upright one has to go against the block above or below, never a wall.
 */
public class BackfillPlacementTest {

    private static final Rotation ALONG_TUNNEL = new Rotation(-90, 0); // facing +X
    private static final BlockPos LOWER = new BlockPos(3, 0, 0);
    private static final BlockPos UPPER = new BlockPos(3, 1, 0);

    private HeadlessClient client;
    private Baritone baritone;
    private BackfillProcess backfill;
    private BlockState upright;

    @Before
    public void setUp() {
        client = HeadlessClient.get();
        baritone = client.baritone();
        backfill = new BackfillProcess(baritone);
        client.clearWorld();
        upright = Blocks.BASALT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        // a 1x2 tunnel along +X through upright basalt, standing near its start
        for (int x = -2; x <= 8; x++) {
            for (int y = -2; y <= 4; y++) {
                for (int z = -2; z <= 2; z++) {
                    boolean tunnel = z == 0 && (y == 0 || y == 1) && x >= 0 && x <= 5;
                    client.setBlock(new BlockPos(x, y, z), tunnel ? Blocks.AIR.defaultBlockState() : upright);
                }
            }
        }
        client.moveTo(new BlockPos(1, 0, 0));
        baritone.getPlayerContext().player().getInventory().setItem(0, new ItemStack(Items.BASALT, 64));
    }

    @Test
    public void theLowerHoleGoesOnTheFloorUpright() throws Exception {
        BlockHitResult hit = click(LOWER, upright, Blocks.BASALT, true);
        assertNotNull(hit);
        assertEquals(Direction.UP, hit.getDirection());
        assertEquals(LOWER.below(), hit.getBlockPos());
        assertEquals(Direction.Axis.Y, placed(hit).getValue(RotatedPillarBlock.AXIS));
    }

    @Test
    public void theUpperHoleGoesUnderTheCeilingUprightWhileTheLowerOneIsOpen() throws Exception {
        BlockHitResult hit = click(UPPER, upright, Blocks.BASALT, true);
        assertNotNull(hit);
        assertEquals(Direction.DOWN, hit.getDirection());
        assertEquals(UPPER.above(), hit.getBlockPos());
        assertEquals(Direction.Axis.Y, placed(hit).getValue(RotatedPillarBlock.AXIS));
    }

    @Test
    public void withOnlyWallsToClickItWontPlaceItSidewaysUnlessAnyFacingWillDo() throws Exception {
        client.setBlock(UPPER.above(), Blocks.AIR.defaultBlockState()); // no ceiling, and the lower hole is open
        assertNull(click(UPPER, upright, Blocks.BASALT, true));
        BlockHitResult sideways = click(UPPER, upright, Blocks.BASALT, false);
        assertNotNull(sideways);
        assertNotEquals(Direction.Axis.Y, placed(sideways).getValue(RotatedPillarBlock.AXIS));
    }

    @Test
    public void anOreHoleGetsTheRockAroundItWhenTheOreIsntCarried() throws Exception {
        BlockPos ore = new BlockPos(20, 0, 20);
        for (Direction d : Direction.values()) {
            client.setBlock(ore.relative(d), Blocks.NETHERRACK.defaultBlockState());
        }
        List<?> wants = wants(ore, Blocks.NETHER_QUARTZ_ORE.defaultBlockState());
        assertEquals(Blocks.NETHER_QUARTZ_ORE, block(wants.get(0)));
        assertEquals(Blocks.NETHERRACK, block(wants.get(1)));
    }

    @Test
    public void aFenceHoleOnlyEverGetsAFence() throws Exception {
        BlockPos fence = new BlockPos(20, 0, 20);
        for (Direction d : Direction.values()) {
            client.setBlock(fence.relative(d), Blocks.NETHER_BRICKS.defaultBlockState());
        }
        List<?> wants = wants(fence, Blocks.NETHER_BRICK_FENCE.defaultBlockState());
        assertEquals(1, wants.size());
        assertEquals(Blocks.NETHER_BRICK_FENCE, block(wants.get(0)));
    }

    @Test
    public void aMovementWaitsForBackfillToPutBackOneOfItsHolesAndFailsAnywhereElse() {
        BackfillProcess registered = baritone.getBackfillProcess();
        boolean wasOn = Baritone.settings().backfill.value;
        Baritone.settings().backfill.value = true;
        try {
            registered.blocksToReplace.put(LOWER, upright);
            MovementState onHole = new MovementState();
            assertTrue(MovementHelper.backfillHoldsPlacing(onHole, baritone, LOWER));
            assertNotEquals(MovementStatus.UNREACHABLE, onHole.getStatus());

            MovementState elsewhere = new MovementState();
            assertTrue(MovementHelper.backfillHoldsPlacing(elsewhere, baritone, new BlockPos(30, 0, 0)));
            assertEquals(MovementStatus.UNREACHABLE, elsewhere.getStatus());
        } finally {
            Baritone.settings().backfill.value = wasOn;
            registered.blocksToReplace.clear();
        }
        assertFalse(MovementHelper.backfillHoldsPlacing(new MovementState(), baritone, LOWER));
    }

    private BlockState placed(BlockHitResult hit) {
        ItemStack stack = baritone.getPlayerContext().player().getInventory().getItem(0);
        return PrinterClicks.wouldPlace(baritone.getPlayerContext(), stack, hit, ALONG_TUNNEL);
    }

    private BlockHitResult click(BlockPos pos, BlockState was, Block block, boolean sameFacing) throws Exception {
        Class<?> wantType = Class.forName("baritone.process.BackfillProcess$Want");
        Constructor<?> want = wantType.getDeclaredConstructor(Block.class, boolean.class);
        want.setAccessible(true);
        Method method = BackfillProcess.class.getDeclaredMethod("clickWithoutTurning", BlockPos.class, BlockState.class,
                wantType, int.class, Vec3.class, Vec3.class, double.class, Rotation.class);
        method.setAccessible(true);
        Vec3 feet = baritone.getPlayerContext().player().position();
        Object click = method.invoke(backfill, pos, was, want.newInstance(block, sameFacing), 0, feet, feet, 4.5 * 4.5, ALONG_TUNNEL);
        if (click == null) {
            return null;
        }
        Method hit = click.getClass().getDeclaredMethod("hit");
        hit.setAccessible(true);
        return (BlockHitResult) hit.invoke(click);
    }

    private List<?> wants(BlockPos pos, BlockState was) throws Exception {
        Method method = BackfillProcess.class.getDeclaredMethod("wants", BlockPos.class, BlockState.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(backfill, pos, was);
    }

    private static Block block(Object want) throws Exception {
        Method block = want.getClass().getDeclaredMethod("block");
        block.setAccessible(true);
        return (Block) block.invoke(want);
    }
}
