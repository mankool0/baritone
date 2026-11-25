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

package baritone.process.elytra;

import baritone.api.utils.IPlayerContext;
import baritone.utils.accessor.ILivingEntity;
import baritone.utils.accessor.ILocalPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@link baritone.api.Settings#elytraSaveDurability}: a start-gliding packet before the movement packets of each
 * frame. A start while the server has the player gliding stops its glide (ServerGamePacketListenerImpl), and the next
 * starts it again, so it never glides the twenty ticks in a row that wear the elytra a point, and it has the player
 * standing in between. The client holds its own glide, with a standing player's hitbox and pose, through the server's
 * reports of the glide going off and on.
 */
public final class GlideDurabilitySaver {

    /**
     * Ticks with the server's reported glide stuck on or off after which one more start puts the pairs out of step
     */
    private static final int STUCK_TICKS = 5;
    /**
     * Starts closer together than this are for the same frame: see {@link #onSendPacket(Packet)}
     */
    private static final long SAME_FRAME_NANOS = 15_000_000L;

    private final IPlayerContext ctx;

    // the server's glide as it last reported it (shared flag 7), and how many times that changed: written on the
    // network thread
    private volatile boolean serverGlides;
    private volatile int glideReports;
    private int seenReports;
    private int changedTick;
    private int resetTick = -1;
    private long startedNanos;

    public GlideDurabilitySaver(final IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Before each of the player's moves: holds the glide from the first tick the real flag says gliding, while
     * {@code saving}. The server's reports turn the flag off half the time after.
     */
    public void beforeMove(final LocalPlayer player, final boolean saving) {
        final ILivingEntity living = (ILivingEntity) player;
        final boolean was = living.isGlideHeld();
        boolean held = was;
        if (!saving || player.onGround() || player.isPassenger() || player.isInWater()
                || !LivingEntity.canGlideUsing(player.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST)) {
            held = false;
        } else if (living.isFallFlyingFlagSet()) {
            held = true;
        }
        if (held != was) {
            living.setGlideHeld(held);
            // the standing box, or the gliding one again
            player.refreshDimensions();
            this.seenReports = this.glideReports;
            this.changedTick = player.tickCount;
        }
    }

    /**
     * At the start of each tick
     */
    public void onTick() {
        final LocalPlayer player = ctx.player();
        if (player == null || !((ILivingEntity) player).isGlideHeld()) {
            return;
        }
        // a position packet every tick, hovering or not: a start goes before each
        ((ILocalPlayer) player).setPositionReminder(20);
        final int reports = this.glideReports;
        if (reports != this.seenReports) {
            this.seenReports = reports;
            this.changedTick = player.tickCount;
        }
    }

    /**
     * Before a packet goes out. Under 20 fps a frame runs two ticks back to back and their packets land in one server
     * tick: a start for each would cancel. A lagging server does the same to the starts of two frames, and the glide it
     * reports stands still, on (it wears) or off (80 ticks of that is the "flying" kick); after {@link #STUCK_TICKS} of
     * that, one more start puts the pairs out of step.
     */
    public void onSendPacket(final Packet<?> packet) {
        if (!(packet instanceof ServerboundMovePlayerPacket)) {
            return;
        }
        final LocalPlayer player = ctx.player();
        if (player == null || !((ILivingEntity) player).isGlideHeld() || player.tickCount == this.resetTick) {
            return;
        }
        this.resetTick = player.tickCount;
        final long now = System.nanoTime();
        if (now - this.startedNanos <= SAME_FRAME_NANOS) {
            return;
        }
        this.startedNanos = now;
        startFallFlying(player);
        if (this.resetTick - this.changedTick > STUCK_TICKS) {
            startFallFlying(player);
            this.changedTick = this.resetTick;
        }
    }

    /**
     * As a packet comes in, on the network thread: the packet only, nothing of the world
     */
    public void onReceivePacket(final Packet<?> packet) {
        if (!(packet instanceof ClientboundSetEntityDataPacket data)) {
            return;
        }
        final LocalPlayer player = ctx.player();
        if (player == null || data.id() != player.getId()) {
            return;
        }
        for (final SynchedEntityData.DataValue<?> value : data.packedItems()) {
            // the shared flags: on fire, crouching, ..., bit 7 gliding
            if (value.id() == 0 && value.value() instanceof Byte flags) {
                final boolean glides = (flags & (1 << 7)) != 0;
                if (glides != this.serverGlides) {
                    this.serverGlides = glides;
                    this.glideReports++;
                }
            }
        }
    }

    private static void startFallFlying(final LocalPlayer player) {
        player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }
}
