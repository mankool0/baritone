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

import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;

import java.util.Arrays;
import java.util.Optional;

public class PlacingLootEnderChest extends State {
    public PlacingLootEnderChest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            return; // Wait for build to complete
        }

        context.baritone().getPathingBehavior().cancelEverything();
        context.settings().buildRepeat.value = new Vec3i(0, 0, 0);

        if (context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof EnderChestBlock && context.playerContext().world().getBlockState(context.placeLoc().above()).getBlock() instanceof AirBlock) {
            context.transitionTo(HighwayState.OpeningLootEnderChest);
            context.resetTimer();
            return;
        }
        if (context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof AirBlock && context.playerContext().world().getBlockState(context.placeLoc().above()).getBlock() instanceof AirBlock) {
            Optional<Rotation> eChestLocReachable = RotationUtils.reachable(context.playerContext(), context.placeLoc().below(), context.playerContext().playerController().getBlockReachDistance());
            if (eChestLocReachable.isEmpty()) {
                context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
                context.resetTimer();
                return;
            }
            context.baritone().getLookBehavior().updateTarget(eChestLocReachable.get(), true);
            context.baritone().getBuilderProcess().build("enderChest", new FillSchematic(1, 1, 1, Blocks.ENDER_CHEST.defaultBlockState()), context.placeLoc());
        }
        else {
            WhiteBlackSchematic tempSchem = new WhiteBlackSchematic(1, 2, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            context.baritone().getBuilderProcess().build("eChestPrep", tempSchem, context.placeLoc());
        }
        //return;

        //currentState = State.OpeningLootEnderChest;
        context.resetTimer();
    }
}
