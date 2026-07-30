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
import baritone.behavior.highway.NetherHighwayBuilderBehavior;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

public class LiquidRemovalGappleEat extends State {
    private int heldWithoutEatingTicks;

    public LiquidRemovalGappleEat(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        MobEffectInstance fireRest = context.playerContext().player().getEffect(MobEffects.FIRE_RESISTANCE);
        if (fireRest != null && fireRest.getDuration() >= context.fireRestMinDuration() && context.playerContext().player().getFoodData().getFoodLevel() > 16 /*&& ctx.playerFeet().getY() == placeLoc.getY()*/) {
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            context.transitionTo(HighwayState.LiquidRemovalPathing);
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.baritone().getPathingBehavior().cancelEverything();
            return;
        }

        boolean screenOpen = context.playerContext().minecraft().screen != null;
        if (!screenOpen) {
            if (context.playerContext().player().isUsingItem()) {
                heldWithoutEatingTicks = 0;
            } else {
                heldWithoutEatingTicks++;
            }
        }

        if (context.timer() <= 120 && heldWithoutEatingTicks <= 40) {
            NetherHighwayBuilderBehavior.suppressHitResult = true;
            if (!screenOpen) {
                context.playerContext().minecraft().options.keyUse.setDown(true);
            } else if (context.playerContext().player().hasContainerOpen()) {
                context.playerContext().player().closeContainer();
            }
        } else {
            if (heldWithoutEatingTicks > 40) {
                Helper.HELPER.logDirect("Gapple eat isn't progressing (server never confirmed it), re-syncing the hotbar slot.");
            }
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.LiquidRemovalGapplePrep); // Check if we have fire resistance now
            context.resetTimer();
        }
    }
}
