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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

public class FarmingEnderChestPrepEchest extends State {

    /** Ticks to keep retrying the offhand swap before moving on anyway, as the old code did. */
    private static final int SWAP_GIVE_UP_TICKS = 100;

    public FarmingEnderChestPrepEchest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.playerContext().player().hasContainerOpen()) {
            // Checking our own container rather than any screen keeps an incidental pause screen
            // from wedging the farm here.
            context.playerContext().player().closeContainer();
            return;
        }

        Item origItem = context.playerContext().player().getOffhandItem().getItem();
        if (origItem instanceof BlockItem && ((BlockItem) origItem).getBlock().equals(Blocks.ENDER_CHEST)) {
            context.transitionTo(HighwayState.FarmingEnderChestPrepPick);
            context.resetTimer();
            return;
        }

        int eChestSlot = context.getLargestItemSlot(Item.getId(Blocks.ENDER_CHEST.asItem()));
        if (eChestSlot == -1) {
            // Nothing left to load; FarmingEnderChest sees the empty offhand and routes to SwapBack
            context.transitionTo(HighwayState.FarmingEnderChestPrepPick);
            context.resetTimer();
            return;
        }

        if (ticksInState() > SWAP_GIVE_UP_TICKS) {
            Helper.HELPER.logDirect("Couldn't get an ender chest into the offhand, moving on.");
            context.transitionTo(HighwayState.FarmingEnderChestPrepPick);
            context.resetTimer();
            return;
        }

        if (!context.containerClickReady()) {
            return;
        }
        context.swapOffhand(eChestSlot);
        context.noteContainerClick();
    }
}
