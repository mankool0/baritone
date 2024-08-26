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
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public class FloatingFixPrep extends State {
    public FloatingFixPrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        context.clearFloatingFixReachables();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 1; y++) {
                    Optional<Rotation> curIssuePosReachable = RotationUtils.reachable(context.playerContext(), context.playerContext().playerFeet().offset(x, y, z), context.playerContext().playerController().getBlockReachDistance());
                    BlockState state = context.playerContext().world().getBlockState(context.playerContext().playerFeet().offset(x, y, z));
                    Block block = state.getBlock();
                    if (block != Blocks.BEDROCK && !(block instanceof LiquidBlock) && !(block instanceof AirBlock) && curIssuePosReachable.isPresent()) {
                        context.addFloatingFixReachables(curIssuePosReachable.get());
                    }
                }
            }
        }
        context.transitionTo(HighwayState.FloatingFix);
    }
}
