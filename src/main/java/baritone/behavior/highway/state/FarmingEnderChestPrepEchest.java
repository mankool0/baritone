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
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

public class FarmingEnderChestPrepEchest extends State {
    public FarmingEnderChestPrepEchest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 10) {
            return;
        }

        if (context.playerContext().minecraft().screen instanceof ContainerScreen) {
            // Close container screen if it somehow didn't close
            context.playerContext().player().closeContainer();
            context.resetTimer();
            return;
        }

        Item origItem = context.playerContext().player().getOffhandItem().getItem();
        if (!(origItem instanceof BlockItem) || !(((BlockItem) origItem).getBlock().equals(Blocks.ENDER_CHEST))) {
            int eChestSlot = context.getLargestItemSlot(Item.getId(Blocks.ENDER_CHEST.asItem()));
            context.swapOffhand(eChestSlot);
        }

        context.transitionTo(HighwayState.FarmingEnderChestPrepPick);
        context.resetTimer();
    }
}
