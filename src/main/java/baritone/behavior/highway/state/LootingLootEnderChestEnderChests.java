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

public class LootingLootEnderChestEnderChests extends State {
    public LootingLootEnderChestEnderChests(HighwayState state) {
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

        int wantShulks = context.settings().highwayEnderChestShulksToHave.value;
        if (!context.paving()) {
            wantShulks = context.refillingEnderChests() ? 1 : 0;
        }

        if (context.getShulkerCountInventory(ShulkerType.EnderChest) >= wantShulks) {
            context.transitionTo(HighwayState.LootingLootEnderChestGapples);
            return;
        }

        if (!context.containerClickReady()) {
            return;
        }

        int enderShulksLooted = context.lootShulkerChestSlot(ShulkerType.EnderChest);
        context.noteContainerClick();
        if (enderShulksLooted > 0) {
            Helper.HELPER.logDirect("Looted " + enderShulksLooted + " ender chest shulker");
            return;
        }

        Helper.HELPER.logDirect("No more ender chest shulkers. Rolling with what we have.");
        context.setEnderChestHasEnderShulks(false);
        context.transitionTo(HighwayState.LootingLootEnderChestGapples);
    }
}
