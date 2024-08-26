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

import baritone.api.utils.input.Input;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

public class LiquidRemovalGappleEat extends State {
    public LiquidRemovalGappleEat(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        MobEffectInstance fireRest = context.playerContext().player().getEffect(MobEffects.FIRE_RESISTANCE);
        if (fireRest != null && fireRest.getDuration() >= context.settings().highwayFireRestMinDuration.value && context.playerContext().player().getFoodData().getFoodLevel() > 16 /*&& ctx.playerFeet().getY() == placeLoc.getY()*/) {
            context.transitionTo(HighwayState.LiquidRemovalPathing);
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.baritone().getPathingBehavior().cancelEverything();
            return;
        }

        if (context.timer() <= 120) {
            if (context.playerContext().minecraft().screen == null) {
                context.playerContext().minecraft().options.keyUse.setDown(true);
            } else {
                context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
        } else {
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.LiquidRemovalGapplePrep); // Check if we have fire resistance now
            context.resetTimer();
        }
    }
}
