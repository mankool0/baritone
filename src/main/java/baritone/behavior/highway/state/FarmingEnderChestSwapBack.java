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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class FarmingEnderChestSwapBack extends State {
    public FarmingEnderChestSwapBack(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        Item curItem = context.playerContext().player().getOffhandItem().getItem();
        if (curItem.equals(context.instantMineOriginalOffhandItem())) {
            context.resetTimer();
            context.transitionTo(HighwayState.FarmingEnderChestClear);
            return;
        }

        if (!context.containerClickReady()) {
            return;
        }

        // Unload the leftover chests merge-first so the reserve lands on an existing partial loose
        // stack instead of fragmenting into a fresh slot (or the original item's old slot)
        if (context.stashOffhandEnderChests()) {
            context.noteContainerClick();
            return; // re-check next tick; a merge can leave a remainder in the offhand
        }

        // Offhand is drained (or the inventory is packed solid): restore the original item
        if (!context.instantMineOriginalOffhandItem().equals(Items.AIR)) {
            int origItemSlot = context.getItemSlot(Item.getId(context.instantMineOriginalOffhandItem()));
            if (origItemSlot != -1) {
                context.swapOffhand(origItemSlot);
                context.noteContainerClick();
                return;
            }
            // Original offhand item is gone (e.g. totem popped mid-farm): settle for an empty hand
        }

        // Move on rather than retry forever; the tick-level rescue reclaims any leftovers
        // once space frees up
        context.resetTimer();
        context.transitionTo(HighwayState.FarmingEnderChestClear);
    }
}
