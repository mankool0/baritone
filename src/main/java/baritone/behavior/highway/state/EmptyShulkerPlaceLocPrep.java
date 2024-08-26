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

import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.world.phys.Vec3;

public class EmptyShulkerPlaceLocPrep extends State {
    public EmptyShulkerPlaceLocPrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.getShulkerSlot(ShulkerType.Empty) == -1) {
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        Vec3 curPos = new Vec3(context.playerContext().playerFeet().getX() + (7 * -context.highwayDirection().getX()), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ() + (7 * -context.highwayDirection().getZ())); // Go back a bit just in case
        Vec3 direction = new Vec3(context.highwayDirection().getX(), context.highwayDirection().getY(), context.highwayDirection().getZ());

        context.setPlaceLoc(context.getClosestPoint(new Vec3(context.eChestEmptyShulkOriginVector().x, context.eChestEmptyShulkOriginVector().y, context.eChestEmptyShulkOriginVector().z), direction, curPos, LocationType.SideStorage));

        context.transitionTo(HighwayState.GoingToEmptyShulkerPlaceLoc);
    }
}
