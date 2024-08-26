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
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class GoingToPlaceLocGappleShulker extends State {
    public GoingToPlaceLocGappleShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.baritone().getCustomGoalProcess().isActive()) {
            return; // Wait to get there
        }

        if (context.getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) >= context.settings().highwayGapplesToHave.value || context.getShulkerSlot(ShulkerType.Gapple) == -1) {
            // We have enough gapples, or no more gapple shulker
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        if (context.playerContext().playerFeet().equals(context.placeLoc().offset(context.highwayDirection().getX(), 0, context.highwayDirection().getZ()))) {
            // We have arrived
            context.transitionTo(HighwayState.PlacingGappleShulker);
            context.resetTimer();
        } else {
            // Keep trying to get there
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(context.placeLoc().offset(context.highwayDirection().getX(), 0, context.highwayDirection().getZ())));
        }
    }
}
