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

package baritone.behavior.highway.state;

import baritone.api.pathing.goals.GoalBlock;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.Vec3i;

public class GoingToPlaceLocEnderChest extends State {
    public GoingToPlaceLocEnderChest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.baritone().getCustomGoalProcess().isActive()) {
            return; // Wait for us to reach the goal
        }

        if (context.playerContext().playerFeet().getX() == context.placeLoc().getX() && context.playerContext().playerFeet().getY() == context.placeLoc().getY() && context.playerContext().playerFeet().getZ() == (context.placeLoc().getZ() - 2)) {
            // We have arrived
            context.baritone().getPathingBehavior().cancelEverything();
            context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
            context.resetTimer();
            context.setInstantMineOriginalOffhandItem(context.playerContext().player().getOffhandItem().getItem());
            context.transitionTo(HighwayState.FarmingEnderChestPrepEchest);
        } else {
            // Keep trying to get there
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(context.placeLoc().getX(), context.placeLoc().getY(), context.placeLoc().getZ() - 2));
        }
    }
}
