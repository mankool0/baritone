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
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;

public class BoatRemoval extends State {
    public BoatRemoval(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 10) {
            return;
        }

        if (context.boatLocation() == null) {
            Helper.HELPER.logDirect("Boat location is non-existent, restarting builder");
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        boolean done;
        if (context.boatHasPassenger()) {
            //Helper.HELPER.logDirect("Boat has a passenger, mining deeper");
            done = context.baritone().getBuilderProcess().checkNoEntityCollision(new AABB(context.boatLocation()), context.playerContext().player()) && context.baritone().getBuilderProcess().checkNoEntityCollision(new AABB(context.boatLocation().below()), context.playerContext().player());
        } else {
            done = context.baritone().getBuilderProcess().checkNoEntityCollision(new AABB(context.boatLocation()), context.playerContext().player());
        }

        // Check if boat is still there
        if (done) {
            Helper.HELPER.logDirect("Boat seems to be gone");
            context.baritone().getPathingBehavior().cancelEverything();
            context.setBoatLocation(null);
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            return; // Wait for build to complete
        }

        int depth = context.boatHasPassenger() ? 3 : 1;
        //int yOffset = boatHasPassenger ? -4 : -2;
        //FillSchematic toClear = new FillSchematic(4, depth, 4, Blocks.AIR.defaultBlockState());
        context.baritone().getBuilderProcess().clearArea(context.boatLocation().offset(2, 1, 2), context.boatLocation().offset(-2, -depth, -2));

        // Check if boat is still there
        //if (!baritone.getBuilderProcess().checkNoEntityCollision(new AABB(boatLocation), ctx.player())) {
        //baritone.getBuilderProcess().build("boatClearing", toClear, boatLocation.add(-1, yOffset, -3));
        //return;
        //}
    }
}
