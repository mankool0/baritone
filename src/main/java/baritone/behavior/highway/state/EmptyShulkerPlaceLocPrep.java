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
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.core.BlockPos;

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

        // Start ~7 blocks back and scan further back, then ahead, for a spot we can place into
        BlockPos safeLoc = context.findSafeSideStorageSpot(7, 25);
        if (safeLoc == null) {
            Helper.HELPER.logDirect("Couldn't find a usable empty shulker spot, skipping.");
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        context.setPlaceLoc(safeLoc);
        context.transitionTo(HighwayState.GoingToEmptyShulkerPlaceLoc);
    }
}
