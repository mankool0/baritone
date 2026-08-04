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
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.BlockPos;

/**
 * Walks the player out of a lit nether portal before it teleports us to the overworld. Entered
 * from any state the moment the hitbox overlaps portal blocks; exits to Nothing once clear.
 */
public class PortalEscape extends State {

    public PortalEscape(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!context.isPlayerInPortal()) {
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.Nothing);
            context.resetTimer();
            context.resetStuckTimer();
            return;
        }

        BlockPos target = context.findPortalEscapeTarget();
        if (target == null) {
            // boxed in: hand over to a human instead of bouncing between dimensions
            Helper.HELPER.logDirect("Inside a nether portal with nowhere to step out, pausing.");
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.Nothing);
            context.setPaused(true);
            return;
        }

        context.walkTowardBlock(target);
    }
}
