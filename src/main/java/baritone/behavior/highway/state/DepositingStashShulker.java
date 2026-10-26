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
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.world.inventory.ChestMenu;

/**
 * With ender storage open, deposit the (partially depleted) shulker back into it so it stays in
 * the ender inventory rather than being carried. Ends the digging ender chest refill cycle or the
 * refill cycle of a {@link Supply}, whichever is active.
 */
public class DepositingStashShulker extends State {
    public DepositingStashShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!(context.playerContext().player().containerMenu instanceof ChestMenu)) {
            context.transitionTo(HighwayState.OpeningLootEnderChest); // stashing flag routes the reopen back here
            return;
        }

        if (!context.sync().openContainerReady()) {
            return;
        }

        // Legs run one at a time (each clears its flag before the next starts), so the still-latched
        // flag in priority order names the shulker this stash is for.
        Supply supply = context.resupply().refillingEnderChests() ? null : context.resupply().firstRefillingSupply();
        ShulkerType stashType = context.resupply().refillingEnderChests() ? ShulkerType.EnderChest
                : supply != null ? supply.shulkerType()
                : null;
        if (!context.sync().containerClickReady()) {
            return;
        }

        if (stashType != null && context.looting().depositShulkerChestSlot(stashType) > 0) {
            context.sync().noteContainerClick();
            Helper.HELPER.logDirect("Stashed " + (supply != null ? supply.noun() : "ender chest") + " shulker back into storage.");
            return;
        }

        // Nothing left to deposit (done), or storage was full (keep the shulker and move on)
        context.playerContext().player().closeContainer();
        int shulkerCount = context.inventory().getShulkerCountInventory(ShulkerType.Any);
        if (shulkerCount < context.resupply().startShulkerCount()) {
            // The stashed shulker was counted, either carried at startup or counted when its top-up
            // cycle placed it: lower the expectation so we don't go walking back in search of a "lost" shulker.
            Helper.HELPER.logDirect("Stashed a counted shulker, lowering startShulkerCount from " + context.resupply().startShulkerCount() + " to " + shulkerCount);
            context.resupply().setStartShulkerCount(shulkerCount);
        }
        context.resupply().setStashingShulker(false);
        if (supply != null) {
            context.resupply().setRefilling(supply, false);
        } else {
            context.resupply().setRefillingEnderChests(false);
        }
        context.resupply().releaseEnderChestAccessLoc(); // kept when another refill is queued behind us
        context.transitionTo(HighwayState.Nothing);
    }
}
