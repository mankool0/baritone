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

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public class FarmingEnderChest extends State {
    public FarmingEnderChest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        //if (timer < 1) {
        //    return;
        //}


        //baritone.getInputOverrideHandler().clearAllKeys();
        if (context.timer() > 120) {
            Optional<Rotation> eChestReachable = RotationUtils.reachable(context.playerContext(), context.placeLoc(), context.playerContext().playerController().getBlockReachDistance());
            eChestReachable.ifPresent(rotation -> context.baritone().getLookBehavior().updateTarget(rotation, true));
            context.setTarget(context.placeLoc());
            context.resetTimer();
        }

        int pickSlot = context.putPickaxeHotbar();
        if (context.playerContext().player().getInventory().selected != pickSlot) {
            context.transitionTo(HighwayState.FarmingEnderChestPrepEchest);
            context.resetTimer();
            return;
        }


        Item origItem = context.playerContext().player().getOffhandItem().getItem();
        if ((context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) + context.playerContext().player().getOffhandItem().getCount()) <= context.settings().highwayEnderChestsToKeep.value) {
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.setInstantMinePlace(true);
            context.setInstantMineActivated(false);
            context.transitionTo(HighwayState.FarmingEnderChestSwapBack);
            context.resetTimer();
            return;
        }
        else if (!(origItem instanceof BlockItem) || !(((BlockItem) origItem).getBlock().equals(Blocks.ENDER_CHEST))) {
            context.transitionTo(HighwayState.FarmingEnderChestPrepEchest);
            context.resetTimer();
            return;
        }

        // Force look at location
        if (context.playerContext().world().getBlockState(context.placeLoc()).getBlock() instanceof AirBlock) {
            Optional<Rotation> shulkerReachable = RotationUtils.reachable(context.playerContext(), context.placeLoc().below(), context.playerContext().playerController().getBlockReachDistance());
            shulkerReachable.ifPresent(rotation -> context.baritone().getLookBehavior().updateTarget(rotation, true));
        }


        if (context.instantMinePlace()) {
            double lastX = context.playerContext().getPlayerEntity().getXLast();
            double lastY = context.playerContext().getPlayerEntity().getYLast();
            double lastZ = context.playerContext().getPlayerEntity().getZLast();
            final Vec3 pos = new Vec3(lastX + (context.playerContext().player().getX() - lastX) * context.playerContext().minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                    lastY + (context.playerContext().player().getY() - lastY) * context.playerContext().minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                    lastZ + (context.playerContext().player().getZ() - lastZ) * context.playerContext().minecraft().getTimer().getGameTimeDeltaPartialTick(true));
            BlockPos originPos = new BetterBlockPos(pos.x, pos.y+0.5f, pos.z);
            double l_Offset = pos.y - originPos.getY();
            HighwayContext.PlaceResult l_Place = context.place(context.placeLoc(), 5.0f, false, l_Offset == -0.5f, InteractionHand.OFF_HAND);

            if (l_Place != HighwayContext.PlaceResult.Placed)
                return;
            context.setInstantMinePlace(false);
            context.resetTimer();
            return;
        }


        if (!context.instantMineActivated()) {
            context.transitionTo(HighwayState.FarmingEnderChestPrepPick);
        }
        if (context.instantMineLastBlock() != null) {
            if (HighwayContext.validPicksList.contains(context.playerContext().player().getItemInHand(InteractionHand.MAIN_HAND).getItem())) {
                context.playerContext().player().connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, context.instantMineLastBlock(), context.instantMineDirection()));
            }
        }

        try {
            context.playerContext().playerController().setDestroyDelay(0);
        } catch (Exception ignored) {}

        context.setInstantMinePlace(true);
    }
}
