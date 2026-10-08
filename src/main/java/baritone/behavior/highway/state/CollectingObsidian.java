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
import baritone.api.utils.Helper;
import baritone.api.utils.VecUtils;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CollectingObsidian extends State {
    public CollectingObsidian(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.baritone().getCustomGoalProcess().isActive()) {
            return; // Wait for us to reach the goal
        }

        Entity closestObsidian = null;
        double closestDistance = Double.MAX_VALUE;
        // Nearest drop the inventory still has room for, and how much lies in range
        Entity closestFitting = null;
        double closestFittingDistance = Double.MAX_VALUE;
        int inRange = 0;
        Inventory inventory = context.playerContext().player().getInventory();
        for (Entity entity : context.playerContext().entities()) {
            if (entity instanceof ItemEntity) {
                if (((ItemEntity) entity).getItem().getItem() instanceof BlockItem &&
                        (((BlockItem) ((ItemEntity) entity).getItem().getItem()).getBlock() == Blocks.OBSIDIAN
                        || ((BlockItem) ((ItemEntity) entity).getItem().getItem()).getBlock() == Blocks.CRYING_OBSIDIAN)) {
                    double obsidDistance = VecUtils.distanceToCenter(context.playerContext().playerFeet(), (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
                    if (obsidDistance > context.settings().highwayObsidianMaxSearchDist.value) {
                        Helper.HELPER.logDirect("Ignoring found obsidian " + obsidDistance + " blocks away. Max search distance is " + context.settings().highwayObsidianMaxSearchDist.value + " blocks");
                    } else {
                        ItemStack drop = ((ItemEntity) entity).getItem();
                        inRange += drop.getCount();
                        if (obsidDistance < closestDistance) {
                            closestDistance = obsidDistance;
                            closestObsidian = entity;
                        }
                        if (obsidDistance < closestFittingDistance && (inventory.getFreeSlot() != -1 || inventory.getSlotWithRemainingSpace(drop) != -1)) {
                            closestFittingDistance = obsidDistance;
                            closestFitting = entity;
                        }
                    }
                }
            }
        }

        if (closestObsidian != null) {
            if (context.getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                if (context.getThrowawaySlotToToss() != -1) {
                    // No space for obsid, need to do removal
                    context.transitionTo(HighwayState.InventoryCleaningObsidian);
                    context.resetTimer();
                } else if (closestFitting == null) {
                    // None of it fits and nothing is left to throw out. Waiting here would only end
                    // when the drops despawn, five minutes on.
                    Helper.HELPER.logDirect("Leaving " + inRange + " obsidian on the ground: no room for it and nothing left to throw out [" + context.obsidianRoomBreakdown() + "]");
                    context.transitionTo(HighwayState.EmptyShulkerPlaceLocPrep);
                    context.resetTimer();
                    return;
                }
            }
            Entity target = closestFitting != null ? closestFitting : closestObsidian;
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(target.getX(), target.getY(), target.getZ())));
            return;
        }

        // No more obsid to find
        context.transitionTo(HighwayState.EmptyShulkerPlaceLocPrep);
        context.resetTimer();
    }
}
