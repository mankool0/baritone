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
import baritone.behavior.highway.enums.ShulkerType;

public class PlacingEmptyShulker extends PlacingShulkerBase {
    public PlacingEmptyShulker(HighwayState state) {
        super(state);
    }

    @Override
    protected HighwayState getPreviousState() {
        return HighwayState.GoingToEmptyShulkerPlaceLoc;
    }

    @Override
    protected HighwayState getNextState() {
        return HighwayState.Nothing;
    }

    @Override
    protected ShulkerType getShulkerType() {
        return ShulkerType.Empty;
    }

    @Override
    public void handle(HighwayContext context) {
        handleWithShulkerType(context, ShulkerType.Empty);

        // Decrement shulker count after empty shulker placed
        if (context.currentState().getState() == HighwayState.Nothing) {
            Helper.HELPER.logDirect("Lowering startShulkerCount from " + context.startShulkerCount() + " to " + (context.startShulkerCount() - 1));
            context.setStartShulkerCount(context.startShulkerCount() - 1);
        }
    }
}
