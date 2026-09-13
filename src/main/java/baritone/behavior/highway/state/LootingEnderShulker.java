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

        int target;
        if (context.paving()) {
            // Loot no more than the obsidian room can take: a looted stack trades its slot for eight
            // of obsidian once broken, and the box takes a slot back once mined. Stacks come out
            // whole, so this can overshoot by part of a stack; the farm stops at the exact count.
            int keep = context.settings().highwayEnderChestsToKeep.value;
            int fits = keep + context.enderChestFarmCapacity(keep, -1, context.obsidianOnGroundNearby());
            target = Math.min(context.settings().highwayEnderChestsToLoot.value, fits);
        } else {
            target = Math.min(64, Math.max(context.settings().highwayEnderChestsToHave.value, Math.min(context.settings().highwayEnderChestsThreshold.value, 56)));
        }

        if (context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) >= target) {
            if (context.paving() && target < context.settings().highwayEnderChestsToLoot.value) {
                Helper.HELPER.logDirect("Looting stopped at " + context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) + " ender chests, the inventory only has room for the obsidian of " + Math.max(0, target - context.settings().highwayEnderChestsToKeep.value));
            }
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
