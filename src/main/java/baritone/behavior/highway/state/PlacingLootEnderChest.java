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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public class PlacingLootEnderChest extends State {
    private boolean placed = false;

    public PlacingLootEnderChest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        // Wait for an in-progress clear to finish
        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            context.resetTimer();
            return;
        }

        BlockState placeState = context.playerContext().world().getBlockState(context.placeLoc());
        BlockState aboveState = context.playerContext().world().getBlockState(context.placeLoc().above());

        // Chest is down with headroom to open it - we're done here
        if (placeState.getBlock() instanceof EnderChestBlock && aboveState.getBlock() instanceof AirBlock) {
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.OpeningLootEnderChest);
            placed = false;
            context.resetTimer();
            return;
        }

        // Placement is client-predicted, so the chest is normally there on the next tick; the
        // timeout is what covers a placement the server refuses.
        if (placed) {
            if (context.timer() < context.settings().highwayPlaceConfirmTimeout.value) {
                return;
            }
            Helper.HELPER.logDirect("Ender chest placement timed out, relocating.");
            placed = false;
            context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
            context.resetTimer();
            return;
        }

        // Lava has crept into the spot (or was never safe) - don't break netherrack into it, pick a new spot
        if (!context.isSideStorageSpotSafe(context.placeLoc())) {
            Helper.HELPER.logDirect("Lava near ender chest spot, relocating.");
            context.baritone().getPathingBehavior().cancelEverything();
            context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
            context.resetTimer();
            return;
        }

        context.baritone().getPathingBehavior().cancelEverything();
        context.settings().buildRepeat.value = new Vec3i(0, 0, 0);

        // Clear the chest position and the block above it before placing
        if (!(placeState.getBlock() instanceof AirBlock) || !(aboveState.getBlock() instanceof AirBlock)) {
            context.baritone().getBuilderProcess().clearArea(context.placeLoc(), context.placeLoc().above());
            context.resetTimer();
            return;
        }

        // Make sure we can still reach the support before committing
        Optional<Rotation> reach = RotationUtils.reachable(context.playerContext(), context.placeLoc().below(), context.playerContext().playerController().getBlockReachDistance());
        if (reach.isEmpty()) {
            context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
            context.resetTimer();
            return;
        }

        // Placement isn't getting accepted after a while (not the block being in the way, we already cleared it) - relocate
        if (context.timer() > 2 * context.settings().highwayPlaceConfirmTimeout.value) {
            Helper.HELPER.logDirect("Ender chest placement not progressing, relocating.");
            context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
            context.resetTimer();
            return;
        }

        int slot = context.putItemHotbar(Item.getId(Blocks.ENDER_CHEST.asItem()));
        if (slot == -1) {
            Helper.HELPER.logDirect("No ender chests to place, relocating.");
            context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
            context.resetTimer();
            return;
        }
        if (slot >= 9) {
            // Couldn't get the chest onto the hotbar yet, wait for the inventory move
            context.resetTimer();
            return;
        }

        // Convert to a plain BlockPos to avoid BetterBlockPos leaking into Minecraft's block entity maps
        BlockPos placeLoc = new BlockPos(context.placeLoc().getX(), context.placeLoc().getY(), context.placeLoc().getZ());
        if (context.placeBlockAgainstNeighbor(placeLoc, slot)) {
            placed = true;
            context.resetTimer();
        }
    }
}
