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

    void stop();

    void printStatus();
}