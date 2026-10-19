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

package baritone.utils.player;

import baritone.api.utils.IPlayerController;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;


/**
 * Implementation of {@link IPlayerController} that chains to the primary player controller's methods
 *
 * @author Brady
 * @since 12/14/2018
 */
public final class BaritonePlayerController implements IPlayerController {

    private final Minecraft mc;

    public BaritonePlayerController(Minecraft mc) {
        this.mc = mc;
    }

    @Override
    public void syncHeldItem() {
        ((IPlayerControllerMP) mc.gameMode).callSyncCurrentPlayItem();
    }

    @Override
    public boolean hasBrokenBlock() {
        return !((IPlayerControllerMP) mc.gameMode).isHittingBlock();
    }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        return mc.gameMode.continueDestroyBlock(pos, side);
    }

    @Override
    public void resetBlockRemoving() {
        mc.gameMode.stopDestroyBlock();
    }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ClickType type, Player player) {
        mc.gameMode.handleInventoryMouseClick(windowId, slotId, mouseButton, type, player);
    }

    @Override
    public GameType getGameType() {
        return mc.gameMode.getPlayerMode();
    }

    @Override
    public InteractionResult processRightClickBlock(LocalPlayer player, Level world, InteractionHand hand, BlockHitResult result) {
        // primaryplayercontroller is always in a ClientWorld so this is ok
        return mc.gameMode.useItemOn(player, hand, result);
    }

    @Override
    public InteractionResult processRightClick(LocalPlayer player, Level world, InteractionHand hand) {
        return mc.gameMode.useItem(player, hand);
    }

    @Override
    public boolean clickBlock(BlockPos loc, Direction face) {
        return mc.gameMode.startDestroyBlock(loc, face);
    }

    @Override
    public boolean clickBlockInstant(BlockPos loc, Direction face) {
        if (!beginOneTickDig(loc, face)) {
            return false;
        }
        MultiPlayerGameMode gameMode = mc.gameMode;
        ((IPlayerControllerMP) gameMode).callStartPrediction(mc.level, sequence -> {
            gameMode.destroyBlock(loc);
            return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, loc, face, sequence);
        });
        return true;
    }

    @Override
    public boolean clickBlockFinishing(BlockPos loc, Direction face) {
        if (!beginOneTickDig(loc, face)) {
            return false;
        }
        MultiPlayerGameMode gameMode = mc.gameMode;
        IPlayerControllerMP accessor = (IPlayerControllerMP) gameMode;
        accessor.callStartPrediction(mc.level, sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, loc, face, sequence));
        // predicted under the finishing packet's sequence like a vanilla finished dig, so a refused
        // break is restored by the server's ack instead of leaving ghost air
        accessor.callStartPrediction(mc.level, sequence -> {
            gameMode.destroyBlock(loc);
            return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, loc, face, sequence);
        });
        return true;
    }

    /**
     * The checks startDestroyBlock makes before a dig, and the abort of one in progress elsewhere.
     *
     * @return whether the block may be dug
     */
    private boolean beginOneTickDig(BlockPos loc, Direction face) {
        MultiPlayerGameMode gameMode = mc.gameMode;
        IPlayerControllerMP accessor = (IPlayerControllerMP) gameMode;
        if (mc.player.blockActionRestricted(mc.level, loc, gameMode.getPlayerMode()) || !mc.level.getWorldBorder().isWithinBounds(loc)) {
            return false;
        }
        if (accessor.isHittingBlock()) {
            // a dig in progress elsewhere is over once the server's dig target moves; abort it the
            // way startDestroyBlock does so the client doesn't finish it into a mismatch later
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, accessor.getCurrentBlock(), face));
            accessor.setIsHittingBlock(false);
        }
        return true;
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {
        ((IPlayerControllerMP) mc.gameMode).setIsHittingBlock(hittingBlock);
    }

    @Override
    public void setDestroyDelay(int delay) {
        ((IPlayerControllerMP) mc.gameMode).setDestroyDelay(delay);
    }
}
