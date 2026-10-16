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

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * @author Brady
 * @since 8/25/2018
 */
public final class BlockBreakHelper {
    // base ticks between block breaks caused by tick logic
    private static final int BASE_BREAK_DELAY = 1;

    private final IPlayerContext ctx;
    private boolean wasHitting;
    private int breakDelayTimer = 0;
    private final BrokenBlocks broken = new BrokenBlocks();

    BlockBreakHelper(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public void stopBreakingBlock() {
        // The player controller will never be null, but the player can be
        if (ctx.player() != null && wasHitting) {
            ctx.playerController().setHittingBlock(false);
            ctx.playerController().resetBlockRemoving();
            wasHitting = false;
        }
    }

    public void tick(boolean isLeftClick) {
        if (breakDelayTimer > 0) {
            breakDelayTimer--;
            return;
        }
        HitResult trace = ctx.objectMouseOver();
        boolean isBlockTrace = trace != null && trace.getType() == HitResult.Type.BLOCK;

        if (isLeftClick && isBlockTrace) {
            BlockPos pos = ((BlockHitResult) trace).getBlockPos();
            BlockState before = ctx.world().getBlockState(pos);
            ctx.playerController().setHittingBlock(wasHitting);
            if (finishesInOneTick(pos, before)) {
                // done within the tick like an instant break, so no break delay either
                ctx.playerController().syncHeldItem();
                if (ctx.playerController().clickBlockFinishing(pos, ((BlockHitResult) trace).getDirection())) {
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }
            } else if (ctx.playerController().hasBrokenBlock()) {
                ctx.playerController().syncHeldItem();
                ctx.playerController().clickBlock(((BlockHitResult) trace).getBlockPos(), ((BlockHitResult) trace).getDirection());
                ctx.player().swing(InteractionHand.MAIN_HAND);
            } else {
                if (ctx.playerController().onPlayerDamageBlock(((BlockHitResult) trace).getBlockPos(), ((BlockHitResult) trace).getDirection())) {
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }
                if (ctx.playerController().hasBrokenBlock()) { // block broken this tick
                    // break delay timer only applies for multi-tick block breaks like vanilla
                    breakDelayTimer = BaritoneAPI.getSettings().blockBreakSpeed.value - BASE_BREAK_DELAY;
                    // must reset controller's destroy delay to prevent the client from delaying itself unnecessarily
                    ((IPlayerControllerMP) ctx.minecraft().gameMode).setDestroyDelay(0);
                }
            }
            // if true, we're breaking a block. if false, we broke the block this tick
            wasHitting = !ctx.playerController().hasBrokenBlock();
            // this value will be reset by the MC client handling mouse keys
            // since we're not spoofing the click keybind to the client, the client will stop the break if isDestroyingBlock is true
            // we store and restore this value on the next tick to determine if we're breaking a block
            ctx.playerController().setHittingBlock(false);
            if (ctx.world().getBlockState(pos) != before) {
                broken.add(pos, before);
            }
        } else {
            wasHitting = false;
        }
    }

    /**
     * Whether to break this block with {@link IPlayerController#clickBlockFinishing} instead of a
     * dig that spans ticks
     */
    private boolean finishesInOneTick(BlockPos pos, BlockState state) {
        if (ctx.playerController().getGameType().isCreative()
                || !breaksNearInstant(state.getDestroyProgress(ctx.player(), ctx.world(), pos))) {
            return false;
        }
        // a dig of this block already under way finishes this tick anyway
        return ctx.playerController().hasBrokenBlock()
                || !pos.equals(((IPlayerControllerMP) ctx.minecraft().gameMode).getCurrentBlock());
    }

    /**
     * @return whether a left-click dig with this per-tick progress finishes in the tick it starts
     * because of {@link baritone.api.Settings#breakNearInstant}
     */
    public static boolean breaksNearInstant(float progress) {
        return BaritoneAPI.getSettings().breakNearInstant.value
                && progress >= IPlayerController.SERVER_FINISH_DIG_PROGRESS && progress < 1.0f;
    }

    public BrokenBlocks broken() {
        return broken;
    }
}
