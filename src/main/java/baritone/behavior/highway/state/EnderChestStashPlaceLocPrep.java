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
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * After topping up loose ender chests (digging) or gapples, decide where to reopen ender storage to
 * stash the shulker back. Reuses the access chest placed during the earlier grab if it's still there
 * (it was left placed and is only a few blocks away); otherwise finds a fresh, lava-free side spot.
 * Both paths hand off to the shared loot-access chain, which places-or-detects the chest and opens it.
 */
public class EnderChestStashPlaceLocPrep extends State {
    public EnderChestStashPlaceLocPrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        context.setStashingShulker(true);
        context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
        context.resetTimer();

        BlockPos reuse = context.enderChestAccessLoc();
        if (reuse != null) {
            BlockState atLoc = context.playerContext().world().getBlockState(reuse);
            BlockState above = context.playerContext().world().getBlockState(reuse.above());
            if (atLoc.getBlock() instanceof EnderChestBlock && above.getBlock() instanceof AirBlock) {
                context.setPlaceLoc(reuse);
                context.transitionTo(HighwayState.GoingToLootEnderChestPlaceLoc);
                return;
            }
        }

        // No chest to reuse: place a fresh one
        BlockPos safeLoc = context.findSafeSideStorageSpot(7, 25);
        if (safeLoc == null) {
            Helper.HELPER.logDirect("Couldn't find a spot to stash the shulker, keeping it for now.");
            context.setStashingShulker(false);
            context.setRefillingEnderChests(false);
            context.setRefillingGapples(false);
            context.setRefillingTotems(false);
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        context.setEnderChestAccessLoc(null);
        context.setPlaceLoc(safeLoc);
        context.transitionTo(HighwayState.GoingToLootEnderChestPlaceLoc);
    }
}
