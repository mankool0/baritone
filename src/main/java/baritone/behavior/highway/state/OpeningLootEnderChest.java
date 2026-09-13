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
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.level.block.EnderChestBlock;

import java.util.Optional;

public class OpeningLootEnderChest extends State {
    public OpeningLootEnderChest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        Optional<Rotation> enderChestReachable = RotationUtils.reachable(context.playerContext(), context.placeLoc(), context.playerContext().playerController().getBlockReachDistance());
        enderChestReachable.ifPresent(rotation -> context.baritone().getLookBehavior().updateTarget(rotation, true));

        // A placement the server refuses reverts a few ticks after the client predicted it. Without
        // this we would hold right-click on air forever, since nothing else here ever times out.
        boolean gone = !(context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof EnderChestBlock);
        if (gone || ticksInState() > context.settings().highwayPlaceConfirmTimeout.value) {
            Helper.HELPER.logDirect(gone
                    ? "Ender chest didn't stay placed, retrying the placement."
                    : "Ender chest never opened, retrying the placement.");
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.PlacingLootEnderChest);
            context.resetTimer();
            return;
        }

        context.baritone().getInputOverrideHandler().clearAllKeys();
        if (context.playerContext().minecraft().screen instanceof ContainerScreen) {
            context.transitionTo(context.stashingShulker()
                    ? HighwayState.DepositingStashShulker
                    : HighwayState.DepositingLootEnderChestDepletedShulkers);
            return;
        }
        context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
    }
}
