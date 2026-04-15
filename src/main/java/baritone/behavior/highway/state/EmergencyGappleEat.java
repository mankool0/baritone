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
import baritone.behavior.highway.NetherHighwayBuilderBehavior;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;

public class EmergencyGappleEat extends State {
    public EmergencyGappleEat(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        float healthThreshold = context.settings().highwayGappleEatHealthThreshold.value;
        int foodThreshold = context.settings().highwayGappleEatFoodThreshold.value;
        float health = context.playerContext().player().getHealth();
        int food = context.playerContext().player().getFoodData().getFoodLevel();

        boolean healthOk = healthThreshold <= 0 || health >= healthThreshold;
        boolean foodOk = foodThreshold <= 0 || food > foodThreshold;

        if (healthOk && foodOk) {
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.baritone().getPathingBehavior().cancelEverything();
            context.transitionTo(context.emergencyEatReturnState());
            return;
        }

        if (context.timer() <= 120) {
            // suppressHitResult nulls the hitResult inside startUseItem() via a mixin redirect, preventing
            // block/container interaction (offhand echest placement, container opening) while keyUse is held.
            NetherHighwayBuilderBehavior.suppressHitResult = true;
            if (context.playerContext().minecraft().screen == null) {
                context.playerContext().minecraft().options.keyUse.setDown(true);
            } else {
                context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
        } else {
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.EmergencyGapplePrep); // Check if thresholds are met now
            context.resetTimer();
        }
    }
}