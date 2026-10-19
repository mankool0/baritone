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

import baritone.api.schematic.CompositeSchematic;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The schematic the builder gets for {@code highwayPrinterLookahead} slices of a classic highway at
 * once, so the printer always has targets, and where it goes.
 * <p>
 * On a diagonal the front of the window is cut square to the heading. A diagonal slice runs at 45
 * degrees to travel, so a window of whole slices ends on a slant: its last slice reaches several
 * blocks further along on one side than on the other. The window only moves on once every cell in
 * it is done, and the walk-creep stops short of the next window's floor, which nothing places yet.
 * From there the leading corner is out of reach, so every window ended with the builder's
 * stationary place and pathing fallback finishing it. A square front is done all at once from a
 * reach away, the way a straight highway's is.
 * <p>
 * The window holds its slices plus the next few, and only cells up to the line through its last
 * slice's leading corner count. The back edge stays the first slice's own slant, so nothing before
 * the build start is ever part of a window. The next window repeats the slices this one reached
 * into, and by then only the part this one left out is still unbuilt.
 *
 * @param schematic   what to hand the builder
 * @param originShift from the first slice's origin to the schematic's, since the copies are laid
 *                    out from their low corner whichever way the highway heads
 * @param extraSlices how many slices past its own the window reaches into
 */
public record LookaheadWindow(CompositeSchematic schematic, Vec3i originShift, int extraSlices) {

    /**
     * @param slice     one slice's cross-section, with its origin at the slice's origin
     * @param lookahead slices per window, which is also how far each repeat advances
     */
    public static LookaheadWindow of(CompositeSchematic slice, int stepX, int stepZ, int lookahead) {
        // How far along the heading a slice's cells reach from its origin, as x * stepX + z * stepZ:
        // level on a straight highway, spread over the whole cross-section on a diagonal
        int sliceBack = (slice.widthX() - 1) * Math.min(stepX, 0) + (slice.lengthZ() - 1) * Math.min(stepZ, 0);
        int sliceFront = (slice.widthX() - 1) * Math.max(stepX, 0) + (slice.lengthZ() - 1) * Math.max(stepZ, 0);
        int perSlice = stepX * stepX + stepZ * stepZ;
        // the slices past the window whose back reaches behind its last slice's leading corner
        int extra = (sliceFront - sliceBack) / perSlice;
        int copies = lookahead + extra;
        Vec3i shift = new Vec3i(stepX < 0 ? (copies - 1) * stepX : 0, 0, stepZ < 0 ? (copies - 1) * stepZ : 0);
        if (extra == 0) {
            return new LookaheadWindow(slice.repeated(stepX, 0, stepZ, lookahead), shift, 0);
        }
        // the last slice's leading corner, in the shifted schematic's coordinates
        int frontAlong = (lookahead - 1) * perSlice + sliceFront - (shift.getX() * stepX + shift.getZ() * stepZ);
        return new LookaheadWindow(slice.repeatedInto(new SquareFront(stepX, stepZ, frontAlong), stepX, 0, stepZ, copies), shift, extra);
    }

    private static final class SquareFront extends CompositeSchematic {

        private final int stepX;
        private final int stepZ;
        private final int frontAlong;

        SquareFront(int stepX, int stepZ, int frontAlong) {
            super(0, 0, 0);
            this.stepX = stepX;
            this.stepZ = stepZ;
            this.frontAlong = frontAlong;
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState currentState) {
            return x * stepX + z * stepZ <= frontAlong && super.inSchematic(x, y, z, currentState);
        }
    }
}
