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
import baritone.api.schematic.ISchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;


public class Nothing extends State {
    public Nothing(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        // Never (re)start the builder while we're underneath the highway (e.g. down the hole dug to
        // drop a boat through): the printer would pave the floor right over our head and seal us
        // underneath. Climb back onto the road first.
        if (context.settings().highwayFallRecovery.value && context.belowBuiltHighway()) {
            BetterBlockPos target = context.computeRecoveryTarget();
            if (target != null) {
                Helper.HELPER.logDirect("Below the highway level (y=" + context.playerContext().playerFeet().y + "), returning to " + target + " before building.");
                context.resetRecovery();
                context.setRecoveryTarget(target);
                context.baritone().getInputOverrideHandler().clearAllKeys();
                context.baritone().getPathingBehavior().cancelEverything();
                context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(target));
                context.transitionTo(HighwayState.FallRecovery);
                return;
            }
        }
        context.resetTimer();
        Helper.HELPER.logDirect("Starting highway build");

        context.settings().buildRepeat.value = new Vec3i(context.highwayDirection().getX(), 0, context.highwayDirection().getZ());

        Vec3 origin = new Vec3(context.originVector().x, context.originVector().y, context.originVector().z);
        Vec3 direction = new Vec3(context.highwayDirection().getX(), context.highwayDirection().getY(), context.highwayDirection().getZ());
        Vec3 curPos = new Vec3(context.playerContext().playerFeet().getX() + (context.highwayCheckBackDistance() * -context.highwayDirection().getX()), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ() + (context.highwayCheckBackDistance() * -context.highwayDirection().getZ())); // Go back a bit to clear up our mess
        BetterBlockPos buildStart = context.getClosestPoint(origin, direction, curPos, LocationType.HighwayBuild);
        BlockPos fixStart = context.invalidBlockFixScanStart();
        if (context.invalidBlockFixActive() && fixStart != null && context.stepsAlongHighway(fixStart, buildStart) > 0) {
            buildStart = new BetterBlockPos(fixStart);
        }
        context.setOriginBuild(buildStart);

        if (context.endPos() != null) {
            // Bound the repeat so the builder never places past the end slice; originBuild is
            // clamped to the end, so this is always >= 1
            context.settings().buildRepeatCount.value = context.stepsAlongHighway(context.originBuild(), context.endPos()) + 1;
        } else {
            context.settings().buildRepeatCount.value = -1;
        }

        context.baritone().getPathingBehavior().cancelEverything();

        // The context keeps the single-slice schematic that the correctness scans size themselves
        // against; the builder gets several slices at once so the printer always has targets.
        ISchematic buildSchem = context.schematic();
        BlockPos buildOrigin = context.originBuild();
        int lookahead = context.settings().highwayPrinterLookahead.value;
        int slicesToEnd = context.settings().buildRepeatCount.value;
        boolean nearEnd = slicesToEnd != -1 && slicesToEnd <= context.highwayCheckBackDistance() + 2 * lookahead + 2;
        if (context.settings().printer.value && lookahead > 1 && !nearEnd) {
            int stepX = context.highwayDirection().getX();
            int stepZ = context.highwayDirection().getZ();
            buildSchem = context.schematic().repeated(stepX, 0, stepZ, lookahead);
            buildOrigin = buildOrigin.offset(stepX < 0 ? (lookahead - 1) * stepX : 0, 0, stepZ < 0 ? (lookahead - 1) * stepZ : 0);
            // the builder only repeats once the whole region is correct, so advance by the full
            // window width or each repeat would just re-expose slices it already built
            context.settings().buildRepeat.value = new Vec3i(stepX * lookahead, 0, stepZ * lookahead);
            if (slicesToEnd != -1) {
                context.settings().buildRepeatCount.value = slicesToEnd / lookahead;
            }
        }
        context.baritone().getBuilderProcess().build("netherHighway", buildSchem, buildOrigin);

        context.transitionTo(HighwayState.BuildingHighway);
    }
}
