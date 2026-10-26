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
import baritone.behavior.highway.LookaheadWindow;
import baritone.behavior.highway.NetherHighwayBuilderBehavior;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;


public class Nothing extends State {
    // Bounds the wait for the stretch behind us to load, so a stretch that never does can't hold
    // the restart forever
    private static final int LOAD_WAIT_TICKS = 200;

    private int loadWaitTicks = 0;

    public Nothing(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        // Never (re)start the builder while we're underneath the highway (e.g. down the hole dug to
        // drop a boat through): the printer would pave the floor right over our head and seal us
        // underneath. Climb back onto the road first.
        if (context.settings().highwayFallRecovery.value && context.recovery().belowBuiltHighway()) {
            BetterBlockPos target = context.recovery().computeRecoveryTarget();
            if (target != null) {
                Helper.HELPER.logDirect("Below the highway level (y=" + context.playerContext().playerFeet().y + "), returning to " + target + " before building.");
                context.recovery().resetRecovery();
                context.recovery().setRecoveryTarget(target);
                context.baritone().getInputOverrideHandler().clearAllKeys();
                context.baritone().getPathingBehavior().cancelEverything();
                context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(target));
                context.transitionTo(HighwayState.FallRecovery);
                return;
            }
        }
        Vec3 curPos = new Vec3(context.playerContext().playerFeet().getX(), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ());
        // Start a bit behind us to clear up our own mess, counted in slices so the restart point
        // lands where the correctness scan starts on an angled pattern too
        BetterBlockPos buildStart = context.layout().sliceAlongLine(context.layout().originVector(), curPos, -context.layout().highwayCheckBackDistance(), LocationType.HighwayBuild);
        BlockPos fixStart = context.invalidBlockFixScanStart();
        if (context.invalidBlockFixActive() && fixStart != null && context.layout().stepsAlongHighway(fixStart, buildStart) > 0) {
            buildStart = new BetterBlockPos(fixStart);
        }

        // A join hands us the inventory well before the chunks around us. Started then, the builder
        // reads the not-yet-received stretch between the restart point and us as unbuilt and walks
        // back to it, only to walk forward again once it sees from up close that it was done.
        if (!context.scanner().isLoadedUpToFeet(buildStart)) {
            if (loadWaitTicks == 0) {
                Helper.HELPER.logDirect("Waiting for the highway behind us to load before restarting the builder.");
            }
            if (loadWaitTicks < LOAD_WAIT_TICKS) {
                loadWaitTicks++;
                return;
            }
            Helper.HELPER.logDirect("The highway behind us still isn't loaded after " + LOAD_WAIT_TICKS + " ticks, restarting the builder anyway.");
        }

        context.resetTimer();
        Helper.HELPER.logDirect("Starting highway build");

        context.settings().buildRepeat.value = new Vec3i(context.layout().highwayDirection().getX(), 0, context.layout().highwayDirection().getZ());

        context.layout().setOriginBuild(buildStart);

        if (context.layout().endPos() != null) {
            // Bound the repeat so the builder never places past the end slice; originBuild is
            // clamped to the end, so this is always >= 1
            context.settings().buildRepeatCount.value = context.layout().stepsAlongHighway(context.layout().originBuild(), context.layout().endPos()) + 1;
        } else {
            context.settings().buildRepeatCount.value = -1;
        }

        context.baritone().getPathingBehavior().cancelEverything();

        // The context keeps the single-slice schematic that the correctness scans size themselves
        // against; the builder gets several slices at once so the printer always has targets.
        BlockPos buildOrigin = context.layout().originBuild();
        ISchematic buildSchem = context.layout().schematicForOrigin(buildOrigin);
        int lookahead = context.settings().highwayPrinterLookahead.value;
        int slicesToEnd = context.settings().buildRepeatCount.value;
        // The windows stop short of the end, leaving the last slices to a single-slice restart once
        // we're close. On a stretch that's already built nothing walks us that close: the travel
        // front parks us short of the first unbuilt cell, which can be in those very slices, so a
        // stall short of the end goes over to single slices as well.
        boolean nearEnd = slicesToEnd != -1 && (slicesToEnd <= context.layout().highwayCheckBackDistance() + 2 * lookahead + 2
                || context.endTracker().endStallRestarted());
        // A custom-pattern build repeats one slice at a time: a straight lookahead window can't
        // follow the jogs, and its internal shape would depend on the pattern phase.
        if (context.settings().printer.value && lookahead > 1 && !nearEnd && !context.layout().pattern().isCustom()) {
            int stepX = context.layout().highwayDirection().getX();
            int stepZ = context.layout().highwayDirection().getZ();
            // digging has no rails to place, and digs don't care which way the face looks
            int laneLead = context.layout().paving() && NetherHighwayBuilderBehavior.hasRails(context.settings()) ? LookaheadWindow.PAVING_LANE_LEAD : 0;
            LookaheadWindow window = LookaheadWindow.of(context.layout().schematic(), stepX, stepZ, lookahead,
                    laneLead, context.settings().highwayWidth.value);
            buildOrigin = buildOrigin.offset(window.originShift());
            buildSchem = context.layout().bindCellOverrides(window.schematic(), buildOrigin);
            // the builder only repeats once the whole region is correct, so advance by the full
            // window width or each repeat would just re-expose slices it already built
            context.settings().buildRepeat.value = new Vec3i(stepX * lookahead, 0, stepZ * lookahead);
            if (slicesToEnd != -1) {
                // the slices the last window reaches into have to stop at the end slice as well
                context.settings().buildRepeatCount.value = (slicesToEnd - window.extraSlices()) / lookahead;
            }
        }
        context.baritone().getBuilderProcess().build("netherHighway", buildSchem, buildOrigin);

        context.transitionTo(HighwayState.BuildingHighway);
    }
}
