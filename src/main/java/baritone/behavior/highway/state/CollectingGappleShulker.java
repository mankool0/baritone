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

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class CollectingGappleShulker extends State {
    /** Ticks to give an in-flight pickup to land in the inventory before treating the box as gone. */
    private static final int BOX_LANDING_TICKS = 20;

    private int boxWaitTicks = 0;

    public CollectingGappleShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.baritone().getCustomGoalProcess().isActive()) {
            return; // Wait for us to reach the goal
        }

        for (Entity entity : context.playerContext().entities()) {
            if (entity instanceof ItemEntity) {
                if (HighwayContext.shulkerItemList.contains(((ItemEntity) entity).getItem().getItem())) {
                    if (context.getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                        // No space for shulker, need to do removal
                        context.transitionTo(HighwayState.InventoryCleaningGappleShulker);
                        context.resetTimer();
                    }
                    context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(entity.getX(), entity.getY(), entity.getZ())));
                    boxWaitTicks = 0;
                    return;
                }
            }
        }

        // No box on the ground. The count not having risen usually means the pickup packet has
        // removed the item but the slot update is still in flight, so give it a moment before
        // concluding the box is gone (moving on early counts its slot as free).
        if (!context.minedShulkerLanded() && boxWaitTicks++ < BOX_LANDING_TICKS) {
            return;
        }
        if (!context.minedShulkerLanded()
                && context.maybeStartThiefHunt(HighwayState.CollectingGappleShulker)) {
            return; // The mined box never reached the inventory and a piglin is carrying one
        }
        if (context.refillingGapples()) {
            if (context.settings().highwayStashGappleShulkers.value && context.getShulkerCountInventory(ShulkerType.Gapple) > 0) {
                context.transitionTo(HighwayState.EnderChestStashPlaceLocPrep); // fetched from storage: put it back
                return;
            }
            // Carry mode, or the shulker emptied out while topping up: nothing to stash
            context.setRefillingGapples(false);
            context.releaseEnderChestAccessLoc();
        }
        context.transitionTo(HighwayState.Nothing);
    }
}
