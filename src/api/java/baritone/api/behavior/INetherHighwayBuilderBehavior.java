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

public interface INetherHighwayBuilderBehavior extends IBehavior {
    boolean isBuildingHighwayState();

    boolean isEndDistanceWalkHeld();

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

    boolean isHighwayActive();

    void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave);

    void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave, Vec3i endCoords);

    void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave, Vec3i endCoords, Vec3i startCoords);

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

    void printStatus();
}