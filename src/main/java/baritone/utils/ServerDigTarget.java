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

import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server's dig target as the dig packets we send leave it.
 * <p>
 * The server keeps one dig target per player and times it from the last start packet. A start
 * restarts that timer whatever it hits, but only moves the target when the block survives it: an
 * instant break leaves the target where it was. A stop packet at the target breaks whatever stands
 * there once the time since that start covers {@link IPlayerController#SERVER_FINISH_DIG_PROGRESS}
 * of that block's break time, so a target left to sit breaks even an ender chest in one packet,
 * whichever block the start was for.
 * <p>
 * A best guess: a block only the server judges instant moves nothing, yet counts here as the
 * target. Check it with a stop before relying on it.
 */
public final class ServerDigTarget implements AbstractGameEventListener {

    /**
     * Ticks a stop waits past the server's threshold, so a start packet held back by network
     * jitter can't make the stop land early.
     */
    private static final int MARGIN_TICKS = 2;

    private final IPlayerContext ctx;
    // null when not known
    private BlockPos target;
    private long startTick;
    // the server's dig state goes with its player, which a rejoin, a respawn and a dimension change all replace
    private LocalPlayer player;

    public ServerDigTarget(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (event.getState() != EventState.PRE || !(event.getPacket() instanceof ServerboundPlayerActionPacket packet)
                || ctx.player() == null || ctx.world() == null) {
            return;
        }
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK -> {
                // a lone start predicts an instant break before it goes out, so the block is
                // already air here; the target stays wherever it was, which we can't vouch for
                BlockState state = ctx.world().getBlockState(packet.getPos());
                target = state.isAir() || state.getDestroyProgress(ctx.player(), ctx.world(), packet.getPos()) >= 1.0f
                        ? null : packet.getPos().immutable();
                startTick = ctx.world().getGameTime();
                player = ctx.player();
            }
            case ABORT_DESTROY_BLOCK -> {
                // The server keeps its target through an abort, but an anticheat then takes a stop
                // at the target for a dig of a different block than the last one it saw.
                if (!packet.getPos().equals(target)) {
                    target = null;
                }
            }
            default -> {}
        }
    }

    /**
     * @return whether the server's dig target is pos, as far as our packets tell
     */
    public boolean isTarget(BlockPos pos) {
        return target != null && player == ctx.player() && target.equals(pos);
    }

    /**
     * @return the game tick the start that set the target went out, which tells one start from another
     */
    public long startTick() {
        return startTick;
    }

    /**
     * @return whether a stop sent now breaks the block at pos, which the server digs at
     * {@code progress} a tick: pos is the target and its start is old enough
     */
    public boolean stopBreaks(BlockPos pos, float progress) {
        return isTarget(pos) && ctx.world().getGameTime() - startTick
                >= (long) ticksToReach(progress, IPlayerController.SERVER_FINISH_DIG_PROGRESS) + MARGIN_TICKS;
    }

    /**
     * @return the elapsed ticks after which the server counts the dig as reaching the fraction;
     * it compares {@code progress * (elapsed + 1)} in float
     */
    static int ticksToReach(float progress, float fraction) {
        if (progress <= 0 || fraction / progress >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE; // never, say a server zeroing the block break speed attribute
        }
        int elapsed = Math.max(0, (int) Math.ceil(fraction / progress) - 1);
        while (elapsed > 0 && progress * (float) elapsed >= fraction) {
            elapsed--;
        }
        while (progress * (float) (elapsed + 1) < fraction) {
            elapsed++;
        }
        return elapsed;
    }
}
