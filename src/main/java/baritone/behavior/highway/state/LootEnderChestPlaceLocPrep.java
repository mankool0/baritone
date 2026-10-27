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
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class LootEnderChestPlaceLocPrep extends State {
    public LootEnderChestPlaceLocPrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.inventory().getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) == 0) {
            context.baritone().getPathingBehavior().cancelEverything();
            context.pause("No ender chest to place.");
            return;
        }

        context.sync().clearThresholdConfirm();
        context.resetTimer();

        // Start ~7 blocks back and scan further back, then ahead, for a spot we can place into
        BlockPos safeLoc = context.shulkerPlacement().findSafeSideStorageSpot(7, 25);
        if (safeLoc == null) {
            context.baritone().getPathingBehavior().cancelEverything();
            if (context.resupply().stashingShulker()) {
                // A stash leg that had to move off its chest: the shulker can wait for a later
                // trip, it's no reason to stop the build or to mark storage as out of it
                Helper.HELPER.logDirect("Couldn't find a spot to stash the shulker, keeping it for now.");
                context.resupply().abandonStash();
                context.transitionTo(HighwayState.Nothing);
                return;
            }
            if (goWithout(context)) {
                return;
            }
            context.pause("Couldn't find a usable ender chest spot.");
            return;
        }

        context.setPlaceLoc(safeLoc, LocationType.SideStorage);
        context.transitionTo(HighwayState.GoingToLootEnderChestPlaceLoc);
    }

    /**
     * A trip only for supplies the build can do without goes without them until the next
     * startBuild, as an empty storage does: nowhere to set the chest down is no reason to stop
     * the build. Anything it still needs starts its own trip from BuildingHighway, which pauses
     * here as before.
     */
    private static boolean goWithout(HighwayContext context) {
        if (context.resupply().refillingEnderChests()) {
            return false;
        }
        EnumSet<Supply> without = EnumSet.noneOf(Supply.class);
        for (Supply supply : Supply.values()) {
            if (!context.resupply().refilling(supply)) {
                continue;
            }
            if (supply.pausesWhenStorageEmpty(context.settings())) {
                return false;
            }
            without.add(supply);
        }
        if (without.isEmpty()) {
            return false; // a pick or ender chest trip
        }
        for (Supply supply : without) {
            context.resupply().setRefilling(supply, false);
            context.resupply().setEnderChestHasShulks(supply, false);
            // Not "No more ... shulkers": external tooling reads that line as the ender chest being out of them
            Helper.HELPER.logDirect("No ender chest spot for " + supply.plural() + " here. Rolling with what we have.");
        }
        context.transitionTo(HighwayState.Nothing);
        return true;
    }
}
