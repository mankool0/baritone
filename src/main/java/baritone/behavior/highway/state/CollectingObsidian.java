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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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

        for (Entity entity : context.playerContext().entities()) {
            if (entity instanceof ItemEntity) {
                if (((ItemEntity) entity).getItem().getItem() instanceof BlockItem &&
                        (((BlockItem) ((ItemEntity) entity).getItem().getItem()).getBlock() == Blocks.OBSIDIAN
                        || ((BlockItem) ((ItemEntity) entity).getItem().getItem()).getBlock() == Blocks.CRYING_OBSIDIAN)) {
                    double obsidDistance = VecUtils.distanceToCenter(context.playerContext().playerFeet(), (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
                    if (obsidDistance <= context.settings().highwayObsidianMaxSearchDist.value) {
                        if (context.getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                            // No space for obsid, need to do removal
                            context.transitionTo(HighwayState.InventoryCleaningObsidian);
                            context.resetTimer();
                        }
                        context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(entity.getX(), entity.getY(), entity.getZ())));
                        return;
                    } else {
                        Helper.HELPER.logDirect("Ignoring found obsidian " + obsidDistance + " blocks away. Max search distance is " + context.settings().highwayObsidianMaxSearchDist.value + " blocks");
                    }
                }
            }
        }

        // No more obsid to find
        context.transitionTo(HighwayState.EmptyShulkerPlaceLocPrep);
        context.resetTimer();
    }
}
