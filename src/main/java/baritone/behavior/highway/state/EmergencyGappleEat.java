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

public class EmergencyGappleEat extends State {
    private int heldWithoutEatingTicks;

    public EmergencyGappleEat(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        float healthThreshold = context.eating().gappleEatHealthThreshold();
        int foodThreshold = context.eating().gappleEatFoodThreshold();
        float health = context.playerContext().player().getHealth();
        int food = context.playerContext().player().getFoodData().getFoodLevel();

        boolean healthOk = healthThreshold <= 0 || health >= healthThreshold;
        // Hunger is the golden carrots' job where they'd feed us: the emergency check starts a carrot
        // eat for it as soon as we're back
        boolean foodOk = foodThreshold <= 0 || food > foodThreshold || context.eating().goldenCarrotsFeed(context.eating().emergencyEatReturnState());

        if (healthOk && foodOk) {
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.baritone().getPathingBehavior().cancelEverything();
            context.transitionTo(context.eating().emergencyEatReturnState());
            return;
        }

        boolean containerOpen = context.playerContext().player().hasContainerOpen();
        if (!containerOpen) {
            // Only count while we can eat; an open container is closed first, so no progress then is expected.
            // isUsingItem() is the client's prediction, so this only catches an eat the client won't
            // start; holdUseToEat() retries the ones the server dropped.
            if (context.playerContext().player().isUsingItem()) {
                heldWithoutEatingTicks = 0;
            } else {
                heldWithoutEatingTicks++;
            }
        }

        if (heldWithoutEatingTicks <= 40) {
            // suppressHitResult nulls the hitResult inside startUseItem() via a mixin redirect, preventing
            // block/container interaction (offhand echest placement, container opening) while keyUse is held.
            NetherHighwayBuilderBehavior.suppressHitResult = true;
            if (containerOpen) {
                context.playerContext().player().closeContainer();
            } else {
                context.eating().holdUseToEat();
            }
        } else {
            Helper.HELPER.logDirect("Gapple eat isn't starting, re-selecting the gapple slot.");
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.EmergencyGapplePrep);
            context.resetTimer();
        }
    }
}