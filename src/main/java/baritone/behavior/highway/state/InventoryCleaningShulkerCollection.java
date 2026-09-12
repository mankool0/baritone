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
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.inventory.ClickType;

public class InventoryCleaningShulkerCollection extends State {
    public InventoryCleaningShulkerCollection(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!context.containerClickReady()) {
            return;
        }

        int throwawaySlot = context.getThrowawaySlotToToss();
        if (throwawaySlot == -1) {
            return;
        }
        context.baritone().getLookBehavior().updateTarget(new Rotation(45, 0), true);
        context.playerContext().playerController().windowClick(context.playerContext().player().inventoryMenu.containerId, throwawaySlot < 9 ? throwawaySlot + 36 : throwawaySlot, 0, ClickType.PICKUP, context.playerContext().player());
        context.playerContext().playerController().windowClick(context.playerContext().player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, context.playerContext().player());
        context.noteContainerClick();
        context.transitionTo(HighwayState.ShulkerCollection);
    }
}
