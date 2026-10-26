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
import baritone.behavior.highway.Supply;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;

import java.util.EnumSet;

/**
 * With ender storage open, take the shulkers each {@link Supply} wants, in priority order: the one
 * a refill trip came for, plus any other running low that this trip can bring back as well.
 */
public class LootingLootEnderChestSupplies extends State {
    // Supplies storage turned out to hold no shulkers of on this visit
    private final EnumSet<Supply> emptied = EnumSet.noneOf(Supply.class);

    public LootingLootEnderChestSupplies(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!(context.playerContext().minecraft().screen instanceof ContainerScreen)) {
            context.transitionTo(HighwayState.OpeningLootEnderChest);
            return;
        }

        if (!context.sync().openContainerReady()) {
            return;
        }

        for (Supply supply : Supply.values()) {
            if (emptied.contains(supply)) {
                continue;
            }
            // Already at storage and low on it: turn this trip into its refill too (grab a shulker
            // now, top up after the trip, stash it back) instead of taking a second trip
            if (supply.enabled(context.settings()) && supply.stash(context.settings()) && !context.resupply().refilling(supply)
                    && context.inventory().getShulkerCountInventory(supply.shulkerType()) == 0
                    && supply.count(context) <= supply.threshold(context.settings())) {
                context.resupply().setRefilling(supply, true);
            }

            if (context.inventory().getShulkerCountInventory(supply.shulkerType()) >= supply.shulksWanted(context)) {
                continue;
            }

            if (!context.sync().containerClickReady()) {
                return;
            }

            int shulksLooted = context.looting().lootShulkerChestSlot(supply.shulkerType());
            context.sync().noteContainerClick();
            if (shulksLooted < 0) {
                context.looting().pauseNoRoomFor(supply.noun());
                return;
            }
            if (shulksLooted > 0) {
                Helper.HELPER.logDirect("Looted " + shulksLooted + " " + supply.noun() + " shulker");
                return;
            }

            Helper.HELPER.logDirect("No more " + supply.noun() + " shulkers. Rolling with what we have.");
            context.resupply().setEnderChestHasShulks(supply, false);
            emptied.add(supply);
            return;
        }

        context.transitionTo(HighwayState.DepositingLootEnderChestDepletedShulkersFinal);
    }
}
