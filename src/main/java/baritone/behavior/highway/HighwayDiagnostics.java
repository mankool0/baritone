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

import baritone.api.utils.Helper;
import baritone.behavior.highway.enums.HighwayState;
import baritone.process.BuilderProcess;
import baritone.utils.PendingPlaces;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tallies for nhwstatus: what held the walk-creep, what the printer did each tick, and the time
 * spent in each state.
 */
public class HighwayDiagnostics extends ContextComponent {
    private final EnumMap<HighwayState, int[]> stateDwell = new EnumMap<>(HighwayState.class);
    private HighwayState lastDwellState = null;
    private int dwellTotalTicks = 0;

    // why the walk-creep did or didn't hold the key, tallied since the last nhwstatus
    private final Map<String, Integer> walkGateTally = new LinkedHashMap<>();
    private int walkGateTicks;
    private int walkGateHeld;
    private int walkGateToEdge;
    private int walkGateEdgeGiveUps;

    // what each tick did for placing, tallied since the last nhwstatus
    private final Map<String, Integer> printerTally = new LinkedHashMap<>();
    private int printerTicks;
    private int printerPlacedTicks;
    private int printerPlacedBlocks;
    private int printerReverts;
    private PendingPlaces.Unanswered printerUnhandled = PendingPlaces.Unanswered.NONE;
    private int printerPlaceWindowMillis;
    private boolean printerOnePlaceATick;
    private int printerSetbackBackOffs;
    private int printerSetbackBackOffTicks;
    private long printerLastTickNanos;
    private long printerTickNanos; // time between tallied ticks, leaving out the gaps of a pause
    private int printerTickGaps;
    private static final long PRINTER_TALLY_MAX_GAP_NANOS = TimeUnit.SECONDS.toNanos(5);
    // the same over the ticks spent in BuildingHighway, with the progress made along the heading
    private long pavingNanos;
    private double pavingBlocksAlong;
    private int pavingPlacedBlocks;
    private Vec3 pavingLastPos; // null once a tick wasn't paving, so the walk back from elsewhere isn't counted

    // blocks placed or broken in BuildingHighway since the build started; only ever goes up
    private long builderWork;
    private long brokenSeen = -1;
    // ticks in a row the builder has been running, not pathing, with nothing placed or broken
    private int builderIdleTicks;
    /**
     * How long the builder may sit doing nothing before a line says what pins it. A builder parked
     * at a goal it has nothing in reach from logs nothing of its own, and a stuck check can be set
     * to minutes, so without this a stall says nothing until someone asks nhwstatus.
     */
    private static final int BUILDER_IDLE_LOG_TICKS = 200;

    HighwayDiagnostics(HighwayContext ctx) {
        super(ctx);
    }

    /** Per build, so a stop leaves the last run's timings readable. */
    @Override
    void onBuildStart() {
        resetStateDwell();
        builderIdleTicks = 0;
    }

    /**
     * Blocks placed or broken in BuildingHighway since the build started. It only goes up, so two
     * readings that are the same mean the builder got nothing done in between.
     */
    public long builderWork() {
        return builderWork;
    }

    /** Ticks in a row the builder has been running in BuildingHighway, not pathing, with nothing placed or broken. */
    public int builderIdleTicks() {
        return builderIdleTicks;
    }

    void noteStateTick(HighwayState state) {
        int[] entry = stateDwell.computeIfAbsent(state, k -> new int[2]);
        entry[0]++;
        if (state != lastDwellState) {
            entry[1]++;
            lastDwellState = state;
        }
        dwellTotalTicks++;
    }

    private void resetStateDwell() {
        stateDwell.clear();
        lastDwellState = null;
        dwellTotalTicks = 0;
    }

    /**
     * Ticks spent in each state since the build started, worst first. This is the measurement the
     * wait tuning is meant to be judged on: a state whose share is far above the work it actually
     * does is sitting on a timer it shouldn't be.
     */
    public List<String> stateDwellDiagnostic(int limit) {
        List<String> out = new ArrayList<>();
        if (dwellTotalTicks == 0) {
            out.add("No state time recorded yet.");
            return out;
        }
        out.add(String.format("State time over %d ticks (%.1fs), worst first:", dwellTotalTicks, dwellTotalTicks / 20.0));
        stateDwell.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(limit)
                .forEach(e -> out.add(String.format("  %s - %d ticks (%.1fs, %.1f%%) over %d visit(s), avg %.1ft",
                        e.getKey(), e.getValue()[0], e.getValue()[0] / 20.0,
                        100.0 * e.getValue()[0] / dwellTotalTicks, e.getValue()[1],
                        (double) e.getValue()[0] / e.getValue()[1])));
        return out;
    }

    /**
     * Records the first condition that stopped the walk-creep holding the key this tick, or null if it held.
     *
     * @param toEdge the key is down only to walk out to the edge of the floor, which the gate
     *               would otherwise have stopped short of
     */
    public void noteWalkGate(String blocker, boolean toEdge) {
        walkGateTicks++;
        if (blocker == null) {
            walkGateHeld++;
            if (toEdge) {
                walkGateToEdge++;
            }
        } else {
            walkGateTally.merge(blocker, 1, Integer::sum);
        }
    }

    public void noteEdgeGiveUp() {
        walkGateEdgeGiveUps++;
    }

    /**
     * Records what the builder's printer did this tick.
     *
     * @param report this tick's report, or null if the builder didn't tick
     */
    public void notePrinterTick(BuilderProcess.PrinterReport report) {
        long now = System.nanoTime();
        // the gaps measure the client's tick rate, since every reason below scales with it
        boolean continuous = printerLastTickNanos != 0 && now - printerLastTickNanos < PRINTER_TALLY_MAX_GAP_NANOS;
        if (continuous) {
            printerTickNanos += now - printerLastTickNanos;
            printerTickGaps++;
        }
        noteBuilderWork(report);
        if (ctx.currentState().getState() == HighwayState.BuildingHighway) {
            Vec3 pos = playerContext.player().position();
            if (continuous && pavingLastPos != null) {
                double headX = layout().pattern().isCustom() ? layout().pattern().periodWorldX() : layout().highwayDirection().getX();
                double headZ = layout().pattern().isCustom() ? layout().pattern().periodWorldZ() : layout().highwayDirection().getZ();
                pavingNanos += now - printerLastTickNanos;
                pavingBlocksAlong += ((pos.x - pavingLastPos.x) * headX + (pos.z - pavingLastPos.z) * headZ) / Math.sqrt(headX * headX + headZ * headZ);
            }
            pavingLastPos = pos;
            if (report != null) {
                pavingPlacedBlocks += report.placed();
            }
        } else {
            pavingLastPos = null;
        }
        printerLastTickNanos = now;
        printerTicks++;
        if (report != null) {
            printerPlacedBlocks += report.placed();
            printerReverts += report.reverts();
            printerUnhandled = printerUnhandled.plus(report.unhandled());
            printerPlaceWindowMillis = report.placeWindowMillis();
            printerOnePlaceATick = report.onePlaceATick();
            printerSetbackBackOffs += report.setbackBackOffs();
            if (report.setbackBackOff()) {
                printerSetbackBackOffTicks++;
            }
            if (report.placed() > 0) {
                printerPlacedTicks++;
                return;
            }
        }
        printerTally.merge(report != null ? report.idleReason() : printerNotTickingReason(), 1, Integer::sum);
    }

    /** Adds this tick's places and breaks to {@link #builderWork}, and says so once the builder has sat idle too long. */
    private void noteBuilderWork(BuilderProcess.PrinterReport report) {
        long broken = baritone.getInputOverrideHandler().getBlockBreakHelper().broken().count();
        long work = 0;
        BuilderProcess builder = baritone.getBuilderProcess();
        boolean building = ctx.currentState().getState() == HighwayState.BuildingHighway;
        if (building) {
            work = (report != null ? report.placed() : 0) + (brokenSeen >= 0 ? broken - brokenSeen : 0);
            builderWork += work;
        }
        brokenSeen = broken;
        if (!building || work > 0 || !builder.isActive() || builder.isPaused() || baritone.getPathingBehavior().isPathing()) {
            builderIdleTicks = 0;
            return;
        }
        if (++builderIdleTicks == BUILDER_IDLE_LOG_TICKS) {
            Helper.HELPER.logDiagnostic("Builder idle for " + BUILDER_IDLE_LOG_TICKS / 20 + " s at " + playerContext.playerFeet().toShortString()
                    + ", nothing placed or broken and no path. " + ctx.scanner().frontDistanceDiagnostic()
                    + "; printer: " + (report != null && report.idleReason() != null ? report.idleReason() : printerNotTickingReason()));
        }
    }

    private String printerNotTickingReason() {
        if (ctx.currentState().getState() != HighwayState.BuildingHighway) {
            return "in " + ctx.currentState().getState();
        }
        BuilderProcess builder = baritone.getBuilderProcess();
        if (!builder.isActive()) {
            return "builder not running";
        }
        if (builder.isPaused()) {
            return "builder paused";
        }
        return baritone.getPathingControlManager().mostRecentInControl()
                .map(process -> process.displayName() + " in control")
                .orElse("builder not ticked");
    }

    /**
     * @return whether any ticks were tallied for {@link #printerDiagnostic} since it was last read
     */
    public boolean printerTallied() {
        return printerTicks > 0;
    }

    /**
     * Where the ticks went since this was last asked: on how many the printer placed, and why it
     * didn't on the rest. Reading it resets the tally.
     */
    public String printerDiagnostic() {
        if (printerTicks == 0) {
            return "Printer: no ticks tallied";
        }
        double ticksPerSecond = printerTickNanos > 0 ? printerTickGaps / (printerTickNanos / 1e9) : 0;
        StringBuilder sb = new StringBuilder(String.format("Printer: placed on %d of %d ticks (%d%%), %d blocks, %d undone again, %d never handled by the server",
                printerPlacedTicks, printerTicks, Math.round(100.0 * printerPlacedTicks / printerTicks),
                printerPlacedBlocks, printerReverts, printerUnhandled.total()));
        if (printerUnhandled.total() > 0) {
            // turned down: acked alone, by a check; dropped: never reached the server's handler
            sb.append(String.format(" (%d turned down, %d of those second in their tick, %d dropped unread)",
                    printerUnhandled.refused(), printerUnhandled.refusedLater(), printerUnhandled.dropped()));
        }
        sb.append(String.format(", client at %.1f ticks/s", ticksPerSecond));
        if (settings.printerMultiPlace.value > 1) {
            sb.append(printerOnePlaceATick ? ", placing one a tick since the server throws out more"
                    : String.format(", pacing to at most 9 places per %d ms", printerPlaceWindowMillis));
            if (printerSetbackBackOffs > 0 || printerSetbackBackOffTicks > 0) {
                sb.append(String.format(", one a tick after setbacks on %d ticks (%d times)", printerSetbackBackOffTicks, printerSetbackBackOffs));
            }
        }
        if (pavingNanos > 0) {
            double seconds = pavingNanos / 1e9;
            sb.append(String.format("; paving %.1f s at %.2f m/s along the highway, %.1f blocks/s",
                    seconds, pavingBlocksAlong / seconds, pavingPlacedBlocks / seconds));
            if (pavingBlocksAlong >= 1) {
                // per slice rather than per block of travel, to compare with the cross-section
                sb.append(String.format(", %.1f blocks a slice", pavingPlacedBlocks / (pavingBlocksAlong / layout().stepLength())));
            }
        }
        printerTally.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append(" | ").append(e.getKey()).append(" x").append(e.getValue()));
        printerTally.clear();
        printerTicks = 0;
        printerPlacedTicks = 0;
        printerPlacedBlocks = 0;
        printerReverts = 0;
        printerUnhandled = PendingPlaces.Unanswered.NONE;
        printerSetbackBackOffs = 0;
        printerSetbackBackOffTicks = 0;
        printerTickNanos = 0;
        printerTickGaps = 0;
        pavingNanos = 0;
        pavingBlocksAlong = 0;
        pavingPlacedBlocks = 0;
        return sb.toString();
    }

    /** What has been stopping the walk-creep since this was last asked. Reading it resets the tally. */
    public String walkGateDiagnostic() {
        if (walkGateTicks == 0) {
            return "Walk gate: not evaluated (highwayEndDistance is -1, or not in BuildingHighway)";
        }
        StringBuilder sb = new StringBuilder(String.format("Walk gate: held %d of %d ticks (%d%%)",
                walkGateHeld, walkGateTicks, Math.round(100.0 * walkGateHeld / walkGateTicks)));
        if (walkGateToEdge > 0 || walkGateEdgeGiveUps > 0) {
            sb.append(String.format(", %d of them out to the edge of the floor for the printer", walkGateToEdge));
            if (walkGateEdgeGiveUps > 0) {
                sb.append(String.format(", left to the builder %d times", walkGateEdgeGiveUps));
            }
        }
        walkGateTally.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append(" | ").append(e.getKey()).append(" x").append(e.getValue()));
        walkGateTally.clear();
        walkGateTicks = 0;
        walkGateHeld = 0;
        walkGateToEdge = 0;
        walkGateEdgeGiveUps = 0;
        return sb.toString();
    }
}
