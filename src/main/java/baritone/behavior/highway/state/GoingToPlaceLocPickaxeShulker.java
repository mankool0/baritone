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

public class GoingToPlaceLocPickaxeShulker extends State {
    public GoingToPlaceLocPickaxeShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.baritone().getCustomGoalProcess().isActive()) {
            return; // Wait to get there
        }

        if (context.getPickCountInventory() >= context.picksToHave() || context.getShulkerSlot(context.picksToUse()) == -1) {
            // We have enough picks, or no more pickaxe shulker
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        //if (ctx.playerFeet().getX() == placeLoc.getX() && ctx.playerFeet().getY() == placeLoc.getY() && ctx.playerFeet().getZ() == (placeLoc.getZ() - 1)) {
        if (context.playerContext().playerFeet().equals(context.placeLoc().offset(context.highwayDirection().getX(), 0, context.highwayDirection().getZ()))) {
            // We have arrived
            context.transitionTo(HighwayState.PlacingPickaxeShulker);
            context.resetTimer();
        } else {
            // Keep trying to get there
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(context.placeLoc().offset(context.highwayDirection().getX(), 0, context.highwayDirection().getZ())));
        }
    }
}
