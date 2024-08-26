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
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;


public class Nothing extends State {
    public Nothing(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        context.resetTimer();
        Helper.HELPER.logDirect("Starting highway build");

        context.settings().buildRepeat.value = new Vec3i(context.highwayDirection().getX(), 0, context.highwayDirection().getZ());

        Vec3 origin = new Vec3(context.originVector().x, context.originVector().y, context.originVector().z);
        Vec3 direction = new Vec3(context.highwayDirection().getX(), context.highwayDirection().getY(), context.highwayDirection().getZ());
        Vec3 curPos = new Vec3(context.playerContext().playerFeet().getX() + (context.highwayCheckBackDistance() * -context.highwayDirection().getX()), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ() + (context.highwayCheckBackDistance() * -context.highwayDirection().getZ())); // Go back a bit to clear up our mess
        context.setOriginBuild(context.getClosestPoint(origin, direction, curPos, LocationType.HighwayBuild));


        context.baritone().getPathingBehavior().cancelEverything();
        context.baritone().getBuilderProcess().build("netherHighway", context.schematic(), context.originBuild());

        context.transitionTo(HighwayState.BuildingHighway);
    }
}
