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

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;

/**
 * Noticing we fell off the highway or got trapped underneath it, and where to recover to; also
 * the floating fix's aims.
 */
public class RecoveryTracker extends ContextComponent {
    private final ArrayList<Rotation> floatingFixReachables = new ArrayList<>();

    private BetterBlockPos recoveryTarget = null;
    private int recoveryExtraBack = 0;
    // feet block and ticks spent on it while a path runs underneath the road, see trappedUnderRoad
    private BetterBlockPos underRoadLastFeet = null;
    private int underRoadStillTicks = 0;
    // whether the path we were on as we went below the fall threshold planned that depth (null
    // while above it), and the feet block and building ticks spent on it since, see fellOffHighway
    private Boolean descentPlanned = null;
    private BetterBlockPos descentLastFeet = null;
    private int descentStillTicks = 0;
    private boolean recoveryHoldingJump = false; // here, not on FallRecovery: transitionTo throws the state object away, and a latched jump key would outlive it
    private Boolean recoverySwimThroughLavaSaved = null; // non-null while we've overridden allowSwimThroughLava for recovery

    /**
     * How long a path may hold us on one block under the road, or below the fall threshold, before
     * we count as stuck there anyway.
     */
    private static final int TRANSIT_STILL_TICKS = 100;

    RecoveryTracker(HighwayContext ctx) {
        super(ctx);
    }

    /** Held keys, suppressHitResult, and settings (e.g. allowSwimThroughLava) overridden during recovery. */
    @Override
    void onTeardown() {
        resetRecovery();
    }

    public Rotation floatingFixReachablesRemoveFirst() {
        if (!floatingFixReachables.isEmpty()) {
            return floatingFixReachables.removeFirst();
        }
        return null;
    }

    public void clearFloatingFixReachables() {
        floatingFixReachables.clear();
    }

    public void addFloatingFixReachables(Rotation rotation) {
        floatingFixReachables.add(rotation);
    }

    public BetterBlockPos recoveryTarget() {
        return recoveryTarget;
    }

    public void setRecoveryTarget(BetterBlockPos pos) {
        this.recoveryTarget = pos;
    }

    /**
     * Temporarily treat lava as a swimmable fluid so a fall recovery can swim up and out of a lava lake
     * (we have fire resistance during recovery). Saves the user's original {@code allowSwimThroughLava}
     * value so it can be restored afterwards.
     */
    public void enableLavaSwimmingForRecovery() {
        if (recoverySwimThroughLavaSaved == null) {
            recoverySwimThroughLavaSaved = settings.allowSwimThroughLava.value;
            settings.allowSwimThroughLava.value = true;
        }
    }

    public void restoreLavaSwimming() {
        if (recoverySwimThroughLavaSaved != null) {
            settings.allowSwimThroughLava.value = recoverySwimThroughLavaSaved;
            recoverySwimThroughLavaSaved = null;
        }
    }

    /** Clears all fall-recovery state, releases the keys we may have held, and restores overridden settings. */
    public void resetRecovery() {
        restoreLavaSwimming();
        recoveryTarget = null;
        recoveryExtraBack = 0;
        recoveryHoldingJump = false;
        NetherHighwayBuilderBehavior.suppressHitResult = false;
        if (playerContext.minecraft() != null) {
            playerContext.minecraft().options.keyJump.setDown(false);
            ctx.eating().releaseUseToEat();
        }
    }

    /**
     * Hold (or release) the jump key that floats us at the lava surface during recovery. Pressed
     * again on every call while held: opening a screen lets go of every key, and grabbing the mouse
     * once it closes can set them all back to what the keyboard holds.
     */
    public void setRecoveryHoldingJump(boolean holding) {
        if (!holding && !recoveryHoldingJump) {
            return; // not ours to let go of
        }
        recoveryHoldingJump = holding;
        if (playerContext.minecraft() != null) {
            playerContext.minecraft().options.keyJump.setDown(holding);
        }
    }

    /** Search a bit further back next time we look for a spot to return to. */
    public void growRecoveryBack() {
        int step = Math.max(1, settings.highwayRecoveryBackDistance.value);
        recoveryExtraBack = Math.min(recoveryExtraBack + step, settings.highwayRecoveryMaxSearch.value);
    }

    /**
     * Find a standable, already-built spot on the highway to path back to after a fall.
     * Starts {@code highwayRecoveryBackDistance} (+ any grown offset) blocks back and searches further
     * until a column with solid footing and clear feet/head is found, up to {@code highwayRecoveryMaxSearch}.
     * A spot ahead wins if it's nearer, as long as restarting from it can't skip anything.
     *
     * @return the feet position to stand at, or null if nothing suitable was found
     */
    public BetterBlockPos computeRecoveryTarget() {
        int start = Math.max(1, settings.highwayRecoveryBackDistance.value) + recoveryExtraBack;
        int max = Math.max(start, settings.highwayRecoveryMaxSearch.value);
        BetterBlockPos behind = null;
        int behindAt = max + 1;
        for (int d = start; d <= max; d++) {
            behind = standableSpotAcross(-d);
            if (behind != null) {
                behindAt = d;
                break;
            }
        }
        // The road behind can be unwalkable for a long way while it's clear just ahead: an
        // obsidian-filled stretch we got under through a tunnel. Walking back past the fill only
        // leaves the build on its far side, and the builder heads for it under the road again.
        // The restart begins highwayCheckBackDistance behind wherever we stand, so a spot further
        // ahead than that past the build origin would skip the stretch in between.
        for (int d = start; d < behindAt; d++) {
            BetterBlockPos ahead = standableSpotAcross(d);
            if (ahead != null) {
                boolean skipsNothing = ctx.originBuild() == null || layout().stepsAlongHighway(ctx.originBuild(), ahead) <= layout().highwayCheckBackDistance();
                return skipsNothing ? ahead : behind;
            }
        }
        return behind;
    }

    /**
     * The first standable spot at highway level across the slice {@code slices} along the highway
     * from our feet (negative is behind), scanning outward from the walk lane, or null.
     */
    private BetterBlockPos standableSpotAcross(int slices) {
        int floorY = settings.highwayLowestY.value + (layout().paving() ? 1 : 0);
        BetterBlockPos feet = playerContext.playerFeet();
        int maxLateral = settings.highwayWidth.value + 2;
        int baseX;
        int baseZ;
        int crossX;
        int crossZ;
        if (layout().pattern().isCustom()) {
            // Walk slice by slice along the pattern (a straight walk leaves the road within a few
            // slices), scanning across the road width from the walk lane.
            int t = layout().sliceIndexNear(feet.x, feet.z) + slices;
            baseX = (int) Math.round(layout().backPathOriginVector().x) + layout().pattern().worldOffsetX(t);
            baseZ = (int) Math.round(layout().backPathOriginVector().z) + layout().pattern().worldOffsetZ(t);
            crossX = layout().pattern().majorIsX() ? 0 : 1;
            crossZ = layout().pattern().majorIsX() ? 1 : 0;
        } else {
            baseX = feet.x + layout().highwayDirection().getX() * slices;
            baseZ = feet.z + layout().highwayDirection().getZ() * slices;
            // perpendicular to the highway in the XZ plane
            crossX = layout().highwayDirection().getZ();
            crossZ = -layout().highwayDirection().getX();
        }
        // scan laterally outward from our column to snap onto a built part of the highway
        for (int w = 0; w <= maxLateral; w++) {
            for (int sign = (w == 0 ? 1 : -1); sign <= 1; sign += 2) {
                int fx = baseX + crossX * w * sign;
                int fz = baseZ + crossZ * w * sign;
                if (MovementHelper.canWalkOn(baritone.bsi, fx, floorY, fz)
                        && MovementHelper.canWalkThrough(baritone.bsi, fx, floorY + 1, fz)
                        && MovementHelper.canWalkThrough(baritone.bsi, fx, floorY + 2, fz)) {
                    return new BetterBlockPos(fx, floorY + 1, fz);
                }
            }
        }
        return null;
    }

    /**
     * True if the paved road is directly over our head. This is what separates being underneath the
     * highway from standing in ground the printer simply hasn't mined yet: raw netherrack overhead
     * is not a road, it's the next thing to dig, and it must never trigger a recovery.
     */
    public boolean roadOverhead() {
        BetterBlockPos feet = playerContext.playerFeet();
        Block above = baritone.bsi.get0(feet.x, layout().highwayFloorY(), feet.z).getBlock();
        return above == Blocks.OBSIDIAN || above == Blocks.CRYING_OBSIDIAN;
    }

    /**
     * True if the highway's walking space is open above our column, i.e. this stretch is already dug
     * out. That's the boat pit case: we're below the highway where the floor has been cleared away,
     * so restarting the printer would pave it shut over our head.
     */
    public boolean highwayWalkSpaceOpen() {
        BetterBlockPos feet = playerContext.playerFeet();
        return passableForRecovery(feet.x, layout().highwayFeetY(), feet.z)
                && passableForRecovery(feet.x, layout().highwayFeetY() + 1, feet.z);
    }

    /** True if we're stuck below the road with it sealing us in, so we have to walk back on top of it. */
    public boolean sealedUnderHighway() {
        return underRoadChecksApply()
                && playerContext.playerFeet().y < layout().highwayFloorY()
                && roadOverhead();
    }

    /**
     * {@link #sealedUnderHighway}, except while a path is carrying us through underneath the road.
     * A tunnel dug beneath an obsidian-filled stretch is the cheapest way past it, and pulling the
     * bot out of the tunnel only leaves it on the wrong side, where the restarted builder heads into
     * the same tunnel again. A path that stops getting anywhere still counts as trapped.
     */
    boolean trappedUnderRoad() {
        if (!sealedUnderHighway()) {
            underRoadLastFeet = null;
            return false;
        }
        // planning counts too, or the gap between two path segments would fire it mid-tunnel
        if (!baritone.getPathingBehavior().hasPath() && baritone.getPathingBehavior().getInProgress().isEmpty()) {
            underRoadLastFeet = null;
            return true;
        }
        BetterBlockPos feet = playerContext.playerFeet();
        if (!feet.equals(underRoadLastFeet)) {
            underRoadLastFeet = feet;
            underRoadStillTicks = 0;
        }
        return ++underRoadStillTicks > TRANSIT_STILL_TICKS;
    }

    /**
     * Feet {@code highwayFallDetectThreshold} or more below the road, unless a path walked us down
     * there on purpose. A ravine across the road that nothing bridges is crossed by walking down
     * into it and up the far side, and pulling the bot back onto the near rim each time only starts
     * the same walk again. Whether the descent was planned is judged once, as we go below, from the
     * path we're on then, because one replanned after a real fall reaches down there too. Lava, or a
     * walk that stops getting anywhere, still counts as having fallen. Run every tick so the descent
     * is judged whatever state takes us down; only building ticks count towards a stall.
     */
    boolean fellOffHighway(boolean building) {
        BetterBlockPos feet = playerContext.playerFeet();
        if (feet.y > layout().highwayFeetY() - settings.highwayFallDetectThreshold.value) {
            descentPlanned = null;
            return false;
        }
        if (descentPlanned == null) {
            descentPlanned = pathPlannedDepth(feet);
            descentLastFeet = null;
        }
        if (!descentPlanned || playerContext.player().isInLava()) {
            return true;
        }
        if (!building || !feet.equals(descentLastFeet)) {
            descentLastFeet = feet;
            descentStillTicks = 0;
        }
        return ++descentStillTicks > TRANSIT_STILL_TICKS;
    }

    /** Whether the descent below the fall threshold was planned, null while we're above it; see {@link #fellOffHighway}. */
    Boolean descentPlanned() {
        return descentPlanned;
    }

    /**
     * True if the path we're following started on solid footing and goes as deep as our feet right
     * where we are. A path replanned while we fall starts in mid-air.
     */
    private boolean pathPlannedDepth(BetterBlockPos feet) {
        PathExecutor current = baritone.getPathingBehavior().getCurrent();
        if (current == null) {
            return false;
        }
        IPath path = current.getPath();
        BetterBlockPos src = path.getSrc();
        if (!MovementHelper.canWalkOn(baritone.bsi, src.x, src.y - 1, src.z)) {
            return false;
        }
        for (BetterBlockPos pos : path.positions()) {
            if (pos.y <= feet.y && Math.abs(pos.x - feet.x) <= 1 && Math.abs(pos.z - feet.z) <= 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if we're below the highway somewhere the printer could pave the floor in on top of us:
     * either the road is already over our head, or we're in a pit under a dug out stretch that is
     * still waiting to be paved.
     */
    public boolean belowBuiltHighway() {
        return underRoadChecksApply()
                && playerContext.playerFeet().y < layout().highwayFeetY()
                && (roadOverhead() || highwayWalkSpaceOpen());
    }

    /** A digging build lays no floor, so there is nothing that could ever pave us in. */
    private boolean underRoadChecksApply() {
        return layout().paving() && settings.highwayUnderRoadRecovery.value;
    }

    /** Air, liquid, or something the printer replaces - nothing that could box us in. */
    boolean passableForRecovery(int x, int y, int z) {
        BlockState state = baritone.bsi.get0(x, y, z);
        return state.getBlock() instanceof AirBlock || state.getBlock() instanceof LiquidBlock
                || MovementHelper.isReplaceable(x, y, z, state, baritone.bsi);
    }
}
