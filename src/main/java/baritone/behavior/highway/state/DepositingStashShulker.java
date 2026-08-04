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

/**
 * With ender storage open, deposit the (partially depleted) shulker back into it so it stays in
 * the ender inventory rather than being carried. Ends the digging ender chest refill cycle or the
 * gapple refill cycle, whichever is active.
 */
public class DepositingStashShulker extends State {
    public DepositingStashShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 10) {
            return;
        }

        if (!(context.playerContext().minecraft().screen instanceof ContainerScreen)) {
            context.transitionTo(HighwayState.OpeningLootEnderChest); // stashing flag routes the reopen back here
            return;
        }

        if (context.timer() < 40 && !context.openContainerHasContents()) {
            return; // Wait for the initial content sync; a truly empty container proceeds at 40
        }

        ShulkerType stashType = context.refillingEnderChests() ? ShulkerType.EnderChest : ShulkerType.Gapple;
        if (context.depositShulkerChestSlot(stashType) > 0) {
            Helper.HELPER.logDirect("Stashed " + (stashType == ShulkerType.EnderChest ? "ender chest" : "gapple") + " shulker back into storage.");
            context.resetTimer();
            return;
        }

        // Nothing left to deposit (done), or storage was full (keep the shulker and move on)
        context.playerContext().player().closeContainer();
        int shulkerCount = context.getShulkerCountInventory(ShulkerType.Any);
        if (shulkerCount < context.startShulkerCount()) {
            // The stashed shulker was carried (and counted) at startup rather than grabbed on this
            // trip: lower the expectation so we don't go walking back in search of a "lost" shulker.
            Helper.HELPER.logDirect("Stashed a shulker we started with, lowering startShulkerCount from " + context.startShulkerCount() + " to " + shulkerCount);
            context.setStartShulkerCount(shulkerCount);
        }
        context.setStashingShulker(false);
        if (stashType == ShulkerType.EnderChest) {
            context.setRefillingEnderChests(false);
            if (!context.refillingGapples()) {
                context.setEnderChestAccessLoc(null); // keep the chest loc when a gapple refill is queued behind us
            }
        } else {
            context.setRefillingGapples(false);
            context.setEnderChestAccessLoc(null);
        }
        context.transitionTo(HighwayState.Nothing);
    }
}
