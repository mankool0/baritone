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

    /** Ticks to let the builder pick up a dispatched clearArea before considering another one. */
    private static final int BUILDER_SPINUP_TICKS = 5;
    /** Ticks to wait for the server's reply to the resync click below. This one is a real round trip. */
    private static final int RESYNC_REPLY_TICKS = 20;

    private int sinceClearDispatch = BUILDER_SPINUP_TICKS;
    private int sinceResyncRequest = -1;

    public FarmingEnderChestClear(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            sinceClearDispatch = BUILDER_SPINUP_TICKS;
            context.resetTimer();
            return; // Wait for build to complete
        }

        if (sinceClearDispatch < BUILDER_SPINUP_TICKS) {
            sinceClearDispatch++;
            return; // a dispatched clearArea takes a couple of ticks to show up as an active builder
        }

        context.baritone().getPathingBehavior().cancelEverything();

        if (!(context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof AirBlock)) {
            context.setFarmPlaceLocResynced(false); // verify again once this chest is mined
            sinceResyncRequest = -1;
            context.baritone().getBuilderProcess().clearArea(context.placeLoc(), context.placeLoc());
            sinceClearDispatch = 0;
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
                if (pickSlot < 9) {
                    context.playerContext().player().getInventory().selected = pickSlot;
                }
                return;
            }
            context.requestBlockResync(context.placeLoc());
            context.setFarmPlaceLocResynced(true);
            sinceResyncRequest = 0;
            return;
        }

        if (sinceResyncRequest >= 0 && sinceResyncRequest < RESYNC_REPLY_TICKS) {
            sinceResyncRequest++;
            return; // resync reply still in flight; a restored chest hits the clear branch above
        }

        context.setFarmPlaceLocResynced(false);
        sinceResyncRequest = -1;
        context.resetTimer();
        context.transitionTo(HighwayState.CollectingObsidian);
    }
}
