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
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public abstract class PlacingShulkerBase extends State {
    private boolean placed = false;

    public PlacingShulkerBase(HighwayState state) {
        super(state);
    }

    protected abstract HighwayState getPreviousState();
    protected abstract HighwayState getNextState();
    protected abstract ShulkerType getShulkerType();

    /**
     * State to jump to when lava is found at the placement spot, so a fresh (safe) spot can be picked.
     * Returns {@code null} to disable lava relocation (default), keeping the original behavior for shulker
     * types that aren't placed out on the side-storage line.
     */
    protected HighwayState getRelocateState() {
        return null;
    }

    @Override
    public void handle(HighwayContext context) {
        handleWithShulkerType(context, getShulkerType());
    }

    protected void handleWithShulkerType(HighwayContext context, ShulkerType shulkerType) {
        if (placed && context.timer() < 30) {
            return;
        }

        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            context.resetTimer();
            return; // Wait for build to complete
        }

        // Check placement status
        BlockState testState = context.playerContext().world().getBlockState(context.placeLoc());
        Helper.HELPER.logDirect("State: " + testState + " @ " + context.placeLoc());
        
        // If we've attempted placement, wait for the block to appear
        if (placed) {
            if (testState.getBlock() instanceof ShulkerBoxBlock) {
                Helper.HELPER.logDirect("Shulker has been placed successfully");
                context.baritone().getInputOverrideHandler().clearAllKeys();
                context.transitionTo(getNextState());
                placed = false;
                context.resetTimer();
                return;
            } else if (context.timer() < 100) {
                // Still waiting for server to confirm placement
                return;
            } else {
                // Timeout - placement failed
                Helper.HELPER.logDirect("Error: Shulker placement timed out. Restarting.");
                context.transitionTo(HighwayState.Nothing);
                placed = false;
                return;
            }
        }

        // Lava has crept into the spot (or was never safe) - don't break netherrack into it, pick a new spot
        HighwayState relocateState = getRelocateState();
        if (relocateState != null && !context.isSideStorageSpotSafe(context.placeLoc())) {
            Helper.HELPER.logDirect("Lava near shulker spot, relocating.");
            context.baritone().getPathingBehavior().cancelEverything();
            context.transitionTo(relocateState);
            placed = false;
            context.resetTimer();
            return;
        }

        // Shulker box spot isn't air or shulker, lets fix that. A solid block in the spot itself
        // counts on its own: a spot picked in unpaved ground (nowhere behind us to place into) is
        // netherrack with air above it, and waiting for both to be blocked left that looping on
        // "Cannot place shulker" forever. Replaceable blocks (snow, liquids) still place as before.
        BlockState testStateAbove = context.playerContext().world().getBlockState(context.placeLoc().above());
        boolean spotBlocked = !testState.canBeReplaced();
        boolean bothBlocked = !(testState.getBlock() instanceof AirBlock) && !(testStateAbove.getBlock() instanceof AirBlock);
        if ((spotBlocked || bothBlocked) && !(testState.getBlock() instanceof ShulkerBoxBlock)) {
            context.baritone().getPathingBehavior().cancelEverything();
            context.baritone().getBuilderProcess().clearArea(context.placeLoc(), context.placeLoc().above());
            placed = false;
            context.resetTimer();
            return;
        }

        // Convert to regular BlockPos to avoid BetterBlockPos/BlockPos collision in block entity maps
        BlockPos placeLoc = new BlockPos(context.placeLoc().getX(), context.placeLoc().getY(), context.placeLoc().getZ());
        
        Optional<Rotation> shulkerReachable = RotationUtils.reachable(context.playerContext(), placeLoc, context.playerContext().playerController().getBlockReachDistance());
        Optional<Rotation> underShulkerReachable = RotationUtils.reachable(context.playerContext(), placeLoc.below(), context.playerContext().playerController().getBlockReachDistance());
        HighwayState result = context.placeShulkerBox(shulkerReachable.orElse(null), underShulkerReachable.orElse(null), placeLoc, getPreviousState(), this.getState(), getNextState(), shulkerType);
        
        if (result == this.getState()) {
            placed = true;
            context.resetTimer();
        } else {
            // Placement failed, transition to the returned state
            context.transitionTo(result);
        }
    }
}