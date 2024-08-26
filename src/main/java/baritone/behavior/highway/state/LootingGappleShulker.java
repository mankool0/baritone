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
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class LootingGappleShulker extends State {
    public LootingGappleShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 40) {
            return;
        }

        if (!(context.playerContext().minecraft().screen instanceof ShulkerBoxScreen)) {
            context.transitionTo(HighwayState.OpeningGappleShulker);
            return;
        }

        if (context.getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) < context.settings().highwayGapplesToHave.value) {
            int gapplesLooted = context.lootGappleChestSlot();
            if (gapplesLooted > 0) {
                Helper.HELPER.logDirect("Looted " + gapplesLooted + " gapples");
            } else {
                Helper.HELPER.logDirect("Can't loot/empty shulker. Rolling with what we have.");
                context.transitionTo(HighwayState.MiningGappleShulker);
                context.playerContext().player().closeContainer();
            }

            context.resetTimer();
        } else {
            context.transitionTo(HighwayState.MiningGappleShulker);
            context.playerContext().player().closeContainer();
        }
    }
}
