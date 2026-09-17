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

import baritone.api.utils.Helper;
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
        Vec3 feetPos = new Vec3(context.playerContext().playerFeet().getX(), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ());
        // Start a bit behind us to clear up our own mess, and scan far enough forward to always
        // re-find whatever the detection scan flagged - both counted in slices
        BlockPos startCheckPos = context.sliceAlongLine(context.liqOriginVector(), feetPos, -HighwayContext.LIQUID_SCAN_BACK_SLICES, LocationType.ShulkerEchestInteraction);

        BlockPos liquidPos = context.findFirstLiquidGround(startCheckPos, context.liquidScanWindowSlices(), false);

        if (liquidPos == null) {
            Helper.HELPER.logDebug("findFirstLiquidGround Failed. Going back to state Nothing");
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
                    Helper.HELPER.logDebug("Lava source Y position too high, ignoring and adding " + flowingPos + " to list");
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

        // Retreat away from the lava: back down the highway normally, forward when the lava is the
        // stuff we already walked past. Both positions go onto the liquid line first and the
        // comparison counts slices: a raw axis compare reads the far side of the cross-section as
        // behind us on a diagonal, and on an angled pattern - whose minor axis crawls a third of a
        // block per slice - it does that to lava a dozen slices ahead, sending us into it.
        int retreatSlices = -HighwayContext.LIQUID_SCAN_BACK_SLICES;
        if (context.firstStartingPos() != null) {
            BlockPos feetOnLiqLine = context.getClosestPoint(context.liqOriginVector(), direction, feetPos, LocationType.ShulkerEchestInteraction);
            BlockPos liquidOnLiqLine = context.getClosestPoint(context.liqOriginVector(), direction,
                    new Vec3(liquidPos.getX(), liquidPos.getY(), liquidPos.getZ()), LocationType.ShulkerEchestInteraction);
            if (context.stepsAlongHighway(feetOnLiqLine, liquidOnLiqLine) < 0) {
                retreatSlices = HighwayContext.LIQUID_SCAN_BACK_SLICES;
            }
        }

        context.setPlaceLoc(context.liftOntoPavement(context.sliceAlongLine(context.backPathOriginVector(), feetPos, retreatSlices, LocationType.ShulkerEchestInteraction)));
        // Get the closest point
        if (!context.sourceBlocks().isEmpty()) {
            context.baritone().getPathingBehavior().cancelEverything();
            if (context.liquidThroughWalls()) {
                // Fill it from here through the cover, no retreat and no gapple. This used to be
                // limited to pools that were still sealed; the approach now places into the lava
                // it has to dig past and into any cell it could step into, so an open pool is no
                // more exposure than a sealed one. If something does breach anyway, Pathing's
                // caught-fire bail goes and eats a gapple, then comes back here.
                Helper.HELPER.logDebug("Filling liquids through cover");
                context.transitionTo(HighwayState.LiquidRemovalPathing);
            } else {
                context.transitionTo(HighwayState.LiquidRemovalPathingBack);
            }
        } else {
            context.transitionTo(HighwayState.LiquidRemovalPrepWait);
        }

        context.resetTimer();
    }
}
