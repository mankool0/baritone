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
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

public class InventoryCleaningEnderShulker extends State {
    public InventoryCleaningEnderShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 40) {
            return;
        }

        int netherRackSlot = context.getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
        if (netherRackSlot == 8) {
            netherRackSlot = context.getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
        }
        if (netherRackSlot == -1) {
            return;
        }
        context.baritone().getLookBehavior().updateTarget(new Rotation(45, 0), true);
        context.playerContext().playerController().windowClick(context.playerContext().player().inventoryMenu.containerId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, context.playerContext().player());
        context.playerContext().playerController().windowClick(context.playerContext().player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, context.playerContext().player());
        context.transitionTo(HighwayState.CollectingEnderShulker);
    }
}
