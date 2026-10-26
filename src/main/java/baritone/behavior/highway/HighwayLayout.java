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
import baritone.api.Settings;
import baritone.api.schematic.CellOverrides;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Where the highway runs: its direction, pattern and schematics, the parallel lines everything along
 * it is anchored to, and the start and end it is clamped to. nhwbuild works it out once and it never
 * changes after that; the next nhwbuild builds a new one. Also the slice arithmetic for stepping
 * along those lines.
 */
public class HighwayLayout {
    private final Baritone baritone;
    private final Settings settings;

    private final int highwayCheckBackDistance = 32;

    private final Vec3i highwayDirection;
    private final HighwayPattern pattern;
    private final boolean paving;
    /** highwayCellOverrides as parsed at nhwbuild; NONE off a lane. */
    private final CellOverrides cellOverrides;
    private final CompositeSchematic schematic;
    /** Per-slice cross-sections for an angled build with rails; null when every slice is the same. */
    private final CompositeSchematic[] patternSchematics;
    private final WhiteBlackSchematic liqCheckSchem;
    private final Vec3 originVector;
    private final Vec3 liqOriginVector;
    private final Vec3 backPathOriginVector;
    private final Vec3 eChestEmptyShulkOriginVector;
    // the side-storage line mirrored to the road's other side, c=W+2
    private final Vec3 farSideStorageOriginVector;
    private final BetterBlockPos firstStartingPos; // projections never land behind it; null = not clamped
    private final BetterBlockPos endPos; // on the highway line at HighwayBuild Y; null = build forever

    private HighwayLayout(Builder b, BetterBlockPos firstStartingPos, BetterBlockPos endPos) {
        this.baritone = b.baritone;
        this.settings = b.settings;
        this.highwayDirection = b.highwayDirection;
        this.pattern = b.pattern;
        this.paving = b.paving;
        this.cellOverrides = b.cellOverrides;
        this.schematic = b.schematic;
        this.patternSchematics = b.patternSchematics;
        this.liqCheckSchem = b.liqCheckSchem;
        this.originVector = b.originVector;
        this.liqOriginVector = b.liqOriginVector;
        this.backPathOriginVector = b.backPathOriginVector;
        this.eChestEmptyShulkOriginVector = b.eChestEmptyShulkOriginVector;
        this.farSideStorageOriginVector = b.farSideStorageOriginVector;
        this.firstStartingPos = firstStartingPos;
        this.endPos = endPos;
    }

    /** The layout before any nhwbuild: no schematic, so no highway to build. */
    static HighwayLayout none(Baritone baritone, Settings settings) {
        return new Builder(baritone, settings).build();
    }

    /**
     * This layout clamped to a build's start and end, so no projection lands behind the start or
     * past the end. Null leaves that side open.
     */
    HighwayLayout withBounds(BetterBlockPos firstStartingPos, BetterBlockPos endPos) {
        return new HighwayLayout(new Builder(this), firstStartingPos, endPos);
    }

    public Vec3 originVector() {
        return originVector;
    }

    public Vec3i highwayDirection() {
        return highwayDirection;
    }

    public CompositeSchematic schematic() {
        return schematic;
    }

    public CellOverrides cellOverrides() {
        return cellOverrides;
    }

    /** {@code schematic} with the cell overrides applied, its origin at {@code origin}. */
    public CompositeSchematic bindCellOverrides(CompositeSchematic schematic, Vec3i origin) {
        return cellOverrides.bind(schematic, origin);
    }

    public BetterBlockPos firstStartingPos() {
        return firstStartingPos;
    }

    public BetterBlockPos endPos() {
        return endPos;
    }

    public Vec3 liqOriginVector() {
        return liqOriginVector;
    }

    public Vec3 backPathOriginVector() {
        return backPathOriginVector;
    }

    public Vec3 eChestEmptyShulkOriginVector() {
        return eChestEmptyShulkOriginVector;
    }

    public Vec3 farSideStorageOriginVector() {
        return farSideStorageOriginVector;
    }

    public WhiteBlackSchematic liqCheckSchem() {
        return liqCheckSchem;
    }

    public HighwayPattern pattern() {
        return pattern;
    }

    /**
     * Slice index of an anchor relative to the given parallel line's origin. Exact: the minor
     * coordinate disambiguates the slices that share a major coordinate at the jogs, so this must
     * be called with the origin of the line the anchor actually lies on. Custom patterns only.
     */
    int sliceIndexOf(BlockPos anchor, Vec3 lineOrigin) {
        int ox = (int) Math.round(lineOrigin.x);
        int oz = (int) Math.round(lineOrigin.z);
        int qMajor = (pattern.majorIsX() ? anchor.getX() - ox : anchor.getZ() - oz) * pattern.majorSign();
        int qMinor = (pattern.majorIsX() ? anchor.getZ() - oz : anchor.getX() - ox) * pattern.minorSign();
        return pattern.sliceIndex(qMajor, qMinor);
    }

    /**
     * Slice index from the major (driving) coordinate alone, relative to originVector. All the
     * parallel lines share their major origin component, so this works for points on any of them
     * (and for points off the path entirely). Ambiguous by the jog run length - the last slice at
     * that coordinate is returned - so use it where a one-slice phase error is harmless.
     */
    int sliceIndexNear(double worldX, double worldZ) {
        int q = (int) ((pattern.majorIsX() ? Math.round(worldX) - Math.round(originVector.x)
                : Math.round(worldZ) - Math.round(originVector.z)) * pattern.majorSign());
        return pattern.sliceFromMajor(q);
    }

    /** World-space offset from slice t's anchor to slice (t + slices)'s anchor. */
    Vec3i sliceDelta(int t, int slices) {
        return pattern.worldDelta(t, slices);
    }

    /** The parallel line that anchors of a given kind sit on. */
    private Vec3 lineOriginFor(LocationType locType) {
        return switch (locType) {
            case HighwayBuild -> originVector;
            case ShulkerEchestInteraction -> backPathOriginVector;
            case SideStorage -> eChestEmptyShulkOriginVector;
        };
    }

    /**
     * The anchor {@code slices} along the highway from {@code from}, which has to lie on the line
     * {@code locType} names. On a classic highway that is a plain multiple of {@link #highwayDirection};
     * under a custom pattern the direction vector only carries the signs - stepping by it walks a 45
     * degree diagonal away from the path - so the offset comes from the pattern's slice arithmetic.
     *
     * <p>Deliberately not re-projected, unlike {@link #sliceAlongLine}: the placing scans walk back
     * onto pavement laid before this build started, and the build-start clamp in
     * {@link #getClosestPoint} would snap that away.
     */
    public BetterBlockPos alongLine(BlockPos from, LocationType locType, int slices) {
        if (!pattern.isCustom()) {
            return new BetterBlockPos(from.offset(slices * highwayDirection.getX(), 0, slices * highwayDirection.getZ()));
        }
        int t = sliceIndexOf(from, lineOriginFor(locType));
        return new BetterBlockPos(from.offset(pattern.laneDelta(t, slices, lowSideLane(locType))));
    }

    /**
     * Whether a line has to stay outside the road on the low-cross side, which is the side-storage
     * lane only: at a jogged column the road is the union of the slices sharing it, so that lane
     * tracks the outermost of them rather than each slice's own cross-section. See
     * {@link HighwayPattern#lowSideSlice}.
     */
    private static boolean lowSideLane(LocationType locType) {
        return locType == LocationType.SideStorage;
    }

    /**
     * Like {@link #alongLine}, but for a spot to stand on while working on {@code from}: the offset
     * is walked on until the cell it lands in is the {@code |slices|} cells clear of {@code from}
     * that the same offset leaves on a straight highway.
     *
     * <p>An angled pattern's slice steps one world axis, so two slices taken across a jog advance
     * one block on each axis and land diagonally touching the spot instead of a cell clear of it.
     * A body standing anywhere in a touching cell reaches 0.3 over the shared corner, and a cell an
     * entity overlaps is one {@code BlockItem.place} refuses to build into - which is the "no gap
     * left, placement blocked forever" the two-back flows exist to avoid. Cells two apart on either
     * axis can never be reached over, so that is the gap to insist on.
     *
     * <p>On the side-storage lane a slice step can also stand still, since a jogged column has one
     * lane anchor for all the slices sharing it; the same walk-on covers that.
     */
    public BetterBlockPos standOffAlongLine(BlockPos from, LocationType locType, int slices) {
        return alongLine(from, locType, standOffSlices(from, locType, slices));
    }

    /** The slice offset {@link #standOffAlongLine} settles on; scans that walk the lane need the count itself. */
    public int standOffSlices(BlockPos from, LocationType locType, int slices) {
        if (!pattern.isCustom()) {
            return slices; // a cardinal step is one cell along, a diagonal one a diagonal cell: both already clear
        }
        return pattern.standOffSlices(sliceIndexOf(from, lineOriginFor(locType)), slices, lowSideLane(locType));
    }

    /** True when the cross-section varies from slice to slice (an angled build with rails). */
    public boolean hasPatternSchematics() {
        return patternSchematics != null;
    }

    /**
     * The cross-section slice {@code t} is built and checked against. Identical for every slice
     * unless the build is an angled one with rails, where slices sharing a major coordinate have to
     * split the rail columns between them - see {@link HighwayPattern#ownsLowRail}. All the variants
     * report the same dimensions as {@link #schematic()}, so scans keep sizing their loops against
     * that one and only the cell queries go through here.
     */
    public CompositeSchematic schematicForSlice(int t) {
        if (patternSchematics == null) {
            return schematic;
        }
        return patternSchematics[(pattern.ownsLowRail(t) ? 1 : 0) | (pattern.ownsHighRail(t) ? 2 : 0)];
    }

    /**
     * {@link #schematicForSlice} for slice {@code t} anchored at {@code slicePos}, with the cell
     * overrides applied. What every scan has to query: the overrides are world boxes.
     */
    public CompositeSchematic schematicForSlice(int t, BlockPos slicePos) {
        return cellOverrides.bind(schematicForSlice(t), slicePos);
    }

    /** The slice a build origin anchors, as the builder builds it there. */
    public CompositeSchematic schematicForOrigin(BlockPos origin) {
        if (patternSchematics == null) {
            return cellOverrides.bind(schematic, origin);
        }
        return schematicForSlice(sliceIndexOf(origin, originVector), origin);
    }

    /**
     * Origin advance for the repeating builder: from the slice the current origin anchors to the
     * next one. A pure function of position, so builder restarts and detours can never desync the
     * pattern phase (the old stateful cursor did exactly that).
     */
    public Vec3i repeatAdvance(BlockPos currentOrigin) {
        if (!pattern.isCustom()) {
            return new Vec3i(highwayDirection.getX(), 0, highwayDirection.getZ());
        }
        return sliceDelta(sliceIndexOf(currentOrigin, originVector), 1);
    }

    public boolean paving() {
        return paving;
    }

    public int highwayCheckBackDistance() {
        return highwayCheckBackDistance;
    }

    public int highwayFeetY() {
        return settings.highwayMainY.value + (paving ? 1 : 0);
    }

    public int highwayFloorY() {
        return highwayFeetY() - 1;
    }

    /**
     * Lifts a point on the walking/placement lane onto the pavement when the road under it is
     * already obsidian. A digging-only run aims at highwayMainY because that's the floor it digs
     * down to, but a dig that starts on (or runs through) an already-paved stretch finds obsidian
     * sitting there: the shulker, the ender chest and our own feet all belong one block up, exactly
     * where a paving run puts them. Without this the bot mines the road out from under itself and
     * then keeps failing to place into the block it just tried to stand in.
     */
    public BetterBlockPos liftOntoPavement(BetterBlockPos lanePos) {
        if (paving) {
            return lanePos; // already one above the obsidian the printer lays
        }
        Block at = baritone.bsi.get0(lanePos.x, lanePos.y, lanePos.z).getBlock();
        return (at == Blocks.OBSIDIAN || at == Blocks.CRYING_OBSIDIAN) ? lanePos.above() : lanePos;
    }

    public BetterBlockPos getClosestPoint(Vec3 origin, Vec3 direction, Vec3 point, LocationType locType) {
        int yLevel = switch (locType) {
            case HighwayBuild -> settings.highwayLowestY.value;
            case ShulkerEchestInteraction -> paving ? settings.highwayMainY.value + 1 : settings.highwayMainY.value;
            case SideStorage -> settings.highwayEmptyShulkEchestY.value;
        };

        if (pattern.isCustom()) {
            // Project onto the pattern path instead of a straight line. Slice indices are shared
            // across the parallel lines because build() gives them all the same major-axis origin
            // component, so the start/end clamps (computed on the build line) apply directly.
            long ox = Math.round(origin.x);
            long oz = Math.round(origin.z);
            int qMajor = (int) ((pattern.majorIsX() ? Math.round(point.x) - ox : Math.round(point.z) - oz) * pattern.majorSign());
            int qMinor = (int) ((pattern.majorIsX() ? Math.round(point.z) - oz : Math.round(point.x) - ox) * pattern.minorSign());
            int t = pattern.sliceIndex(qMajor, qMinor);
            if (firstStartingPos != null) {
                t = Math.max(t, sliceIndexOf(firstStartingPos, originVector));
            }
            if (endPos != null) {
                t = Math.min(t, sliceIndexOf(endPos, originVector));
            }
            boolean lowSide = lowSideLane(locType);
            return new BetterBlockPos((int) (ox + pattern.laneOffsetX(t, lowSide)), yLevel, (int) (oz + pattern.laneOffsetZ(t, lowSide)));
        }

        // Project onto the highway line, but round the along-line step count once and derive both
        // coordinates from it in integer space. Rounding x and z independently after a double
        // projection can disagree on half-integer results and return a point one block off the
        // line, which laterally shifted offset highways whenever the build restarted.
        long ox = Math.round(origin.x);
        long oz = Math.round(origin.z);
        long dirX = Math.round(direction.x);
        long dirZ = Math.round(direction.z);
        long lenSq = dirX * dirX + dirZ * dirZ;
        if (lenSq == 0) {
            return new BetterBlockPos((int) ox, yLevel, (int) oz);
        }
        long steps = Math.round(((point.x - ox) * dirX + (point.z - oz) * dirZ) / lenSq);
        // Never allow points behind the original starting location. Clamped in step
        // space, after projection: the raw-coordinate test this replaces read
        // laterally offset points (a parallel lane, a diagonal's cross component)
        // as behind and snapped them onto the start.
        if (firstStartingPos != null) {
            long startSteps = Math.round(((firstStartingPos.getX() - ox) * dirX + (firstStartingPos.getZ() - oz) * dirZ) / (double) lenSq);
            steps = Math.max(steps, startSteps);
        }
        // Mirror of the firstStartingPos clamp: never allow points past the set end
        if (endPos != null) {
            long endSteps = dirX != 0 ? (endPos.getX() - ox) * dirX : (endPos.getZ() - oz) * dirZ;
            steps = Math.min(steps, endSteps);
        }
        return new BetterBlockPos((int) (ox + steps * dirX), yLevel, (int) (oz + steps * dirZ));
    }

    /**
     * Signed slice count from {@code from} to {@code to} measured on the driving axis, so points on
     * parallel lines (build/liquid/side-storage) agree on where a given slice ends. Negative means
     * {@code to} is behind {@code from}.
     */
    public int stepsAlongHighway(BlockPos from, BlockPos to) {
        if (pattern.isCustom()) {
            // Count slices, not blocks: the major axis no longer advances one block per slice.
            // Major-only indexing keeps the original property that points on parallel lines
            // (offset on the cross axis only) agree on where a slice ends.
            return sliceIndexNear(to.getX(), to.getZ()) - sliceIndexNear(from.getX(), from.getZ());
        }
        return highwayDirection.getX() != 0
                ? (to.getX() - from.getX()) * highwayDirection.getX()
                : (to.getZ() - from.getZ()) * highwayDirection.getZ();
    }

    /**
     * Blocks of travel per schematic slice: 1 on a straight highway, sqrt(2) on a diagonal, and a
     * period's length over its slice count on an angled pattern, whose slices step one world axis
     * each and so advance the heading by a fraction of a block apiece.
     */
    double stepLength() {
        if (pattern.isCustom()) {
            return Math.hypot(pattern.periodWorldX(), pattern.periodWorldZ()) / pattern.periodSlices();
        }
        return Math.sqrt(highwayDirection.getX() * highwayDirection.getX()
                + highwayDirection.getZ() * highwayDirection.getZ());
    }

    /**
     * Slice count covering at least {@code blocks} blocks of travel along the highway. Never fewer
     * slices than blocks: a diagonal slice is sqrt(2) blocks long, and converting the scans that
     * were written as slice counts down to the same distance in blocks would shorten lookahead the
     * classic builds have always had. An angled pattern's slices are shorter than a block, so there
     * this hands back more slices than the block count, which is the whole point.
     */
    public int slicesForBlocks(double blocks) {
        return (int) Math.max(Math.ceil(blocks), Math.ceil(blocks / stepLength()));
    }

    /** World offset from the slice at {@code t0} to the one {@code steps} further up the highway. */
    Vec3i sliceOffset(int t0, int steps) {
        return pattern.isCustom()
                ? sliceDelta(t0, steps)
                : new Vec3i(steps * highwayDirection.getX(), 0, steps * highwayDirection.getZ());
    }

    /**
     * Signed index of the cross-section column the given block sits in, measured across the highway
     * from its centre line. Steps along the highway don't change it, on diagonals as well as
     * straights: the perpendicular of an unnormalised (dx, dz) is exactly one per column and zero
     * per slice for all eight directions. Any point on the line works as the reference, since a
     * perpendicular kills the along-line component.
     */
    public int lateralColumn(int x, int z) {
        int ox = (int) Math.round(originVector.x);
        int oz = (int) Math.round(originVector.z);
        if (pattern.isCustom()) {
            // Column relative to the block's own slice anchor, so the jogs don't drift the
            // banding. The last-slice convention makes it deterministic; a block in a pre-jog
            // slice reads one column high, which is harmless for the strip sweep.
            int t = sliceIndexNear(x, z);
            return (pattern.majorIsX() ? (z - oz) : (x - ox)) * pattern.minorSign() - pattern.minorOffset(t);
        }
        return -(x - ox) * highwayDirection.getZ() + (z - oz) * highwayDirection.getX();
    }

    /**
     * The cross-section column the given block sits in, counted the way the schematic counts: 0 is
     * the low-side rail, 1 to {@code highwayWidth} the walk lane, {@code highwayWidth + 1} the
     * high-side rail. That is {@link #lateralColumn} with the direction's sign taken back out, so
     * the same column reads the same number whichever of the eight directions we're heading.
     */
    public int crossSectionColumn(int x, int z) {
        // Group A lays the cross-section out along Z and group B along X; the perpendicular grows
        // one per column in both, up to this sign. Same crossPerp scanFrontDistance measures with.
        int crossPerp = NetherHighwayBuilderBehavior.isGroupA(highwayDirection)
                ? highwayDirection.getX() : -highwayDirection.getZ();
        return crossPerp < 0 ? -lateralColumn(x, z) : lateralColumn(x, z);
    }

    /** Whether the given block is in the walk lane rather than one of the rail columns beside it. */
    public boolean inWalkLane(int x, int z) {
        int column = crossSectionColumn(x, z);
        return column >= 1 && column <= settings.highwayWidth.value;
    }

    /** The yaw that points straight down the highway direction. */
    public float highwayDirectionYaw() {
        if (pattern.isCustom()) {
            return (float) Math.toDegrees(Math.atan2(-pattern.periodWorldX(), pattern.periodWorldZ()));
        }
        return (float) Math.toDegrees(Math.atan2(-highwayDirection.getX(), highwayDirection.getZ()));
    }

    /** A block's length straight down the highway, the way the walk creep walks it. */
    public Vec3 highwayHeading() {
        double dirX = pattern.isCustom() ? pattern.periodWorldX() : highwayDirection.getX();
        double dirZ = pattern.isCustom() ? pattern.periodWorldZ() : highwayDirection.getZ();
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        return len == 0 ? Vec3.ZERO : new Vec3(dirX / len, 0, dirZ / len);
    }

    /**
     * The anchor {@code slices} slices up the highway - negative walks back down it - from
     * {@code point} projected onto the parallel line at {@code lineOrigin}.
     *
     * <p>Callers used to offset {@code point} by the direction vector in world blocks and let
     * {@link #getClosestPoint} re-project it. A straight or 45-degree highway travels one block on
     * every axis it uses per slice, so that landed exactly {@code slices} slices away; an angled
     * pattern advances its minor axis a fraction of a block per slice, so subtracting the same
     * count from both axes overshoots by the period-to-major ratio - 32 blocks back is 46 slices
     * back on XXZXXZXXXZ. Scan lengths are counted in slices, so that overshoot came straight off
     * the far end: the correctness and liquid scans finished several slices behind the bot instead
     * of looking ahead of it, and lava in front was never seen at all.
     */
    public BetterBlockPos sliceAlongLine(Vec3 lineOrigin, Vec3 point, int slices, LocationType locType) {
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        BetterBlockPos on = getClosestPoint(lineOrigin, direction, point, locType);
        Vec3i step = sliceOffset(pattern.isCustom() ? sliceIndexOf(on, lineOrigin) : 0, slices);
        // back through the projection for the start and end clamps
        return getClosestPoint(lineOrigin, direction,
                new Vec3(on.getX() + step.getX(), on.getY(), on.getZ() + step.getZ()), locType);
    }

    /** Collects a layout piece by piece while nhwbuild works it out, then builds it unclamped. */
    static final class Builder {
        private final Baritone baritone;
        private final Settings settings;
        private Vec3i highwayDirection = new Vec3i(1, 0, -1);
        private HighwayPattern pattern = HighwayPattern.NONE;
        private boolean paving = false;
        private CellOverrides cellOverrides = CellOverrides.NONE;
        private CompositeSchematic schematic;
        private CompositeSchematic[] patternSchematics;
        private WhiteBlackSchematic liqCheckSchem;
        private Vec3 originVector = new Vec3(0, 0, 0);
        private Vec3 liqOriginVector = new Vec3(0, 0, 0);
        private Vec3 backPathOriginVector = new Vec3(0, 0, 0);
        private Vec3 eChestEmptyShulkOriginVector = new Vec3(0, 0, 0);
        private Vec3 farSideStorageOriginVector = new Vec3(0, 0, 0);

        Builder(Baritone baritone, Settings settings) {
            this.baritone = baritone;
            this.settings = settings;
        }

        private Builder(HighwayLayout from) {
            this(from.baritone, from.settings);
            highwayDirection = from.highwayDirection;
            pattern = from.pattern;
            paving = from.paving;
            cellOverrides = from.cellOverrides;
            schematic = from.schematic;
            patternSchematics = from.patternSchematics;
            liqCheckSchem = from.liqCheckSchem;
            originVector = from.originVector;
            liqOriginVector = from.liqOriginVector;
            backPathOriginVector = from.backPathOriginVector;
            eChestEmptyShulkOriginVector = from.eChestEmptyShulkOriginVector;
            farSideStorageOriginVector = from.farSideStorageOriginVector;
        }

        HighwayLayout build() {
            return new HighwayLayout(this, null, null);
        }

        void setHighwayDirection(Vec3i highwayDirection) {
            this.highwayDirection = highwayDirection;
        }

        void setPattern(HighwayPattern pattern) {
            this.pattern = pattern == null ? HighwayPattern.NONE : pattern;
        }

        void setPaving(boolean paving) {
            this.paving = paving;
        }

        void setCellOverrides(CellOverrides cellOverrides) {
            this.cellOverrides = cellOverrides == null ? CellOverrides.NONE : cellOverrides;
        }

        void setSchematic(CompositeSchematic schematic) {
            this.schematic = schematic;
        }

        void setPatternSchematics(CompositeSchematic[] patternSchematics) {
            this.patternSchematics = patternSchematics;
        }

        void setLiqCheckSchem(WhiteBlackSchematic liqCheckSchem) {
            this.liqCheckSchem = liqCheckSchem;
        }

        void setOriginVector(Vec3 originVector) {
            this.originVector = originVector;
        }

        Vec3 liqOriginVector() {
            return liqOriginVector;
        }

        void setLiqOriginVector(Vec3 liqOriginVector) {
            this.liqOriginVector = liqOriginVector;
        }

        void setBackPathOriginVector(Vec3 backPathOriginVector) {
            this.backPathOriginVector = backPathOriginVector;
        }

        Vec3 eChestEmptyShulkOriginVector() {
            return eChestEmptyShulkOriginVector;
        }

        void seteChestEmptyShulkOriginVector(Vec3 eChestEmptyShulkOriginVector) {
            this.eChestEmptyShulkOriginVector = eChestEmptyShulkOriginVector;
        }

        void setFarSideStorageOriginVector(Vec3 farSideStorageOriginVector) {
            this.farSideStorageOriginVector = farSideStorageOriginVector;
        }
    }
}
