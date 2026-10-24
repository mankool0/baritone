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
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Digs two slow blocks at once using both of the vanilla server's dig slots.
 * <p>
 * The server finishes a regular dig when its stop packet arrives after
 * {@link IPlayerController#SERVER_FINISH_DIG_PROGRESS} of the break time. A stop that arrives
 * earlier parks the block in a single delayed-destroy slot instead, which the server finishes by
 * itself once the full break time has passed: no more packets, no reach check. One block is kept
 * parked there while the next is dug in the regular slot, and whenever the delayed slot frees up
 * the regular dig moves into it (the server keeps its start tick) so the regular slot can start
 * another block.
 * <p>
 * Both slots time from the tick their start packet was handled and finish with whatever tool is
 * held at that moment. Any start packet restarts the regular slot's timer and a non-instant one
 * moves it to another block, so nothing else may dig while {@link #digging()}.
 */
public final class DoubleMine implements AbstractGameEventListener {

    /**
     * Ticks a regular dig's stop waits past the server's threshold, so a start packet held back
     * by network jitter can't make the stop land early.
     */
    private static final int FINISH_MARGIN_TICKS = 2;

    /**
     * How long past its expected finish a parked block may stay before the park is presumed lost:
     * the server only parks a block when its delayed slot is free.
     */
    private static final int PARK_TIMEOUT_TICKS = 40;

    private final IPlayerContext ctx;
    private final ServerDigSpeed digSpeed;
    private Dig regular;
    private Dig parked;
    private boolean sending;
    private boolean sentThisTick;

    public DoubleMine(IPlayerContext ctx, ServerDigSpeed digSpeed) {
        this.ctx = ctx;
        this.digSpeed = digSpeed;
    }

    /**
     * Advances both slots and fills free ones from the candidates.
     *
     * @param candidates blocks to mine that take longer than one tick, in order of preference,
     *                   with a rotation that reaches each
     * @return the block the regular slot is digging, else the parked one, else null
     */
    public BlockPos tick(List<Tuple<BetterBlockPos, Rotation>> candidates) {
        long now = ctx.world().getGameTime();
        sentThisTick = false;
        if (parked != null && (gone(parked) || now > parked.start + ServerDigTarget.ticksToReach(progress(parked), 1.0f) + PARK_TIMEOUT_TICKS)) {
            parked = null;
        }
        if (regular != null && (gone(regular) || !ctx.player().isWithinBlockInteractionRange(regular.pos, 0.5))) {
            regular = null;
        }
        if (regular != null && now >= regular.start + ServerDigTarget.ticksToReach(progress(regular), IPlayerController.SERVER_FINISH_DIG_PROGRESS) + FINISH_MARGIN_TICKS) {
            send(regular, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, true);
            regular = null;
        }
        // start one, park it and start another: at most two passes
        for (int pass = 0; pass < 2; pass++) {
            if (regular == null) {
                Tuple<BetterBlockPos, Rotation> next = nextCandidate(candidates);
                if (next == null) {
                    break;
                }
                regular = start(next, now);
            }
            if (parked != null || nextCandidate(candidates) == null) {
                break; // with nothing to start in its place, finishing in the regular slot is quicker
            }
            send(regular, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, false);
            parked = regular;
            regular = null;
        }
        if (sentThisTick) {
            ctx.player().swing(InteractionHand.MAIN_HAND); // once a tick, like a held attack
        }
        Dig current = regular != null ? regular : parked;
        if (current == null) {
            return null;
        }
        // both slots finish with whatever is in hand at that moment
        MovementHelper.switchToBestToolFor(ctx, current.state);
        return current.pos;
    }

    /**
     * @return whether the regular slot is digging, which any other dig packet would ruin
     */
    public boolean digging() {
        return regular != null;
    }

    /**
     * @return whether a block is parked in the delayed slot, which the server finishes wherever we are
     */
    public boolean parking() {
        return parked != null;
    }

    public boolean isMining(BlockPos pos) {
        return regular != null && regular.pos.equals(pos) || parked != null && parked.pos.equals(pos);
    }

    public void reset() {
        regular = null;
        parked = null;
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (sending || regular == null || event.getState() != EventState.PRE
                || !(event.getPacket() instanceof ServerboundPlayerActionPacket packet)) {
            return;
        }
        // a start anywhere restarts or moves the server's regular dig; a stop on its block
        // finishes or parks it. Aborts leave the dig's block and timer alone
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK && packet.getPos().equals(regular.pos)) {
            regular = null;
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        reset();
    }

    private Tuple<BetterBlockPos, Rotation> nextCandidate(List<Tuple<BetterBlockPos, Rotation>> candidates) {
        for (Tuple<BetterBlockPos, Rotation> candidate : candidates) {
            BlockState state = ctx.world().getBlockState(candidate.getA());
            // the list predates this tick's finished dig, which is already air client-side
            if (!isMining(candidate.getA()) && !state.isAir() && !(state.getBlock() instanceof LiquidBlock)) {
                return candidate;
            }
        }
        return null;
    }

    private Dig start(Tuple<BetterBlockPos, Rotation> candidate, long now) {
        BlockPos pos = new BlockPos(candidate.getA().x, candidate.getA().y, candidate.getA().z);
        Dig dig = new Dig(pos, face(pos, candidate.getB()), ctx.world().getBlockState(pos), now);
        send(dig, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, false);
        return dig;
    }

    /**
     * Per-tick progress with the tool the dig finishes with. Read again every tick rather than
     * once at the start, the way the server judges a stop: progress of the moment times the
     * elapsed ticks. Fixed at the start, a dig started with a block in hand would be timed
     * without the pick's Efficiency, which the server only applies a tick later.
     */
    private float progress(Dig dig) {
        var inventory = ctx.player().getInventory();
        int previous = inventory.getSelectedSlot();
        MovementHelper.switchToBestToolFor(ctx, dig.state);
        float progress = digSpeed.progress(dig.state, dig.pos);
        inventory.setSelectedSlot(previous);
        return progress;
    }

    /**
     * @param predictBreak remove the block client-side under the packet's sequence, the way a
     *                     vanilla finished dig does, so a refused break is restored by the ack
     */
    private void send(Dig dig, ServerboundPlayerActionPacket.Action action, boolean predictBreak) {
        MovementHelper.switchToBestToolFor(ctx, dig.state);
        // the server judges the dig with the tool it believes is held when the packet arrives
        ctx.playerController().syncHeldItem();
        MultiPlayerGameMode gameMode = ctx.minecraft().gameMode;
        sending = true;
        try {
            ((IPlayerControllerMP) gameMode).callStartPrediction(ctx.minecraft().level, sequence -> {
                if (predictBreak) {
                    gameMode.destroyBlock(dig.pos);
                }
                return new ServerboundPlayerActionPacket(action, dig.pos, dig.face, sequence);
            });
        } finally {
            sending = false;
        }
        sentThisTick = true;
    }

    private boolean gone(Dig dig) {
        return ctx.world().getBlockState(dig.pos).getBlock() != dig.state.getBlock();
    }

    /**
     * The face the rotation hits, else the one facing the eye: the server checks the clicked face
     * is on the player's side of the block.
     */
    private Direction face(BlockPos pos, Rotation rotation) {
        HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), rotation, ctx.playerController().getBlockReachDistance());
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK && blockHit.getBlockPos().equals(pos)) {
            return blockHit.getDirection();
        }
        return Direction.getApproximateNearest(ctx.playerHead().subtract(Vec3.atCenterOf(pos)));
    }

    private static final class Dig {

        final BlockPos pos;
        final Direction face;
        final BlockState state;
        /**
         * The tick the start was sent. The server's delayed slot keeps the regular dig's start
         * tick, so a parked block finishes a full break time after it was first started, not
         * after it was parked.
         */
        final long start;

        Dig(BlockPos pos, Direction face, BlockState state, long start) {
            this.pos = pos;
            this.face = face;
            this.state = state;
            this.start = start;
        }
    }
}
