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

public class LootingTotemShulker extends State {
    public LootingTotemShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 10) {
            return;
        }

        if (!(context.playerContext().minecraft().screen instanceof ShulkerBoxScreen)) {
            context.transitionTo(HighwayState.OpeningTotemShulker);
            return;
        }

        if (context.timer() < 40 && !context.openContainerHasContents()) {
            return; // Wait for the initial content sync; a truly empty container proceeds at 40
        }

        if (context.getTotemCountInventory() < context.totemsToHave()) {
            int totemsLooted = context.lootTotemChestSlot();
            if (totemsLooted > 0) {
                Helper.HELPER.logDirect("Looted " + totemsLooted + " totems");
            } else {
                Helper.HELPER.logDirect("Can't loot/empty shulker. Rolling with what we have.");
                context.transitionTo(HighwayState.MiningTotemShulker);
                context.playerContext().player().closeContainer();
            }

            context.resetTimer();
        } else {
            context.transitionTo(HighwayState.MiningTotemShulker);
            context.playerContext().player().closeContainer();
        }
    }
}
