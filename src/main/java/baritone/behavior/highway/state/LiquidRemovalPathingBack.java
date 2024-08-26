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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

public class LiquidRemovalPathingBack extends State {
    public LiquidRemovalPathingBack(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        MobEffectInstance fireRest = context.playerContext().player().getEffect(MobEffects.FIRE_RESISTANCE);
        if (fireRest != null && fireRest.getDuration() >= context.settings().highwayFireRestMinDuration.value && context.playerContext().playerFeet().getY() == context.placeLoc().getY()) {
            context.transitionTo(HighwayState.LiquidRemovalPathing);
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.baritone().getPathingBehavior().cancelEverything();
            return;
        }

        BlockState tempState = context.playerContext().world().getBlockState(context.playerContext().playerFeet());
        if (tempState.getBlock() instanceof LiquidBlock) {
            Helper.HELPER.logDirect("We are stuck in lava, going directly to gapple eating.");
            context.transitionTo(HighwayState.LiquidRemovalGapplePrep);
        }
        if (context.baritone().getCustomGoalProcess().isActive()) {
            return; // Wait to get there
        }

        if (context.playerContext().playerFeet().getX() == context.placeLoc().getX() && context.playerContext().playerFeet().getY() == context.placeLoc().getY() && context.playerContext().playerFeet().getZ() == context.placeLoc().getZ()) {
            // We have arrived
            context.transitionTo(HighwayState.LiquidRemovalGapplePrep);
        } else {
            // Keep trying to get there
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(context.placeLoc().getX(), context.placeLoc().getY(), context.placeLoc().getZ()));
        }
    }
}
