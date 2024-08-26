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

import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.Optional;

public class PlacingGappleShulker extends State {
    public PlacingGappleShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            context.resetTimer();
            return; // Wait for build to complete
        }

        // Shulker box spot isn't air or shulker, lets fix that
        if (!(context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof AirBlock) && !(context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof ShulkerBoxBlock)) {
            context.baritone().getPathingBehavior().cancelEverything();
            context.baritone().getBuilderProcess().clearArea(context.placeLoc(), context.placeLoc());
            context.resetTimer();
            return;
        }


        Optional<Rotation> shulkerReachable = RotationUtils.reachable(context.playerContext(), context.placeLoc(), context.playerContext().playerController().getBlockReachDistance());

        Optional<Rotation> underShulkerReachable = RotationUtils.reachable(context.playerContext(), context.placeLoc().below(), context.playerContext().playerController().getBlockReachDistance());


        context.transitionTo(context.placeShulkerBox(shulkerReachable.orElse(null), underShulkerReachable.orElse(null), context.placeLoc(), HighwayState.GoingToPlaceLocGappleShulker, this.getState(), HighwayState.OpeningGappleShulker, ShulkerType.Gapple));
    }
}
