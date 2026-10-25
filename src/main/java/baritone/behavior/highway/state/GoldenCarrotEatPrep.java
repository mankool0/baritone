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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class GoldenCarrotEatPrep extends State {
    // Ticks spent waiting for a deferred inventory move to land the carrots on the hotbar
    private int hotbarWaitTicks;

    public GoldenCarrotEatPrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        int carrotSlot = context.putItemHotbar(Item.getId(Items.GOLDEN_CARROT));
        if (carrotSlot == -1) {
            Helper.HELPER.logDirect("Error getting golden carrot slot");
            context.transitionTo(context.goldenCarrotEatReturnState());
            return;
        }

        if (carrotSlot >= 9) {
            if (++hotbarWaitTicks <= 20) {
                return;
            }
            Helper.HELPER.logDirect("Couldn't move golden carrots onto the hotbar (allowInventory off or moves blocked?)");
            context.transitionTo(context.goldenCarrotEatReturnState());
            return;
        }

        context.playerContext().player().getInventory().setSelectedSlot(carrotSlot);
        context.transitionTo(HighwayState.GoldenCarrotPreEat);
        context.resetTimer();
    }
}
