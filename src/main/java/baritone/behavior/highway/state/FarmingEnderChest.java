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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public class FarmingEnderChest extends State {
    public FarmingEnderChest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        // Stuck too long on this spot: clear it and force a fresh calibration break.
        if (context.timer() > 120) {
            context.baritone().getBuilderProcess().clearArea(context.placeLoc(), context.placeLoc());
            context.setInstantMineCalibrated(false);
            context.resetTimer();
        }

        // Keep a non-silk-touch pickaxe selected so the eChest drops obsidian when broken.
        int pickSlot = context.putPickaxeHotbar(true);
        if (context.playerContext().player().getInventory().selected != pickSlot) {
            context.transitionTo(HighwayState.FarmingEnderChestPrepEchest);
            context.resetTimer();
            return;
        }

        Item origItem = context.playerContext().player().getOffhandItem().getItem();
        if ((context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) + context.playerContext().player().getOffhandItem().getCount()) <= context.settings().highwayEnderChestsToKeep.value) {
            // Out of ender chests to farm, swap the offhand back and move on.
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.setInstantMineCalibrated(false);
            context.transitionTo(HighwayState.FarmingEnderChestSwapBack);
            context.resetTimer();
            return;
        } else if (!(origItem instanceof BlockItem) || !(((BlockItem) origItem).getBlock().equals(Blocks.ENDER_CHEST))) {
            context.transitionTo(HighwayState.FarmingEnderChestPrepEchest);
            context.resetTimer();
            return;
        }

        BlockState state = context.playerContext().world().getBlockState(context.placeLoc());

        // Nothing placed yet: face the support block and drop a fresh ender chest from the offhand.
        if (state.getBlock() instanceof AirBlock) {
            Optional<Rotation> support = RotationUtils.reachable(context.playerContext(), context.placeLoc().below(), context.playerContext().playerController().getBlockReachDistance());
            support.ifPresent(rotation -> context.baritone().getLookBehavior().updateTarget(rotation, true));

            if (context.place(context.placeLoc(), 5.0f, false, false, InteractionHand.OFF_HAND) == HighwayContext.PlaceResult.Placed) {
                context.resetTimer();
            }
            return;
        }

        if (!HighwayContext.validPicksList.contains(context.playerContext().player().getItemInHand(InteractionHand.MAIN_HAND).getItem())) {
            return;
        }

        // First chest of the session so break it the legitimate way (real client mining) so
        // the server's destroy target is set
        if (!context.instantMineCalibrated()) {
            if (context.calibrationBreakTick(context.placeLoc())) {
                context.setInstantMineCalibrated(true);
            }
            return;
        }

        // Calibrated: instant-rebreak the chest like Meteor's InstantRebreak - face it and fire
        // STOP_DESTROY_BLOCK + swing every tick.
        context.instantMineTick(context.placeLoc());
    }
}
