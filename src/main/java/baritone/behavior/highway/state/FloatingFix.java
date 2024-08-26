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
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.item.ItemStack;


public class FloatingFix extends State {

    public FloatingFix(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        int pickSlot = context.putPickaxeHotbar();
        if (pickSlot == -1) {
            Helper.HELPER.logDirect("Error getting pick slot");
            context.transitionTo(HighwayState.Nothing);
            return;
        }
        ItemStack stack = context.playerContext().player().getInventory().items.get(pickSlot);
        if (HighwayContext.validPicksList.contains(stack.getItem())) {
            context.playerContext().player().getInventory().selected = pickSlot;
        }

        Rotation floatingReachable = context.floatingFixReachablesRemoveFirst();
        if (floatingReachable != null) {
            context.baritone().getLookBehavior().updateTarget(floatingReachable, true);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            return;
        }
        context.transitionTo(HighwayState.Nothing);
        context.resetTimer();
    }
}
