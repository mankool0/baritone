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

public class LiquidRemovalGapplePreEat extends State {
    public LiquidRemovalGapplePreEat(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        // Constantiam has some weird issue where you want to eat for half a second, stop and then eat again
        if (context.timer() <= 10) {
            if (context.playerContext().minecraft().screen == null) {
                context.playerContext().minecraft().options.keyUse.setDown(true);
            } else {
                context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
        } else {
            context.playerContext().minecraft().options.keyUse.setDown(false);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.LiquidRemovalGappleEat);
            context.resetTimer();
        }
    }
}
