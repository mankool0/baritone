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

import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

public class LiquidRemovalPrep extends State {
    public LiquidRemovalPrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        Vec3 direction = new Vec3(context.highwayDirection().getX(), context.highwayDirection().getY(), context.highwayDirection().getZ());
        Vec3 curPos = new Vec3(context.playerContext().playerFeet().getX() + (7 * -context.highwayDirection().getX()), context.playerContext().playerFeet().getY(),  context.playerContext().playerFeet().getZ() + (7 * -context.highwayDirection().getZ())); // Go back a bit to clear up our mess
        BlockPos startCheckPos = context.getClosestPoint(new Vec3(context.liqOriginVector().x, context.liqOriginVector().y, context.liqOriginVector().z), direction, curPos, LocationType.ShulkerEchestInteraction);

        BlockPos liquidPos = context.findFirstLiquidGround(startCheckPos, 18, false);

        if (liquidPos == null) {
            context.transitionTo(HighwayState.Nothing); // Nothing found for some reason, shouldn't happen lol
            return;
        }

        context.clearSourceBlocks();
        ArrayList<BlockPos> flowingBlocks = new ArrayList<>();
        context.findSourceLiquid(liquidPos.getX(), liquidPos.getY(), liquidPos.getZ(), new ArrayList<>(), context.sourceBlocks(), flowingBlocks);
        context.sourceBlocks().removeIf(blockPos -> (blockPos.getY() > 123)); // Remove all source blocks above Y 123 as it might be unreachable

        // If sourceBlocks is empty we cleared it in the remove, want to fill in flowing lava at top bedrock level
        if (context.sourceBlocks().isEmpty()) {
            for (BlockPos flowingPos : flowingBlocks) {
                if (flowingPos.getY() == 123) {
                    //Helper.HELPER.logDirect("Lava source Y position too high, ignoring and adding " + flowingPos + " to list");
                    context.sourceBlocks().add(flowingPos);
                }
            }
        }

        //int sizeBeforeRemove = sourceBlocks.size();
        //sourceBlocks.removeIf(this::isLiquidCoveredAllSides); // Remove all liquids that are surrounded by blocks
        //if (sizeBeforeRemove >= 1 && sourceBlocks.size() == 0) {
        // All source blocks are covered aka we will do another removal when we uncover them
        //    currentState = State.Nothing;
        //    return;
        //}

        if (context.firstStartingPos() != null &&
                ((context.highwayDirection().getZ() == -1 && liquidPos.getZ() > context.playerContext().playerFeet().getZ()) || // NW, N, NE
                        (context.highwayDirection().getZ() == 1 && liquidPos.getZ() < context.playerContext().playerFeet().getZ()) || // SE, S, SW
                        (context.highwayDirection().getX() == -1 && context.highwayDirection().getZ() == 0 && liquidPos.getX() > context.playerContext().playerFeet().getX()) || // W
                        (context.highwayDirection().getX() == 1 && context.highwayDirection().getZ() == 0 && liquidPos.getX() < context.playerContext().playerFeet().getX()))) { // E
            curPos = new Vec3(context.playerContext().playerFeet().getX() + (7 * context.highwayDirection().getX()), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ() + (7 * context.highwayDirection().getZ()));
        }

        context.setPlaceLoc(context.getClosestPoint(new Vec3(context.backPathOriginVector().x, context.backPathOriginVector().y, context.backPathOriginVector().z), new Vec3(context.highwayDirection().getX(), context.highwayDirection().getY(), context.highwayDirection().getZ()), curPos, LocationType.ShulkerEchestInteraction));
        // Get the closest point
        if (!context.sourceBlocks().isEmpty()) {
            context.baritone().getPathingBehavior().cancelEverything();
            context.transitionTo(HighwayState.LiquidRemovalPathingBack);
        } else {
            context.transitionTo(HighwayState.LiquidRemovalPrepWait);
        }

        context.resetTimer();
    }
}
