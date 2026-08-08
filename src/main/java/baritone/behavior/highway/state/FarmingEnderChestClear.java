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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.AirBlock;

public class FarmingEnderChestClear extends State {
    public FarmingEnderChestClear(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 10) {
            return;
        }

        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            context.resetTimer();
            return; // Wait for build to complete
        }

        context.baritone().getPathingBehavior().cancelEverything();

        if (!(context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof AirBlock)) {
            context.setFarmPlaceLocResynced(false); // verify again once this chest is mined
            context.baritone().getBuilderProcess().clearArea(context.placeLoc(), context.placeLoc());
            context.resetTimer();
            return;
        }

        // Make the server resend placeLoc and only trust air it confirmed.
        if (!context.farmPlaceLocResynced()) {
            if (context.playerContext().player().getMainHandItem().getItem() instanceof BlockItem) {
                int pickSlot = context.putPickaxeHotbar(true);
                if (pickSlot == -1) {
                    // Nothing safe to hold for the click; skip verification rather than risk
                    // placing the held block at placeLoc
                    context.resetTimer();
                    context.transitionTo(HighwayState.CollectingObsidian);
                    return;
                }
                context.playerContext().player().getInventory().selected = pickSlot;
                context.resetTimer();
                return; // click next pass, once the held-item change has synced
            }
            context.requestBlockResync(context.placeLoc());
            context.setFarmPlaceLocResynced(true);
            context.resetTimer();
            return;
        }

        if (context.timer() < 20) {
            return; // resync reply still in flight; a restored chest hits the clear branch above
        }

        context.setFarmPlaceLocResynced(false);
        context.resetTimer();
        context.transitionTo(HighwayState.CollectingObsidian);
    }
}
