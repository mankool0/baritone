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

import net.minecraft.core.Vec3i;

/**
 * Closed-form description of an angled ("off-axis") highway path.
 *
 * <p>An angled highway is a repeating sequence of one-block slice steps, e.g. the string
 * {@code XXZXXZXXXZ} means seven +X slices and three +Z slices per ten-slice period, i.e. a
 * heading of atan(3/7) &asymp; 23&deg; off the X axis. The axis with more steps is the
 * <b>major</b> axis (the driving axis), the other is the <b>minor</b> (jog) axis. The actual
 * signs of travel come from the direction the build was requested with.
 *
 * <p>Everything here is a pure function of the slice index {@code t} (or its inverse from
 * coordinates). There is deliberately <b>no mutable state</b>: the previous implementation kept a
 * pattern cursor that had to be reset, and it desynced from world position on every builder
 * restart (restock, liquid removal, invalid-block fix, ...). Deriving the phase from position
 * makes restarts, detours and re-anchoring inherently consistent.
 *
 * <p>Slice index {@code t} is defined relative to a line origin: the anchor of slice {@code t} on
 * a given parallel line (build line, liquid line, walk lane, side storage) is
 * {@code lineOrigin + (worldOffsetX(t), 0, worldOffsetZ(t))}. All four parallel lines set up by
 * {@code build()} share the same major-axis origin component (they are offset from each other on
 * the cross axis only), so a slice index computed against any of them refers to the same physical
 * cross-section. {@code HighwayContext} relies on that invariant.
 *
 * <p>{@link #NONE} is the marker for classic straight/diagonal highways; when it is in effect,
 * every caller keeps the original straight-line arithmetic verbatim, so classic behaviour is
 * bit-for-bit unchanged.
 */
public final class HighwayPattern {

    /** Marker instance: no custom pattern, classic straight-line highway behaviour applies. */
    public static final HighwayPattern NONE = new HighwayPattern();

    private final boolean custom;
    /** True when the major (driving) axis is world X, false when it is world Z. */
    private final boolean majorIsX;
    /** Sign (+1/-1) of travel along the major world axis. */
    private final int majorSign;
    /** Sign (+1/-1) of travel along the minor world axis. */
    private final int minorSign;
    /** Slices per period. */
    private final int period;
    /** Major-axis blocks advanced per period. */
    private final int periodMajor;
    /** Minor-axis blocks advanced per period. */
    private final int periodMinor;
    /** prefixMajor[k] = major-axis blocks advanced before slice k of the period, k in [0, period]. */
    private final int[] prefixMajor;
    /** prefixMinor[k] = minor-axis blocks advanced before slice k of the period, k in [0, period]. */
    private final int[] prefixMinor;
    /** lastSliceForMajor[r] = largest k in [0, period) with prefixMajor[k] == r, r in [0, periodMajor). */
    private final int[] lastSliceForMajor;
    private final String source;

    private HighwayPattern() {
        this.custom = false;
        this.majorIsX = true;
        this.majorSign = 1;
        this.minorSign = 1;
        this.period = 1;
        this.periodMajor = 1;
        this.periodMinor = 0;
        this.prefixMajor = new int[]{0, 1};
        this.prefixMinor = new int[]{0, 0};
        this.lastSliceForMajor = new int[]{0};
        this.source = "";
    }

    private HighwayPattern(String source, boolean[] stepIsMajor, boolean majorIsX, int majorSign, int minorSign) {
        this.custom = true;
        this.source = source;
        this.majorIsX = majorIsX;
        this.majorSign = majorSign;
        this.minorSign = minorSign;
        this.period = stepIsMajor.length;
        this.prefixMajor = new int[period + 1];
        this.prefixMinor = new int[period + 1];
        for (int k = 0; k < period; k++) {
            prefixMajor[k + 1] = prefixMajor[k] + (stepIsMajor[k] ? 1 : 0);
            prefixMinor[k + 1] = prefixMinor[k] + (stepIsMajor[k] ? 0 : 1);
        }
        this.periodMajor = prefixMajor[period];
        this.periodMinor = prefixMinor[period];
        this.lastSliceForMajor = new int[periodMajor];
        for (int k = 0; k < period; k++) {
            if (prefixMajor[k] < periodMajor) {
                lastSliceForMajor[prefixMajor[k]] = Math.max(lastSliceForMajor[prefixMajor[k]], k);
            }
        }
    }

    /**
     * Parse the {@code highwayPattern} setting against the requested build direction.
     *
     * <p>The pattern string is a sequence of {@code X} and {@code Z} characters in absolute world
     * axes, one per slice, e.g. {@code XXZXXZXXXZ}. The direction supplies the signs: a pattern
     * that steps in X needs {@code direction.x != 0}, one that steps in Z needs
     * {@code direction.z != 0}; for a two-axis pattern that means requesting a diagonal quadrant
     * such as {@code ne}. Patterns using only one axis, or using both axes equally (that is just a
     * 45&deg; diagonal), fall back to {@link #NONE} so the classic code path runs.
     *
     * @throws IllegalArgumentException with a user-readable message when the pattern string is
     *                                  malformed or incompatible with the direction
     */
    public static HighwayPattern parse(String setting, Vec3i direction) {
        if (setting == null) {
            return NONE;
        }
        String s = setting.trim().toUpperCase();
        if (s.isEmpty()) {
            return NONE;
        }
        if (s.length() > 128) {
            throw new IllegalArgumentException("pattern is longer than 128 slices");
        }
        int countX = 0;
        int countZ = 0;
        for (char c : s.toCharArray()) {
            if (c == 'X') {
                countX++;
            } else if (c == 'Z') {
                countZ++;
            } else {
                throw new IllegalArgumentException("pattern may only contain X and Z, got '" + c + "'");
            }
        }
        if (countX == 0 || countZ == 0) {
            // single-axis pattern is just a cardinal highway
            return NONE;
        }
        if (countX == countZ) {
            // equal steps is a 45 degree diagonal; the classic diagonal build does that natively
            // (and does it better: true diagonal slices instead of a one-block staircase)
            return NONE;
        }
        boolean majorIsX = countX > countZ;
        int sx = Integer.signum(direction.getX());
        int sz = Integer.signum(direction.getZ());
        if (sx == 0 || sz == 0) {
            throw new IllegalArgumentException("pattern " + s + " steps in both X and Z, so the build direction has to name"
                    + " both signs (e.g. 1 1, not 1 0) - it only supplies the signs, the angle comes from the pattern. Got " + direction);
        }
        boolean[] stepIsMajor = new boolean[s.length()];
        for (int i = 0; i < s.length(); i++) {
            stepIsMajor[i] = (s.charAt(i) == 'X') == majorIsX;
        }
        int majorSign = majorIsX ? sx : sz;
        int minorSign = majorIsX ? sz : sx;
        return new HighwayPattern(s, stepIsMajor, majorIsX, majorSign, minorSign);
    }

    public boolean isCustom() {
        return custom;
    }

    public boolean majorIsX() {
        return majorIsX;
    }

    public int majorSign() {
        return majorSign;
    }

    public int minorSign() {
        return minorSign;
    }

    public int periodSlices() {
        return period;
    }

    /** Signed world-X blocks advanced over one full period; with the period length this is the average heading. */
    public int periodWorldX() {
        return majorIsX ? majorSign * periodMajor : minorSign * periodMinor;
    }

    /** Signed world-Z blocks advanced over one full period. */
    public int periodWorldZ() {
        return majorIsX ? minorSign * periodMinor : majorSign * periodMajor;
    }

    /** Unsigned major-axis blocks per period. */
    public int periodMajorBlocks() {
        return periodMajor;
    }

    /** Unsigned minor-axis blocks per period. */
    public int periodMinorBlocks() {
        return periodMinor;
    }

    /** Unsigned major-axis offset of slice t from slice 0. Defined for negative t via periodicity. */
    public int majorOffset(int t) {
        return periodMajor * Math.floorDiv(t, period) + prefixMajor[Math.floorMod(t, period)];
    }

    /** Unsigned minor-axis offset of slice t from slice 0. */
    public int minorOffset(int t) {
        return periodMinor * Math.floorDiv(t, period) + prefixMinor[Math.floorMod(t, period)];
    }

    /** Signed world-X offset of slice t's anchor from the line origin. */
    public int worldOffsetX(int t) {
        return majorIsX ? majorSign * majorOffset(t) : minorSign * minorOffset(t);
    }

    /** Signed world-Z offset of slice t's anchor from the line origin. */
    public int worldOffsetZ(int t) {
        return majorIsX ? minorSign * minorOffset(t) : majorSign * majorOffset(t);
    }

    /** The single-slice advance from slice t to slice t+1, as a signed world vector (y = 0). */
    public Vec3i worldStep(int t) {
        return new Vec3i(worldOffsetX(t + 1) - worldOffsetX(t), 0, worldOffsetZ(t + 1) - worldOffsetZ(t));
    }

    /** The signed world vector from slice t's anchor to slice (t + slices)'s anchor (y = 0). */
    public Vec3i worldDelta(int t, int slices) {
        return new Vec3i(worldOffsetX(t + slices) - worldOffsetX(t), 0, worldOffsetZ(t + slices) - worldOffsetZ(t));
    }

    /**
     * Slice index from a major-axis coordinate alone (given in pattern-local units: world delta
     * from the line origin times {@link #majorSign()}). Several consecutive slices share a major
     * coordinate wherever the pattern jogs; this returns the <b>last</b> of them, i.e. the slice
     * whose anchor sits at the post-jog cross position. That convention makes the result a
     * monotone function of the major coordinate, so slice counts between two major coordinates
     * are exact and independent of any cross-axis offset - the property the parallel-line
     * bookkeeping (see {@code stepsAlongHighway}) needs.
     */
    public int sliceFromMajor(int qMajor) {
        int p = Math.floorDiv(qMajor, periodMajor);
        int rem = qMajor - periodMajor * p;
        return p * period + lastSliceForMajor[rem];
    }

    /**
     * Slice index from both pattern-local coordinates. Starts from {@link #sliceFromMajor} and
     * then, among the slices sharing that major coordinate, picks the one whose anchor's minor
     * coordinate is closest to {@code qMinor} (ties toward the later slice). For a point that is
     * exactly some slice's anchor - possibly shifted by a constant cross-axis line offset that is
     * the same for every slice - this recovers that slice exactly.
     */
    public int sliceIndex(int qMajor, int qMinor) {
        int t = sliceFromMajor(qMajor);
        int best = t;
        int bestDist = Math.abs(minorOffset(t) - qMinor);
        // candidates sharing this major coordinate form a contiguous run ending at t
        int t2 = t;
        while (majorOffset(t2 - 1) == qMajor) {
            t2--;
            int d = Math.abs(minorOffset(t2) - qMinor);
            if (d < bestDist) {
                bestDist = d;
                best = t2;
            }
        }
        return best;
    }

    /**
     * True when the step into slice {@code t} advances the major axis, i.e. {@code t} is the first
     * slice of the run of slices sharing its major coordinate. Every slice is both the first and
     * the last of its run unless the pattern jogs there.
     */
    public boolean startsMajorColumn(int t) {
        return majorOffset(t) != majorOffset(t - 1);
    }

    /** True when the step out of slice {@code t} advances the major axis (last slice of its run). */
    public boolean endsMajorColumn(int t) {
        return majorOffset(t + 1) != majorOffset(t);
    }

    /**
     * Whether slice {@code t} owns the rail column at cross-axis offset 0 of its own cross-section
     * (the {@code highwayRailLow} side).
     *
     * <p>Slices that share a major coordinate overlap: their roads are offset by one on the cross
     * axis, so the road at that coordinate is the union and only its outermost slice may put a rail
     * beside it - the other one's rail cell would land inside the road, where the neighbouring
     * slice demands air. The low rail belongs to the slice sitting furthest toward -cross, which is
     * the first of the run when the pattern jogs toward +cross and the last when it jogs the other
     * way.
     */
    public boolean ownsLowRail(int t) {
        return minorSign > 0 ? startsMajorColumn(t) : endsMajorColumn(t);
    }

    /** Mirror of {@link #ownsLowRail}: the {@code highwayRailHigh} side, at cross offset width+1. */
    public boolean ownsHighRail(int t) {
        return minorSign > 0 ? endsMajorColumn(t) : startsMajorColumn(t);
    }

    /** Human-readable description for chat logging. */
    public String describe() {
        return source + " (" + Math.abs(periodWorldX()) + "x" + (periodWorldX() < 0 ? " -X" : " +X")
                + " : " + Math.abs(periodWorldZ()) + "z" + (periodWorldZ() < 0 ? " -Z" : " +Z")
                + " per " + period + " slices, "
                + String.format("%.1f", Math.toDegrees(Math.atan2(periodMinor, periodMajor))) + "\u00b0 off the "
                + (majorIsX ? "X" : "Z") + " axis)";
    }
}
