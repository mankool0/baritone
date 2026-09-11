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
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

public class LootEnderChestPlaceLocPrep extends State {
    public LootEnderChestPlaceLocPrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) == 0) {
            context.baritone().getPathingBehavior().cancelEverything();
            context.pause("No ender chest to place.");
            return;
        }

        context.setRepeatCheck(false);
        context.resetTimer();

        // Start ~7 blocks back and scan further back, then ahead, for a spot we can place into
        BlockPos safeLoc = context.findSafeSideStorageSpot(7, 25);
        if (safeLoc == null) {
            context.baritone().getPathingBehavior().cancelEverything();
            context.pause("Couldn't find a usable ender chest spot.");
            return;
        }

        context.setPlaceLoc(safeLoc);
        context.transitionTo(HighwayState.GoingToLootEnderChestPlaceLoc);
    }
}
