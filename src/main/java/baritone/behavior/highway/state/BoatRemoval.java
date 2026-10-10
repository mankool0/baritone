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

package baritone.behavior.highway.state;

import baritone.api.utils.Helper;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class BoatRemoval extends State {

    /** Ticks to let the builder pick up a dispatched clearArea before considering another one. */
    private static final int BUILDER_SPINUP_TICKS = 5;
    /** Ticks to wait for it to drop once the hole under it is dug out. */
    private static final int DROP_WAIT_TICKS = 40;

    private int sinceClearDispatch = BUILDER_SPINUP_TICKS;
    private int dropWait = 0;

    public BoatRemoval(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.boatLocation() == null) {
            Helper.HELPER.logDirect("Boat location is non-existent, restarting builder");
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        // Check if boat is still there. The hole is deep enough that whatever was riding it is
        // out of the way once the cell itself is.
        if (context.baritone().getBuilderProcess().checkNoEntityCollision(new AABB(context.boatLocation()), context.playerContext().player())) {
            Helper.HELPER.logDirect("Boat seems to be gone");
            context.baritone().getPathingBehavior().cancelEverything();
            context.setBoatLocation(null);
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            sinceClearDispatch = BUILDER_SPINUP_TICKS;
            return; // Wait for build to complete
        }

        if (sinceClearDispatch < BUILDER_SPINUP_TICKS) {
            sinceClearDispatch++;
            return; // a dispatched clearArea takes a couple of ticks to show up as an active builder
        }

        List<BlockPos> hole = context.entityDropHole(context.boatLocation());
        if (hole == null) {
            Helper.HELPER.logDirect("Can't dig a hole the boat would drop through, restarting builder");
            context.baritone().getPathingBehavior().cancelEverything();
            context.setBoatLocation(null);
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        // Clear only around what's still solid. The hole is a box, so a box around part of it is all hole
        BlockPos min = null;
        BlockPos max = null;
        for (BlockPos pos : hole) {
            if (context.playerContext().world().getBlockState(pos).getCollisionShape(context.playerContext().world(), pos).isEmpty()) {
                continue;
            }
            min = min == null ? pos : BlockPos.min(min, pos);
            max = max == null ? pos : BlockPos.max(max, pos);
        }
        if (min == null) {
            // dug out and it's still there: it should be falling. Don't wait on it forever if not.
            if (++dropWait >= DROP_WAIT_TICKS) {
                Helper.HELPER.logDirect("Boat isn't dropping through the hole, restarting builder");
                context.baritone().getPathingBehavior().cancelEverything();
                context.setBoatLocation(null);
                context.transitionTo(HighwayState.Nothing);
            }
            return;
        }
        dropWait = 0;
        context.baritone().getBuilderProcess().clearArea(min, max);
        sinceClearDispatch = 0;
    }
}
