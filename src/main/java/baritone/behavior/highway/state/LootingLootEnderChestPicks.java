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
import net.minecraft.client.gui.screens.inventory.ContainerScreen;

public class LootingLootEnderChestPicks extends State {
    public LootingLootEnderChestPicks(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!(context.playerContext().minecraft().screen instanceof ContainerScreen)) {
            context.transitionTo(HighwayState.OpeningLootEnderChest);
            return;
        }

        if (!context.openContainerReady()) {
            return;
        }

        if (context.getShulkerCountInventory(context.picksToUse()) >= context.settings().highwayPickShulksToHave.value) {
            context.transitionTo(HighwayState.LootingLootEnderChestEnderChests);
            return;
        }

        if (!context.containerClickReady()) {
            return;
        }

        int picksShulksLooted = context.lootShulkerChestSlot(context.picksToUse());
        context.noteContainerClick();
        if (picksShulksLooted > 0) {
            Helper.HELPER.logDirect("Looted " + picksShulksLooted + " pickaxe shulker");
            return;
        }

        Helper.HELPER.logDirect("No more pickaxe shulkers. Rolling with what we have.");
        context.setEnderChestHasPickShulks(false);
        context.transitionTo(HighwayState.LootingLootEnderChestEnderChests);
    }
}
