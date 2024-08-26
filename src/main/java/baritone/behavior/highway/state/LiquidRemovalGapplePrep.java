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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class LiquidRemovalGapplePrep extends State {
    public LiquidRemovalGapplePrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        int gappleSlot = context.putItemHotbar(Item.getId(Items.ENCHANTED_GOLDEN_APPLE));
        if (gappleSlot == -1) {
            Helper.HELPER.logDirect("Error getting gapple slot");
            context.transitionTo(HighwayState.LiquidRemovalPrep);
            return;
        }

        ItemStack stack = context.playerContext().player().getInventory().items.get(gappleSlot);
        if (Item.getId(stack.getItem()) == Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) {
            context.playerContext().player().getInventory().selected = gappleSlot;
        }

        context.transitionTo(HighwayState.LiquidRemovalGapplePreEat);
        context.resetTimer();
    }
}
