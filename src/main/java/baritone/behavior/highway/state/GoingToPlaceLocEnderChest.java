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
import baritone.api.utils.Helper;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class GoingToPlaceLocEnderChest extends State {
    public GoingToPlaceLocEnderChest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.baritone().getCustomGoalProcess().isActive()) {
            return; // Wait for us to reach the goal
        }

        BlockPos stand = context.placeLoc().offset(context.highwayDirection().getX() * -2, 0, context.highwayDirection().getZ() * -2);

        if (context.playerContext().playerFeet().equals(stand)) {
            // We have arrived
            context.baritone().getPathingBehavior().cancelEverything();
            context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
            context.resetTimer();
            Item offhandItem = context.playerContext().player().getOffhandItem().getItem();
            int chests = context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()));
            if (offhandItem instanceof BlockItem && ((BlockItem) offhandItem).getBlock().equals(Blocks.ENDER_CHEST)) {
                // Stranded chests from an interrupted farm session: recording them as the "original"
                // would make SwapBack deliberately keep them in the offhand when the session ends
                chests += context.playerContext().player().getOffhandItem().getCount();
                offhandItem = Items.AIR;
            }
            context.setInstantMineOriginalOffhandItem(offhandItem);
            context.beginFarmAccounting();
            // Stop early rather than drop obsidian the inventory can't take. Nothing else moves
            // during the farm: the original offhand item comes back out of the slot it borrows.
            // A mined box whose pickup is still in flight lands in a slot that reads empty right now.
            int boxSlots = context.minedShulkerLanded() ? 0 : -1;
            int farmKeep = context.enderChestFarmKeep(chests, boxSlots, context.obsidianOnGroundNearby());
            context.setFarmEnderChestsToKeep(farmKeep);
            Helper.HELPER.logDirect("Farming " + Math.max(0, chests - farmKeep) + " of " + chests + " ender chests, keeping " + farmKeep
                    + (farmKeep > context.settings().highwayEnderChestsToKeep.value ? " instead of " + context.settings().highwayEnderChestsToKeep.value + " (obsidian room)" : "")
                    + " [" + context.obsidianRoomBreakdown() + (boxSlots < 0 ? " boxInFlight" : "") + "]");
            context.setInstantMineCalibrated(false);
            context.transitionTo(HighwayState.FarmingEnderChestPrepEchest);
        } else {
            // Keep trying to get there
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(stand));
        }
    }
}
