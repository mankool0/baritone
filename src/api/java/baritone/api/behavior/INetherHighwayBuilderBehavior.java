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

package baritone.api.behavior;

import baritone.api.schematic.ISchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

public interface INetherHighwayBuilderBehavior extends IBehavior {
    boolean isBuildingHighwayState();

    boolean isEndDistanceWalkHeld();

    /**
     * Whether, on its last tick, the walk creep let go only because the build front is further
     * ahead than it walks to. Getting there is then up to the builder's paths.
     */
    boolean isBuildFrontBeyondCreep();

    boolean isFixingInvalidBlocks();

    /**
     * True when the highway cross-section is too wide to work from a single lane, so the builder
     * has to walk sideways to reach the outer columns.
     */
    boolean needsLateralTraverses();

    /**
     * Signed index of the cross-section column the given block sits in, measured across the highway
     * from its centre line. Consecutive columns differ by one and steps along the highway don't
     * change it, so a sideways sweep is just a walk through consecutive values.
     */
    int lateralColumn(int x, int z);

    /**
     * Whether the given block is in the walk lane rather than one of the rail columns beside it.
     */
    boolean inWalkLane(int x, int z);

    /**
     * A block's length straight down the highway, the way the walk creep walks it.
     */
    Vec3 highwayHeading();

    boolean isHighwayActive();

    void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave);

    void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave, Vec3i endCoords);

    void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave, Vec3i endCoords, Vec3i startCoords);

    /**
     * @param pattern One {@code X} or {@code Z} character per slice in absolute world axes, e.g.
     *                {@code XXZXXZXXXZ} for 7 blocks of X per 3 of Z (about 23 degrees off the X
     *                axis), to build an angled highway. The direction only supplies the two signs,
     *                so a pattern using both axes needs both of them non-zero. Null, empty, or a
     *                pattern that uses one axis only or both equally builds the classic
     *                straight/diagonal road.
     */
    void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave, Vec3i endCoords, Vec3i startCoords, String pattern);

    /**
     * True when the active build follows a custom slice pattern (an angled highway) rather than a
     * straight or 45-degree diagonal line.
     */
    boolean hasCustomPattern();

    /**
     * The origin advance for the next repeat of a pattern build, derived from the current
     * origin's slice position. A pure function of position, so builder restarts can never desync
     * the pattern phase. Only meaningful while {@link #hasCustomPattern()} is true.
     */
    Vec3i repeatAdvance(BlockPos currentOrigin);

    /**
     * The cross-section to build at the given origin. Angled builds with rails alternate between
     * cross-sections: slices that share a driving-axis coordinate overlap, so only the outermost of
     * them carries the rail on that side. A pure function of position, like
     * {@link #repeatAdvance}. Null when the active build has no per-slice cross-sections, which is
     * everything except an angled build with rails.
     */
    ISchematic schematicForOrigin(BlockPos origin);

    void stop();

    /**
     * {@link #stop()} once the builder is paused or in a state that has nothing of its own set
     * down (mid-dig, mid-pave), so whoever sends it need not watch the state. A new build cancels it.
     */
    void stopWhenSafe();

    void printStatus();
}