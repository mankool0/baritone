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
 * <p>
 * A window can also run the walk lane on past that line, without the rail columns beside it. A
 * rail over open air has nothing to go against but the rail before it, on the face that looks
 * along the road, and the server only takes that face from a player standing past it. The creep
 * stops short of the next window's unbuilt floor, before it gets to the last rails' slice, so the
 * last rails of every window were left to the builder to path over and place. A diagonal's outer
 * rail support is the same: its only face turned towards the lane is on the next slice's floor.
 * With the lane laid a little further, the creep walks on past the last rails, and the next
 * slice's floor is there to place against.
 *
 * @param schematic   what to hand the builder
 * @param originShift from the first slice's origin to the schematic's, since the copies are laid
 *                    out from their low corner whichever way the highway heads
 * @param extraSlices how many slices past its own the window reaches into
 */
public record LookaheadWindow(CompositeSchematic schematic, Vec3i originShift, int extraSlices) {

    /**
     * How many slices of walk lane a paving window runs on past its rails. The creep only takes a
     * step with up to three blocks of floor ahead, the step's own and two more, so this is what
     * lets it into a window's last slice.
     */
    public static final int PAVING_LANE_LEAD = 2;

    /**
     * @param slice     one slice's cross-section, with its origin at the slice's origin
     * @param lookahead slices per window, which is also how far each repeat advances
     * @param laneLead  slices of walk lane to take on past the window's last slice
     * @param laneWidth the walk lane's columns across a slice, 1 to this, between the rail columns
     */
    public static LookaheadWindow of(CompositeSchematic slice, int stepX, int stepZ, int lookahead, int laneLead, int laneWidth) {
        // How far along the heading a slice's cells reach from its origin, as x * stepX + z * stepZ:
        // level on a straight highway, spread over the whole cross-section on a diagonal
        int sliceBack = (slice.widthX() - 1) * Math.min(stepX, 0) + (slice.lengthZ() - 1) * Math.min(stepZ, 0);
        int sliceFront = (slice.widthX() - 1) * Math.max(stepX, 0) + (slice.lengthZ() - 1) * Math.max(stepZ, 0);
        int perSlice = stepX * stepX + stepZ * stepZ;
        // the slices past the window whose back reaches behind its last slice's leading corner
        int extra = (sliceFront - sliceBack) / perSlice;
        int copies = lookahead + extra + laneLead;
        Vec3i shift = new Vec3i(stepX < 0 ? (copies - 1) * stepX : 0, 0, stepZ < 0 ? (copies - 1) * stepZ : 0);
        if (extra == 0 && laneLead == 0) {
            return new LookaheadWindow(slice.repeated(stepX, 0, stepZ, lookahead), shift, 0);
        }
        // the last slice's leading corner, in the shifted schematic's coordinates
        int frontAlong = (lookahead - 1) * perSlice + sliceFront - (shift.getX() * stepX + shift.getZ() * stepZ);
        Front front = new Front(stepX, stepZ, frontAlong, frontAlong + laneLead * perSlice,
                shift, slice.lengthZ() >= slice.widthX(), laneWidth);
        return new LookaheadWindow(slice.repeatedInto(front, stepX, 0, stepZ, copies), shift, extra + laneLead);
    }

    private static final class Front extends CompositeSchematic {

        private final int stepX;
        private final int stepZ;
        private final int frontAlong;
        private final int laneFrontAlong;
        private final Vec3i shift;
        private final boolean crossZ;
        private final int laneWidth;

        Front(int stepX, int stepZ, int frontAlong, int laneFrontAlong, Vec3i shift, boolean crossZ, int laneWidth) {
            super(0, 0, 0);
            this.stepX = stepX;
            this.stepZ = stepZ;
            this.frontAlong = frontAlong;
            this.laneFrontAlong = laneFrontAlong;
            this.shift = shift;
            this.crossZ = crossZ;
            this.laneWidth = laneWidth;
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState currentState) {
            int along = x * stepX + z * stepZ;
            if (along > laneFrontAlong || (along > frontAlong && !inLane(x, z))) {
                return false;
            }
            return super.inSchematic(x, y, z, currentState);
        }

        private boolean inLane(int x, int z) {
            // back to the first slice's origin, then the slice is the step along the axis its
            // cross-section is one block thick on, and the column what's left across
            int ux = x + shift.getX();
            int uz = z + shift.getZ();
            int column;
            if (crossZ) {
                column = uz - ux / stepX * stepZ;
            } else {
                column = ux - uz / stepZ * stepX;
            }
            return column >= 1 && column <= laneWidth;
        }
    }
}
