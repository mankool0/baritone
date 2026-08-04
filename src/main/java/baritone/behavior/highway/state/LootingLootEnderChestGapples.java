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
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class LootingLootEnderChestGapples extends State {
    public LootingLootEnderChestGapples(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 10) {
            return;
        }

        if (!(context.playerContext().minecraft().screen instanceof ContainerScreen)) {
            context.transitionTo(HighwayState.OpeningLootEnderChest);
            return;
        }

        if (context.timer() < 40 && !context.openContainerHasContents()) {
            return; // Wait for the initial content sync; a truly empty container proceeds at 40
        }

        // Already at storage and out of gapples: turn this trip into a gapple refill (grab a
        // shulker now, top up after the trip, stash it back) instead of taking a second trip
        if (context.settings().highwayStashGappleShulkers.value && !context.refillingGapples()
                && context.getShulkerCountInventory(ShulkerType.Gapple) == 0
                && context.getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) <= context.settings().highwayGapplesThreshold.value) {
            context.setRefillingGapples(true);
        }

        int wantShulks = context.settings().highwayStashGappleShulkers.value ? 0 : context.settings().highwayGappleShulksToHave.value;
        if (context.refillingGapples()) {
            wantShulks = Math.max(wantShulks, 1); // this trip has to come back with a shulker to loot from
        }

        if (context.getShulkerCountInventory(ShulkerType.Gapple) < wantShulks) {
            int gappleShulksLooted = context.lootShulkerChestSlot(ShulkerType.Gapple);
            if (gappleShulksLooted > 0) {
                Helper.HELPER.logDirect("Looted " + gappleShulksLooted + " gapple shulker");
            } else {
                Helper.HELPER.logDirect("No more gapple shulkers. Rolling with what we have.");
                context.transitionTo(HighwayState.DepositingLootEnderChestDepletedShulkersFinal);
                context.setEnderChestHasGappleShulks(false);
            }

            context.resetTimer();
        } else {
            context.transitionTo(HighwayState.DepositingLootEnderChestDepletedShulkersFinal);
        }
    }
}
