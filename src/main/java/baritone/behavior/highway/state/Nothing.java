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
        Vec3 curPos = new Vec3(context.playerContext().playerFeet().getX(), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ());
        // Start a bit behind us to clear up our own mess, counted in slices so the restart point
        // lands where the correctness scan starts on an angled pattern too
        BetterBlockPos buildStart = context.sliceAlongLine(context.originVector(), curPos, -context.highwayCheckBackDistance(), LocationType.HighwayBuild);
        BlockPos fixStart = context.invalidBlockFixScanStart();
        if (context.invalidBlockFixActive() && fixStart != null && context.stepsAlongHighway(fixStart, buildStart) > 0) {
            buildStart = new BetterBlockPos(fixStart);
        }

        // A join hands us the inventory well before the chunks around us. Started then, the builder
        // reads the not-yet-received stretch between the restart point and us as unbuilt and walks
        // back to it, only to walk forward again once it sees from up close that it was done.
        if (!context.isLoadedUpToFeet(buildStart)) {
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

        context.settings().buildRepeat.value = new Vec3i(context.highwayDirection().getX(), 0, context.highwayDirection().getZ());

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
        BlockPos buildOrigin = context.originBuild();
        ISchematic buildSchem = context.schematicForOrigin(buildOrigin);
        int lookahead = context.settings().highwayPrinterLookahead.value;
        int slicesToEnd = context.settings().buildRepeatCount.value;
        boolean nearEnd = slicesToEnd != -1 && slicesToEnd <= context.highwayCheckBackDistance() + 2 * lookahead + 2;
        // A custom-pattern build repeats one slice at a time: a straight lookahead window can't
        // follow the jogs, and its internal shape would depend on the pattern phase.
        if (context.settings().printer.value && lookahead > 1 && !nearEnd && !context.pattern().isCustom()) {
            int stepX = context.highwayDirection().getX();
            int stepZ = context.highwayDirection().getZ();
            LookaheadWindow window = LookaheadWindow.of(context.schematic(), stepX, stepZ, lookahead);
            buildSchem = window.schematic();
            buildOrigin = buildOrigin.offset(window.originShift());
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
