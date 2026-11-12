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

import baritone.Baritone;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockUtils;
import baritone.behavior.highway.enums.HighwayBlockState;
import baritone.behavior.highway.enums.LocationType;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.EntityHole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Scans of the highway around us: whether it is built right, liquids beside it, how far ahead it is
 * built, and whether its chunks are loaded.
 */
public class HighwayScanner extends ContextComponent {
    private final List<BlockState> approxPlaceable = new ArrayList<>() {};

    private final Lock renderLockLiquid = new ReentrantLock();
    private final Lock renderLockBuilding = new ReentrantLock();

    private AABB renderAreaLiquid = null;
    private final HashMap<BlockPos, Color> renderBlocksBuilding = new HashMap<>();

    private final ArrayList<String> lastMismatches = new ArrayList<>();
    // where the last isHighwayCorrect that found a liquid found it
    private BlockPos lastLiquid = null;

    private BlockPos boatLocation = null;

    /** A highway cell an end crystal stands in the way of, for CrystalRemoval. */
    private BlockPos crystalLocation = null;

    // what the last scanFrontDistance was pinned on, for nhwstatus
    private BlockPos frontBlockerSlice;
    private CompositeSchematic frontBlockerSchem;
    private int frontBlockerX;
    private int frontBlockerY;
    private int frontBlockerZ;
    private double frontBlockerAlong;
    private double frontBlockerCross;
    private int frontBlockerColumn;
    private int frontBlockerCrossLen;
    private double frontBlockerPlayerColumn;
    private int frontBlockerLaneLo;
    private int frontBlockerLaneHi;

    /** Blocks ahead of the bot the correctness scan looks, so lava is found before we dig into it. */
    private static final int SCAN_AHEAD_BLOCKS = 5;
    /** Through-wall filling has to spot a sealed pocket before its cover comes into break reach. */
    private static final int SCAN_AHEAD_BLOCKS_THROUGH_WALLS = 8;
    /** Slices behind the bot {@code LiquidRemovalPrep} re-scans from, to catch what flowed in behind us. */
    public static final int LIQUID_SCAN_BACK_SLICES = 7;
    /**
     * Slack on top of the detection scan's reach for the removal's own re-scan. The bot keeps moving
     * between the tick that flags the lava and the tick that goes looking for it again. Too little
     * and the re-scan comes up empty and bounces straight back to Nothing.
     */
    private static final int LIQUID_SCAN_MARGIN_BLOCKS = 3;

    /** Slices either side of a dropped entity that are checked for cells the highway builds back. */
    private static final int ENTITY_HOLE_SLICES = 6;

    HighwayScanner(HighwayContext ctx) {
        super(ctx);
    }

    public Lock renderLockLiquid() {
        return renderLockLiquid;
    }

    public Lock renderLockBuilding() {
        return renderLockBuilding;
    }

    public AABB renderAreaLiquid() {
        return renderAreaLiquid;
    }

    public HashMap<BlockPos, Color> renderBlocksBuilding() {
        return renderBlocksBuilding;
    }

    /** Where the last {@link #isHighwayCorrect} that found a liquid found it. */
    public BlockPos lastLiquid() {
        return lastLiquid;
    }

    public List<String> lastMismatches() {
        return lastMismatches;
    }

    public void setBoatLocation(BlockPos boatLocation) {
        this.boatLocation = boatLocation;
    }

    public void setCrystalLocation(BlockPos crystalLocation) {
        this.crystalLocation = crystalLocation;
    }

    /**
     * How far past the stand-off distance the build front may sit before creeping stops making sense.
     * Within it the creep is just trailing the printer a block at a time, which is all it is for; the
     * printer can only work about a reach ahead of us, so anything further is travel and the pathfinder
     * does travel better than a held walk key.
     */
    public int creepMaxOvershoot() {
        return 2 * (int) Math.ceil(settings.blockReachDistance.value);
    }

    public int getHighwayFrontDistance() {
        // scan far enough that the configured stand-off distance is actually reachable, and past it far
        // enough that the caller can tell "the front is right there" from "the front is a hike away"
        return scanFrontDistance(Math.max(10, settings.highwayEndDistance.value + creepMaxOvershoot()));
    }

    /**
     * Slices ahead of us we have actually seen built, stopping at the first slice whose chunks aren't
     * loaded instead of counting it as built. That is the built front, and it is as far as walking
     * anywhere is safe: past it the highway is either unknown or missing, and pathing over missing
     * highway makes the pathfinder bridge with throwaway netherrack that the printer then has to
     * break out again.
     */
    public int builtSlicesAhead(int maxScan) {
        return scanFrontLength(maxScan);
    }

    /**
     * Distance in blocks along the highway from the player to the nearest cell the schematic still
     * wants changed, capped at {@code maxBlocks}.
     * <p>
     * Blocks rather than slices, because a slice index is not a distance on a diagonal. The schematic
     * is sliced on a world axis, so a diagonal slice is sqrt(2) blocks long and its own cells are
     * spread a further (cross section - 1) / sqrt(2) blocks along the travel axis - all of them ahead
     * of the slice's origin for +X+Z and +X-Z, all behind it for -X-Z and -X+Z, since the schematic is
     * laid out from its low-cross-axis corner whichever way we're heading. Counting slices therefore
     * read the front several blocks late in one diagonal sense and several blocks early in the other,
     * and only the latter ever cleared the walk-creep's stand-off distance: the two +X diagonals never
     * creeped at all and built the whole road from pathed break goals, stopping at each end of the
     * 45-degree slice in turn. On a straight highway a slice is exactly one block along travel and
     * this returns what the slice count returned.
     * <p>
     * An angled pattern is that problem again, worse: its slices each step a single world axis, so no
     * slice step is the heading, consecutive slices jog sideways instead of stacking up a straight
     * line, and where the pattern jogs the neighbouring slices hand the rail columns between them, so
     * a cell's own slice decides what the cross-section wants there. The scan is therefore written
     * against a heading vector - {@link HighwayLayout#highwayDirection} on a classic highway, the world offset of a
     * whole period on a pattern - with slices walked by {@link HighwayLayout#sliceOffset} and cells checked against
     * {@link HighwayLayout#schematicForSlice}. With the classic heading that is the original arithmetic verbatim.
     */
    private int scanFrontDistance(int maxBlocks) {
        Vec3 direction = new Vec3(layout().highwayDirection().getX(), layout().highwayDirection().getY(), layout().highwayDirection().getZ());
        Vec3 curPosPlayer = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BlockPos startCheckPos = layout().getClosestPoint(new Vec3(layout().originVector().x, layout().originVector().y, layout().originVector().z), direction, curPosPlayer, LocationType.HighwayBuild);

        boolean custom = layout().pattern().isCustom();
        int scanT0 = custom ? layout().sliceIndexOf(startCheckPos, layout().originVector()) : 0;
        // The heading, unnormalised: the direction vector on a classic highway, a whole period's world
        // offset on a pattern (7 by 3 for XXZXXZXXXZ), since no single pattern slice travels it.
        double headX = custom ? layout().pattern().periodWorldX() : layout().highwayDirection().getX();
        double headZ = custom ? layout().pattern().periodWorldZ() : layout().highwayDirection().getZ();
        double headLen = Math.sqrt(headX * headX + headZ * headZ);
        // Least along-the-heading progress one slice can make, unnormalised, for the scan bounds only:
        // a full step on a classic highway, a jog (minor axis) step on a pattern. Bounding with the
        // slowest slice overshoots the scan on a pattern, which the break below cuts short anyway.
        double minAdvance = custom ? Math.min(Math.abs(headX), Math.abs(headZ)) : headX * headX + headZ * headZ;

        boolean crossZ = layout().schematic().lengthZ() >= layout().schematic().widthX();
        int crossLen = crossZ ? layout().schematic().lengthZ() : layout().schematic().widthX();
        // One column across the highway shifts a cell crossAlong along the highway and crossPerp across
        // it, both unnormalised, so both come out in blocks once divided by headLen. crossAlong is 0 on
        // a straight, where a slice is perpendicular to travel, and non-zero wherever it isn't.
        double crossAlong = crossZ ? headZ : headX;
        double crossPerp = crossZ ? headX : -headZ;
        // measured from the centre of the block we're standing in, so the reading only moves when we
        // change block rather than jittering with the walk; the +0.5s cancel between the two centres
        int relX = startCheckPos.getX() - playerContext.playerFeet().getX();
        int relZ = startCheckPos.getZ() - playerContext.playerFeet().getZ();
        double alongBase = relX * headX + relZ * headZ;
        double perpBase = -relX * headZ + relZ * headX;

        // The corridor the creep walks down: the column we stand in and one either side, clamped to
        // the walk lane at cross-axis offsets [1, highwayWidth] so the rails stay out of it. This is
        // the N-block generalisation of canWalkOnFloorAhead/canWalkThroughAhead, which ask the same
        // question about the single next step. Columns are read per slice, against that slice's own
        // cross-section, so on a pattern the corridor jogs sideways with the road the way we do.
        //
        // Not the full width, because on a diagonal the road's outer columns lie ahead as well as to
        // the side: measured in game at width 7, column 7 of 8 sat 2.8 to 3.5 blocks across and 1.4
        // to 3.5 along, up to 5.3 from the eye against a 4.5 reach, so it could not be dug from the
        // lane at all - the bot digs it on the way past, when it draws level. "The whole width is
        // finished N blocks ahead" is therefore never true while a diagonal is being built. It isn't
        // a sign the printer is behind, and gating the walk on it reduced the walk to an inch.
        // Work to either side is still dug by the builder, still bounded by creepMaxOvershoot, and
        // still verified across the full cross-section by the back-check behind us.
        double playerColumn = -perpBase / crossPerp;
        playerColumn = Math.max(0, Math.min(crossLen - 1, playerColumn)); // standing off the side still measures a real corridor
        int bodyColumn = (int) Math.round(playerColumn);
        int laneLo = Math.max(1, bodyColumn - 1);
        int laneHi = Math.min(Math.min(crossLen - 1, settings.highwayWidth.value), bodyColumn + 1);

        // A cell of slice i in column j sits (sliceAlong(i) + j * crossAlong) / headLen blocks along
        // the highway from us, where sliceAlong(i) is its own slice's anchor. Solve that for the
        // slices that can put a cell in [0, maxBlocks]: on a diagonal or a pattern that reaches back
        // past startCheckPos, since a slice trails cells behind its own origin, and one of those can
        // be the nearest thing left unbuilt.
        double alongSpanLo = Math.min(0, (crossLen - 1) * crossAlong);
        double alongSpanHi = Math.max(0, (crossLen - 1) * crossAlong);
        int iLo = (int) Math.floor((-alongBase - alongSpanHi) / minAdvance);
        int iHi = (int) Math.ceil((maxBlocks * headLen - alongBase - alongSpanLo) / minAdvance);

        frontBlockerSlice = null;
        frontBlockerSchem = layout().schematic();
        frontBlockerCrossLen = crossLen;
        frontBlockerPlayerColumn = playerColumn;
        frontBlockerLaneLo = laneLo;
        frontBlockerLaneHi = laneHi;

        double nearest = maxBlocks; // an all-correct scan means "at least this far built ahead"
        if (layout().endPos() != null) {
            // endPos anchors the build line, so on a pattern its slice index is exact; the major-axis
            // count stepsAlongHighway uses is only exact to the length of a jog run
            int stepsToEnd = custom ? layout().sliceIndexOf(layout().endPos(), layout().originVector()) - scanT0 : layout().stepsAlongHighway(startCheckPos, layout().endPos());
            iHi = Math.min(iHi, stepsToEnd);
            // past the last slice there is only the end, not more highway
            Vec3i toEnd = layout().sliceOffset(scanT0, stepsToEnd);
            nearest = Math.min(nearest, Math.max(0, (alongBase + toEnd.getX() * headX + toEnd.getZ() * headZ) / headLen));
        }

        for (int i = iLo; i <= iHi; i++) {
            Vec3i delta = layout().sliceOffset(scanT0, i);
            double sliceAlong = alongBase + delta.getX() * headX + delta.getZ() * headZ;
            if ((sliceAlong + alongSpanLo) / headLen >= nearest) {
                break; // this slice and every one past it is further off than what we already found
            }
            double slicePerp = perpBase + (-delta.getX() * headZ + delta.getZ() * headX);
            BlockPos curPos = startCheckPos.offset(delta);
            CompositeSchematic sliceSchem = layout().schematicForSlice(scanT0 + i, curPos);
            for (int y = 0; y < layout().schematic().heightY(); y++) {
                for (int z = 0; z < layout().schematic().lengthZ(); z++) {
                    for (int x = 0; x < layout().schematic().widthX(); x++) {
                        int j = crossZ ? z : x;
                        if (j < laneLo || j > laneHi) {
                            continue;
                        }
                        double along = (sliceAlong + j * crossAlong) / headLen;
                        if (along < 0 || along >= nearest) {
                            continue; // behind us, or no closer than what we already found
                        }
                        if (cellScan(sliceSchem, curPos, x, y, z) == CellScan.MISMATCH) {
                            nearest = along;
                            frontBlockerSlice = curPos;
                            frontBlockerSchem = sliceSchem;
                            frontBlockerX = x;
                            frontBlockerY = y;
                            frontBlockerZ = z;
                            frontBlockerAlong = along;
                            frontBlockerCross = (slicePerp + j * crossPerp) / headLen;
                            frontBlockerColumn = j;
                        }
                    }
                }
            }
        }
        return (int) Math.floor(nearest);
    }

    /**
     * What the walk-creep's front distance is currently pinned on. Rescans, so it reports the state
     * right now rather than whatever the last creep tick happened to see.
     */
    public String frontDistanceDiagnostic() {
        if (layout().schematic() == null) {
            return "Front: no schematic";
        }
        if (playerContext.player() == null) {
            return "Front: not in game"; // nhwstatus can be asked between a disconnect and the rejoin
        }
        int dist = getHighwayFrontDistance();
        String corridor = String.format("cross-section %d wide, we're in column %.0f, watching columns %d-%d",
                frontBlockerCrossLen, frontBlockerPlayerColumn, frontBlockerLaneLo, frontBlockerLaneHi);
        if (frontBlockerSlice == null) {
            return "Front: " + dist + " blocks, nothing unbuilt in range (" + corridor + ")";
        }
        BlockPos pos = frontBlockerSlice.offset(frontBlockerX, frontBlockerY, frontBlockerZ);
        BlockState current = playerContext.world().getBlockState(pos);
        // the pinning slice's own cross-section, which on a jogged pattern is not the same one its
        // neighbours are built against - they split the rail columns between them
        BlockState desired = frontBlockerSchem.desiredState(frontBlockerX, frontBlockerY, frontBlockerZ, current, this.approxPlaceable);
        return String.format("Front: %d blocks, pinned by %d,%d,%d (column %d of %d, %.2f along, %.2f across, y+%d): is %s, wants %s [%s]",
                dist, pos.getX(), pos.getY(), pos.getZ(),
                frontBlockerColumn, frontBlockerCrossLen - 1, frontBlockerAlong, frontBlockerCross, frontBlockerY,
                BlockUtils.blockToString(current.getBlock()), BlockUtils.blockToString(desired.getBlock()), corridor);
    }

    private enum CellScan {CORRECT, MISMATCH, UNLOADED}

    /**
     * Whether the cell at schematic coordinates ({@code x}, {@code y}, {@code z}) of the slice starting
     * at {@code slicePos} already matches the highway. Cells the schematic doesn't cover, cells held
     * valid by the block above them, and cells the builder leaves alone count as correct.
     */
    private CellScan cellScan(BlockPos slicePos, int x, int y, int z) {
        return cellScan(layout().cellOverrides().bind(layout().schematic(), slicePos), slicePos, x, y, z);
    }

    /** {@link #cellScan(BlockPos, int, int, int)} against one slice's own schematic, for angled patterns. */
    private CellScan cellScan(CompositeSchematic sliceSchem, BlockPos slicePos, int x, int y, int z) {
        int blockX = x + slicePos.getX();
        int blockY = y + slicePos.getY();
        int blockZ = z + slicePos.getZ();
        BlockState current = playerContext.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

        if (!sliceSchem.inSchematic(x, y, z, current)) {
            return CellScan.CORRECT;
        }
        if (!baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) {
            return CellScan.UNLOADED;
        }
        // we can directly observe this block, it is in render distance

        ISchematic ourSchem = sliceSchem.getSchematic(x, y, z, current).schematic;
        if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                MovementHelper.isBlockNormalCube(playerContext.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)))) {
            return CellScan.CORRECT;
        }
        BlockState desired = sliceSchem.desiredState(x, y, z, current, this.approxPlaceable);
        if (desired.equals(current)) {
            return CellScan.CORRECT;
        }
        // The same cells BuilderProcess.valid() accepts and isHighwayCorrect skips: a lit portal pops
        // once its frame is mined, and buildIgnoreBlocks (signs) are never touched. Read as unbuilt,
        // a sign standing on the lane pinned the travel walk short of it for good while the builder
        // had nothing left to do, so the bot restarted the builder over and over in place.
        if (current.getBlock() instanceof NetherPortalBlock
                || (desired.getBlock() instanceof AirBlock && settings.buildIgnoreBlocks.value.contains(current.getBlock()))) {
            return CellScan.CORRECT;
        }
        return CellScan.MISMATCH;
    }

    private int scanFrontLength(int scanLength) {
        Vec3 direction = new Vec3(layout().highwayDirection().getX(), layout().highwayDirection().getY(), layout().highwayDirection().getZ());
        Vec3 curPosPlayer = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BlockPos startCheckPos = layout().getClosestPoint(new Vec3(layout().originVector().x, layout().originVector().y, layout().originVector().z), direction, curPosPlayer, LocationType.HighwayBuild);

        // Only the columns the player can actually work from where they stand count. On a highway
        // wider than the printer's reach sphere the outer columns are built later from another
        // lane, so demanding a whole correct cross-section would pin this at 0 forever and the
        // walk-creep would never engage. On narrow highways the window covers the full section, so
        // this is exactly the old behaviour.
        boolean crossZ = layout().schematic().lengthZ() >= layout().schematic().widthX();
        int crossLen = crossZ ? layout().schematic().lengthZ() : layout().schematic().widthX();
        int lateral = crossZ ? playerContext.playerFeet().getZ() - startCheckPos.getZ()
                : playerContext.playerFeet().getX() - startCheckPos.getX();
        lateral = Math.max(0, Math.min(crossLen - 1, lateral)); // standing off the side still measures a real corridor
        int corridorReach = (int) Math.ceil(settings.blockReachDistance.value);
        int crossLo = Math.max(0, lateral - corridorReach);
        int crossHi = Math.min(crossLen - 1, lateral + corridorReach);

        int maxResult = scanLength; // an all-correct scan means "at least this much built ahead"
        if (layout().endPos() != null) {
            int stepsToEnd = layout().stepsAlongHighway(startCheckPos, layout().endPos());
            if (stepsToEnd + 1 <= scanLength) {
                scanLength = stepsToEnd + 1;
                maxResult = stepsToEnd; // past the last slice there is only the end, not more highway
            }
        }
        int scanT0 = layout().pattern().isCustom() ? layout().sliceIndexOf(startCheckPos, layout().originVector()) : 0;
        for (int i = 0; i < scanLength; i++) {
            BlockPos curPos = layout().pattern().isCustom()
                    ? startCheckPos.offset(layout().sliceDelta(scanT0, i))
                    : startCheckPos.offset(i * layout().highwayDirection().getX(), 0, i * layout().highwayDirection().getZ());
            CompositeSchematic sliceSchem = layout().schematicForSlice(scanT0 + i, curPos);
            for (int y = 0; y < layout().schematic().heightY(); y++) {
                for (int z = 0; z < layout().schematic().lengthZ(); z++) {
                    if (crossZ && (z < crossLo || z > crossHi)) {
                        continue;
                    }
                    for (int x = 0; x < layout().schematic().widthX(); x++) {
                        if (!crossZ && (x < crossLo || x > crossHi)) {
                            continue;
                        }
                        CellScan cell = cellScan(sliceSchem, curPos, x, y, z);
                        if (cell == CellScan.MISMATCH || cell == CellScan.UNLOADED) {
                            return i;
                        }
                    }
                }
            }
        }

        // the whole scanned stretch is correct; report its length rather than 0, which is how far up
        // the highway the travel walk may head in one hop.
        return maxResult;
    }

    /**
     * Whether every chunk under the slices from {@code from} up to the slice we stand on is loaded.
     * The builder reads a cell in a chunk we haven't received as unbuilt and only looks at it again
     * once we stand within builderTickScanRadius of it, so a build started behind us before the world
     * has streamed in walks all the way back to cells that are already built.
     */
    public boolean isLoadedUpToFeet(BlockPos from) {
        Vec3 direction = new Vec3(layout().highwayDirection().getX(), layout().highwayDirection().getY(), layout().highwayDirection().getZ());
        Vec3 curPosPlayer = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BetterBlockPos feetClosest = layout().getClosestPoint(new Vec3(layout().originVector().x, layout().originVector().y, layout().originVector().z), direction, curPosPlayer, LocationType.HighwayBuild);
        int steps = layout().stepsAlongHighway(from, feetClosest);
        // a start level with or ahead of us leaves nothing behind to wait for
        return steps <= 0 || isStretchLoaded(from, layout().originVector(), layout().schematic().widthX(), layout().schematic().lengthZ(), 0, steps + 1);
    }

    private boolean isStretchLoaded(BlockPos startPos, Vec3 lineOrigin, int widthX, int lengthZ, int distanceToCheck) {
        // the correctness scans start one slice past their start, and so does what they need loaded
        return isStretchLoaded(startPos, lineOrigin, widthX, lengthZ, 1, distanceToCheck);
    }

    private boolean isStretchLoaded(BlockPos startPos, Vec3 lineOrigin, int widthX, int lengthZ, int firstSlice, int distanceToCheck) {
        return firstUnloadedCell(startPos, lineOrigin, widthX, lengthZ, firstSlice, distanceToCheck) == null;
    }

    BlockPos firstUnloadedCell(BlockPos startPos, Vec3 lineOrigin, int widthX, int lengthZ, int firstSlice, int distanceToCheck) {
        int scanT0 = layout().pattern().isCustom() ? layout().sliceIndexOf(startPos, lineOrigin) : 0;
        for (int i = distanceToCheck - 1; i >= firstSlice; i--) {
            BlockPos curPos = layout().pattern().isCustom()
                    ? startPos.offset(layout().sliceDelta(scanT0, i))
                    : startPos.offset(i * layout().highwayDirection().getX(), 0, i * layout().highwayDirection().getZ());
            for (int z = 0; z < lengthZ; z++) {
                for (int x = 0; x < widthX; x++) {
                    if (!baritone.bsi.worldContainsLoadedChunk(x + curPos.getX(), z + curPos.getZ())) {
                        return new BlockPos(x + curPos.getX(), curPos.getY(), z + curPos.getZ());
                    }
                }
            }
        }
        return null;
    }

    /** How far ahead {@code BuildingHighway} looks for lava and boats. */
    public ScanFront scanAheadFront(boolean builderPaused) {
        return scanFront(builderPaused || ctx.liquidRemoval().liquidThroughWalls()
                ? SCAN_AHEAD_BLOCKS_THROUGH_WALLS : SCAN_AHEAD_BLOCKS);
    }

    /** How far ahead the removal's re-scan looks: far enough to always re-find whatever the detection scan flagged. */
    public ScanFront liquidScanFront() {
        return scanFront(SCAN_AHEAD_BLOCKS_THROUGH_WALLS + LIQUID_SCAN_MARGIN_BLOCKS);
    }

    /**
     * Where a scan ahead of us ends: {@code blocks} in front of the block we stand in, cut square to
     * the heading the way {@link LookaheadWindow} cuts the builder's window.
     * <p>
     * A scan of whole slices ends on a slant on a diagonal. A diagonal slice is sqrt(2) blocks long
     * and runs at 45 degrees to travel, so with the scan counted in slices its far end reached 10 to
     * 18 blocks ahead on the two +X diagonals, depending on the side of the road and the width,
     * against 7 on a straight highway. Lava that far out started a removal that then had to dig all
     * the way there through netherrack the builder had not reached yet. On the two -X diagonals the
     * cross-section trails its slice instead, so one side of the road was only looked at 3.5 to 6
     * blocks ahead, too close for through-wall filling to see a pocket before its cover is in break
     * reach. With the cut every column ends between 7 and 8 blocks out, and a straight highway still
     * scans exactly the slices it always has.
     */
    public ScanFront scanFront(int blocks) {
        boolean custom = layout().pattern().isCustom();
        // the heading, unnormalised, as in scanFrontDistance: a whole period's offset on a pattern
        double headX = custom ? layout().pattern().periodWorldX() : layout().highwayDirection().getX();
        double headZ = custom ? layout().pattern().periodWorldZ() : layout().highwayDirection().getZ();
        double headLen = Math.sqrt(headX * headX + headZ * headZ);
        // Enough slices to reach the front moving at the least a slice can advance along the
        // heading - a whole step on a classic highway, a jog on a pattern - plus how far the widest
        // cross-section spreads along it, twice over: once for the columns that trail its anchor and
        // once for where the projection puts the anchor relative to our feet. The scans stop at the
        // first slice that lies wholly past the front, so overshooting costs nothing.
        double minAdvance = (custom ? Math.min(Math.abs(headX), Math.abs(headZ)) : headX * headX + headZ * headZ) / headLen;
        double spread = Math.max(alongSpread(layout().schematic(), headX, headZ), alongSpread(layout().liqCheckSchem(), headX, headZ)) / headLen;
        int slicesAhead = (int) Math.ceil((blocks + 2 * spread) / minAdvance) + 1;
        BetterBlockPos feet = playerContext.playerFeet();
        return new ScanFront(feet.getX(), feet.getZ(), headX / headLen, headZ / headLen, blocks, slicesAhead);
    }

    /** How far a cross-section's cells spread along an unnormalised heading, unnormalised. */
    private static double alongSpread(ISchematic crossSection, double headX, double headZ) {
        return (crossSection.widthX() - 1) * Math.abs(headX) + (crossSection.lengthZ() - 1) * Math.abs(headZ);
    }

    /**
     * @param headingX    with {@code headingZ}, the heading as a unit vector
     * @param slicesAhead slices past the one we stand on that a scan has to run to reach the front on
     *                    every column
     */
    public record ScanFront(int feetX, int feetZ, double headingX, double headingZ, double blocks, int slicesAhead) {

        /** Whether the block at x, z lies at or past the front. */
        boolean excludes(int x, int z) {
            return along(x, z) >= blocks;
        }

        /**
         * Whether the cross-section laid out from {@code origin} lies wholly past the front. A scan's
         * slices only ever move on along the heading, so every slice after it does as well.
         */
        boolean passed(BlockPos origin, ISchematic crossSection) {
            // the least along-the-heading offset of any of its cells from the corner it is laid out from
            double back = (crossSection.widthX() - 1) * Math.min(0, headingX) + (crossSection.lengthZ() - 1) * Math.min(0, headingZ);
            return along(origin.getX(), origin.getZ()) + back >= blocks;
        }

        private double along(int x, int z) {
            return (x - feetX) * headingX + (z - feetZ) * headingZ;
        }
    }

    public HighwayBlockState isHighwayCorrect(BlockPos startPos, BlockPos startPosLiq, int distanceToCheck, boolean renderLiquidScan) {
        return isHighwayCorrect(startPos, startPosLiq, distanceToCheck, null, renderLiquidScan);
    }

    /** @param front where to stop looking ahead of us, or null to scan all {@code distanceToCheck} slices */
    public HighwayBlockState isHighwayCorrect(BlockPos startPos, BlockPos startPosLiq, int distanceToCheck, ScanFront front, boolean renderLiquidScan) {
        // startPos needs to be in center of highway
        if (layout().endPos() != null) {
            // Terrain past the end stays unbuilt; scanning it would trigger endless fixes
            distanceToCheck = Math.min(distanceToCheck, layout().stepsAlongHighway(startPos, layout().endPos()) + 1);
        }
        renderLockBuilding.lock();
        renderBlocksBuilding.clear();
        lastMismatches.clear();
        boolean foundBlocks = false;
        int scanT0 = layout().pattern().isCustom() ? layout().sliceIndexOf(startPos, layout().originVector()) : 0;
        for (int i = 1; i < distanceToCheck; i++) {
            BlockPos curPos = layout().pattern().isCustom()
                    ? startPos.offset(layout().sliceDelta(scanT0, i))
                    : startPos.offset(i * layout().highwayDirection().getX(), 0, i * layout().highwayDirection().getZ());
            if (front != null && front.passed(curPos, layout().schematic())) {
                break;
            }
            CompositeSchematic sliceSchem = layout().schematicForSlice(scanT0 + i, curPos);
            for (int y = 0; y < layout().schematic().heightY(); y++) {
                for (int z = 0; z < layout().schematic().lengthZ(); z++) {
                    for (int x = 0; x < layout().schematic().widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        if (front != null && front.excludes(blockX, blockZ)) {
                            continue;
                        }
                        BlockState current = playerContext.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

                        if (!sliceSchem.inSchematic(x, y, z, current)) {
                            continue;
                        }
                        BlockState desiredState = sliceSchem.desiredState(x, y, z, current, this.approxPlaceable);
                        if (i == 1) {
                            if (desiredState.getBlock() instanceof AirBlock) {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.CYAN);
                            } else if (desiredState.getBlock().equals(Blocks.NETHERRACK)) {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.RED);
                            } else if (desiredState.getBlock().equals(Blocks.OBSIDIAN) || desiredState.getBlock().equals(Blocks.CRYING_OBSIDIAN)) {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.BLACK);
                            } else {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.GREEN);
                            }
                        }
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // we can directly observe this block, it is in render distance

                            ISchematic ourSchem = sliceSchem.getSchematic(x, y, z, current).schematic;
                            if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                                    MovementHelper.isBlockNormalCube(playerContext.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)))) {
                                continue;
                            }

                            if (!desiredState.equals(current)) {
                                if (current.getBlock() instanceof NetherPortalBlock) {
                                    // unbreakable directly; it pops on its own once the builder mines the frame
                                    continue;
                                }
                                if (!baritone.getBuilderProcess().checkNoEntityCollision(new AABB(new BlockPos(blockX, blockY, blockZ)), playerContext.player())) {
                                    List<Entity> entityList = playerContext.world().getEntities(null, new AABB(new BlockPos(blockX, blockY, blockZ)));
                                    for (Entity entity : entityList) {
                                        if (entity.getRootVehicle() instanceof AbstractBoat || entity.isVehicle()) {
                                            // can't do boats lol
                                            boatLocation = new BlockPos(blockX, blockY, blockZ);
                                            renderLockBuilding.unlock();
                                            return HighwayBlockState.Boat;
                                        }
                                        if (entity instanceof EndCrystal && entity.isAlive() && !ctx.threats().isCrystalIgnored(entity)) {
                                            // never moves, so no hole drops it: it has to be broken
                                            crystalLocation = new BlockPos(blockX, blockY, blockZ);
                                            renderLockBuilding.unlock();
                                            return HighwayBlockState.Crystal;
                                        }
                                    }
                                }

                                if (desiredState.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
                                    continue;
                                }
                                // Never should be liquids, except where the schematic covers them with a
                                // placed block (the support row under the floor, the diagonal rail supports).
                                // Those are ordinary build work: the liquid removal cannot even see them,
                                // since its scan starts at the walking level, so routing there just
                                // bounces between LiquidRemovalPrep and a builder restart forever.
                                if (current.getBlock() instanceof LiquidBlock
                                        && !(ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).coversLiquid(current))) {
                                    renderLockBuilding.unlock();
                                    lastLiquid = new BlockPos(blockX, blockY, blockZ);
                                    return HighwayBlockState.Liquids;
                                } else {
                                    foundBlocks = true;
                                    if (lastMismatches.size() < 4) {
                                        lastMismatches.add("(" + blockX + ", " + blockY + ", " + blockZ + ") "
                                                + BlockUtils.blockToString(current.getBlock()) + " -> " + BlockUtils.blockToString(desiredState.getBlock()));
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }
        renderLockBuilding.unlock();

        BlockPos liquidGround = findFirstLiquidGround(startPosLiq, distanceToCheck, front, renderLiquidScan);
        if (liquidGround != null) {
            lastLiquid = liquidGround;
            return HighwayBlockState.Liquids;
        }

        if (foundBlocks) {
            return HighwayBlockState.Blocks;
        }
        return HighwayBlockState.Air;
    }

    /** @param front where to stop looking ahead of us, or null to scan all {@code distanceToCheck} slices */
    public BlockPos findFirstLiquidGround(BlockPos startPos, int distanceToCheck, ScanFront front, boolean renderCheckedBlocks) {
        if (layout().endPos() != null) {
            distanceToCheck = Math.min(distanceToCheck, layout().stepsAlongHighway(startPos, layout().endPos()) + 1);
        }
        int scanT0 = layout().pattern().isCustom() ? layout().sliceIndexOf(startPos, layout().liqOriginVector()) : 0;
        if (renderCheckedBlocks) {
            BlockPos slice = layout().pattern().isCustom()
                    ? startPos.offset(layout().sliceDelta(scanT0, 1))
                    : startPos.offset(layout().highwayDirection().getX(), 0, layout().highwayDirection().getZ());
            AABB area = new AABB(slice.getX(), slice.getY(), slice.getZ(),
                    slice.getX() + layout().liqCheckSchem().widthX(), slice.getY() + layout().liqCheckSchem().heightY(), slice.getZ() + layout().liqCheckSchem().lengthZ());
            renderLockLiquid.lock();
            renderAreaLiquid = area;
            renderLockLiquid.unlock();
        }
        //Liquid Checking all around
        for (int i = 1; i < distanceToCheck; i++) {
            BlockPos curPos = layout().pattern().isCustom()
                    ? startPos.offset(layout().sliceDelta(scanT0, i))
                    : startPos.offset(i * layout().highwayDirection().getX(), 0, i * layout().highwayDirection().getZ());
            if (front != null && front.passed(curPos, layout().liqCheckSchem())) {
                break;
            }
            for (int y = 0; y < layout().liqCheckSchem().heightY(); y++) {
                for (int z = 0; z < layout().liqCheckSchem().lengthZ(); z++) {
                    for (int x = 0; x < layout().liqCheckSchem().widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        if (front != null && front.excludes(blockX, blockZ)) {
                            continue;
                        }
                        BlockState current = playerContext.world().getBlockState(new BlockPos(blockX, blockY, blockZ));
                        if (!layout().liqCheckSchem().inSchematic(x, y, z, current)) {
                            continue;
                        }
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // Never should be liquids
                            if (current.getBlock() instanceof LiquidBlock) {
                                return new BlockPos(blockX, blockY, blockZ);
                            }
                        }

                    }
                }
            }
        }
        return null;
    }

    public BlockPos boatLocation() {
        return boatLocation;
    }

    public BlockPos crystalLocation() {
        return crystalLocation;
    }

    /**
     * The hole that drops whatever is in the way at {@code blocked} - a boat, or anything with a
     * rider - out of the highway: dug under its own footprint only, and deep enough for it to end
     * up below every floor, rail or support cell the hole takes out, since those get built back
     * over it.
     *
     * @return the hole's cells, empty once nothing is in the way; null if it can't be dropped
     */
    public List<BlockPos> entityDropHole(BlockPos blocked) {
        Entity self = playerContext.player().getRootVehicle();
        List<Entity> roots = playerContext.world().getEntities((Entity) null, new AABB(blocked), e -> e.isAlive() && e.blocksBuilding)
                .stream()
                .map(Entity::getRootVehicle)
                .filter(root -> root != self)
                .toList();
        if (roots.isEmpty()) {
            return List.of();
        }
        // the boat or ridden entity that sent us here, over anything else standing in the same cell
        Entity root = roots.stream()
                .filter(r -> r instanceof AbstractBoat || r.isVehicle())
                .findFirst()
                .orElse(roots.get(0));
        if (!EntityHole.falls(root)) {
            return null;
        }
        Set<BlockPos> refill = highwayBlockCellsNear(blocked, ENTITY_HOLE_SLICES);
        return EntityHole.plan(playerContext.world(), EntityHole.groupBox(root), blocked.getY(), refill::contains);
    }

    /**
     * Cells within {@code slices} slices of {@code pos} that the highway puts a block in when
     * they're empty: the floor, the rails and the supports under them.
     */
    private Set<BlockPos> highwayBlockCellsNear(BlockPos pos, int slices) {
        Set<BlockPos> cells = new HashSet<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos start = layout().sliceAlongLine(layout().originVector(), new Vec3(pos.getX(), pos.getY(), pos.getZ()), -slices, LocationType.HighwayBuild);
        int count = 2 * slices + 1;
        if (layout().endPos() != null) {
            count = Math.min(count, layout().stepsAlongHighway(start, layout().endPos()) + 1);
        }
        int t0 = layout().pattern().isCustom() ? layout().sliceIndexOf(start, layout().originVector()) : 0;
        for (int i = 0; i < count; i++) {
            BlockPos anchor = layout().pattern().isCustom()
                    ? start.offset(layout().sliceDelta(t0, i))
                    : start.offset(i * layout().highwayDirection().getX(), 0, i * layout().highwayDirection().getZ());
            CompositeSchematic slice = layout().schematicForSlice(t0 + i, anchor);
            for (int y = 0; y < slice.heightY(); y++) {
                for (int z = 0; z < slice.lengthZ(); z++) {
                    for (int x = 0; x < slice.widthX(); x++) {
                        if (slice.inSchematic(x, y, z, air)
                                && !(slice.desiredState(x, y, z, air, this.approxPlaceable).getBlock() instanceof AirBlock)) {
                            // plain BlockPos: EntityHole looks these up with plain ones
                            cells.add(new BlockPos(anchor.getX() + x, anchor.getY() + y, anchor.getZ() + z));
                        }
                    }
                }
            }
        }
        return cells;
    }
}
