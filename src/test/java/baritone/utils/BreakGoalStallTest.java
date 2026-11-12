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

import baritone.api.utils.BetterBlockPos;
import baritone.test.HeadlessGame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A wall sign in a floor cell that wants obsidian, hung on the block east of it, with the bot
 * standing on that block where nothing of the sign shows.
 */
public class BreakGoalStallTest {

    private static final BlockPos SIGN = new BlockPos(0, 64, 0);
    private static final BlockPos ON_SUPPORT = SIGN.east().above();
    private static final BlockPos NORTH_SIDE = SIGN.above().north();
    private static final BlockPos SOUTH_SIDE = SIGN.above().south();

    private static BlockState sign;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
        sign = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.WEST);
    }

    /** {@code ticks} builder ticks that each got as far as the check; whether the last one stalled. */
    private static boolean run(BreakGoalStall stall, int ticks, boolean pinned) {
        boolean stalled = false;
        for (int i = 0; i < ticks; i++) {
            stall.startTick();
            stalled = stall.pinned(pinned);
        }
        return stalled;
    }

    @Test
    public void stallsOnTheFortiethPinnedTick() {
        BreakGoalStall stall = new BreakGoalStall();
        assertFalse(run(stall, BreakGoalStall.STALL_TICKS - 1, true));
        assertTrue(run(stall, 1, true));
        // and starts over
        assertFalse(run(stall, BreakGoalStall.STALL_TICKS - 1, true));
        assertTrue(run(stall, 1, true));
    }

    @Test
    public void aTickWithSomethingInReachStartsOver() {
        BreakGoalStall stall = new BreakGoalStall();
        run(stall, BreakGoalStall.STALL_TICKS - 1, true);
        run(stall, 1, false);
        assertFalse(run(stall, BreakGoalStall.STALL_TICKS - 1, true));
        assertTrue(run(stall, 1, true));
    }

    @Test
    public void aTickThatReturnedEarlyStartsOver() {
        BreakGoalStall stall = new BreakGoalStall();
        run(stall, BreakGoalStall.STALL_TICKS - 1, true);
        stall.startTick(); // broke or placed something, and never got to the check
        assertFalse(run(stall, BreakGoalStall.STALL_TICKS - 1, true));
        assertTrue(run(stall, 1, true));
    }

    @Test
    public void strikesSpotsOffOneBlockAtATime() {
        BreakGoalStall stall = new BreakGoalStall();
        assertTrue(stall.isEmpty());
        assertEquals(1, stall.strike(SIGN, sign, ON_SUPPORT));
        assertEquals(1, stall.strike(SIGN, sign, ON_SUPPORT)); // the same spot twice is one spot
        assertTrue(stall.struckSpots(SIGN).contains(ON_SUPPORT.asLong()));
        assertFalse(stall.struckSpots(SIGN).contains(NORTH_SIDE.asLong()));
        assertFalse(stall.struckSpots(SIGN.above()).contains(ON_SUPPORT.asLong()));
        assertEquals(2, stall.strike(SIGN, sign, NORTH_SIDE));
        assertEquals(BreakGoalStall.SPOTS_PER_BLOCK, stall.strike(SIGN, sign, SOUTH_SIDE));
        assertEquals(Set.of(ON_SUPPORT.asLong(), NORTH_SIDE.asLong(), SOUTH_SIDE.asLong()), stall.struckSpots(SIGN));
        assertEquals(Set.of(SIGN.asLong()), stall.struckBlocks());
    }

    @Test
    public void betterBlockPosesAreTheSamePlaces() {
        // BetterBlockPos hashes differently from BlockPos, and the builder hands over both
        BreakGoalStall stall = new BreakGoalStall();
        stall.strike(new BetterBlockPos(SIGN), sign, new BetterBlockPos(ON_SUPPORT));
        assertEquals(1, stall.struckCount(SIGN));
        assertEquals(2, stall.strike(SIGN, sign, NORTH_SIDE));
        assertTrue(stall.struckSpots(new BetterBlockPos(SIGN)).contains(new BetterBlockPos(ON_SUPPORT).asLong()));
    }

    @Test
    public void struckSpotsAreACopy() {
        BreakGoalStall stall = new BreakGoalStall();
        stall.strike(SIGN, sign, ON_SUPPORT);
        Set<Long> handedToThePath = stall.struckSpots(SIGN);
        stall.strike(SIGN, sign, NORTH_SIDE);
        assertEquals(1, handedToThePath.size());
        assertTrue(stall.struckSpots(SIGN.above()).isEmpty());
    }

    @Test
    public void forgetsABlockThatBrokeOrChanged() {
        BreakGoalStall stall = new BreakGoalStall();
        BlockPos other = SIGN.west();
        BlockState netherrack = Blocks.NETHERRACK.defaultBlockState();
        stall.strike(SIGN, sign, ON_SUPPORT);
        stall.strike(other, netherrack, ON_SUPPORT);
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(SIGN, sign);
        world.put(other, netherrack);

        stall.forgetChanged(pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState()));
        assertEquals(2, stall.struckBlocks().size());

        world.put(SIGN, Blocks.AIR.defaultBlockState()); // broken at last
        stall.forgetChanged(pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState()));
        assertEquals(Set.of(other.asLong()), stall.struckBlocks());

        world.put(other, Blocks.OBSIDIAN.defaultBlockState()); // a different job now
        stall.forgetChanged(pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState()));
        assertTrue(stall.isEmpty());
    }

    @Test
    public void clearForgetsEverything() {
        BreakGoalStall stall = new BreakGoalStall();
        stall.strike(SIGN, sign, ON_SUPPORT);
        run(stall, BreakGoalStall.STALL_TICKS - 1, true);
        stall.clear();
        assertTrue(stall.isEmpty());
        assertEquals(0, stall.struckCount(SIGN));
        assertFalse(run(stall, BreakGoalStall.STALL_TICKS - 1, true));
    }
}
