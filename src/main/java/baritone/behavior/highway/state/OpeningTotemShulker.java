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

import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.Helper;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.Optional;

public class OpeningTotemShulker extends State {
    public OpeningTotemShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        Optional<Rotation> shulkerReachable = RotationUtils.reachable(context.playerContext(), context.placeLoc(), context.playerContext().playerController().getBlockReachDistance());
        shulkerReachable.ifPresent(rotation -> context.baritone().getLookBehavior().updateTarget(rotation, true));

        // A placement the server refuses reverts a few ticks after the client predicted it. Without
        // this we would hold right-click on air forever, since nothing else here ever times out.
        boolean gone = !(context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof ShulkerBoxBlock);
        if (gone || ticksInState() > context.settings().highwayPlaceConfirmTimeout.value) {
            context.baritone().getInputOverrideHandler().clearAllKeys();
            // A box that is still standing and still won't open is usually facing something solid,
            // which re-placing into the same spot can never fix on its own
            if (!gone && context.handleShulkerWouldNotOpen(context.placeLoc())) {
                return;
            }
            Helper.HELPER.logDirect(gone
                    ? "Shulker didn't stay placed, retrying the placement."
                    : "Shulker never opened, retrying the placement.");
            context.transitionTo(HighwayState.PlacingTotemShulker);
            context.resetTimer();
            return;
        }

        context.baritone().getInputOverrideHandler().clearAllKeys();
        if (context.playerContext().minecraft().screen instanceof ShulkerBoxScreen) {
            context.noteShulkerOpened();
            context.transitionTo(HighwayState.LootingTotemShulker);
            return;
        }
        context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
    }
}
