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

import baritone.process.BuilderProcess.GoalAdjacent;
import baritone.process.BuilderProcess.GoalStepOut;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A wall cell to fill with the bot standing in it: the goal it gets has to be somewhere its 0.6-wide
 * body can't reach into the cell from, and only at the two levels that cell is walked out of.
 */
public class PlacementGoalTest {

    private static final BlockPos CELL = new BlockPos(10, 120, -5);

    @Test
    public void stepOutIsTwoCellsOffAtTheCellsLevelOrOneUnder() {
        GoalStepOut goal = new GoalStepOut(CELL);
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    boolean want = (dy == 0 || dy == -1) && Math.max(Math.abs(dx), Math.abs(dz)) >= 2;
                    String at = dx + "," + dy + "," + dz;
                    assertEquals(at, want, goal.isInGoal(CELL.getX() + dx, CELL.getY() + dy, CELL.getZ() + dz));
                }
            }
        }
    }

    @Test
    public void stepOutHeuristicIsZeroInTheGoalAndFallsTowardIt() {
        GoalStepOut goal = new GoalStepOut(CELL);
        int x = CELL.getX(), y = CELL.getY(), z = CELL.getZ();
        assertEquals(0, goal.heuristic(x + 2, y, z), 0);
        assertEquals(0, goal.heuristic(x - 3, y - 1, z + 1), 0);
        assertTrue(goal.heuristic(x, y, z) > goal.heuristic(x + 1, y, z));
        assertTrue(goal.heuristic(x + 1, y, z) > goal.heuristic(x + 2, y, z));
        // digging down two out of the way is no shortcut
        assertTrue(goal.heuristic(x, y - 2, z) > 0);
        assertTrue(goal.heuristic(x + 2, y - 2, z) > 0);
    }

    @Test
    public void stepOutGoalsOfOneCellAreEqual() {
        // FORCE_REVALIDATE_GOAL_AND_PATH drops a path whose goal isn't equal to the new one
        assertEquals(new GoalStepOut(CELL), new GoalStepOut(new BlockPos(CELL.getX(), CELL.getY(), CELL.getZ())));
        assertEquals(new GoalStepOut(CELL).hashCode(), new GoalStepOut(new BlockPos(CELL.getX(), CELL.getY(), CELL.getZ())).hashCode());
    }

    @Test
    public void adjacentNeverPutsOurHeadInTheCell() {
        // a block over the cell lets it be placed from a level under, head beside it
        // against the side, so it isn't `no` that rules out the cell under
        GoalAdjacent goal = new GoalAdjacent(CELL, CELL.west(), true);
        assertFalse(goal.isInGoal(CELL.getX(), CELL.getY() - 1, CELL.getZ()));
        assertTrue(goal.isInGoal(CELL.getX() + 1, CELL.getY() - 1, CELL.getZ()));
        assertTrue(goal.isInGoal(CELL.getX(), CELL.getY(), CELL.getZ() + 1));
        assertFalse(goal.isInGoal(CELL.getX(), CELL.getY(), CELL.getZ()));
    }
}
