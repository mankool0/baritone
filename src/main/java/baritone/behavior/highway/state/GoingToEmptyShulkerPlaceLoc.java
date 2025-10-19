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
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

public class GoingToEmptyShulkerPlaceLoc extends State {
    public GoingToEmptyShulkerPlaceLoc(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.baritone().getCustomGoalProcess().isActive()) {
            return; // Wait to get there
        }

        BlockPos oneBlockAway = context.placeLoc().offset(context.highwayDirection().getX(), 0, context.highwayDirection().getZ());
        BlockPos twoBlocksAway = context.placeLoc().offset(context.highwayDirection().getX() * 2, 0, context.highwayDirection().getZ() * 2);

        if (context.playerContext().playerFeet().equals(twoBlocksAway)) {
            context.baritone().getPathingBehavior().cancelEverything();
            context.transitionTo(HighwayState.PlacingEmptyShulkerSupport);
        } else if (context.playerContext().playerFeet().equals(oneBlockAway)) {
            if (!context.baritone().getBuilderProcess().checkNoEntityCollision(new AABB(context.placeLoc()), null)) {
                // We're blocking, move to 2 blocks away
                Helper.HELPER.logDirect("Player blocking shulker placement, moving to 2 blocks away");
                context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(twoBlocksAway));
            } else {
                context.baritone().getPathingBehavior().cancelEverything();
                context.transitionTo(HighwayState.PlacingEmptyShulkerSupport);
            }
        } else {
            // Not at either position, path to 1 block away first
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(oneBlockAway));
        }
    }
}
