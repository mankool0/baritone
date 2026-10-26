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

public class GoldenCarrotEat extends State {
    private int heldWithoutEatingTicks;

    public GoldenCarrotEat(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        int food = context.playerContext().player().getFoodData().getFoodLevel();
        // Out of carrots once the last one is down: whatever hunger is left goes back to the gapples
        boolean outOfCarrots = context.inventory().getGoldenCarrotCountInventory() == 0 && !context.playerContext().player().isUsingItem();
        if (food > context.eating().goldenCarrotEatTarget() || outOfCarrots) {
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.baritone().getPathingBehavior().cancelEverything();
            context.transitionTo(context.eating().goldenCarrotEatReturnState());
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
            NetherHighwayBuilderBehavior.suppressHitResult = true;
            if (containerOpen) {
                context.playerContext().player().closeContainer();
            } else {
                context.eating().holdUseToEat();
            }
        } else {
            Helper.HELPER.logDirect("Golden carrot eat isn't starting, re-selecting the golden carrot slot.");
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.GoldenCarrotEatPrep);
            context.resetTimer();
        }
    }
}
