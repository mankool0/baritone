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

public class DepositingLootEnderChestDepletedShulkers extends State {
    public DepositingLootEnderChestDepletedShulkers(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!(context.playerContext().minecraft().screen instanceof ContainerScreen)) {
            context.transitionTo(HighwayState.OpeningLootEnderChest);
            return;
        }

        if (!context.openContainerReady()) {
            return;
        }

        if (!context.containerClickReady()) {
            return;
        }

        if (context.depositDepletedShulkerChestSlot() > 0) {
            context.noteContainerClick();
            Helper.HELPER.logDirect("Deposited depleted pickaxe shulker, lowering startShulkerCount from " + context.startShulkerCount() + " to " + (context.startShulkerCount() - 1));
            context.setStartShulkerCount(context.startShulkerCount() - 1);
            return;
        }

        // Nothing left to deposit, or the ender chest is full
        if (state == HighwayState.DepositingLootEnderChestDepletedShulkersFinal) {
            context.playerContext().player().closeContainer();
            if (context.refillingEnderChests()) {
                // This storage trip was a digging ender-chest refill: we should now hold a shulker to open.
                if (context.getShulkerCountInventory(ShulkerType.EnderChest) > 0) {
                    context.setEnderChestAccessLoc(context.placeLoc()); // reuse this access chest for the stash
                    context.transitionTo(HighwayState.EchestMiningPlaceLocPrep); // -> top up loose ender chests
                } else {
                    // Storage had no shulker; abort the refill and let BuildingHighway re-evaluate (it will pause).
                    context.setRefillingEnderChests(false);
                    context.transitionTo(HighwayState.Nothing);
                }
            } else if (context.refillingGapples()) {
                // This storage trip grabbed a gapple shulker: go place and loot it.
                if (context.getShulkerCountInventory(ShulkerType.Gapple) > 0) {
                    // Remember the chest unconditionally: carry mode won't stash the gapple box, but a
                    // totem leg queued behind us still can, and a stale loc is dropped on release anyway.
                    context.setEnderChestAccessLoc(context.placeLoc());
                    if (context.getPickCountInventory() >= context.settings().highwayPicksThreshold.value) {
                        context.transitionTo(HighwayState.GappleShulkerPlaceLocPrep); // -> top up gapples
                    } else {
                        // A pick-triggered trip piggybacked this grab and we're near out of picks, which
                        // mining the placed shulker box would need. BuildingHighway runs the pick cycle
                        // first and then comes back for the gapples; the refill flag survives the detour.
                        context.transitionTo(HighwayState.Nothing);
                    }
                } else {
                    // Storage had no gapple shulker; abort the refill and let BuildingHighway re-evaluate (it will pause).
                    context.setRefillingGapples(false);
                    context.transitionTo(HighwayState.Nothing);
                }
            } else if (context.refillingTotems()) {
                // This storage trip grabbed a totem shulker: go place and loot it.
                if (context.getShulkerCountInventory(ShulkerType.Totem) > 0) {
                    context.setEnderChestAccessLoc(context.placeLoc()); // reuse this access chest for the stash
                    if (context.getPickCountInventory() >= context.settings().highwayPicksThreshold.value) {
                        context.transitionTo(HighwayState.TotemShulkerPlaceLocPrep); // -> top up totems
                    } else {
                        // Same pick-priority detour as the gapple leg above: mining the placed box
                        // needs a pickaxe, so let BuildingHighway run the pick cycle first.
                        context.transitionTo(HighwayState.Nothing);
                    }
                } else {
                    // Storage had no totem shulker; abort the refill and let BuildingHighway re-evaluate (it will pause).
                    context.setRefillingTotems(false);
                    context.transitionTo(HighwayState.Nothing);
                }
            } else {
                context.transitionTo(HighwayState.Nothing);
            }
        } else {
            context.transitionTo(HighwayState.LootingLootEnderChestPicks);
        }
    }
}
