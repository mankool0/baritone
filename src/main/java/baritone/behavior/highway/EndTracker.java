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

package baritone.behavior.highway;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockUtils;
import baritone.api.utils.Helper;
import baritone.behavior.highway.enums.HighwayBlockState;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Getting to the end of a bounded build: walking up stretches that are already built, and what to
 * do when the builder runs out of work short of the end.
 */
public class EndTracker extends ContextComponent {
    private static final int TRAVEL_STALL_TICKS = 200; // a walk that hasn't closed any distance in this long isn't going to

    private boolean travellingToEnd = false; // walking up an already-built stretch because the builder found nothing to do
    private Goal travelGoal = null; // the goal object we handed the custom goal process, so we only ever cancel our own walk
    private BetterBlockPos travelTarget = null;
    private int travelBestSteps = Integer.MAX_VALUE; // closest we've come to travelTarget, in slices
    private int travelNoProgressTicks = 0;
    private int travelStallCooldown = 0; // ticks to leave the walk alone after one failed to make progress
    private static final int END_STALL_RESTART_TICKS = 20; // builder restarts while stalled short of the end, at most one this often
    private static final int END_STALL_REPORT_TICKS = 100; // stalled this long, say what the end check is failing on
    private static final int END_STALL_RELOG_TICKS = 200; // and this long, reconnect
    // a longer gap between two idle builders means something else ran meanwhile; Nothing alone can wait 200 ticks for chunks
    private static final int END_STALL_GAP_TICKS = 300;
    private static final int END_STALL_MOVE_SQ = 4; // moving further than 2 blocks ends a stall
    private BetterBlockPos endStallFeet = null; // where we stood when the builder first ran out of work short of the end, null if it hasn't
    private long endStallSince; // game times of that, of the latest time since, and of the latest builder restart
    private long endStallLast;
    private long endStallLastRestart;
    private boolean endStallReported = false;
    private BetterBlockPos endStallRelogSpot = null; // where we last reconnected for one, so a rejoin that didn't help can't loop

    EndTracker(HighwayContext ctx) {
        super(ctx);
    }

    @Override
    void onTeardown() {
        stopTravelTowardsEnd();
    }

    public boolean travelTowardsEnd() {
        if (layout().endPos() == null || ctx.invalidBlockFixActive()) {
            stopTravelTowardsEnd();
            return false;
        }
        if (travellingToEnd) {
            boolean ours = travelGoal != null && travelTarget != null && baritone.getCustomGoalProcess().isActive()
                    && baritone.getCustomGoalProcess().getGoal() == travelGoal;
            if (!ours) {
                // arrived, or something else took the wheel: let the builder look at what we loaded
                stopTravelTowardsEnd();
                return false;
            }
            int remaining = layout().stepsAlongHighway(playerContext.playerFeet(), travelTarget);
            if (remaining < travelBestSteps) {
                travelBestSteps = remaining;
                travelNoProgressTicks = 0;
                return true; // still closing in
            }
            if (++travelNoProgressTicks <= TRAVEL_STALL_TICKS) {
                return true;
            }
            Helper.HELPER.logDirect("Walk to " + travelTarget + " hasn't gotten any closer in " + TRAVEL_STALL_TICKS + " ticks, handing it back to the builder.");
            stopTravelTowardsEnd();
            travelStallCooldown = TRAVEL_STALL_TICKS;
            return false;
        }
        if (travelStallCooldown > 0) {
            return false;
        }

        Vec3 direction = new Vec3(layout().highwayDirection().getX(), layout().highwayDirection().getY(), layout().highwayDirection().getZ());
        Vec3 feetVec = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BetterBlockPos feetSlice = layout().getClosestPoint(new Vec3(layout().originVector().x, layout().originVector().y, layout().originVector().z), direction, feetVec, LocationType.HighwayBuild);

        // builtSlicesAhead counts slices while the stand-off distance is in blocks, and a diagonal
        // slice is sqrt(2) of them
        int standOffSlices = (int) Math.ceil(Math.max(0, settings.highwayEndDistance.value) / layout().stepLength());
        int hop = ctx.scanner().builtSlicesAhead(Math.max(32, playerContext.minecraft().options.renderDistance().get() * 16)) - 1
                - standOffSlices;
        hop = Math.min(hop, layout().stepsAlongHighway(feetSlice, layout().endPos()));
        if (hop <= 0) {
            return false;
        }
        BetterBlockPos target = laneOnSlice(layout().pattern().isCustom()
                ? feetSlice.offset(layout().sliceDelta(layout().sliceIndexOf(feetSlice, layout().originVector()), hop))
                : feetSlice.offset(hop * layout().highwayDirection().getX(), 0, hop * layout().highwayDirection().getZ()));
        int steps = layout().stepsAlongHighway(playerContext.playerFeet(), target);
        if (steps <= 0) {
            // On a -X diagonal the slice our feet project onto lies a slice or two behind where we
            // stand, so a hop that short lands on our own cell or behind it. The walk would arrive
            // at once and hand straight back to a builder that still has nothing to do.
            return false;
        }
        travelTarget = target;
        travelGoal = new GoalBlock(travelTarget);
        travelBestSteps = steps;
        travelNoProgressTicks = 0;
        travellingToEnd = true;
        Helper.HELPER.logDirect("Nothing left to build within render distance, walking to " + travelTarget + " to load more of the highway.");
        baritone.getCustomGoalProcess().setGoalAndPath(travelGoal);
        return true;
    }

    /** What the BuildingHighway state does about a builder that ran out of work, see {@link #endStall} */
    public enum EndStall {
        /** restart it, as for any other time it runs out of work */
        RESTART_BUILDER,
        /** leave it stopped this tick and go on with the state's other checks */
        WAIT,
        /** dealt with: the state changed, or we're reconnecting */
        HANDLED
    }

    /**
     * Called whenever the builder has run out of work and {@link #travelTowardsEnd} has nowhere to
     * walk. Short of an end that isn't complete, that repeats every few ticks for as long as the end
     * check and the builder disagree about what's left, which standing there never settles: the end
     * check also wants every chunk up to the end loaded and no liquid beside the road, and a cell the
     * client shows wrong stays wrong until the server sends it again. So once it has gone on for a
     * while this says what the end check is failing on, starts the liquid removal for a liquid, and
     * otherwise reconnects, once per spot, to load the world afresh.
     */
    public EndStall endStall() {
        if (layout().endPos() == null || ctx.invalidBlockFixActive()) {
            clearEndStall();
            return EndStall.RESTART_BUILDER;
        }
        long now = playerContext.world().getGameTime();
        BetterBlockPos feet = playerContext.playerFeet();
        if (endStallFeet == null || now - endStallLast > END_STALL_GAP_TICKS || feet.distSqr(endStallFeet) > END_STALL_MOVE_SQ) {
            endStallFeet = feet;
            endStallSince = now;
            endStallLast = now;
            endStallLastRestart = now;
            endStallReported = false;
            return EndStall.RESTART_BUILDER;
        }
        endStallLast = now;
        long stalled = now - endStallSince;
        if (!endStallReported && stalled >= END_STALL_REPORT_TICKS) {
            endStallReported = true;
            EndCheck check = endCheck();
            BlockPos unloaded = endCheckUnloadedCell(check);
            HighwayBlockState found = unloaded != null ? null : ctx.scanner().isHighwayCorrect(check.start(), check.startLiq(), check.slices(), false);
            String why = unloaded != null ? "the chunk with " + unloaded.toShortString() + " isn't loaded"
                    : switch (found) {
                        case Liquids -> BlockUtils.blockToString(playerContext.world().getBlockState(ctx.scanner().lastLiquid()).getBlock()) + " at " + ctx.scanner().lastLiquid().toShortString();
                        case Blocks -> "blocks that differ, " + String.join("; ", ctx.scanner().lastMismatches());
                        case Boat -> "a boat at " + ctx.scanner().boatLocation().toShortString();
                        case Crystal -> "an end crystal at " + ctx.scanner().crystalLocation().toShortString();
                        case Air -> "nothing any more";
                    };
            Helper.HELPER.logDirect("The builder has had nothing to do for " + stalled / 20 + " s short of the end at "
                    + layout().endPos().toShortString() + ", where the end check finds " + why);
            if (found == HighwayBlockState.Liquids) {
                ctx.liquidRemoval().startRemoval(); // or a pause, once removals keep coming back to it
                return EndStall.HANDLED;
            }
            if (found == HighwayBlockState.Boat) {
                Helper.HELPER.logDirect("Found a boat, trying to remove blocks around and under it.");
                ctx.transitionTo(HighwayState.BoatRemoval);
                baritone.getPathingBehavior().cancelEverything();
                ctx.resetTimer();
                return EndStall.HANDLED;
            }
            if (found == HighwayBlockState.Crystal) {
                Helper.HELPER.logDirect("Found an end crystal in the way at " + ctx.scanner().crystalLocation().toShortString() + ", breaking it from behind cover.");
                ctx.transitionTo(HighwayState.CrystalRemoval);
                baritone.getPathingBehavior().cancelEverything();
                ctx.resetTimer();
                return EndStall.HANDLED;
            }
        }
        if (stalled >= END_STALL_RELOG_TICKS && (endStallRelogSpot == null || feet.distSqr(endStallRelogSpot) > END_STALL_MOVE_SQ)) {
            endStallRelogSpot = feet;
            clearEndStall();
            Component dcMsg = Component.literal("Stuck short of the end of the highway for " + stalled / 20 + " s. Reconnect to load the world afresh");
            Helper.HELPER.logDirect(dcMsg);
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
            ctx.transitionTo(HighwayState.Nothing);
            playerContext.player().connection.getConnection().disconnect(dcMsg);
            return EndStall.HANDLED;
        }
        // now and then, in case what came in meanwhile gave the builder something
        if (now - endStallLastRestart >= END_STALL_RESTART_TICKS) {
            endStallLastRestart = now;
            return EndStall.RESTART_BUILDER;
        }
        return EndStall.WAIT;
    }

    /** The builder has work again, so whatever it had run out on is settled */
    public void clearEndStall() {
        endStallFeet = null;
    }

    /** Whether a builder restarted during the current {@link #endStall} ran out of work here as well */
    public boolean endStallRestarted() {
        return endStallFeet != null && endStallLastRestart > endStallSince;
    }

    void tickTravelStallCooldown() {
        if (travelStallCooldown > 0) {
            travelStallCooldown--;
        }
    }

    public void stopTravelTowardsEnd() {
        if (!travellingToEnd) {
            return;
        }
        travellingToEnd = false;
        travelTarget = null;
        travelBestSteps = Integer.MAX_VALUE;
        travelNoProgressTicks = 0;
        if (travelGoal != null && baritone.getCustomGoalProcess().getGoal() == travelGoal) {
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelSegmentIfSafe();
        }
        travelGoal = null;
    }

    private BetterBlockPos laneOnSlice(BlockPos buildLinePos) {
        Vec3 direction = new Vec3(layout().highwayDirection().getX(), layout().highwayDirection().getY(), layout().highwayDirection().getZ());
        BetterBlockPos lane = layout().getClosestPoint(new Vec3(layout().backPathOriginVector().x, layout().backPathOriginVector().y, layout().backPathOriginVector().z),
                direction, new Vec3(buildLinePos.getX(), buildLinePos.getY(), buildLinePos.getZ()), LocationType.ShulkerEchestInteraction);
        int off = layout().stepsAlongHighway(lane, buildLinePos);
        if (off == 0) {
            return layout().liftOntoPavement(lane);
        }
        return layout().liftOntoPavement(new BetterBlockPos(layout().pattern().isCustom()
                ? lane.offset(layout().sliceDelta(layout().sliceIndexOf(lane, layout().backPathOriginVector()), off))
                : lane.offset(off * layout().highwayDirection().getX(), 0, off * layout().highwayDirection().getZ())));
    }

    public boolean isHighwayEndComplete() {
        if (layout().endPos() == null) {
            return false;
        }
        EndCheck check = endCheck();
        if (endCheckUnloadedCell(check) != null) {
            return false;
        }
        return ctx.scanner().isHighwayCorrect(check.start(), check.startLiq(), check.slices(), false) == HighwayBlockState.Air;
    }

    /**
     * The stretch {@link #isHighwayEndComplete} checks: from highwayCheckBackDistance slices behind us
     * to the end, on the build line and on the liquid line
     */
    private record EndCheck(BlockPos start, BlockPos startLiq, int slices) {}

    private EndCheck endCheck() {
        Vec3 direction = new Vec3(layout().highwayDirection().getX(), layout().highwayDirection().getY(), layout().highwayDirection().getZ());
        Vec3 curPosPlayer = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        // feetClosest is clamped to the end: even if we wandered past the end the scan still covers
        // the final checkBackDistance slices instead of degenerating
        BetterBlockPos feetClosest = layout().getClosestPoint(new Vec3(layout().originVector().x, layout().originVector().y, layout().originVector().z), direction, curPosPlayer, LocationType.HighwayBuild);
        Vec3 feetClosestVec = new Vec3(feetClosest.getX(), feetClosest.getY(), feetClosest.getZ());
        BlockPos startCheckPos = layout().sliceAlongLine(layout().originVector(), feetClosestVec, -layout().highwayCheckBackDistance(), LocationType.HighwayBuild);
        BlockPos startCheckPosLiq = layout().sliceAlongLine(layout().liqOriginVector(), feetClosestVec, -layout().highwayCheckBackDistance(), LocationType.ShulkerEchestInteraction);
        return new EndCheck(startCheckPos, startCheckPosLiq, layout().stepsAlongHighway(startCheckPos, layout().endPos()) + 1);
    }

    /**
     * @return a cell of the end check's stretch in a chunk we haven't got, or null if it's all loaded
     */
    private BlockPos endCheckUnloadedCell(EndCheck check) {
        BlockPos unloaded = ctx.scanner().firstUnloadedCell(check.start(), layout().originVector(), layout().schematic().widthX(), layout().schematic().lengthZ(), 1, check.slices());
        return unloaded != null ? unloaded
                : ctx.scanner().firstUnloadedCell(check.startLiq(), layout().liqOriginVector(), layout().liqCheckSchem().widthX(), layout().liqCheckSchem().lengthZ(), 1, check.slices());
    }
}
