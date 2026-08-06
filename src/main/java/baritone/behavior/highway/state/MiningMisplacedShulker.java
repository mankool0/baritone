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
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.AirBlock;

/**
 * Mines back a shulker box that inventory desync placed into the highway instead of a building
 * block (placeLoc points at it), then hands off to ShulkerCollection to pick up the drop.
 */
public class MiningMisplacedShulker extends State {

    private int guardWaitTicks = 0;

    public MiningMisplacedShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 20) {
            return;
        }

        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            context.resetTimer();
            return; // Wait for the dispatched clear to complete
        }

        context.baritone().getPathingBehavior().cancelEverything();
        if (!(context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof AirBlock)) {
            if (context.shulkerThiefNear(context.placeLoc(), context.settings().highwayShulkerTheftGuardRadius.value)
                    && guardWaitTicks++ < context.settings().highwayShulkerTheftGuardMaxWait.value) {
                if (guardWaitTicks == 1) {
                    Helper.HELPER.logDirect("Holding off mining the shulker until nearby piglins that could steal it wander off.");
                }
                return;
            }

            context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
            context.baritone().getBuilderProcess().clearArea(context.placeLoc(), context.placeLoc());
            context.resetTimer();
            return;
        }

        context.transitionTo(HighwayState.ShulkerCollection);
    }
}
