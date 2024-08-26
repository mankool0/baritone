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

import baritone.api.schematic.WhiteBlackSchematic;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;

public class PlacingEmptyShulkerSupport extends State {
    public PlacingEmptyShulkerSupport(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            return; // Wait for build to complete
        }

        if (context.getShulkerSlot(ShulkerType.Empty) == -1) {
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        context.baritone().getPathingBehavior().cancelEverything();
        context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
        if (context.playerContext().world().getBlockState(context.placeLoc().below()).getBlock() instanceof AirBlock) {
            context.baritone().getBuilderProcess().build("supportBlock", new WhiteBlackSchematic(1, 1, 1, context.blackListBlocks(), Blocks.NETHERRACK.defaultBlockState(), false, false, true), context.placeLoc().below());
            return;
        }

        context.transitionTo(HighwayState.PlacingEmptyShulker);
    }
}
