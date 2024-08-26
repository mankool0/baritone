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
import baritone.api.utils.Helper;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.ShulkerType;

public class ShulkerSearchPathing extends State {
    public ShulkerSearchPathing(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.isShulkerOnGround()) {
            Helper.HELPER.logDirect("Found a missing shulker, going to collection stage.");
            context.baritone().getPathingBehavior().cancelEverything();
            context.transitionTo(HighwayState.ShulkerCollection);
            return;
        }

        if (context.baritone().getCustomGoalProcess().isActive()) {
            return; // Wait to get there
        }

        if (context.playerContext().playerFeet().equals(context.placeLoc())) {
            // We have arrived and no shulkers found, reset start count and go back to building
            Helper.HELPER.logDirect("Mission failure. No shulkers found. Going back to building.");
            context.setStartShulkerCount(context.getShulkerCountInventory(ShulkerType.Any));
            context.transitionTo(HighwayState.Nothing);
            context.resetTimer();
        } else {
            // Keep trying to get there
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(context.placeLoc()));
        }
    }
}
