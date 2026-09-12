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
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

public class LootingEnderShulker extends State {
    public LootingEnderShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!(context.playerContext().minecraft().screen instanceof ShulkerBoxScreen)) {
            context.transitionTo(HighwayState.OpeningEnderShulker);
            return;
        }

        if (!context.openContainerReady()) {
            return;
        }

        int target = context.paving()
                ? context.settings().highwayEnderChestsToLoot.value
                : Math.min(64, Math.max(context.settings().highwayEnderChestsToHave.value, Math.min(context.settings().highwayEnderChestsThreshold.value, 56)));

        if (context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) >= target) {
            context.transitionTo(HighwayState.MiningEnderShulker);
            context.playerContext().player().closeContainer();
            return;
        }

        if (!context.containerClickReady()) {
            return;
        }

        int enderChestsLooted = context.paving()
                ? context.lootEnderChestSlot()
                : context.topUpEnderChestSlotFromShulker(target);
        context.noteContainerClick();
        if (enderChestsLooted > 0) {
            Helper.HELPER.logDirect("Looted " + enderChestsLooted + " ender chests");
            return;
        }
        if (enderChestsLooted < 0) {
            return; // made room for the next stack, loot it next tick
        }

        Helper.HELPER.logDirect("No more ender chests. Rolling with what we have.");
        context.transitionTo(HighwayState.MiningEnderShulker);
        context.playerContext().player().closeContainer();
    }
}
