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

package baritone.api.utils;

import baritone.api.BaritoneAPI;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * @author Brady
 * @since 12/14/2018
 */
public interface IPlayerController {

    void syncHeldItem();

    boolean hasBrokenBlock();

    boolean onPlayerDamageBlock(BlockPos pos, Direction side);

    void resetBlockRemoving();

    void windowClick(int windowId, int slotId, int mouseButton, ClickType type, Player player);

    GameType getGameType();

    InteractionResult processRightClickBlock(LocalPlayer player, Level world, InteractionHand hand, BlockHitResult result);

    InteractionResult processRightClick(LocalPlayer player, Level world, InteractionHand hand);

    boolean clickBlock(BlockPos loc, Direction face);

    /**
     * Breaks a block the server destroys on the start packet alone, predicting the break the way
     * {@link #clickBlock} only does when the client judges the block instant itself. For when the
     * client's view of the dig speed lags the server's.
     */
    boolean clickBlockInstant(BlockPos loc, Direction face);

    /**
     * Starts and finishes digging a block in the same tick. The server accepts a finished dig once
     * {@link #SERVER_FINISH_DIG_PROGRESS} of the break time has passed, so this breaks any block
     * that mines at least that much per tick, not just the ones {@link #clickBlock} breaks
     * instantly.
     */
    boolean clickBlockFinishing(BlockPos loc, Direction face);

    /**
     * Finishes a dig whose start packet went out on an earlier tick, predicting the break like a
     * vanilla finished dig. The server times the dig from that start, so the block breaks once
     * {@link #SERVER_FINISH_DIG_PROGRESS} of its break time has passed, without the client mining
     * it out to the end. A refused break is restored by the server's ack.
     */
    boolean finishBlock(BlockPos loc, Direction face);

    /**
     * Fraction of a block's break time after which the vanilla server accepts a finished dig.
     */
    float SERVER_FINISH_DIG_PROGRESS = 0.7f;

    void setHittingBlock(boolean hittingBlock);

    default double getBlockReachDistance() {
        return this.getGameType().isCreative() ? 5.0F : BaritoneAPI.getSettings().blockReachDistance.value;
    }

    void setDestroyDelay(int delay);
}
