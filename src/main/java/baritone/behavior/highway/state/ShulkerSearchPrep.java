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
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

public class ShulkerSearchPrep extends State {
    public ShulkerSearchPrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        // Back along the highway, counted in slices so an angled pattern's path is followed
        // rather than the 45 degree diagonal its direction vector only supplies the signs of
        Vec3 feetPos = new Vec3(context.playerContext().playerFeet().getX(), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ());
        int backSlices = context.slicesForBlocks(context.settings().highwayMaxLostShulkerSearchDist.value);

        // Get the closest point and shift it so it's in the middle of the highway
        context.setPlaceLoc(context.liftOntoPavement(context.sliceAlongLine(context.backPathOriginVector(), feetPos, -backSlices, LocationType.ShulkerEchestInteraction)));

        context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
        context.transitionTo(HighwayState.ShulkerSearchPathing);
    }
}
